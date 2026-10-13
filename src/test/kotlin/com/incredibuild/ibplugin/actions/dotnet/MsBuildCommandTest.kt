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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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

    private val slnx = """
        <Solution>
          <Configurations>
            <BuildType Name="Debug" />
            <BuildType Name="Release" />
            <Platform Name="Any CPU" />
            <Platform Name="x64" />
            <Platform Name="x86" />
          </Configurations>
          <Folder Name="/src/">
            <Project Path="src/App/App.csproj">
              <Platform Solution="*|x64" Project="x64" />
              <BuildType Solution="Debug|*" Project="Release" />
            </Project>
          </Folder>
          <Project Path="Lib\Lib.csproj" />
          <Project Path="Native\Native.vcxproj" />
        </Solution>
    """.trimIndent()

    @Test
    fun `slnx parsing maps managed projects to Any CPU by default`() {
        val lib = parseSlnxProjectConfigurations(slnx, solutionDir)[normalizedPath(libProject)]!!
        assertEquals("Debug|Any CPU", lib["Debug|Any CPU"])
        assertEquals("Release|Any CPU", lib["Release|x64"])
    }

    @Test
    fun `slnx parsing applies a project's own rules over the defaults`() {
        val app = parseSlnxProjectConfigurations(slnx, solutionDir)[normalizedPath(appProject)]!!
        assertEquals("Release|x64", app["Debug|x64"])
        assertEquals("Release|Any CPU", app["Debug|Any CPU"])
        assertEquals("Release|x64", app["Release|x64"])
    }

    @Test
    fun `slnx parsing maps x86 to Win32 for C++ projects`() {
        val native = File(solutionDir, "Native${File.separator}Native.vcxproj").path
        val mapping = parseSlnxProjectConfigurations(slnx, solutionDir)[normalizedPath(native)]!!
        assertEquals("Debug|Win32", mapping["Debug|x86"])
        assertEquals("Debug|x64", mapping["Debug|x64"])
    }

    @Test
    fun `slnx parsing defaults the solution configurations when none are declared`() {
        val mappings = parseSlnxProjectConfigurations("""<Solution><Project Path="Lib/Lib.csproj" /></Solution>""", solutionDir)
        assertEquals(setOf("Debug|Any CPU", "Release|Any CPU"), mappings[normalizedPath(libProject)]!!.keys)
    }

    @Test
    fun `projects build of an slnx keeps a managed project on AnyCPU for an x64 solution`() {
        val mappings = parseSlnxProjectConfigurations(slnx, solutionDir)
        val build = projectsBuild(msBuild, solutionFile, listOf(libProject), "Debug", "x64", mappings)
        assertEquals(
            listOf(libProject, "-t:Build", "-p:Configuration=Debug", "-p:Platform=AnyCPU"),
            build.commands.single().arguments.take(4)
        )
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
    fun `builds leave restore to Rider by default`() {
        val solution = solutionBuild(msBuild, solutionFile, DotNetBuildKind.BUILD_SOLUTION, "Debug", "Any CPU")
        val projects = projectsBuild(msBuild, solutionFile, listOf(appProject), "Debug", "Any CPU", emptyMap())
        assertFalse(solution.commands.single().arguments.contains(RESTORE_ARGUMENT))
        assertFalse(projects.commands.single().arguments.contains(RESTORE_ARGUMENT))
    }

    @Test
    fun `builds restore when asked to`() {
        val solution = solutionBuild(msBuild, solutionFile, DotNetBuildKind.BUILD_SOLUTION, "Debug", "Any CPU", restore = true)
        val projects =
            projectsBuild(msBuild, solutionFile, listOf(appProject), "Debug", "Any CPU", emptyMap(), restore = true)
        assertTrue(solution.commands.single().arguments.contains(RESTORE_ARGUMENT))
        assertTrue(projects.commands.single().arguments.contains(RESTORE_ARGUMENT))
    }

    private fun withTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("msbuild-restore-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun File.project(relativePath: String, text: String) =
        File(this, relativePath).apply { parentFile.mkdirs(); writeText(text) }

    private fun File.assetsFile(relativeDir: String) =
        File(this, "$relativeDir/project.assets.json").apply { parentFile.mkdirs(); writeText("{}") }

    private val sdkProject = """<Project Sdk="Microsoft.NET.Sdk"/>"""

    @Test
    fun `needsRestore is true only when an SDK-style project has no assets file`() = withTempDir { root ->
        val restored = root.project("Restored/Restored.csproj", sdkProject)
        root.assetsFile("Restored/obj")
        val fresh = root.project("Fresh/Fresh.csproj", sdkProject)

        assertFalse(needsRestore(listOf(restored.path)))
        assertTrue(needsRestore(listOf(restored.path, fresh.path)))
        assertFalse(needsRestore(emptyList()))
    }

    @Test
    fun `needsRestore ignores projects that never get an assets file`() = withTempDir { root ->
        val classic = root.project("Classic/Classic.csproj", """<Project ToolsVersion="15.0"><Import Project="x.props"/></Project>""")
        val shared = root.project("Shared/Shared.shproj", """<Project><Import Project="Shared.projitems"/></Project>""")
        val cpp = root.project("Native/Native.vcxproj", """<Project DefaultTargets="Build"/>""")
        val missing = File(root, "Gone/Gone.csproj").path

        assertFalse(needsRestore(listOf(classic.path, shared.path, cpp.path, missing)))
    }

    @Test
    fun `needsRestore counts classic projects using PackageReference`() = withTempDir { root ->
        val classic = root.project(
            "Classic/Classic.csproj",
            """<Project ToolsVersion="15.0"><ItemGroup><PackageReference Include="X"/></ItemGroup></Project>"""
        )
        assertTrue(needsRestore(listOf(classic.path)))
        root.assetsFile("Classic/obj")
        assertFalse(needsRestore(listOf(classic.path)))
    }

    @Test
    fun `needsRestore finds the assets file in the artifacts output layout`() = withTempDir { root ->
        val app = root.project("src/App/App.csproj", sdkProject)
        assertTrue(needsRestore(listOf(app.path)))
        root.assetsFile("artifacts/obj/App")
        assertFalse(needsRestore(listOf(app.path)))
    }

    @Test
    fun `solutionProjectFiles lists the projects of an sln`() {
        assertEquals(
            listOf(appProject, libProject),
            solutionProjectFiles(sln, solutionDir)
        )
    }

    @Test
    fun `solutionProjectFiles lists the projects of an slnx and skips non-project entries`() {
        val slnx = """
            <Solution>
              <Folder Name="/src/">
                <Project Path="src/App/App.csproj" />
              </Folder>
              <Project Path="Lib\Lib.csproj" Type="Classic C#" />
              <File Path="README.md" />
            </Solution>
        """.trimIndent()
        assertEquals(listOf(appProject, libProject), solutionProjectFiles(slnx, solutionDir))
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
