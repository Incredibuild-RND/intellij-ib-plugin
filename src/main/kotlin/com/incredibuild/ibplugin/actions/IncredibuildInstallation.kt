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

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import java.io.File

private val LOG = Logger.getInstance(IncredibuildInstallation::class.java)

internal const val MINIMUM_RUST_BUILD_VERSION = "10.37.1"

// The Linux team found an issue with an earlier released Linux Incredibuild
// version during RustRover integration testing, hence this gate. Deliberately a
// separate constant, not shared with the Windows one: Windows and Linux
// Incredibuild use entirely unrelated version-numbering schemes (e.g.
// "10.37.0.12597" vs "3.18.0").
internal const val MINIMUM_RUST_BUILD_VERSION_LINUX = "4.29.3"

/**
 * How long [IncredibuildInstallation.check] trusts what it last found. The licence query starts
 * BuildConsole, which takes about a second - too long to pay on every IDE build, but short enough
 * that installing, licensing or upgrading Incredibuild is picked up without restarting the IDE.
 * Settings | Tools | Incredibuild's "Check again" bypasses it.
 */
private const val CACHE_TTL_MS = 5 * 60_000L

private const val LICENSE_QUERY_TIMEOUT_MS = 15_000

/**
 * The "InitiatorRegType <type>" line `BuildConsole /QueryLicense` prints: the licence this machine
 * starts builds with ("Floating", ...), or "None" without one - the same word it uses for the
 * other licence types it lists (e.g. "CIInitiatorRegType None").
 */
private val INITIATOR_REG_TYPE_REGEX = Regex("""^\s*InitiatorRegType\s+(\S+)""", RegexOption.MULTILINE)

/** Which kind of build is asking - they have different minimum Incredibuild versions. */
internal enum class AcceleratedBuildTool {
    /** Cargo (RustRover), built with a custom Incredibuild profile. */
    CARGO,

    /** CMake (CLion) and MSBuild (Rider), covered by Incredibuild's own default profile. */
    NATIVE;

    /**
     * The oldest Incredibuild this kind of build is supported on, or null if there's no known
     * minimum - the case for CMake/C++ and MSBuild, unlike the specific version bump Rust build
     * support needed.
     */
    val minimumVersion: String?
        get() = when (this) {
            CARGO -> if (SystemInfo.isLinux) MINIMUM_RUST_BUILD_VERSION_LINUX else MINIMUM_RUST_BUILD_VERSION
            NATIVE -> null
        }
}

/** What [IncredibuildInstallation.check] found. */
internal sealed interface IncredibuildStatus {
    /** One line for Settings | Tools | Incredibuild. */
    val summary: String

    class Ready(val installFolder: File, val version: String?, val licensed: Boolean?) : IncredibuildStatus {
        override val summary: String
            get() = buildString {
                append(version?.let { "Incredibuild $it" } ?: "Incredibuild (version unknown)")
                append(" in ${installFolder.absolutePath}")
                if (licensed == null && SystemInfo.isWindows) append(" - the licence could not be checked")
            }
    }

    data object NotInstalled : IncredibuildStatus {
        override val summary = "Incredibuild is not installed on this machine."
    }

    class NotLicensed(val installFolder: File, val version: String?) : IncredibuildStatus {
        override val summary: String
            get() = "Incredibuild${version?.let { " $it" } ?: ""} in ${installFolder.absolutePath} has no licence to " +
                "start builds on this machine."
    }

    class UnsupportedVersion(val installFolder: File, val version: String, val minimumVersion: String) :
        IncredibuildStatus {
        override val summary: String
            get() = "Incredibuild $version in ${installFolder.absolutePath} is older than the minimum supported " +
                "version, $minimumVersion."
    }
}

/**
 * Finds and validates the Incredibuild installation the plugin builds with: that it's installed,
 * new enough, and licensed to start builds here. Used where a build has to decide *quietly*
 * whether Incredibuild can take it - the IDE's own build actions, when Settings | Tools |
 * Incredibuild routes them through Incredibuild - and shown on that settings page. The Incredibuild
 * menu's own actions keep their dialogs instead (see [IncredibuildRunner.ensureIncredibuildReady]).
 *
 * The licence can only be checked on Windows, through `BuildConsole /QueryLicense`; Linux
 * Incredibuild has no equivalent query, so there it's reported as unknown and not held against
 * the installation - as it is on Windows when the query itself fails (e.g. the Coordinator can't
 * be reached), since that says nothing definite about the licence.
 */
internal object IncredibuildInstallation {

    private class Facts(val installFolder: File?, val version: String?, val licensed: Boolean?, val checkedAt: Long)

    @Volatile
    private var cached: Facts? = null

    /**
     * Blocking - reads the registry, and may start BuildConsole - so never call this on the EDT.
     * [refresh] ignores what was found before, e.g. for "Check again".
     */
    fun check(tool: AcceleratedBuildTool, refresh: Boolean = false): IncredibuildStatus {
        val facts = cached?.takeUnless { refresh || it.isStale() } ?: lookUp().also { cached = it }
        return statusOf(facts, tool)
    }

    /** What [check] would return without having to look again, or null if it would have to. */
    fun cachedStatus(tool: AcceleratedBuildTool): IncredibuildStatus? =
        cached?.takeUnless { it.isStale() }?.let { statusOf(it, tool) }

    private fun Facts.isStale(): Boolean =
        System.currentTimeMillis() - checkedAt > CACHE_TTL_MS ||
            // Uninstalled since: no need to wait out the cache to notice.
            (installFolder != null && !installFolder.isDirectory)

    private fun lookUp(): Facts {
        val installFolder = IncredibuildLocator.findInstallFolder()?.takeIf { it.isDirectory }
            ?: return Facts(null, null, null, System.currentTimeMillis())
        return Facts(installFolder, IncredibuildLocator.findVersion(), queryLicensed(installFolder), System.currentTimeMillis())
    }

    private fun statusOf(facts: Facts, tool: AcceleratedBuildTool): IncredibuildStatus {
        val installFolder = facts.installFolder ?: return IncredibuildStatus.NotInstalled
        val minimumVersion = tool.minimumVersion
        if (minimumVersion != null && facts.version != null &&
            IncredibuildLocator.compareVersions(facts.version, minimumVersion) < 0
        ) {
            return IncredibuildStatus.UnsupportedVersion(installFolder, facts.version, minimumVersion)
        }
        if (facts.licensed == false) return IncredibuildStatus.NotLicensed(installFolder, facts.version)
        return IncredibuildStatus.Ready(installFolder, facts.version, facts.licensed)
    }

    /** True or false if `BuildConsole /QueryLicense` says so, null if it can't be asked or didn't say. */
    private fun queryLicensed(installFolder: File): Boolean? {
        if (!SystemInfo.isWindows) return null
        val buildConsole = File(installFolder, "BuildConsole.exe")
        if (!buildConsole.isFile) return null
        return try {
            val output = ExecUtil.execAndGetOutput(
                GeneralCommandLine(buildConsole.absolutePath, "/QueryLicense"),
                LICENSE_QUERY_TIMEOUT_MS
            )
            if (output.isTimeout || output.exitCode != 0) return null
            parseLicensed(output.stdout)
        } catch (e: Exception) {
            LOG.warn("Could not query the Incredibuild licence", e)
            null
        }
    }

    /** Whether `BuildConsole /QueryLicense` output [stdout] shows a licence to start builds with. */
    internal fun parseLicensed(stdout: String): Boolean? {
        val type = INITIATOR_REG_TYPE_REGEX.find(stdout)?.groupValues?.get(1) ?: return null
        return !type.equals("None", ignoreCase = true)
    }
}
