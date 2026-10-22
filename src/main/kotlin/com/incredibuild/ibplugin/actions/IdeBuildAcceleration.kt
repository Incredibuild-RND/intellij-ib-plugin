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
package com.incredibuild.ibplugin.actions

import com.incredibuild.ibplugin.analytics.PostHogClient
import com.incredibuild.ibplugin.settings.IncredibuildConfigurable
import com.incredibuild.ibplugin.settings.IncredibuildSettings
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.File
import java.util.Collections

/** Reasons already reported in this project, so a fallback is announced once per session rather than on every build. */
private val REPORTED_FALLBACKS = Key.create<MutableSet<String>>("Incredibuild.ReportedIdeBuildFallbacks")

/**
 * What the IDE's own Build/Rebuild actions share when they are routed through Incredibuild (the
 * "Use Incredibuild for the IDE's build actions" setting) - in RustRover, CLion and Rider alike:
 * whether to route at all, and what happens when Incredibuild can't take the build.
 *
 * The rule throughout is that a standard IDE command must never fail because of this plugin: if
 * Incredibuild is missing, unlicensed or too old, the build goes ahead with the IDE's own builder
 * and the user is told why, once, with a way to fix it.
 */
object IdeBuildAcceleration {

    /** The opt-in setting - off by default. */
    val isEnabled: Boolean
        get() = IncredibuildSettings.getInstance().accelerateIdeBuilds

    /**
     * The install folder to run an IDE build through, or null - after telling the user why - if
     * the build should fall back to [standardBuilder] (e.g. "Cargo", "CMake", "Rider's build").
     * Blocking; never call it on the EDT.
     */
    fun installFolderOrFallBack(project: Project, flavor: BuildFlavor, standardBuilder: String): File? =
        when (val status = IncredibuildInstallation.check(flavor)) {
            is InstallationStatus.Ready -> status.installFolder
            else -> {
                notifyFallback(project, status, standardBuilder)
                null
            }
        }

    /**
     * Tells the user an IDE build that should have gone through Incredibuild is running with
     * [standardBuilder] instead, because of [status]. Shown once per project session per kind of
     * problem, so a team member without Incredibuild isn't nagged on every build - the settings
     * page shows the same status permanently.
     */
    fun notifyFallback(project: Project, status: InstallationStatus, standardBuilder: String) {
        val reasonKey = when (status) {
            InstallationStatus.NotInstalled -> "not_installed"
            is InstallationStatus.UnsupportedVersion -> "unsupported_version"
            is InstallationStatus.NotLicensed -> "not_licensed"
            is InstallationStatus.Ready -> return
        }
        notifyOnce(
            project,
            reasonKey,
            IncredibuildInstallation.describe(status),
            standardBuilder,
            offerDownload = status !is InstallationStatus.NotLicensed
        )
    }

    /**
     * Tells the user an IDE build fell back to [standardBuilder] for a reason other than the
     * installation itself - e.g. the build command couldn't be prepared. Same once-per-session rule.
     */
    fun notifyFallback(project: Project, reason: String, standardBuilder: String) {
        notifyOnce(project, "other:$reason", reason, standardBuilder, offerDownload = false)
    }

    private fun notifyOnce(
        project: Project,
        reasonKey: String,
        reason: String,
        standardBuilder: String,
        offerDownload: Boolean
    ) {
        val reported = synchronized(project) {
            project.getUserData(REPORTED_FALLBACKS)
                ?: Collections.synchronizedSet(HashSet<String>()).also { project.putUserData(REPORTED_FALLBACKS, it) }
        }
        if (!reported.add(reasonKey)) return

        PostHogClient.capture("ide_build_fallback", mapOf("reason" to reasonKey.substringBefore(':')))
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                "Building without Incredibuild",
                "$reason<br>This build runs with $standardBuilder instead.",
                NotificationType.WARNING
            )
            .addAction(NotificationAction.createSimple("Incredibuild settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, IncredibuildConfigurable::class.java)
            })
        if (offerDownload) {
            notification.addAction(NotificationAction.createSimple("Download Incredibuild") {
                PostHogClient.capture("download_latest_version_opened")
                BrowserUtil.browse(INCREDIBUILD_DOWNLOAD_PAGE)
            })
        }
        notification.notify(project)
    }
}
