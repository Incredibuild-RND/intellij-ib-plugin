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

/** How long the license query may take before it's treated as inconclusive - see [IncredibuildInstallation.queryLicense]. */
private const val LICENSE_QUERY_TIMEOUT_MS = 5_000

/** Matches the "InitiatorRegType Floating" / "CIInitiatorRegType None" lines of `BuildConsole /QueryLicense`. */
private val INITIATOR_REG_TYPE_REGEX = Regex("""^\s*(?:CI)?InitiatorRegType\s+(\S+)""", RegexOption.MULTILINE)

/**
 * Which kind of build an installation is being validated for. Rust build support needed a
 * specific Incredibuild release (see [minimumVersion]); the native CMake/MSBuild support has no
 * known minimum.
 */
enum class BuildFlavor {
    RUST,
    NATIVE;

    /** The oldest supported Incredibuild version for this flavor on the current OS, or null if any version is fine. */
    fun minimumVersion(): String? = when (this) {
        RUST -> if (SystemInfo.isLinux) MINIMUM_RUST_BUILD_VERSION_LINUX else MINIMUM_RUST_BUILD_VERSION
        NATIVE -> null
    }

    internal companion object {
        const val MINIMUM_RUST_BUILD_VERSION = "10.37.1"

        // The Linux team found an issue with an earlier released Linux Incredibuild
        // version during RustRover integration testing, hence this gate. Deliberately a
        // separate constant, not shared with the Windows one: Windows and Linux
        // Incredibuild use entirely unrelated version-numbering schemes (e.g.
        // "10.37.0.12597" vs "3.18.0").
        const val MINIMUM_RUST_BUILD_VERSION_LINUX = "4.29.3"
    }
}

/**
 * The Incredibuild installation the plugin would use, and whether it can accelerate a build -
 * shown on the settings page, and consulted before the IDE's own build actions are routed
 * through Incredibuild (where anything but [Ready] falls back to the IDE's standard build).
 */
sealed interface InstallationStatus {
    /** The install folder, when one was found at all. */
    val installFolder: File?

    object NotInstalled : InstallationStatus {
        override val installFolder: File? = null
    }

    class UnsupportedVersion(
        override val installFolder: File,
        val installedVersion: String,
        val minimumVersion: String
    ) : InstallationStatus

    class NotLicensed(override val installFolder: File, val installedVersion: String?) : InstallationStatus

    /**
     * Usable. [licensed] is null where the license couldn't be determined (always on Linux,
     * which has no license query; on Windows if the query failed) - that isn't treated as a
     * reason to fall back, since Incredibuild itself reports a licensing problem in the build
     * output anyway.
     */
    class Ready(
        override val installFolder: File,
        val installedVersion: String?,
        val licensed: Boolean?
    ) : InstallationStatus
}

object IncredibuildInstallation {

    /**
     * Locates and validates the installation for [flavor]. Blocking (registry query, file reads
     * and a short-lived BuildConsole process), so never call it on the EDT.
     */
    fun check(flavor: BuildFlavor): InstallationStatus {
        val installFolder = IncredibuildLocator.findInstallFolder() ?: return InstallationStatus.NotInstalled
        val installedVersion = IncredibuildLocator.findVersion()

        val minimumVersion = flavor.minimumVersion()
        if (minimumVersion != null && installedVersion != null &&
            IncredibuildLocator.compareVersions(installedVersion, minimumVersion) < 0
        ) {
            return InstallationStatus.UnsupportedVersion(installFolder, installedVersion, minimumVersion)
        }

        val licensed = queryLicense(installFolder)
        if (licensed == false) return InstallationStatus.NotLicensed(installFolder, installedVersion)
        return InstallationStatus.Ready(installFolder, installedVersion, licensed)
    }

    /**
     * Whether this machine holds an Incredibuild initiator license, from `BuildConsole
     * /QueryLicense` - or null if that can't be determined (Linux, a missing BuildConsole, a
     * failed or timed-out query, or output in an unrecognized shape).
     */
    private fun queryLicense(installFolder: File): Boolean? {
        if (!SystemInfo.isWindows) return null
        val buildConsole = File(installFolder, "BuildConsole.exe")
        if (!buildConsole.exists()) return null
        return try {
            val output = ExecUtil.execAndGetOutput(
                GeneralCommandLine(buildConsole.absolutePath, "/QueryLicense"),
                LICENSE_QUERY_TIMEOUT_MS
            )
            if (output.isTimeout || output.exitCode != 0) null else parseLicenseQuery(output.stdout)
        } catch (e: Exception) {
            LOG.info("Could not query the Incredibuild license", e)
            null
        }
    }

    /**
     * Reads `BuildConsole /QueryLicense` output: licensed if either the developer
     * (`InitiatorRegType`) or CI (`CIInitiatorRegType`) initiator registration is anything but
     * "None" - a machine can start builds with either. Null if neither line is present.
     */
    internal fun parseLicenseQuery(output: String): Boolean? {
        val regTypes = INITIATOR_REG_TYPE_REGEX.findAll(output).map { it.groupValues[1] }.toList()
        if (regTypes.isEmpty()) return null
        return regTypes.any { !it.equals("None", ignoreCase = true) }
    }

    /** One line describing [status], for the settings page and fallback notifications. */
    fun describe(status: InstallationStatus): String = when (status) {
        InstallationStatus.NotInstalled -> "Incredibuild is not installed on this machine."
        is InstallationStatus.UnsupportedVersion ->
            "Incredibuild ${status.installedVersion} (${status.installFolder.absolutePath}) is not supported - " +
                "version ${status.minimumVersion} or later is required."
        is InstallationStatus.NotLicensed ->
            "Incredibuild${status.installedVersion?.let { " $it" } ?: ""} (${status.installFolder.absolutePath}) " +
                "is installed, but this machine has no Incredibuild license."
        is InstallationStatus.Ready -> buildString {
            append("Incredibuild")
            status.installedVersion?.let { append(' ').append(it) }
            append(" (").append(status.installFolder.absolutePath).append(')')
            if (status.licensed == true) append(" - licensed")
        }
    }
}
