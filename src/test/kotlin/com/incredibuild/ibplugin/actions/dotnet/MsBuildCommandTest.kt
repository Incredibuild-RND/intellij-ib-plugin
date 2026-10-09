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

import com.incredibuild.ibplugin.actions.IncredibuildRunner
import com.incredibuild.ibplugin.actions.IncredibuildRunner.NativeBuild
import com.incredibuild.ibplugin.actions.IncredibuildRunner.NativeCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Covers how the .NET actions turn Rider's state into MSBuild invocations, and the wrapper
 * scripts those invocations are run through. */
class MsBuildCommandTest {

    private val solutionDir = File("work", "Sln").absoluteFile
    private val solutionFile = File(solutionDir, "App.sln").path
    private val appProject = File(solutionDir, "src${File.separator}App${File.separator}App.csproj").path
    private val libProject = File(solutionDir, "Lib${File.separator}Lib.csproj").path

    private val msBuild = MsBuildTool("C:\\VS\\MSBuild.exe", emptyList(), "MSBuild")

    private val sln = """
        Microsoft Visual Studio Solution File, Format Version 12.00
        Project("{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}") = "App", "src\App\App.csproj", "{11111111-1111-1111-1111-111111111111}"
        EndProject
        Project("{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}") = "Lib", "Lib\Lib.csproj", "{22222222-2222-2222-2222-222222222222}"
        EndProject
        Global
            GlobalSection(ProjectConfigurationPlatforms) = postSolution
                {11111111-1111-1111-1111-111111111111}.Debug|Any CPU.ActiveCfg = Debug|Any CPU
                {11111111-1111-1111-1111-111111111111}.Debug|Any CPU.Build.0 = Debug|Any CPU
                {22222222-2222-2222-2222-222222222222}.Debug|Any CPU.ActiveCfg = Release|x64
                {22222222-2222-2222-2222-222222222222}.Debug|Any CPU.Build.0 = Release|x64
            EndGlobalSection
        EndGlobal
    """.trimIndent()

    @Test
    fun `MsBuildTool fromPath runs dotnet through its msbuild verb`() {
        val tool = MsBuildTool.fromPath("C:\\Program Files\\dotnet\\dotnet.exe")
        assertEquals(listOf("msbuild"), tool.leadingArguments)
    }

    @Test
    fun `MsBuildTool fromPath runs MSBuild exe directly`() {
        assertEquals(emptyList<String>(), MsBuildTool.fromPath("C:\\VS\\MSBuild.exe").leadingArguments)
    }

    @Test
    fun `solution build passes the target and active configuration unchanged`() {
        val build = solutionBuild(msBuild, solutionFile, DotNetBuildKind.REBUILD_SOLUTION, "Debug", "Any CPU")
        val command = build.commands.single()
        assertEquals(msBuild.executable, command.executable)
        assertEquals(
            listOf(solutionFile, "-t:Rebuild", "-p:Configuration=Debug", "-p:Platform=Any CPU"),
            command.arguments.take(4)
        )
        assertTrue(command.arguments.containsAll(COMMON_MSBUILD_ARGUMENTS))
        assertEquals(solutionDir.path, build.workingDirectory)
    }

    @Test
    fun `solution build through dotnet puts msbuild first`() {
        val dotnet = MsBuildTool.fromPath("/usr/bin/dotnet")
        val command = solutionBuild(dotnet, solutionFile, DotNetBuildKind.BUILD_SOLUTION, "Debug", "Any CPU").commands.single()
        assertEquals(listOf("msbuild", solutionFile, "-t:Build"), command.arguments.take(3))
    }

    @Test
    fun `sln parsing maps each project to its own configuration`() {
        val mappings = parseSolutionProjectConfigurations(sln, solutionDir)
        assertEquals("Debug|Any CPU", mappings[normalizedPath(appProject)]?.get("Debug|Any CPU"))
        assertEquals("Release|x64", mappings[normalizedPath(libProject)]?.get("Debug|Any CPU"))
    }

    @Test
    fun `projects build uses each project's mapped configuration and solution context`() {
        val mappings = parseSolutionProjectConfigurations(sln, solutionDir)
        val build = projectsBuild(msBuild, solutionFile, listOf(appProject, libProject), "Debug", "Any CPU", mappings)

        val (app, lib) = build.commands
        assertEquals(listOf(appProject, "-t:Build", "-p:Configuration=Debug", "-p:Platform=AnyCPU"), app.arguments.take(4))
        assertEquals(listOf(libProject, "-t:Build", "-p:Configuration=Release", "-p:Platform=x64"), lib.arguments.take(4))
        assertTrue(app.arguments.contains("-p:SolutionDir=${solutionDir.path}${File.separator}"))
        assertTrue(app.arguments.contains("-p:SolutionPath=$solutionFile"))
        assertTrue(app.arguments.contains("-p:SolutionName=App"))
        assertTrue(app.arguments.contains("-p:SolutionExt=.sln"))
    }

    @Test
    fun `projects build falls back to the solution configuration without a mapping`() {
        val build = projectsBuild(msBuild, solutionFile, listOf(appProject), "Release", "Any CPU", emptyMap())
        assertEquals(
            listOf(appProject, "-t:Build", "-p:Configuration=Release", "-p:Platform=AnyCPU"),
            build.commands.single().arguments.take(4)
        )
    }

    @Test
    fun `projectPlatformName only rewrites Any CPU`() {
        assertEquals("AnyCPU", projectPlatformName("Any CPU"))
        assertEquals("x64", projectPlatformName("x64"))
        assertEquals("Mixed Platforms", projectPlatformName("Mixed Platforms"))
    }

    @Test
    fun `windows wrapper stops at the first failing command`() {
        val build = NativeBuild(
            listOf(NativeCommand("msbuild", listOf("a b.csproj")), NativeCommand("msbuild", listOf("c.csproj"))),
            "."
        )
        assertEquals(
            "@echo off\r\n" +
                "msbuild \"a b.csproj\"\r\n" +
                "if errorlevel 1 exit /b %ERRORLEVEL%\r\n" +
                "msbuild c.csproj\r\n" +
                "exit /b %ERRORLEVEL%\r\n",
            IncredibuildRunner.windowsWrapperScript(build)
        )
    }

    @Test
    fun `windows wrapper for a single command is unchanged from the CMake one`() {
        val build = NativeBuild(listOf(NativeCommand("cmake", listOf("--build", "out"))), ".", mapOf("A" to "1"))
        assertEquals(
            "@echo off\r\nset \"A=1\"\r\ncmake --build out\r\nexit /b %ERRORLEVEL%\r\n",
            IncredibuildRunner.windowsWrapperScript(build)
        )
    }

    @Test
    fun `linux wrapper chains several commands inside one ib_console`() {
        val build = NativeBuild(
            listOf(NativeCommand("dotnet", listOf("msbuild", "a.csproj")), NativeCommand("dotnet", listOf("msbuild", "b.csproj"))),
            "."
        )
        val script = IncredibuildRunner.linuxWrapperScript("Sln", "/opt/incredibuild/bin/ib_console", build)
        assertTrue(
            script,
            script.endsWith(
                "-- /bin/sh -c ''\\''dotnet'\\'' '\\''msbuild'\\'' '\\''a.csproj'\\'' && " +
                    "'\\''dotnet'\\'' '\\''msbuild'\\'' '\\''b.csproj'\\''' 2>&1\n"
            )
        )
    }

    @Test
    fun `linux wrapper runs a single command directly`() {
        val build = NativeBuild(listOf(NativeCommand("cmake", listOf("--build", "out"))), ".")
        val script = IncredibuildRunner.linuxWrapperScript("P", "/ib", build)
        assertTrue(script, script.endsWith("-- 'cmake' '--build' 'out' 2>&1\n"))
    }
}
