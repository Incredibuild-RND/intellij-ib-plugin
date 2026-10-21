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

import com.incredibuild.ibplugin.actions.BuildFlavor
import com.incredibuild.ibplugin.actions.IdeBuildAcceleration
import com.incredibuild.ibplugin.actions.IncredibuildRunner
import com.incredibuild.ibplugin.actions.IncredibuildRunner.AcceleratedBuildPresentation
import com.incredibuild.ibplugin.actions.IncredibuildRunner.IdeBuildOutcome
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.task.ModuleBuildTask
import com.intellij.task.ModuleFilesBuildTask
import com.intellij.task.ProjectModelBuildTask
import com.intellij.task.ProjectTask
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskRunner
import com.intellij.task.TaskRunnerResults
import com.jetbrains.cidr.cpp.cmake.model.CMakeConfiguration
import com.jetbrains.cidr.cpp.execution.CMakeExplicitTargetConfiguration
import com.jetbrains.cidr.cpp.execution.build.runners.CMakeProjectTaskRunner
import com.jetbrains.cidr.execution.build.tasks.CidrCleanTask
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.util.concurrent.CompletableFuture

/**
 * Runs CLion's own CMake build actions through Incredibuild: Build, Rebuild, Build Project, the
 * toolbar's build button, and the build that runs before a CMake run configuration starts.
 *
 * All of those converge on the platform's [ProjectTaskRunner] dispatch, which CLion serves with
 * [CMakeProjectTaskRunner]; `ProjectTaskManagerImpl` gives each task to the *first* runner whose
 * `canRun` accepts it, so this is registered ahead of that one (cmake-support.xml) - the same
 * arrangement as the Cargo [com.incredibuild.ibplugin.actions.IncredibuildProjectTaskRunner].
 *
 * Likewise, everything about *what* to build is left to CLion: [CMakeProjectTaskRunner] is asked
 * whether it would handle the task and to expand it into per-profile builds (and the clean that
 * a Rebuild adds), and only the execution is replaced - by `cmake --build` under Incredibuild,
 * the same invocation the Incredibuild menu's CMake actions run.
 *
 * Declining is always safe: when [canRun] says no, or anything goes wrong before the accelerated
 * build starts, CLion's own runner builds as usual.
 */
class IncredibuildCMakeProjectTaskRunner : ProjectTaskRunner() {

    /** See the identical override in [com.incredibuild.ibplugin.actions.IncredibuildProjectTaskRunner]. */
    override fun canRun(projectTask: ProjectTask): Boolean = false

    /**
     * Also called by CLion's Build Project action on every toolbar/menu update (on a background
     * thread) to decide whether it's available, so this must stay cheap and must not throw.
     */
    override fun canRun(project: Project, projectTask: ProjectTask, context: ProjectTaskContext?): Boolean {
        if (!IdeBuildAcceleration.isEnabled) return false
        // Only builds: compiling a single file (ModuleFilesBuildTask, itself a ModuleBuildTask),
        // installing and plain Clean stay with CLion.
        if (projectTask is ModuleFilesBuildTask) return false
        if (projectTask !is ModuleBuildTask && projectTask !is ProjectModelBuildTask<*>) return false
        val cmakeRunner = cmakeProjectTaskRunner() ?: return false
        return try {
            cmakeRunner.canRun(project, projectTask, context)
        } catch (e: Exception) {
            LOG.warn("Could not ask CLion whether it handles this build task", e)
            false
        }
    }

    override fun run(project: Project, context: ProjectTaskContext, vararg tasks: ProjectTask): Promise<Result> {
        val promise = AsyncPromise<Result>()
        // Locating Incredibuild and reading the CMake model must both stay off the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                runOnPooledThread(project, context, tasks, promise)
            } catch (e: Exception) {
                LOG.warn("Incredibuild build failed to start; falling back to CLion's build", e)
                delegateToCMake(project, context, tasks, promise)
            }
        }
        return promise
    }

    private fun runOnPooledThread(
        project: Project,
        context: ProjectTaskContext,
        tasks: Array<out ProjectTask>,
        promise: AsyncPromise<Result>
    ) {
        val installFolder = IdeBuildAcceleration.installFolderOrFallBack(project, BuildFlavor.NATIVE, CLION_BUILD)
        if (installFolder == null) {
            delegateToCMake(project, context, tasks, promise)
            return
        }

        val plan = planBuilds(project, tasks)
        if (plan.builds.isEmpty()) {
            delegateToCMake(project, context, tasks, promise)
            return
        }

        val resolvedBuilds = plan.builds.map { planned ->
            when (val resolution = deriveCMakeBuild(project, planned.configuration, planned.targetName, plan.cleanFirst)) {
                is CMakeBuildResolution.Ready -> planned to resolution.build
                is CMakeBuildResolution.Unsupported -> return fallBack(project, resolution.reason, context, tasks, promise)
                null -> return fallBack(
                    project,
                    "Could not determine the cmake command for ${planned.configuration.name}.",
                    context,
                    tasks,
                    promise
                )
            }
        }

        // CLion's own build saves unsaved editors first, and cmake reads from disk.
        ApplicationManager.getApplication().invokeAndWait(
            { FileDocumentManager.getInstance().saveAllDocuments() },
            ModalityState.any()
        )

        // One at a time, blocking this pooled thread on each: Build Project expands to one build
        // per CMake profile, and those must not compete for the Incredibuild tool window.
        for ((planned, resolved) in resolvedBuilds) {
            val build = IncredibuildRunner.NativeBuild(
                listOf(IncredibuildRunner.NativeCommand(resolved.executable, resolved.arguments)),
                resolved.workingDirectory,
                resolved.environment
            )
            val outcome = CompletableFuture<IdeBuildOutcome>()
            IncredibuildRunner.launchNativeBuildForIde(
                project,
                installFolder,
                build,
                if (plan.cleanFirst) "cmake_ide_rebuild" else "cmake_ide_build",
                presentation(planned, plan.cleanFirst)
            ) { outcome.complete(it) }

            when (val result = outcome.get()) {
                IdeBuildOutcome.Succeeded -> continue
                IdeBuildOutcome.Failed -> {
                    promise.setResult(TaskRunnerResults.FAILURE)
                    return
                }
                IdeBuildOutcome.Stopped, IdeBuildOutcome.NotStarted -> {
                    promise.setResult(TaskRunnerResults.ABORTED)
                    return
                }
                is IdeBuildOutcome.NotPrepared -> {
                    fallBack(project, result.message, context, tasks, promise)
                    return
                }
            }
        }
        promise.setResult(TaskRunnerResults.SUCCESS)
    }

    /** One `cmake --build` to run: a CMake profile's configuration, and the target (null for all). */
    private class PlannedBuild(val configuration: CMakeConfiguration, val targetName: String?)

    private class BuildPlan(val builds: List<PlannedBuild>, val cleanFirst: Boolean)

    /**
     * The builds behind [tasks], as CLion's own runner expands them. A Rebuild shows up as a
     * [CidrCleanTask] ahead of each build - mapped to `--clean-first` rather than a separate
     * clean, so the clean is accelerated along with the build, as the menu's Rebuild does.
     */
    private fun planBuilds(project: Project, tasks: Array<out ProjectTask>): BuildPlan {
        val cmakeRunner = cmakeProjectTaskRunner() ?: return BuildPlan(emptyList(), false)
        val expanded = ReadAction.compute<List<ProjectTask>, Throwable> {
            tasks.flatMap { task ->
                try {
                    cmakeRunner.runnerForTask(task, project)?.expandTask(task) ?: emptyList()
                } catch (e: Exception) {
                    LOG.warn("Could not expand build task ${task.presentableName}", e)
                    emptyList()
                }
            }
        }
        val builds = expanded.filterIsInstance<ProjectModelBuildTask<*>>().mapNotNull { task ->
            when (val element = task.buildableElement) {
                is CMakeConfiguration -> PlannedBuild(element, element.target.name)
                // Build Project's per-profile "all" target - which, like the menu's Build All,
                // is cmake's default target, so no --target at all (also right for the Visual
                // Studio generators, whose equivalent is called ALL_BUILD).
                is CMakeExplicitTargetConfiguration -> PlannedBuild(
                    element.cmakeConfiguration,
                    element.explicitBuildTargetName?.takeUnless { it.isBlank() || it == ALL_TARGET }
                )
                else -> null
            }
        }
        return BuildPlan(builds, cleanFirst = expanded.any { it is CidrCleanTask })
    }

    private fun presentation(planned: PlannedBuild, cleanFirst: Boolean): AcceleratedBuildPresentation {
        val actionName = if (cleanFirst) "Rebuild" else "Build"
        val target = planned.targetName ?: "all targets"
        return AcceleratedBuildPresentation(
            contentName = actionName,
            banner = "Incredibuild: $actionName (accelerated)\n" +
                "  Building: $target\n" +
                "  Profile:  ${planned.configuration.profileName}",
            progressTitle = "${if (cleanFirst) "Rebuilding" else "Building"} $target with Incredibuild"
        )
    }

    private fun fallBack(
        project: Project,
        reason: String,
        context: ProjectTaskContext,
        tasks: Array<out ProjectTask>,
        promise: AsyncPromise<Result>
    ) {
        LOG.warn(reason)
        IdeBuildAcceleration.notifyFallback(project, reason, CLION_BUILD)
        delegateToCMake(project, context, tasks, promise)
    }

    /** Hands the build back to CLion, mirroring its result into [promise]. */
    private fun delegateToCMake(
        project: Project,
        context: ProjectTaskContext,
        tasks: Array<out ProjectTask>,
        promise: AsyncPromise<Result>
    ) {
        val cmakeRunner = cmakeProjectTaskRunner()
        if (cmakeRunner == null) {
            promise.setResult(TaskRunnerResults.FAILURE)
            return
        }
        cmakeRunner.run(project, context, *tasks)
            .onSuccess { promise.setResult(it) }
            .onError { promise.setError(it) }
    }

    /** CLion's own registered runner, taken from the extension point so it's the instance the platform would have used. */
    private fun cmakeProjectTaskRunner(): CMakeProjectTaskRunner? =
        EP_NAME.extensionList.filterIsInstance<CMakeProjectTaskRunner>().firstOrNull()

    private companion object {
        private val LOG = Logger.getInstance(IncredibuildCMakeProjectTaskRunner::class.java)

        /** The pseudo-target CLion's Build Project expands to - see [planBuilds]. */
        private const val ALL_TARGET = "all"

        /** The standard builder a fallback build runs with, as named in the fallback notification. */
        private const val CLION_BUILD = "CLion's own CMake build"
    }
}
