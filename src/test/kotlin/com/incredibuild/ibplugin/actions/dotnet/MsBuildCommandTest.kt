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
        Project("{2150E333-8FDC-42A3-9474-1A3956D46DE8}") = "src", "src", "{33333333-3333-3333-3333-333333333333}"
        EndProject
        Project("{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}") = "App", "src\App\App.csproj", "{11111111-1111-1111-1111-111111111111}"
            ProjectSection(ProjectDependencies) = postProject
                {22222222-2222-2222-2222-222222222222} = {22222222-2222-2222-2222-222222222222}
            EndProjectSection
        EndProject
        Project("{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}") = "My.Lib", "Lib\Lib.csproj", "{22222222-2222-2222-2222-222222222222}"
        EndProject
        Global
            GlobalSection(NestedProjects) = preSolution
                {11111111-1111-1111-1111-111111111111} = {33333333-3333-3333-3333-333333333333}
            EndGlobalSection
        EndGlobal
    """.trimIndent()

    private val targets = mapOf(normalizedPath(appProject) to "src\\App", normalizedPath(libProject) to "My_Lib")

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
    fun `sln project targets follow solution folders and MSBuild's name cleansing`() {
        // App's ProjectDependencies line has the same {guid} = {guid} shape as a nesting one - it
        // must not make Lib App's parent.
        assertEquals(targets, solutionProjectTargets(sln, solutionDir, isSlnx = false))
    }

    @Test
    fun `sln project targets nest folders inside folders`() {
        val nested = """
            Project("{2150E333-8FDC-42A3-9474-1A3956D46DE8}") = "src", "src", "{33333333-3333-3333-3333-333333333333}"
            EndProject
            Project("{2150E333-8FDC-42A3-9474-1A3956D46DE8}") = "my.dir (x)", "my.dir (x)", "{44444444-4444-4444-4444-444444444444}"
            EndProject
            Project("{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}") = "Lib", "Lib\Lib.csproj", "{22222222-2222-2222-2222-222222222222}"
            EndProject
            Global
                GlobalSection(NestedProjects) = preSolution
                    {44444444-4444-4444-4444-444444444444} = {33333333-3333-3333-3333-333333333333}
                    {22222222-2222-2222-2222-222222222222} = {44444444-4444-4444-4444-444444444444}
                EndGlobalSection
            EndGlobal
        """.trimIndent()
        assertEquals(
            mapOf(normalizedPath(libProject) to "src\\my_dir _x_\\Lib"),
            solutionProjectTargets(nested, solutionDir, isSlnx = false)
        )
    }

    @Test
    fun `slnx project targets use folder paths and project file names`() {
        val slnx = """
            <Solution>
              <Folder Name="/src/my.dir (x)/">
                <Project Path="Lib\Lib.csproj" />
              </Folder>
              <Project Path="src/App/App.csproj" />
            </Solution>
        """.trimIndent()
        assertEquals(
            mapOf(normalizedPath(libProject) to "src\\my_dir _x_\\Lib", normalizedPath(appProject) to "App"),
            solutionProjectTargets(slnx, solutionDir, isSlnx = true)
        )
    }

    @Test
    fun `projects build goes through the solution with one target per project`() {
        // Through the solution, so MSBuild maps every project - and every project it references -
        // to the solution's own configuration for it, instead of passing the selected one on.
        val build = projectsBuild(msBuild, solutionFile, listOf(appProject, libProject), "Release", "x64", targets)
        val command = build.commands.single()
        assertEquals(
            listOf(solutionFile, "-t:src\\App;My_Lib", "-p:Configuration=Release", "-p:Platform=x64"),
            command.arguments.take(4)
        )
        assertTrue(command.arguments.containsAll(COMMON_MSBUILD_ARGUMENTS))
        assertEquals(solutionDir.path, build.workingDirectory)
    }

    @Test
    fun `builds leave restore to Rider by default`() {
        val solution = solutionBuild(msBuild, solutionFile, DotNetBuildKind.BUILD_SOLUTION, "Debug", "Any CPU")
        val projects = projectsBuild(msBuild, solutionFile, listOf(appProject), "Debug", "Any CPU", targets)
        assertFalse(solution.commands.single().arguments.contains(RESTORE_ARGUMENT))
        assertFalse(projects.commands.single().arguments.contains(RESTORE_ARGUMENT))
    }

    @Test
    fun `builds restore when asked to`() {
        val solution = solutionBuild(msBuild, solutionFile, DotNetBuildKind.BUILD_SOLUTION, "Debug", "Any CPU", restore = true)
        val projects =
            projectsBuild(msBuild, solutionFile, listOf(appProject), "Debug", "Any CPU", targets, restore = true)
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
    fun `solutionFilterProjectFiles lists only the filter's projects, relative to the solution`() {
        // The filter in a subfolder of the solution, as Rider allows: its solution path is relative
        // to the filter, its projects to the solution - which is how MSBuild resolves them.
        val filterDir = File(solutionDir, "filters")
        val slnf = """
            {
              "solution": {
                "path": "..\\Sln.sln",
                "projects": [
                  "src\\App\\App.csproj"
                ]
              }
            }
        """.trimIndent()
        assertEquals(listOf(appProject), solutionFilterProjectFiles(slnf, filterDir))
    }

    @Test
    fun `solutionFilterProjectFiles accepts forward slashes`() {
        val slnf = """{"solution": {"path": "Sln.sln", "projects": ["src/App/App.csproj", "Lib/Lib.csproj"]}}"""
        assertEquals(listOf(appProject, libProject), solutionFilterProjectFiles(slnf, solutionDir))
    }

    @Test
    fun `a filter excluding the only unrestored project doesn't need restore`() = withTempDir { root ->
        // App is restored, Unrestored never was; a filter with only App mustn't ask for -restore,
        // though the whole solution would.
        val sdkProject = """<Project Sdk="Microsoft.NET.Sdk"></Project>"""
        val app = File(root, "App/App.csproj").apply { parentFile.mkdirs(); writeText(sdkProject) }
        File(root, "App/obj/project.assets.json").apply { parentFile.mkdirs(); writeText("{}") }
        val unrestored = File(root, "Unrestored/Unrestored.csproj").apply { parentFile.mkdirs(); writeText(sdkProject) }
        val slnf = """{"solution": {"path": "Sln.sln", "projects": ["App\\App.csproj"]}}"""

        assertFalse(needsRestore(solutionFilterProjectFiles(slnf, root)))
        assertTrue(needsRestore(listOf(app.path, unrestored.path)))
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
                "if not \"%ERRORLEVEL%\"==\"0\" exit /b %ERRORLEVEL%\r\n" +
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
    fun `banner project list is capped for large selections`() {
        assertEquals("App", DotNetBuildRunner.cappedProjectList(listOf("App")))
        // Three still fit: "+1 more" would be no shorter than the name it hides.
        assertEquals("App, Lib, Tests", DotNetBuildRunner.cappedProjectList(listOf("App", "Lib", "Tests")))
        assertEquals(
            "P0, P1 +12 more",
            DotNetBuildRunner.cappedProjectList((0 until 14).map { "P$it" })
        )
    }

    @Test
    fun `msbuild builds leave Build Cache off`() {
        val tool = MsBuildTool("msbuild", emptyList(), "MSBuild")
        assertFalse(solutionBuild(tool, "Sln.sln", DotNetBuildKind.BUILD_SOLUTION, "Debug", "Any CPU").buildCache)
        assertFalse(
            projectsBuild(tool, "Sln.sln", listOf(appProject), "Debug", "Any CPU", mapOf(normalizedPath(appProject) to "src\\App"))
                .buildCache
        )
    }

    @Test
    fun `linux wrapper passes the build cache options only when the build wants them`() {
        val command = listOf(NativeCommand("cmake", listOf("--build", "out")))
        val cached = IncredibuildRunner.linuxWrapperScript("P", "/ib", NativeBuild(command, "."))
        val uncached = IncredibuildRunner.linuxWrapperScript("P", "/ib", NativeBuild(command, ".", buildCache = false))
        assertTrue(cached, cached.contains(" --ib-quiet --build-cache-local-shared --build-cache-basedir=\"\$PWD\" -- "))
        assertTrue(uncached, uncached.contains(" --ib-quiet -- "))
        assertFalse(uncached, uncached.contains("build-cache"))
    }

    @Test
    fun `windows launcher passes BUILDCACHELOCAL only when the build wants it`() {
        val cached = IncredibuildRunner.windowsLauncherScript("BuildConsole.exe", "w.cmd", "P", buildCache = true)
        val uncached = IncredibuildRunner.windowsLauncherScript("BuildConsole.exe", "w.cmd", "P", buildCache = false)
        assertEquals(
            "& \"BuildConsole.exe\" '/command=\"w.cmd\"' '/title=\"P\"' /BUILDCACHELOCAL=ON\nexit \$LASTEXITCODE\n",
            cached
        )
        assertEquals("& \"BuildConsole.exe\" '/command=\"w.cmd\"' '/title=\"P\"'\nexit \$LASTEXITCODE\n", uncached)
    }

    @Test
    fun `linux wrapper runs a single command directly`() {
        val build = NativeBuild(listOf(NativeCommand("cmake", listOf("--build", "out"))), ".")
        val script = IncredibuildRunner.linuxWrapperScript("P", "/ib", build)
        assertTrue(script, script.endsWith("-- 'cmake' '--build' 'out' 2>&1\n"))
    }
}
