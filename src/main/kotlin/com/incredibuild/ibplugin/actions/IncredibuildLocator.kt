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
import com.intellij.openapi.util.SystemInfo
import java.io.File

object IncredibuildLocator {

    const val REGISTRY_KEY = "HKLM\\SOFTWARE\\WOW6432Node\\Xoreax\\Incredibuild\\Builder"
    private const val REGISTRY_VALUE_FOLDER = "Folder"
    private const val REGISTRY_VALUE_VERSION = "VersionText"

    // Unlike Windows, Linux Incredibuild has no queryable "where am I installed"
    // API - INSTALL_PREFIX is baked into ib_console at compile time
    // (tools/build/configure.mk in the ib_linux repo), always "/opt/incredibuild".
    // PENDING CONFIRMATION with the Linux Incredibuild developer: this mirrors
    // ib_console's own "am I allowed to run here" check
    // (access("/etc/incredibuild/init.d/incredibuild_server", X_OK), see
    // cpp/XgConsole/XgConsole_main.cpp), adapted into "is ib_console present and
    // executable" for our purposes.
    private const val LINUX_INSTALL_PREFIX = "/opt/incredibuild"
    private const val LINUX_CONSOLE_RELATIVE_PATH = "bin/ib_console"

    // "version_info.sh" in the ib_linux repo generates this file at install time,
    // as plain shell-variable-assignment lines (IB_VERSION='...', IB_SHORT_VERSION='...',
    // etc.) - IB_SHORT_VERSION specifically has any build-number/branch/"-dirty"
    // suffix already stripped (see its "${VERSION%%-*}" derivation), leaving a clean
    // dotted-numeric string comparable the same way as the Windows version.
    private const val LINUX_VERSION_FILE_RELATIVE_PATH = "data/version_info.src"
    private val LINUX_SHORT_VERSION_REGEX = Regex("""^IB_SHORT_VERSION='([^']*)'""", RegexOption.MULTILINE)

    /**
     * Resolves the Incredibuild install folder: via the registry on Windows, or a
     * fixed, well-known path on Linux (there is no registry equivalent there).
     */
    fun findInstallFolder(): File? {
        if (SystemInfo.isLinux) {
            val installDir = File(LINUX_INSTALL_PREFIX)
            val console = File(installDir, LINUX_CONSOLE_RELATIVE_PATH)
            return if (console.canExecute()) installDir else null
        }

        val value = queryRegistryValue(REGISTRY_VALUE_FOLDER) ?: return null
        return File(value)
    }

    /**
     * Reads the installed Incredibuild version - from the registry on Windows (e.g.
     * "10.37.0.12597"), or from `IB_SHORT_VERSION` in `version_info.src` on Linux
     * (e.g. "3.18.0") - or null if unavailable. The two platforms use unrelated
     * version-numbering schemes, so callers must compare against a platform-specific
     * minimum (see [com.incredibuild.ibplugin.actions.IncredibuildRunner]'s
     * `MINIMUM_RUST_BUILD_VERSION`/`MINIMUM_RUST_BUILD_VERSION_LINUX`), never mix them.
     */
    fun findVersion(): String? {
        if (SystemInfo.isLinux) {
            return findLinuxVersion()
        }
        return queryRegistryValue(REGISTRY_VALUE_VERSION)
    }

    private fun findLinuxVersion(): String? {
        val versionFile = File(LINUX_INSTALL_PREFIX, LINUX_VERSION_FILE_RELATIVE_PATH)
        if (!versionFile.canRead()) return null
        return try {
            val match = LINUX_SHORT_VERSION_REGEX.find(versionFile.readText()) ?: return null
            match.groupValues[1].takeIf { it.isNotBlank() }
        } catch (ex: Exception) {
            null
        }
    }

    /**
     * Compares two dotted numeric version strings (e.g. "10.37.0.12597") component by
     * component. Returns a negative number if [version] is older than [other], zero if
     * equal, and a positive number if [version] is newer. Missing or non-numeric
     * components are treated as 0.
     */
    fun compareVersions(version: String, other: String): Int {
        val versionParts = version.split(".")
        val otherParts = other.split(".")
        for (i in 0 until maxOf(versionParts.size, otherParts.size)) {
            val comparison = (versionParts.getOrNull(i)?.toIntOrNull() ?: 0)
                .compareTo(otherParts.getOrNull(i)?.toIntOrNull() ?: 0)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun queryRegistryValue(valueName: String): String? {
        if (!SystemInfo.isWindows) return null
        return try {
            val commandLine = GeneralCommandLine("reg", "query", REGISTRY_KEY, "/v", valueName)
            val output = ExecUtil.execAndGetOutput(commandLine)
            if (output.exitCode != 0) return null
            parseValueFromRegOutput(output.stdout, valueName)
        } catch (ex: Exception) {
            null
        }
    }

    private fun parseValueFromRegOutput(output: String, valueName: String): String? {
        val regex = Regex("""${Regex.escape(valueName)}\s+REG_SZ\s+(.+)""")
        for (line in output.lines()) {
            val match = regex.find(line.trim())
            if (match != null) {
                val value = match.groupValues[1].trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }
}
