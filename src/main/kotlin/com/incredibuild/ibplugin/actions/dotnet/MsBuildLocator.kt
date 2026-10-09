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
package com.incredibuild.ibplugin.actions.dotnet

import com.incredibuild.ibplugin.settings.IncredibuildSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import java.io.File

private val LOG = Logger.getInstance("com.incredibuild.ibplugin.actions.dotnet.MsBuildLocator")

private const val VSWHERE_TIMEOUT_MS = 15_000

private const val MSBUILD_COMPONENT = "Microsoft.Component.MSBuild"

/**
 * The ".NET SDK" Visual Studio component. Without it, a Visual Studio install's MSBuild has no
 * .NET SDK resolver (Microsoft.DotNet.MSBuildSdkResolver), so it can't build any SDK-style
 * project: every `<Project Sdk="Microsoft.NET.Sdk">` fails with MSB4236 "The SDK
 * 'Microsoft.NET.Sdk' specified could not be found". A C++-only Visual Studio install still has
 * Microsoft.Component.MSBuild, though, so requiring MSBuild alone isn't enough to tell them apart.
 */
private const val DOTNET_SDK_COMPONENT = "Microsoft.NetCore.Component.SDK"

/** What [MsBuildLocator.locate] came back with. */
internal sealed interface MsBuildLocation {
    class Found(val tool: MsBuildTool) : MsBuildLocation

    /** [reason] is shown to the user as-is. */
    class NotFound(val reason: String) : MsBuildLocation
}

/**
 * Finds the MSBuild to run accelerated .NET builds with.
 *
 * Rider picks its own MSBuild in its backend settings (Build, Execution, Deployment | Toolset and
 * Build), which the frontend this plugin runs in has no API to read - so this follows the same
 * preference Rider's own automatic choice does instead, and lets the user override it in
 * Settings | Tools | Incredibuild when that isn't the one they use:
 *  1. The MSBuild configured in Settings | Tools | Incredibuild, if any.
 *  2. Windows: the newest Visual Studio / Build Tools MSBuild.exe that has the .NET SDK component,
 *     found the way Microsoft documents for it (vswhere). It builds both SDK-style and classic
 *     .NET Framework projects - and mixed solutions containing C++ ones - which `dotnet msbuild`
 *     can't. Just "the newest Visual Studio" isn't enough: a C++-only install can be newer than
 *     the one with .NET in it - see [DOTNET_SDK_COMPONENT].
 *  3. The .NET SDK's `dotnet msbuild`, from PATH or a standard install location.
 *  4. Windows: any Visual Studio MSBuild.exe, for classic .NET Framework-only solutions.
 */
internal object MsBuildLocator {

    fun locate(): MsBuildLocation {
        val configured = IncredibuildSettings.getInstance().msBuildPath
        if (configured.isNotBlank()) {
            return if (File(configured).isFile) {
                MsBuildLocation.Found(MsBuildTool.fromPath(configured))
            } else {
                MsBuildLocation.NotFound(
                    "The MSBuild executable configured in Settings | Tools | Incredibuild doesn't exist:\n$configured"
                )
            }
        }

        if (SystemInfo.isWindows) {
            findVisualStudioMsBuild(MSBUILD_COMPONENT, DOTNET_SDK_COMPONENT)
                ?.let { return MsBuildLocation.Found(MsBuildTool(it, emptyList(), it)) }
        }
        findDotNet()?.let { return MsBuildLocation.Found(MsBuildTool.fromPath(it)) }
        // Last resort: a Visual Studio without the .NET SDK component still builds classic
        // (non-SDK-style) .NET Framework projects, which is better than finding nothing at all.
        if (SystemInfo.isWindows) {
            findVisualStudioMsBuild(MSBUILD_COMPONENT)
                ?.let { return MsBuildLocation.Found(MsBuildTool(it, emptyList(), it)) }
        }

        return MsBuildLocation.NotFound(
            "No MSBuild was found on this machine: neither Visual Studio / Build Tools MSBuild nor the .NET SDK's " +
                "dotnet command. Install one, or point Settings | Tools | Incredibuild at the MSBuild to use."
        )
    }

    /** The newest Visual Studio / Build Tools MSBuild.exe whose install has all [requiredComponents]. */
    private fun findVisualStudioMsBuild(vararg requiredComponents: String): String? {
        val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: return null
        val vswhere = File(programFilesX86, "Microsoft Visual Studio\\Installer\\vswhere.exe")
        if (!vswhere.isFile) return null
        return try {
            val output = ExecUtil.execAndGetOutput(
                GeneralCommandLine(vswhere.absolutePath, "-latest", "-prerelease", "-products", "*", "-requires")
                    // vswhere's -requires takes several ids and, without -requiresAny, matches only
                    // installs that have every one of them.
                    .withParameters(*requiredComponents)
                    .withParameters("-find", "MSBuild\\**\\Bin\\MSBuild.exe"),
                VSWHERE_TIMEOUT_MS
            )
            if (output.exitCode != 0) return null
            output.stdoutLines.map { it.trim() }.firstOrNull { it.isNotEmpty() && File(it).isFile }
        } catch (e: Exception) {
            LOG.warn("Could not query vswhere for MSBuild", e)
            null
        }
    }

    private fun findDotNet(): String? {
        val executableName = if (SystemInfo.isWindows) "dotnet.exe" else "dotnet"
        PathEnvironmentVariableUtil.findInPath(executableName)?.let { return it.absolutePath }

        val candidates = buildList {
            System.getenv("DOTNET_ROOT")?.let { add(File(it, executableName)) }
            if (SystemInfo.isWindows) {
                System.getenv("ProgramFiles")?.let { add(File(it, "dotnet\\$executableName")) }
            } else {
                add(File("/usr/share/dotnet/dotnet"))
                add(File("/usr/lib/dotnet/dotnet"))
                add(File("/usr/local/share/dotnet/dotnet"))
            }
            add(File(System.getProperty("user.home"), ".dotnet${File.separator}$executableName"))
        }
        return candidates.firstOrNull { it.isFile }?.absolutePath
    }
}
