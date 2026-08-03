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

    /**
     * Resolves the Incredibuild install folder via the registry only.
     * Reading HKLM does not require elevation, so this will not trigger UAC.
     * No PATH-based fallback is used by design.
     */
    fun findInstallFolder(): File? {
        val value = queryRegistryValue(REGISTRY_VALUE_FOLDER) ?: return null
        return File(value)
    }

    /** Reads the installed Incredibuild version (e.g. "10.37.0.12597") from the registry, or null if unavailable. */
    fun findVersion(): String? = queryRegistryValue(REGISTRY_VALUE_VERSION)

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
