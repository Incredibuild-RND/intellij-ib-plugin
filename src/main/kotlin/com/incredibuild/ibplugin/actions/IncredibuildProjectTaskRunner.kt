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

import com.intellij.build.BuildViewManager
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.task.ProjectModelBuildTask
import com.intellij.task.ProjectTask
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskRunner
import com.intellij.task.TaskRunnerResults
import com.incredibuild.ibplugin.analytics.PostHogClient
import com.incredibuild.ibplugin.settings.IncredibuildSettings
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import org.rust.cargo.runconfig.buildtool.CargoBuildAdapter
import org.rust.cargo.runconfig.buildtool.CargoBuildConfiguration
import org.rust.cargo.runconfig.buildtool.CargoBuildContext
import org.rust.cargo.runconfig.buildtool.CargoBuildManager
import org.rust.cargo.runconfig.buildtool.CargoBuildResult
import org.rust.cargo.runconfig.buildtool.CargoBuildTaskRunner
import org.rust.cargo.runconfig.command.CargoCommandConfiguration
import org.rust.cargo.project.model.CargoProject
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Future

/**
 * Runs the IDE's own build actions through Incredibuild: Build Project, and the build
 * phase that Run and Test perform before launching.
 *
 * Both of those converge on the platform's [ProjectTaskRunner] dispatch - Build Project
 * as a `ModuleBuildTask`, and the Run/Test build phase via
 * `RsBuildTaskProvider.executeTask`, which calls `ProjectTaskManager.build(...)` with the
 * Rust plugin's own `CargoBuildConfiguration`. `ProjectTaskManagerImpl.groupByRunner`
 * assigns each task to the *first* registered runner whose `canRun` accepts it, so
 * registering ahead of `CargoBuildTaskRunner` (order="first" in plugin.xml) is what lets
 * this substitute Incredibuild for the build without the build running twice.
 *
 * Everything about *what* to build is left to the Rust plugin: [CargoBuildTaskRunner] is
 * asked whether it would handle the task and to expand it, and only the execution is
 * replaced. That keeps this in step with RustRover's own decisions about scope, profile
 * and target selection instead of reconstructing them.
 *
 * Declining is always safe. When this returns false from [canRun], the platform simply
 * moves on to `CargoBuildTaskRunner` and the user gets an ordinary Cargo build - which is
 * how the opt-in setting and "Incredibuild isn't installed" both degrade gracefully,
 * rather than breaking a standard IDE command.
 */
class IncredibuildProjectTaskRunner : ProjectTaskRunner() {

    /**
     * The context-less form, which the build dispatch never calls (it always uses the
     * overload below). Declining here keeps this runner out of any other platform code
     * path that asks the question without a context, where the opt-in setting and the
     * Rust plugin's own opinion of the task can't be consulted.
     *
     * Overriding it is deprecated and the Plugin Verifier flags it as scheduled for
     * removal, but it cannot be dropped while this plugin supports 2026.1: the method is
     * abstract in build 261 and only became concrete in 262, so a class without it would
     * fail to instantiate on the older IDE. Remove this override when sinceBuild reaches
     * 262.
     */
    override fun canRun(projectTask: ProjectTask): Boolean = false

    override fun canRun(project: Project, projectTask: ProjectTask, context: ProjectTaskContext?): Boolean {
        if (!IncredibuildSettings.getInstance().accelerateIdeBuilds) return false
        val cargoRunner = cargoBuildTaskRunner() ?: return false
        return try {
            cargoRunner.canRun(project, projectTask, context)
        } catch (e: Exception) {
            LOG.warn("Could not ask the Rust plugin whether it handles this build task", e)
            false
        }
    }

    override fun run(
        project: Project,
        context: ProjectTaskContext,
        vararg tasks: ProjectTask
    ): Promise<Result> {
        val promise = AsyncPromise<Result>()
        // Locating Incredibuild reads the registry on Windows, so it must not happen on
        // the EDT - and neither must expanding the task, which consults the run manager
        // and the Cargo project model.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                runOnPooledThread(project, context, tasks, promise)
            } catch (e: Exception) {
                LOG.warn("Incredibuild build failed to start; falling back to the Cargo build", e)
                delegateToCargo(project, context, tasks, promise)
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
        val installFolder = IncredibuildLocator.findInstallFolder()
        if (installFolder == null) {
            // No dialog here, unlike the explicit menu actions: this path is a standard IDE
            // build the user did not ask Incredibuild for, so it must stay silent and simply
            // build normally.
            delegateToCargo(project, context, tasks, promise)
            return
        }

        val plannedBuilds = expandToPlannedBuilds(tasks)
        if (plannedBuilds.isEmpty()) {
            delegateToCargo(project, context, tasks, promise)
            return
        }

        // Sequentially, and blocking this pooled thread on each build's own future: a
        // workspace normally expands to a single configuration, and where it does not
        // (several Cargo projects in one IDE project) they must not compete for the build
        // tool window.
        for (planned in plannedBuilds) {
            if (!planned.isIncrementalBuild && !cleanBeforeRebuild(project, planned.buildConfiguration)) {
                promise.setResult(TaskRunnerResults.FAILURE)
                return
            }

            val result = startAcceleratedBuild(project, installFolder, planned.buildConfiguration)
            if (result == null) {
                delegateToCargo(project, context, tasks, promise)
                return
            }
            val outcome = result.get()
            when {
                outcome.canceled -> {
                    promise.setResult(TaskRunnerResults.ABORTED)
                    return
                }
                !outcome.succeeded -> {
                    promise.setResult(TaskRunnerResults.FAILURE)
                    return
                }
            }
        }
        promise.setResult(TaskRunnerResults.SUCCESS)
    }

    /** One configuration to build, and whether the IDE asked for an incremental build. */
    private class PlannedBuild(
        val buildConfiguration: CargoBuildConfiguration,
        val isIncrementalBuild: Boolean
    )

    /**
     * The builds behind [tasks], obtained by letting [CargoBuildTaskRunner] expand them
     * exactly as it would for its own build.
     *
     * The expanded task's `isIncrementalBuild` flag is carried along, not just the
     * configuration: it is how Rebuild Project is distinguished from Build Project, and
     * dropping it turns a rebuild into a plain build that finds everything already fresh
     * and compiles nothing.
     */
    private fun expandToPlannedBuilds(tasks: Array<out ProjectTask>): List<PlannedBuild> {
        val cargoRunner = cargoBuildTaskRunner() ?: return emptyList()
        return tasks
            .flatMap { task ->
                try {
                    cargoRunner.expandTask(task)
                } catch (e: Exception) {
                    LOG.warn("Could not expand build task ${task.presentableName}", e)
                    emptyList()
                }
            }
            .mapNotNull { expanded ->
                val modelTask = expanded as? ProjectModelBuildTask<*> ?: return@mapNotNull null
                val buildConfiguration = modelTask.buildableElement as? CargoBuildConfiguration
                    ?: return@mapNotNull null
                PlannedBuild(buildConfiguration, modelTask.isIncrementalBuild)
            }
    }

    /**
     * Runs "cargo clean" before a rebuild, exactly as `CargoBuildTaskRunner.executeTask`
     * does - through the Rust plugin's own [CargoBuildManager.clean], so the clean reports
     * into the Build tool window like everything else. Returns false if it could not be
     * run or reported failure, in which case the rebuild must not proceed: building without
     * the clean would silently turn Rebuild Project into a no-op.
     */
    private fun cleanBeforeRebuild(project: Project, buildConfiguration: CargoBuildConfiguration): Boolean {
        val cargoProject = findCargoProject(project, buildConfiguration)
        if (cargoProject == null) {
            LOG.warn("Could not locate the Cargo project to clean before rebuilding")
            return false
        }
        return try {
            CargoBuildManager.clean(cargoProject).get() == true
        } catch (e: Exception) {
            LOG.warn("Cargo clean failed before rebuilding", e)
            false
        }
    }

    /**
     * Runs one configuration through Incredibuild, reporting into the IDE's own Build tool
     * window rather than our console - so an accelerated Build Project looks like an
     * ordinary one, with the same tree of progress and diagnostics.
     *
     * This reuses the Rust plugin's build machinery wholesale and substitutes only the
     * process: [CargoBuildManager.execute] supplies the progress indicator, build queue and
     * result future, and [CargoBuildAdapter.attachToProcessHandler] parses cargo's JSON
     * output into build-tree events. The adapter requires the handler to already be on the
     * context, and starts the process itself - so it must be attached last and the handler
     * must not be started here.
     *
     * Returns null if the command could not be prepared, leaving the caller to fall back to
     * an ordinary Cargo build.
     */
    private fun startAcceleratedBuild(
        project: Project,
        installFolder: File,
        buildConfiguration: CargoBuildConfiguration
    ): Future<CargoBuildResult>? {
        val configuration = buildConfiguration.configuration
        val resolved = cargoInvocationForConfiguration(project, configuration) ?: return null

        val prepared = IncredibuildRunner.prepareBuild(
            project,
            installFolder,
            resolved.invocation.command,
            resolved.invocation.workingDirectory
        )
        if (prepared !is IncredibuildRunner.PreparedBuild.Ready) {
            if (prepared is IncredibuildRunner.PreparedBuild.Failed) LOG.warn(prepared.message)
            return null
        }

        val cargoProject = findCargoProject(project, buildConfiguration) ?: return null

        // The platform identifies build events by an opaque token, and nests them under a
        // parent; a single build is its own parent, which is what CargoBuildManager does.
        val buildId = Any()
        val buildContext = CargoBuildContext(
            cargoProject,
            buildConfiguration.environment,
            BUILD_TASK_NAME,
            BUILD_PROGRESS_TITLE,
            resolved.isTestBuild,
            buildId,
            buildId
        )

        PostHogClient.capture("ide_build_accelerated", mapOf("action" to "ide_build"))

        return CargoBuildManager.execute(buildContext) { runningContext ->
            // IncredibuildRunner's handler, not a plain one: it registers the build as the
            // active one so the Incredibuild menu's Stop Build can reach it, and it turns
            // the build tool window's own stop button into a graceful Incredibuild
            // cancellation rather than a kill.
            //
            // decodeAnsiColors = false because this output goes to the IDE's Build tool
            // window, which colors it by reading cargo's escape codes out of the text
            // itself - decoding them here would strip them and leave plain text.
            val processHandler = IncredibuildRunner.createProcessHandler(
                prepared.commandLine,
                decodeAnsiColors = false
            )
            processHandler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) = prepared.cleanUp()
            })
            runningContext.processHandler = processHandler
            CargoBuildAdapter.attachToProcessHandler(runningContext, project.service<BuildViewManager>())
        }
    }

    /**
     * The Cargo project a configuration belongs to, resolved the same way
     * `CargoBuildTaskRunner` resolves it - from the configuration's own program parameters
     * and working directory.
     */
    private fun findCargoProject(project: Project, buildConfiguration: CargoBuildConfiguration): CargoProject? {
        val configuration = buildConfiguration.configuration
        val workingDirectory = configuration.workingDirectory?.takeIf { it.isNotBlank() }
            ?: project.basePath
            ?: return null
        return try {
            CargoCommandConfiguration.findCargoProject(
                project,
                configuration.programParameters ?: "",
                Path.of(workingDirectory)
            )
        } catch (e: Exception) {
            LOG.warn("Could not resolve the Cargo project for ${configuration.name}", e)
            null
        }
    }

    /** Hands the build back to the Rust plugin, mirroring its result into [promise]. */
    private fun delegateToCargo(
        project: Project,
        context: ProjectTaskContext,
        tasks: Array<out ProjectTask>,
        promise: AsyncPromise<Result>
    ) {
        val cargoRunner = cargoBuildTaskRunner()
        if (cargoRunner == null) {
            promise.setResult(TaskRunnerResults.FAILURE)
            return
        }
        cargoRunner.run(project, context, *tasks)
            .onSuccess { promise.setResult(it) }
            .onError { promise.setError(it) }
    }

    /**
     * The Rust plugin's own registered build runner, taken from the extension point rather
     * than constructed, so it is the same instance the platform would otherwise have used.
     */
    private fun cargoBuildTaskRunner(): CargoBuildTaskRunner? =
        EP_NAME.extensionList.filterIsInstance<CargoBuildTaskRunner>().firstOrNull()

    private companion object {
        private val LOG = Logger.getInstance(IncredibuildProjectTaskRunner::class.java)

        /** Shown as the build's name and progress text in the Build tool window. */
        private const val BUILD_TASK_NAME = "Build"
        private const val BUILD_PROGRESS_TITLE = "Building with Incredibuild…"
    }
}
