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

import com.incredibuild.ibplugin.actions.AcceleratedBuildTool
import com.incredibuild.ibplugin.actions.IdeBuildFallback
import com.incredibuild.ibplugin.actions.IncredibuildInstallation
import com.incredibuild.ibplugin.actions.IncredibuildRunner
import com.incredibuild.ibplugin.actions.IncredibuildRunner.AcceleratedBuildPresentation
import com.incredibuild.ibplugin.actions.IncredibuildRunner.IdeBuildOutcome
import com.incredibuild.ibplugin.actions.IncredibuildRunner.NativeBuild
import com.incredibuild.ibplugin.actions.IncredibuildRunner.NativeCommand
import com.incredibuild.ibplugin.actions.IncredibuildStatus
import com.incredibuild.ibplugin.actions.NOTIFICATION_GROUP_ID
import com.incredibuild.ibplugin.analytics.PostHogClient
import com.incredibuild.ibplugin.settings.IncredibuildSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.task.BuildTask
import com.intellij.task.ModuleBuildTask
import com.intellij.task.ModuleFilesBuildTask
import com.intellij.task.ProjectModelBuildTask
import com.intellij.task.ProjectTask
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskRunner
import com.intellij.task.TaskRunnerResults
import com.jetbrains.cidr.cpp.cmake.model.CMakeConfiguration
import com.jetbrains.cidr.cpp.execution.CMakeExplicitTargetConfiguration
import com.jetbrains.cidr.cpp.execution.build.runners.CMakeBuildTaskRunner
import com.jetbrains.cidr.cpp.execution.build.runners.CMakeProjectTaskRunner
import com.jetbrains.cidr.execution.build.tasks.CidrCleanTask
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise

/**
 * Runs CLion's own build actions through Incredibuild when Settings | Tools | Incredibuild says
 * to: Build and Rebuild (of the selected run configuration's target), Build Project / Build All,
 * and the build a CMake run configuration performs before it launches - from the Build menu, the
 * toolbar, keyboard shortcuts or Run/Debug alike.
 *
 * The CLion counterpart of the Cargo [com.incredibuild.ibplugin.actions.IncredibuildProjectTaskRunner],
 * and it works the same way. All of those entry points converge on the platform's
 * [ProjectTaskRunner] dispatch, which hands each task to the *first* registered runner whose
 * `canRun` accepts it - so registering ahead of CLion's own [CMakeProjectTaskRunner]
 * (cmake-support.xml) is what lets this substitute Incredibuild for the build without it running
 * twice.
 *
 * What to build is left to CLion: [CMakeProjectTaskRunner] is asked whether it would take the
 * task, and CLion's own [CMakeBuildTaskRunner] expands it - Build Project into the active
 * profile's "all" target, a Rebuild into a clean followed by a build - so only the execution is
 * replaced, by the same `cmake --build` the Incredibuild menu's CMake actions run.
 *
 * Declining is always safe: the platform then moves on to [CMakeProjectTaskRunner] and the user
 * gets an ordinary CLion build. That's how the opt-in setting degrades, and how a build that can't
 * be accelerated (Incredibuild not installed, not licensed, too old; a remote toolchain) falls
 * back - with a notification saying why ([IdeBuildFallback]).
 */
class IncredibuildCMakeProjectTaskRunner : ProjectTaskRunner() {

    /** Never called by the build dispatch - see the identical override in the Cargo runner. */
    override fun canRun(projectTask: ProjectTask): Boolean = false

    override fun canRun(project: Project, projectTask: ProjectTask, context: ProjectTaskContext?): Boolean {
        if (!IncredibuildSettings.getInstance().accelerateIdeBuilds) return false
        if (!isBuild(projectTask)) return false
        val cmakeRunner = cmakeProjectTaskRunner() ?: return false
        return try {
            cmakeRunner.canRun(project, projectTask, context)
        } catch (e: Exception) {
            LOG.warn("Could not ask CLion whether it handles this build task", e)
            false
        }
    }

    /**
     * Only builds: [CMakeProjectTaskRunner] also takes Clean, Install and Recompile (a single
     * file, which is a [ModuleFilesBuildTask]), none of which is a `cmake --build` of a target and
     * all of which stay with CLion.
     */
    private fun isBuild(task: ProjectTask): Boolean = when (task) {
        is ModuleFilesBuildTask -> false
        is ModuleBuildTask -> true
        is ProjectModelBuildTask<*> ->
            task.buildableElement is CMakeConfiguration || task.buildableElement is CMakeExplicitTargetConfiguration
        else -> false
    }

    override fun run(project: Project, context: ProjectTaskContext, vararg tasks: ProjectTask): Promise<Result> {
        val promise = AsyncPromise<Result>()
        // Checking the installation reads the registry and may start BuildConsole, and expanding
        // the tasks reads the CMake model - none of which may happen on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                runOnPooledThread(project, context, tasks, promise)
            } catch (e: Exception) {
                LOG.warn("Incredibuild build failed to start; falling back to CLion's own build", e)
                delegateToCLion(project, context, tasks, promise)
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
        fun fallBack(reason: String) {
            IdeBuildFallback.notify(project, reason)
            delegateToCLion(project, context, tasks, promise)
        }

        val status = IncredibuildInstallation.checkForIdeBuild(AcceleratedBuildTool.NATIVE)
        if (status !is IncredibuildStatus.Ready) {
            IdeBuildFallback.notify(project, status)
            delegateToCLion(project, context, tasks, promise)
            return
        }

        val planned = planBuilds(tasks) ?: return fallBack("Could not work out which CMake targets this build covers.")
        val builds = planned.map { plan ->
            when (val resolution = deriveCMakeBuild(project, plan.configuration, plan.targetName, plan.cleanFirst)) {
                null -> return fallBack("Could not determine the cmake command for ${plan.configuration.name}.")
                is CMakeBuildResolution.Unsupported -> return fallBack(resolution.reason)
                is CMakeBuildResolution.Ready -> plan to resolution.build
            }
        }

        // cmake reads the sources from disk, so unsaved editors have to be saved first - as
        // CLion's own build does.
        ApplicationManager.getApplication().invokeAndWait(
            { FileDocumentManager.getInstance().saveAllDocuments() },
            ModalityState.any()
        )

        // Sequentially, waiting for each: a task normally expands to a single build, and where
        // it doesn't they mustn't overlap - only one Incredibuild build runs at a time.
        for ((index, build) in builds.withIndex()) {
            val (plan, resolved) = build
            PostHogClient.capture("ide_build_accelerated", mapOf("action" to "ide_build_cmake"))
            val outcome = IncredibuildRunner.runNativeBuildAndWait(
                project,
                status.installFolder,
                NativeBuild(
                    listOf(NativeCommand(resolved.executable, resolved.arguments)),
                    resolved.workingDirectory,
                    resolved.environment
                ),
                "ide_build_cmake",
                presentation(plan),
                outputBaseDirectories = listOf(resolved.buildDirectory)
            )
            when (outcome) {
                // Handing back to CLion is only right while nothing has been built yet: after
                // that it would build everything again, so the rest fails instead.
                is IdeBuildOutcome.NotPrepared -> return if (index == 0) {
                    fallBack(outcome.message)
                } else {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup(NOTIFICATION_GROUP_ID)
                        .createNotification("Incredibuild", outcome.message, NotificationType.ERROR)
                        .notify(project)
                    promise.setResult(TaskRunnerResults.FAILURE)
                }
                IdeBuildOutcome.NotStarted -> return promise.setResult(TaskRunnerResults.ABORTED)
                is IdeBuildOutcome.Finished -> when {
                    outcome.stopped -> return promise.setResult(TaskRunnerResults.ABORTED)
                    outcome.exitCode != 0 -> return promise.setResult(TaskRunnerResults.FAILURE)
                }
            }
        }
        promise.setResult(TaskRunnerResults.SUCCESS)
    }

    /** One `cmake --build` to run: [targetName] null builds every target. */
    private class PlannedBuild(val configuration: CMakeConfiguration, val targetName: String?, val cleanFirst: Boolean)

    /**
     * The builds behind [tasks], as CLion's own [CMakeBuildTaskRunner] expands them for its own
     * build - or null if the expansion holds anything this can't run as a `cmake --build`, in
     * which case CLion builds it instead.
     *
     * A rebuild expands into a clean task followed by the build; rather than running the clean
     * on its own, it becomes `--clean-first` on the build (as the Incredibuild menu's Rebuild
     * does), so cleaning and building is one accelerated step. Whether it's a rebuild is read
     * off the requested task - [BuildTask.isIncrementalBuild] is how Rebuild differs from Build -
     * not from the clean task, whose configuration needn't be the same object as the build's.
     */
    private fun planBuilds(tasks: Array<out ProjectTask>): List<PlannedBuild>? {
        val plans = tasks.flatMap { task ->
            val rebuild = task is BuildTask && !task.isIncrementalBuild
            val expanded = try {
                CMakeBuildTaskRunner.expandTask(task)
            } catch (e: Exception) {
                LOG.warn("Could not expand build task ${task.presentableName}", e)
                return null
            }
            expanded.mapNotNull { expandedTask ->
                when {
                    expandedTask is CidrCleanTask -> null
                    expandedTask is ProjectModelBuildTask<*> -> plan(expandedTask, rebuild) ?: return null
                    else -> return null
                }
            }
        }
        return plans.ifEmpty { null }
    }

    private fun plan(task: ProjectModelBuildTask<*>, rebuild: Boolean): PlannedBuild? =
        when (val element = task.buildableElement) {
            is CMakeExplicitTargetConfiguration -> {
                val configuration = element.cmakeConfiguration
                val targetName = when (val explicit = element.explicitBuildTargetName?.takeIf { it.isNotBlank() }) {
                    // Build Project / Build All: CLion's "all" target. Built here as cmake's
                    // default target (no --target) instead, which means the same with every
                    // generator - the Visual Studio ones have no target literally named "all"
                    // (theirs is ALL_BUILD).
                    ALL_TARGET -> null
                    null -> configuration.target.name
                    else -> explicit
                }
                PlannedBuild(configuration, targetName, rebuild)
            }
            is CMakeConfiguration -> PlannedBuild(element, element.target.name, rebuild)
            else -> null
        }

    private fun presentation(plan: PlannedBuild): AcceleratedBuildPresentation {
        val actionName = if (plan.cleanFirst) "Rebuild" else "Build"
        val target = plan.targetName ?: "all targets"
        val profile = plan.configuration.profileName
        return AcceleratedBuildPresentation(
            contentName = if (plan.targetName != null) "$actionName '$target'" else "$actionName $target",
            banner = "Incredibuild: $actionName (accelerated)\n" +
                "  Building: $target\n" +
                "  Profile:  $profile",
            progressTitle = "${if (plan.cleanFirst) "Rebuilding" else "Building"} $target with Incredibuild ($profile)"
        )
    }

    /** Hands the build back to CLion, mirroring its result into [promise]. */
    private fun delegateToCLion(
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

    /** CLion's own registered runner, the same instance the platform would otherwise have used. */
    private fun cmakeProjectTaskRunner(): CMakeProjectTaskRunner? =
        EP_NAME.extensionList.filterIsInstance<CMakeProjectTaskRunner>().firstOrNull()

    private companion object {
        private val LOG = Logger.getInstance(IncredibuildCMakeProjectTaskRunner::class.java)

        /** The target name [CMakeBuildTaskRunner] expands Build Project / Build All into. */
        private const val ALL_TARGET = "all"
    }
}
