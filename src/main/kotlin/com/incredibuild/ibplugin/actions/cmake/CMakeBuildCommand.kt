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
package com.incredibuild.ibplugin.actions.cmake

import com.incredibuild.ibplugin.settings.IncredibuildSettings
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.toolchains.CPPBuildUtil
import java.nio.file.Path

private val LOG = Logger.getInstance("com.incredibuild.ibplugin.actions.cmake.CMakeBuildCommand")

/** Which of the three CMake "... with Incredibuild" actions is asking for a command. */
internal enum class CMakeBuildKind(val cleanFirst: Boolean, val wholeProject: Boolean) {
    /** "Build": the target behind the selected run configuration. */
    TARGET(cleanFirst = false, wholeProject = false),

    /** "Rebuild": same target, cleaned first. */
    TARGET_REBUILD(cleanFirst = true, wholeProject = false),

    /** "Build All": every target in the active CMake profile. */
    ALL(cleanFirst = false, wholeProject = true),
}

/**
 * A cmake invocation ready to hand to Incredibuild: the resolved executable, its arguments
 * (kept as a list, not a pre-joined string, so quoting is only ever done once - by whichever
 * mechanism actually launches it), the directory to run it in, and the toolchain environment
 * (PATH/INCLUDE/LIB for an MSVC profile, etc.) it needs to find its compiler.
 */
internal class ResolvedCMakeBuild(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val environment: Map<String, String>
)

/** What [deriveCMakeBuild] came back with. */
internal sealed interface CMakeBuildResolution {
    class Ready(val build: ResolvedCMakeBuild) : CMakeBuildResolution

    /** The active profile's toolchain isn't one this integration can accelerate - see
     * [resolveCMakeBuild]'s WSL/Docker/SSH check. [reason] is shown to the user as-is. */
    class Unsupported(val reason: String) : CMakeBuildResolution
}

/**
 * Whether [project] has a loaded CMake workspace at all - used to hide the CMake-flavoured
 * Incredibuild actions in a project that doesn't have one (e.g. a plain Cargo project open in
 * CLion, which bundles the CMake plugin regardless of whether the project uses it).
 *
 * Mirrors [com.jetbrains.cidr.cpp.execution.build.CMakeTargetAction]'s own `isAvailable` check
 * (`CMakeWorkspace.getInstance(project).isInitialized`), the same one CLion's own CMake build
 * actions use to decide whether they apply.
 */
internal fun hasCMakeWorkspace(project: Project): Boolean = CMakeWorkspace.getInstance(project).isInitialized

/**
 * The cmake invocation to accelerate for [kind], derived from CLion's own state for the
 * currently selected run configuration and its active CMake profile - mirroring what CLion's
 * own "Build" ([com.jetbrains.cidr.execution.build.CidrBuildTargetAction]), "Rebuild"
 * ([com.jetbrains.cidr.cpp.execution.build.CLionRebuildTargetAction]) and "Build All"
 * ([com.jetbrains.cidr.cpp.execution.build.CLionBuildProjectAction]) actions would themselves
 * build, rather than reconstructed from scratch.
 *
 * Deliberately generator-agnostic: rather than resolving ninja/make/nmake and their
 * per-generator argument conventions ourselves, this runs `cmake --build <dir>` - the
 * cross-generator wrapper cmake itself provides - so it works the same way whether the active
 * profile was configured with Ninja, Unix Makefiles or NMake Makefiles, and always agrees with
 * however that build directory was actually configured.
 *
 * Must be called from a background thread: resolving the selected configuration and its CMake
 * profile's toolchain environment reads the CMake project model.
 */
internal fun deriveCMakeBuild(project: Project, kind: CMakeBuildKind): CMakeBuildResolution? =
    try {
        ReadAction.compute<CMakeBuildResolution?, Throwable> { resolveCMakeBuild(project, kind) }
    } catch (e: Exception) {
        LOG.warn("Could not derive the CMake build command for $kind", e)
        null
    }

private fun resolveCMakeBuild(project: Project, kind: CMakeBuildKind): CMakeBuildResolution? {
    val buildAndRun = CMakeAppRunConfiguration.getSelectedBuildAndRunConfigurations(project) ?: return null
    val buildConfiguration = buildAndRun.buildConfiguration

    val profileInfo = CMakeWorkspace.getInstance(project).getProfileInfoFor(buildConfiguration)
    val environment = profileInfo.environment ?: return null

    // WSL/SSH/Docker toolchains run the actual compiler on a different machine (or a
    // container) than the one BuildConsole launches on - CPPBuildUtil.buildCommandLine below
    // rewrites paths and wraps the invocation for that (through wsl.exe, an SSH tunnel, or
    // `docker exec`), but we only keep its resolved *environment*, not that rewritten command
    // line, so running environment.cMake.executablePath directly here would try to run a
    // WSL/container-local path on the Windows host and fail. Decline clearly instead of
    // producing a broken command for a toolchain this integration doesn't support yet.
    val toolSetKind = environment.toolSet.kind
    if (toolSetKind.isRemoteLike) {
        return CMakeBuildResolution.Unsupported(
            "The active CMake profile's toolchain (${toolSetKind.displayName}) runs on a remote host or " +
                "container, which Incredibuild acceleration doesn't support yet - BuildConsole only runs " +
                "locally on this machine."
        )
    }

    val cmake = environment.cMake ?: return null

    val buildDir = buildConfiguration.configurationGenerationDir
    val workingDirectory = buildConfiguration.buildWorkingDir.absolutePath

    val parameters = mutableListOf("--build", buildDir.absolutePath)

    // Needed for multi-config generators (Visual Studio, Ninja Multi-Config): with no --config,
    // "cmake --build" defaults to building the Debug configuration regardless of which build
    // type the active profile actually selected. Harmless to always pass - single-config
    // generators (plain Ninja, Unix/NMake Makefiles) already bake the build type into how the
    // directory itself was configured and just ignore --config.
    buildConfiguration.buildType.takeIf { it.isNotBlank() }?.let { parameters += listOf("--config", it) }

    if (!kind.wholeProject) {
        // An explicit target set on the run configuration (e.g. "Build 'foo'" instead of just
        // "Build") wins over the target the configuration would otherwise produce - matching
        // CMakeAppRunConfiguration.BuildAndRunConfigurations' own field of the same name.
        val targetName = buildAndRun.explicitBuildTargetName?.takeIf { it.isNotBlank() }
            ?: buildConfiguration.target.name
        parameters += listOf("--target", targetName)
    }
    // wholeProject (Build All) omits --target entirely: cmake's own default target already
    // means "build everything", the same "all"/"ALL_BUILD" pseudo-target CLion's own Build
    // Project action builds.

    if (kind.cleanFirst) {
        parameters += "--clean-first"
    }

    // The same "-j" setting Settings > Tools > Incredibuild uses for Cargo builds, reused here
    // for cmake's own "--parallel" - it is CMake's generator-agnostic parallelism hint
    // (supported since CMake 3.12): generators that can act on it (Ninja, Unix/MinGW Makefiles)
    // forward it to the native tool; NMake Makefiles has no parallel build mode at all and
    // simply ignores it. That makes it safe to always pass, without first working out which
    // generator configured this particular build directory.
    val jobCount = IncredibuildSettings.getInstance().jobCount
    parameters += listOf("--parallel", jobCount.toString())

    // Reuses CLion's own environment-preparation for the CPP toolchain (PATH additions, the
    // MSVC INCLUDE/LIB/PATH an NMake or MSVC-flavoured Ninja build needs to find cl.exe, cygwin
    // path translation, ...) rather than reimplementing it - the same public helper
    // CMakeBuild.createBuildProcess itself builds its command line with. Only its resolved
    // environment is kept, not the GeneralCommandLine itself: the executable path and argument
    // list are already known directly (cmake.executablePath/parameters above), and this call is
    // local-toolchain-only by this point (the remote-like check above already returned).
    val commandLine = CPPBuildUtil.buildCommandLine(
        environment,
        cmake.executablePath,
        Path.of(workingDirectory),
        parameters,
        /* passSystemEnvironment = */ true,
        /* additionalEnvironment = */ emptyMap(),
        /* usePty = */ false
    )

    return CMakeBuildResolution.Ready(
        ResolvedCMakeBuild(
            cmake.executablePath,
            parameters,
            workingDirectory,
            commandLine.environment
        )
    )
}
