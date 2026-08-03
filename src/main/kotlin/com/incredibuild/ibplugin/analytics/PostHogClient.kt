/*
 * Copyright 2026 Incredibuild
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.incredibuild.ibplugin.analytics

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.Hashtable
import java.util.UUID
import javax.naming.directory.InitialDirContext

/**
 * Minimal, dependency-free PostHog event capture for major plugin actions
 * (first load, build/rebuild/run-config builds, stop, the "Incredibuild not
 * found" website redirect). No PostHog SDK: a single POST per event to
 * PostHog's `/capture/` endpoint, fired on a pooled background thread so
 * callers never block, with failures logged and swallowed. Mirrors the
 * pattern used by Incredibuild's Visual Studio integration
 * (vs_integration_setup/PostHogClient.pas), adapted to the IntelliJ
 * Platform: a persisted random UUID stands in for its registry-backed
 * identity GUID, JNDI's built-in DNS provider stands in for its raw
 * DnsQuery_W call, and the platform's own pooled executor replaces its
 * manual per-event worker-thread tracking/draining.
 */
object PostHogClient {
    private val LOG = Logger.getInstance(PostHogClient::class.java)

    // Same config-distribution mechanism as the VS integration: the PostHog
    // host and project API key are never hardcoded here. They're published as
    // base64("<host>,<api key>") in this TXT record, so they can be rotated
    // without shipping a new plugin build.
    private const val CONFIG_DNS_TXT_HOST =
        "events_prod_389741faa5afe9d18f5beb64c73cfe4414ff3ad8b22.incredibuild.com"

    private const val DISTINCT_ID_PROPERTY = "com.incredibuild.ibplugin.analytics.distinctId"

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /**
     * The anonymous per-user identifier stamped on every event, exposed so it can be
     * appended (e.g. as a "user-id" query parameter) to web pages we redirect users to -
     * matching the Visual Studio integration's convention of correlating its native
     * PostHog events with the web app's own events for the same onboarding session.
     */
    val distinctId: String by lazy {
        val store = PropertiesComponent.getInstance()
        store.getValue(DISTINCT_ID_PROPERTY) ?: UUID.randomUUID().toString().also {
            store.setValue(DISTINCT_ID_PROPERTY, it)
        }
    }

    // Resolved lazily, at most once per process (never retried, even on
    // failure - mirrors EnsureConfigLoaded in the Delphi client) since it's
    // only ever accessed from within the pooled-thread block in [capture].
    private var configLoaded = false
    private var cachedHost: String? = null
    private var cachedApiKey: String? = null

    private val pluginVersion: String? by lazy {
        (javaClass.classLoader as? PluginAwareClassLoader)?.pluginDescriptor?.version
    }

    /** Fires [event] (auto-prefixed "jetbrains_extension.") with [properties], fully async. */
    fun capture(event: String, properties: Map<String, Any?> = emptyMap()) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ensureConfigLoaded()
                val host = cachedHost
                val apiKey = cachedApiKey
                if (host.isNullOrEmpty() || apiKey.isNullOrEmpty()) {
                    LOG.debug("Skipping PostHog capture of '$event' - config not available")
                    return@executeOnPooledThread
                }

                val body = buildRequestBody(event, properties, apiKey)
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("$host/capture/"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            } catch (e: Exception) {
                LOG.debug("PostHog capture failed", e)
            }
        }
    }

    /** Fires [event] only the first time it's ever seen for this user, tracked via [flagProperty]. */
    fun captureOnce(flagProperty: String, event: String, properties: Map<String, Any?> = emptyMap()) {
        val store = PropertiesComponent.getInstance()
        if (store.getBoolean(flagProperty, false)) return
        store.setValue(flagProperty, true)
        capture(event, properties)
    }

    @Synchronized
    private fun ensureConfigLoaded() {
        if (configLoaded) return
        try {
            val txtValue = queryDnsTxtRecord(CONFIG_DNS_TXT_HOST)
            if (txtValue != null) {
                val decoded = String(Base64.getDecoder().decode(txtValue.trim('"')), Charsets.UTF_8)
                val commaIndex = decoded.indexOf(',')
                if (commaIndex > 0) {
                    cachedHost = decoded.substring(0, commaIndex)
                    cachedApiKey = decoded.substring(commaIndex + 1)
                }
            }
        } catch (e: Exception) {
            LOG.debug("Failed to load PostHog config from DNS TXT record", e)
        } finally {
            configLoaded = true
        }
    }

    /** Looks up the first TXT record string for [hostname] via the JDK's built-in JNDI DNS provider. */
    private fun queryDnsTxtRecord(hostname: String): String? {
        val env = Hashtable<String, String>()
        env["java.naming.factory.initial"] = "com.sun.jndi.dns.DnsContextFactory"
        env["com.sun.jndi.dns.timeout.initial"] = "5000"
        env["com.sun.jndi.dns.timeout.retries"] = "1"
        val ctx = InitialDirContext(env)
        try {
            val txtAttribute = ctx.getAttributes(hostname, arrayOf("TXT")).get("TXT") ?: return null
            return txtAttribute.get()?.toString()
        } finally {
            ctx.close()
        }
    }

    private fun buildRequestBody(event: String, properties: Map<String, Any?>, apiKey: String): String {
        val allProperties = LinkedHashMap<String, Any?>()
        allProperties["pluginVersion"] = pluginVersion
        allProperties["ideName"] = ApplicationInfo.getInstance().versionName
        allProperties["ideBuild"] = ApplicationInfo.getInstance().build.asString()
        allProperties["os"] = System.getProperty("os.name")
        allProperties.putAll(properties)

        val payload = linkedMapOf<String, Any?>(
            "api_key" to apiKey,
            "event" to "jetbrains_extension.$event",
            "distinct_id" to distinctId,
            "properties" to allProperties
        )
        return toJson(payload)
    }

    private fun toJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> jsonString(value)
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> "${jsonString(k.toString())}:${toJson(v)}" }
        else -> jsonString(value.toString())
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
