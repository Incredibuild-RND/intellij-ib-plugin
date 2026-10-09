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

import com.incredibuild.ibplugin.actions.IncredibuildRunner.NativeBuild
import com.incredibuild.ibplugin.actions.IncredibuildRunner.NativeCommand
import java.io.File

/** Which of the three .NET "... with Incredibuild" actions is asking for a command. */
internal enum class DotNetBuildKind(val msBuildTarget: String) {
    /** "Build Solution with Incredibuild". */
    BUILD_SOLUTION("Build"),

    /** "Rebuild Solution with Incredibuild". */
    REBUILD_SOLUTION("Rebuild"),

    /** "Build Selected Projects with Incredibuild". */
    BUILD_PROJECTS("Build"),
}

/**
 * The MSBuild to run: [executable] plus any [leadingArguments] that must come before the
 * project/solution (`msbuild` for `dotnet msbuild`), and a [description] for the console banner.
 */
internal class MsBuildTool(val executable: String, val leadingArguments: List<String>, val description: String) {
    companion object {
        /** MSBuild.exe itself, or the dotnet host - which needs its `msbuild` verb first. */
        fun fromPath(path: String): MsBuildTool {
            val name = File(path).nameWithoutExtension
            return if (name.equals("dotnet", ignoreCase = true)) {
                MsBuildTool(path, listOf("msbuild"), "dotnet msbuild ($path)")
            } else {
                MsBuildTool(path, emptyList(), path)
            }
        }
    }
}

/**
 * Arguments shared by every accelerated MSBuild invocation, on top of the target and the active
 * configuration:
 *  - No `-restore` (see [RESTORE_ARGUMENT] for when it is added): Rider restores NuGet packages
 *    itself - on solution load, on project changes and before its own builds - and watches each
 *    project's restore outputs (obj\*.nuget.dgspec.json, *.nuget.g.props, project.assets.json).
 *    Any restore by another process rewrites those files, which Rider treats as "restore inputs
 *    changed": it re-restores every project on its next build, regenerating *.nuget.g.props - an
 *    import of every project, so an input of every compile - and recompiles the whole solution
 *    even though this build just did. Leaving restore to Rider keeps the accelerated build and
 *    Rider's own builds incremental with each other.
 *  - `-graph`: MSBuild's static graph mode - evaluate the whole project graph up front, then
 *    build it bottom-up, every project only once its references are done. Without it, a project
 *    that references many others (an app referencing all of a solution's libraries) builds those
 *    references itself, from whichever MSBuild node it happens to run on - and MSBuild pins each
 *    project to the first node that touches it, so every library that project reached first then
 *    builds on that one node, one after another. Observed on a 100-library solution: 16 libraries
 *    in parallel, then the remaining 84 strictly one at a time (4 min vs 1:48 with `-graph`) -
 *    leaving Incredibuild one compiler process at a time to distribute.
 *  - `-m`: lets MSBuild build independent projects concurrently, which is what gives Incredibuild
 *    more than one compiler process at a time to distribute. Deliberately unbounded (one node per
 *    local core) rather than the "Parallel jobs" setting: every MSBuild node is a full,
 *    long-lived process, and the hundreds of jobs that setting defaults to for Cargo/CMake would
 *    mean hundreds of them.
 *  - `-nodeReuse:false`: otherwise MSBuild leaves its worker nodes running after the build for the
 *    next one to reuse - processes that outlive the build they were started from, which
 *    Incredibuild would then keep waiting on before it considers the build finished.
 *  - `-p:UseSharedCompilation=false`: otherwise the C#/VB compilers don't run as their own
 *    processes at all, but as requests to one shared, long-lived VBCSCompiler server - which
 *    Incredibuild can't see, let alone distribute. Only changes how the compiler is launched, not
 *    what it compiles.
 *  - `-nologo`/`-v:minimal`: the same console verbosity Rider's own build output defaults to.
 *
 * None of these change anything about how the solution or its projects are configured - they
 * only affect this one command-line invocation.
 */
internal val COMMON_MSBUILD_ARGUMENTS = listOf(
    "-graph",
    "-m",
    "-nodeReuse:false",
    "-p:UseSharedCompilation=false",
    "-nologo",
    "-v:minimal",
)

/**
 * Added only when some project being built has never been restored (see [needsRestore]) - then
 * the build can't succeed without it, and there's no restore state of Rider's to disturb yet.
 */
internal const val RESTORE_ARGUMENT = "-restore"

/**
 * Whether any of [projectFiles] has no NuGet assets file yet (obj\project.assets.json, where the
 * .NET SDK puts it by default) - i.e. was never restored, so building it would fail with NETSDK1004.
 * A project that moves its intermediate folder elsewhere shows up here as unrestored, which only
 * costs it the restore it would have had anyway before this check existed.
 */
internal fun needsRestore(projectFiles: List<String>): Boolean =
    projectFiles.any { !File(File(it).absoluteFile.parentFile, "obj${File.separator}project.assets.json").isFile }

private val SLNX_PROJECT_PATH = Regex("""<Project\s[^>]*Path="([^"]+)"""")

/**
 * The project files a .sln or .slnx lists, resolved against its directory - for [needsRestore].
 * Solution folders and non-project entries (anything not ending in "proj") are skipped.
 */
internal fun solutionProjectFiles(solutionText: String, solutionDir: File): List<String> {
    val relativePaths = SLN_PROJECT_LINE.findAll(solutionText).map { it.groupValues[1] } +
        SLNX_PROJECT_PATH.findAll(solutionText).map { it.groupValues[1] }
    return relativePaths
        .filter { it.endsWith("proj", ignoreCase = true) }
        .map { File(solutionDir, it.replace('\\', File.separatorChar).replace('/', File.separatorChar)).path }
        .distinct()
        .toList()
}

/**
 * The accelerated build for a whole solution - the equivalent of Rider's own Build/Rebuild
 * Solution. [entryPoint] is the .sln/.slnx, or the .slnf when a solution filter is open (MSBuild
 * builds a filter directly, honoring which projects it includes).
 */
internal fun solutionBuild(
    tool: MsBuildTool,
    entryPoint: String,
    kind: DotNetBuildKind,
    configuration: String,
    platform: String,
    restore: Boolean = false
): NativeBuild {
    val arguments = tool.leadingArguments + listOf(
        entryPoint,
        "-t:${kind.msBuildTarget}",
        "-p:Configuration=$configuration",
        "-p:Platform=$platform",
    ) + restoreArguments(restore) + COMMON_MSBUILD_ARGUMENTS
    return NativeBuild(listOf(NativeCommand(tool.executable, arguments)), File(entryPoint).absoluteFile.parent)
}

/**
 * The accelerated build for individually selected projects - the equivalent of Rider's own
 * Build Selection, which also builds each selected project together with the projects it
 * references (MSBuild follows ProjectReferences by itself).
 *
 * Each project is built directly rather than through the solution, since a solution can only be
 * asked to build a specific project by a target name derived from its solution-folder path - so
 * the context the solution would otherwise have provided is passed explicitly instead:
 *  - the project's own configuration|platform that the solution's active configuration maps it
 *    to ([mappings], from [parseSolutionProjectConfigurations]) - falling back to the solution
 *    configuration's own names, as Visual Studio does for a project with no mapping of its own;
 *  - the `SolutionDir`/`SolutionPath`/... properties MSBuild would have set had it come in
 *    through the solution, which build logic commonly relies on (e.g. a shared output folder).
 *
 * One MSBuild invocation per project, run one after the other: MSBuild only accepts a single
 * project per invocation. Each one is still parallel within itself (see [COMMON_MSBUILD_ARGUMENTS]).
 */
internal fun projectsBuild(
    tool: MsBuildTool,
    solutionFile: String,
    projectFiles: List<String>,
    configuration: String,
    platform: String,
    mappings: Map<String, Map<String, String>>,
    restore: Boolean = false
): NativeBuild {
    val solution = File(solutionFile).absoluteFile
    val solutionDir = solution.parent.trimEnd(File.separatorChar) + File.separator
    val solutionProperties = listOf(
        "-p:SolutionDir=$solutionDir",
        "-p:SolutionPath=${solution.path}",
        "-p:SolutionName=${solution.nameWithoutExtension}",
        "-p:SolutionFileName=${solution.name}",
        "-p:SolutionExt=.${solution.extension}",
    )

    val commands = projectFiles.map { projectFile ->
        val mapped = mappings[normalizedPath(projectFile)]?.get("$configuration|$platform")
        val (projectConfiguration, projectPlatform) = mapped?.split('|', limit = 2)?.takeIf { it.size == 2 }
            ?: listOf(configuration, platform)
        val arguments = tool.leadingArguments + listOf(
            projectFile,
            "-t:${DotNetBuildKind.BUILD_PROJECTS.msBuildTarget}",
            "-p:Configuration=$projectConfiguration",
            "-p:Platform=${projectPlatformName(projectPlatform)}",
        ) + solutionProperties + restoreArguments(restore) + COMMON_MSBUILD_ARGUMENTS
        NativeCommand(tool.executable, arguments)
    }
    return NativeBuild(commands, solution.parent)
}

private fun restoreArguments(restore: Boolean): List<String> = if (restore) listOf(RESTORE_ARGUMENT) else emptyList()

/**
 * Solutions spell the .NET "any CPU" platform with a space ("Any CPU"), project files without
 * one ("AnyCPU") - MSBuild's own solution-to-project translation does the same rewrite when it
 * builds a project through a solution, so it has to be done here when building one directly.
 */
internal fun projectPlatformName(platform: String): String =
    if (platform.equals("Any CPU", ignoreCase = true)) "AnyCPU" else platform

private val SLN_PROJECT_LINE = Regex(
    """^Project\("\{[^}]*}"\)\s*=\s*"[^"]*"\s*,\s*"([^"]*)"\s*,\s*"\{([^}]*)}"""",
    RegexOption.MULTILINE
)

/** e.g. `{GUID}.Debug|Any CPU.ActiveCfg = Debug|x64` */
private val SLN_ACTIVE_CFG_LINE = Regex(
    """^\s*\{([^}]*)}\.([^.=]+\|[^=]+?)\.ActiveCfg\s*=\s*(.+?)\s*$""",
    RegexOption.MULTILINE
)

/**
 * Reads a classic (.sln) solution's own record of which project configuration|platform each
 * solution configuration|platform builds (its `ProjectConfigurationPlatforms` section), keyed by
 * [normalizedPath] of each project file and then by solution "Configuration|Platform".
 *
 * Returns an empty map for anything else (e.g. an XML .slnx, which only lists the exceptions to
 * the same-names default, or a .sln this can't parse) - [projectsBuild] then falls back to the
 * solution configuration's own names, which is what such solutions mean by default anyway.
 */
internal fun parseSolutionProjectConfigurations(solutionText: String, solutionDir: File): Map<String, Map<String, String>> {
    val projectPathByGuid = HashMap<String, String>()
    for (match in SLN_PROJECT_LINE.findAll(solutionText)) {
        val relativePath = match.groupValues[1].replace('\\', File.separatorChar).replace('/', File.separatorChar)
        val guid = match.groupValues[2].uppercase()
        projectPathByGuid[guid] = normalizedPath(File(solutionDir, relativePath).path)
    }

    val result = HashMap<String, MutableMap<String, String>>()
    for (match in SLN_ACTIVE_CFG_LINE.findAll(solutionText)) {
        val projectPath = projectPathByGuid[match.groupValues[1].uppercase()] ?: continue
        result.getOrPut(projectPath) { HashMap() }[match.groupValues[2].trim()] = match.groupValues[3]
    }
    return result
}

/** Canonical, case-insensitive form of a path, so a project path from Rider's model matches the
 * one resolved from the .sln regardless of `..`/separator/casing differences. */
internal fun normalizedPath(path: String): String =
    File(path).absoluteFile.normalize().path.replace('\\', '/').lowercase()
