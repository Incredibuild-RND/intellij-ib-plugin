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

import com.google.gson.JsonParser
import com.incredibuild.ibplugin.actions.IncredibuildRunner.NativeBuild
import com.incredibuild.ibplugin.actions.IncredibuildRunner.NativeCommand
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

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
            // Split on either separator rather than through File, whose notion of one depends on
            // the OS this runs on - so a Windows path reads the same everywhere (e.g. in tests).
            val name = path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
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
 * Whether any of [projectFiles] is a PackageReference-restored project with no NuGet assets file
 * yet - i.e. was never restored, so building it would fail with NETSDK1004.
 *
 * Only such projects ever get a project.assets.json, so everything else is skipped rather than
 * counted as unrestored: shared projects (.shproj), C++ projects (.vcxproj), classic projects
 * using packages.config, ... Counting those would add `-restore` to every build of a solution
 * that contains one - exactly the restore-state churn [COMMON_MSBUILD_ARGUMENTS] explains this
 * is meant to avoid. A project file that can't be read is skipped too, leaving restore to Rider.
 */
internal fun needsRestore(projectFiles: List<String>): Boolean =
    projectFiles.any { isUnrestoredPackageReferenceProject(File(it).absoluteFile) }

/** Every SDK-style project restores through PackageReference implicitly, as does a classic one
 * that lists a PackageReference or opts in with RestoreProjectStyle. */
private val PACKAGE_REFERENCE_PROJECT_MARKERS = listOf(
    Regex("""\bSdk\s*=\s*""""),          // <Project Sdk="..."> or <Import Project="Sdk.props" Sdk="...">
    Regex("""<Sdk\s"""),                 // <Sdk Name="..." />
    Regex("""<PackageReference\b"""),
    Regex("""<RestoreProjectStyle\b"""),
)

private fun isUnrestoredPackageReferenceProject(projectFile: File): Boolean {
    val text = try {
        projectFile.readText()
    } catch (e: IOException) {
        return false
    }
    if (PACKAGE_REFERENCE_PROJECT_MARKERS.none { it.containsMatchIn(text) }) return false
    return assetsFileCandidates(projectFile).none { it.isFile }
}

/**
 * Where the .NET SDK may have put [projectFile]'s project.assets.json:
 *  - obj\ next to the project, the default;
 *  - artifacts\obj\<project name>\ in the project's folder or any folder above it, with the
 *    artifacts output layout (UseArtifactsOutput) - whose root defaults to the folder holding
 *    Directory.Build.props, which can be anywhere above the project.
 * A project that moves its intermediate folder anywhere else (a custom BaseIntermediateOutputPath)
 * shows up as unrestored, which only costs it a restore.
 */
private fun assetsFileCandidates(projectFile: File): Sequence<File> {
    val projectDir = projectFile.parentFile
    val artifactsLayout = generateSequence(projectDir) { it.parentFile }
        .map { File(it, "artifacts${File.separator}obj${File.separator}${projectFile.nameWithoutExtension}") }
    return (sequenceOf(File(projectDir, "obj")) + artifactsLayout).map { File(it, "project.assets.json") }
}

private val SLNX_PROJECT_PATH = Regex("""<Project\s[^>]*Path="([^"]+)"""")

/**
 * The project files a .sln or .slnx lists, resolved against its directory - for [needsRestore].
 * Solution folders and non-project entries (anything not ending in "proj") are skipped.
 */
internal fun solutionProjectFiles(solutionText: String, solutionDir: File): List<String> {
    val relativePaths = SLN_PROJECT_LINE.findAll(solutionText).map { it.groupValues[2] } +
        SLNX_PROJECT_PATH.findAll(solutionText).map { it.groupValues[1] }
    return relativePaths
        .filter { it.endsWith("proj", ignoreCase = true) }
        .map { File(solutionDir, nativeSeparators(it)).path }
        .distinct()
        .toList()
}

/**
 * The project files a solution filter (.slnf) includes - for [needsRestore], so a filtered build
 * only restores when one of *its* projects needs it, not when some project the filter leaves out
 * does. A filter is JSON, `{"solution": {"path": ..., "projects": [...]}}`: `path` is the .sln
 * relative to the filter's own directory ([filterDir]), and each project is relative to that
 * solution's directory, as MSBuild resolves them.
 */
internal fun solutionFilterProjectFiles(filterText: String, filterDir: File): List<String> {
    val solution = JsonParser.parseString(filterText).asJsonObject.getAsJsonObject("solution")
    val solutionDir = File(filterDir, nativeSeparators(solution.get("path").asString)).normalize().parentFile
    return solution.getAsJsonArray("projects")
        .map { File(solutionDir, nativeSeparators(it.asString)).path }
        .filter { it.endsWith("proj", ignoreCase = true) }
        .distinct()
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
    return NativeBuild(listOf(NativeCommand(tool.executable, arguments)), File(entryPoint).absoluteFile.parent, buildCache = false)
}

/**
 * The accelerated build for individually selected projects - the equivalent of Rider's own
 * Build Selection, which also builds each selected project together with the projects it
 * references.
 *
 * Built through the solution, asking it for just those projects by the per-project targets its
 * build defines ([targetNames], from [solutionProjectTargets] - every one of [projectFiles] must
 * be in it), rather than by building each project file directly. Only a solution build maps each
 * project, and each project it references, to the configuration|platform the solution's active
 * configuration assigns it: a project built directly passes its own -p:Configuration/-p:Platform
 * on to its references unchanged, so a library the solution builds as Debug|Any CPU would be
 * built as the app's Release|x64, into a different bin\ than Rider's own build uses. Going through
 * the solution also sets the SolutionDir/SolutionPath/... properties build logic relies on, and
 * builds all the selected projects in one parallel invocation.
 */
internal fun projectsBuild(
    tool: MsBuildTool,
    solutionFile: String,
    projectFiles: List<String>,
    configuration: String,
    platform: String,
    targetNames: Map<String, String>,
    restore: Boolean = false
): NativeBuild {
    val targets = projectFiles.map { targetNames.getValue(normalizedPath(it)) }
    val arguments = tool.leadingArguments + listOf(
        solutionFile,
        "-t:${targets.joinToString(";")}",
        "-p:Configuration=$configuration",
        "-p:Platform=$platform",
    ) + restoreArguments(restore) + COMMON_MSBUILD_ARGUMENTS
    return NativeBuild(listOf(NativeCommand(tool.executable, arguments)), File(solutionFile).absoluteFile.parent, buildCache = false)
}

private fun restoreArguments(restore: Boolean): List<String> = if (restore) listOf(RESTORE_ARGUMENT) else emptyList()

/** `Project("{type}") = "name", "path", "{guid}"` - solution folders included. */
private val SLN_PROJECT_LINE = Regex(
    """^Project\("\{[^}]*}"\)\s*=\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"\{([^}]*)}"""",
    RegexOption.MULTILINE
)

private val SLN_NESTED_PROJECTS_SECTION =
    Regex("""GlobalSection\(NestedProjects\)(.*?)EndGlobalSection""", RegexOption.DOT_MATCHES_ALL)

/** `{child} = {parent}` - only meaningful inside [SLN_NESTED_PROJECTS_SECTION]: a project's
 * ProjectDependencies section has lines of exactly the same shape. */
private val SLN_NESTED_LINE = Regex("""\{([^}]*)}\s*=\s*\{([^}]*)}""")

/** The characters MSBuild replaces with `_` in a solution's per-project target names. */
private const val TARGET_NAME_CHARACTERS_TO_CLEANSE = "%$@;.()'"

private fun cleansedTargetName(name: String): String =
    name.map { if (it in TARGET_NAME_CHARACTERS_TO_CLEANSE) '_' else it }.joinToString("")

/**
 * The target a solution build defines for building just one of its projects, keyed by
 * [normalizedPath] of each project file: the project's solution-folder path and its name, joined
 * with `\`, each part with [TARGET_NAME_CHARACTERS_TO_CLEANSE] replaced by `_` - e.g. the "My.Lib"
 * project in the "src" folder is `src\My_Lib`. That's the name MSBuild's solution build gives it,
 * for a .sln (project names as written in it, folders from its NestedProjects section) and a .slnx
 * (project file names, folders from each `<Folder Name="/a/b/">`) alike.
 */
internal fun solutionProjectTargets(solutionText: String, solutionDir: File, isSlnx: Boolean): Map<String, String> =
    if (isSlnx) slnxProjectTargets(solutionText, solutionDir) else slnProjectTargets(solutionText, solutionDir)

private fun slnProjectTargets(solutionText: String, solutionDir: File): Map<String, String> {
    class Entry(val name: String, val path: String)

    val entries = SLN_PROJECT_LINE.findAll(solutionText)
        .associate { it.groupValues[3].uppercase() to Entry(it.groupValues[1], it.groupValues[2]) }
    val parentOf = SLN_NESTED_PROJECTS_SECTION.find(solutionText)?.groupValues?.get(1)
        ?.let { section -> SLN_NESTED_LINE.findAll(section).associate { it.groupValues[1].uppercase() to it.groupValues[2].uppercase() } }
        .orEmpty()

    fun targetName(guid: String): String {
        val parts = ArrayList<String>()
        var current: String? = guid
        // Bounded by the number of entries, so a malformed (cyclic) NestedProjects can't hang this.
        while (current != null && parts.size <= entries.size) {
            parts += cleansedTargetName(entries.getValue(current).name)
            current = parentOf[current]?.takeIf { it in entries }
        }
        return parts.asReversed().joinToString("\\")
    }

    return entries.filterValues { it.path.endsWith("proj", ignoreCase = true) }
        .entries.associate { (guid, entry) -> normalizedPath(File(solutionDir, nativeSeparators(entry.path)).path) to targetName(guid) }
}

private fun slnxProjectTargets(solutionText: String, solutionDir: File): Map<String, String> {
    val projects = parseXml(solutionText).documentElement.getElementsByTagName("Project")
    val result = HashMap<String, String>()
    for (i in 0 until projects.length) {
        val project = projects.item(i) as Element
        val path = project.attributeOrNull("Path") ?: continue
        val projectFile = File(solutionDir, nativeSeparators(path))
        val folder = (project.parentNode as? Element)?.takeIf { it.tagName == "Folder" }?.attributeOrNull("Name")
        val parts = folder?.split('/')?.filter { it.isNotEmpty() }.orEmpty() + projectFile.nameWithoutExtension
        result[normalizedPath(projectFile.path)] = parts.joinToString("\\") { cleansedTargetName(it) }
    }
    return result
}

private fun nativeSeparators(path: String): String = path.replace('\\', File.separatorChar).replace('/', File.separatorChar)

private fun parseXml(text: String): Document {
    val factory = DocumentBuilderFactory.newInstance().apply {
        // A solution file never needs a DTD - refusing one rules out entity-expansion tricks.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isExpandEntityReferences = false
    }
    return factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
}

private fun Element.attributeOrNull(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }

/** Canonical, case-insensitive form of a path, so a project path from Rider's model matches the
 * one resolved from the solution regardless of `..`/separator/casing differences. */
internal fun normalizedPath(path: String): String =
    File(path).absoluteFile.normalize().path.replace('\\', '/').lowercase()
