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

import com.incredibuild.ibplugin.actions.IdeBuildFallback
import com.incredibuild.ibplugin.actions.dotnet.DotNetBuildRunner.IdeBuildCheck
import com.incredibuild.ibplugin.rider.RiderBeforeRunBuild
import com.incredibuild.ibplugin.rider.RiderBuildHooks
import com.incredibuild.ibplugin.rider.RiderSolutionModel
import com.incredibuild.ibplugin.rider.SolutionSelection
import com.incredibuild.ibplugin.settings.IncredibuildSettings
import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionWrapper
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise
import javax.swing.Icon

// Rider's own build actions and build before-launch steps, routed through Incredibuild while
// Settings | Tools | Incredibuild says to - Rider's counterpart of the Cargo and CMake
// ProjectTaskRunners. Rider has no such dispatch to join: it builds in its ReSharper backend,
// through a frontend BuildHost service with no extension point to hand a build elsewhere. So its
// entry points are replaced instead (see RiderBuildHooks), each falling back on Rider's own
// whenever the setting is off or the build can't be accelerated - so with the setting off,
// Rider behaves exactly as it does without this plugin.

/**
 * Overrides one of Rider's own build actions (rider-support.xml, `overrides="true"`), wrapping a
 * fresh instance of it. Overriding by id covers everywhere the action is reached from: the Build
 * menu, the toolbar's Build button (which runs whichever action id its mode names - Build
 * Solution, by default), keyboard shortcuts and the Solution Explorer context menu.
 *
 * Everything but running it - text, icon, enablement - is Rider's own action's.
 */
abstract class RiderBuildActionOverride internal constructor(
    rider: AnAction,
    private val kind: DotNetBuildKind
) : AnActionWrapper(rider) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        if (project == null || !IncredibuildSettings.getInstance().accelerateIdeBuilds) {
            return super.actionPerformed(e)
        }
        val selection = if (kind == DotNetBuildKind.BUILD_PROJECTS) RiderSolutionModel.selection(e.dataContext) else null
        // Anything Rider's own build would make sense of, but this can't (no solution, nothing
        // buildable selected), is left to Rider to report its own way.
        val request = (captureDotNetBuild(project, kind, selection) as? DotNetBuildCapture.Ready)?.request
            ?: return super.actionPerformed(e)

        // Under a modal progress, because what's found decides whether Rider's own action runs
        // instead - which has to happen here, with this event. The installation and MSBuild
        // lookups are cached, the installation looked up in the background when the project opens
        // or the setting is turned on (see IncredibuildInstallationWarmUp) and refreshed in the
        // background after that (see IncredibuildInstallation.checkForIdeBuild), so it's rarely
        // slow. Cancellable all the same: the licence query can take a while, and cancelling it
        // just builds the way Rider does.
        val check = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<IdeBuildCheck, RuntimeException> { DotNetBuildRunner.checkIdeBuild(project, request) },
                "Checking Incredibuild",
                true,
                project
            )
        } catch (_: ProcessCanceledException) {
            IdeBuildCheck.FallBack
        }
        when (check) {
            is IdeBuildCheck.Ready -> {
                // Kept for falling back later, on another thread, once this event has been handled.
                // The context is the platform's snapshot of the UI taken when the action fired
                // (PreCachedDataContext, from Utils.createAsyncDataContext), so Build Selection
                // replayed with it builds what was selected then, even if the selection has moved on.
                val dataContext = e.dataContext
                val place = e.place
                DotNetBuildRunner.buildForIde(project, request, check) { reason ->
                    IdeBuildFallback.notify(project, reason)
                    ApplicationManager.getApplication().invokeLater(
                        {
                            val event = AnActionEvent.createEvent(
                                dataContext,
                                delegate.templatePresentation.clone(),
                                place,
                                ActionUiKind.NONE,
                                null
                            )
                            ActionUtil.performAction(delegate, event)
                        },
                        project.disposed
                    )
                }
            }
            IdeBuildCheck.FallBack -> super.actionPerformed(e)
        }
    }
}

/** Rider's Build Solution ("BuildWholeSolutionAction" - also what the toolbar's Build button runs). */
class RiderBuildSolutionOverride :
    RiderBuildActionOverride(RiderBuildHooks.buildSolutionAction(), DotNetBuildKind.BUILD_SOLUTION)

/** Rider's Rebuild Solution. */
class RiderRebuildSolutionOverride :
    RiderBuildActionOverride(RiderBuildHooks.rebuildSolutionAction(), DotNetBuildKind.REBUILD_SOLUTION)

/** Rider's Build Selection, in the Solution Explorer's context menu. */
class RiderBuildSelectionOverride :
    RiderBuildActionOverride(RiderBuildHooks.buildSelectionAction(), DotNetBuildKind.BUILD_PROJECTS)

/**
 * Stands in for one of Rider's own build before-launch steps ("Build Solution", "Build Project")
 * - the build a .NET run configuration performs before it starts.
 *
 * It takes the same id as Rider's provider and is registered ahead of it (rider-support.xml,
 * `order="first"`). That makes it the one that *runs* such a step: the platform runs a step
 * through `BeforeRunTaskProvider.getProvider`, the first provider with the step's id. Everything
 * else about the step - its name, icon, description, editing it - is passed through to Rider's
 * provider, and its tasks are Rider's own: [createTask] returns null, which keeps this out of the
 * "Add before launch task" list and stops new run configurations from getting the step twice
 * (both list every provider's [createTask]), while saved steps are still read back by Rider's
 * provider - the run manager's by-id lookup for that keeps the *last* provider with an id.
 */
abstract class RiderBeforeRunBuildOverride internal constructor(
    private val project: Project,
    private val step: RiderBeforeRunBuild
) : BeforeRunTaskProvider<BeforeRunTask<*>>() {

    private val rider: BeforeRunTaskProvider<BeforeRunTask<*>>? by lazy {
        EP_NAME.getExtensions(project).firstOrNull { RiderBuildHooks.isRiderProvider(it, step) }
    }

    override fun getId() = RiderBuildHooks.beforeRunTaskId(step)

    override fun getName(): String = rider?.name ?: "Build"

    override fun getIcon(): Icon? = rider?.icon

    override fun getDescription(task: BeforeRunTask<*>): String? = rider?.getDescription(task)

    override fun getTaskIcon(task: BeforeRunTask<*>): Icon? = rider?.getTaskIcon(task)

    override fun isConfigurable(): Boolean = rider?.isConfigurable == true

    override fun isSingleton(): Boolean = rider?.isSingleton == true

    override fun isDumbAware(): Boolean = rider?.isDumbAware == true

    override fun createTask(runConfiguration: RunConfiguration): BeforeRunTask<*>? = null

    override fun configureTask(
        context: DataContext,
        configuration: RunConfiguration,
        task: BeforeRunTask<*>
    ): Promise<Boolean> = rider?.configureTask(context, configuration, task) ?: resolvedPromise(false)

    override fun canExecuteTask(configuration: RunConfiguration, task: BeforeRunTask<*>): Boolean =
        rider?.canExecuteTask(configuration, task) != false

    /** Called on a background thread, and blocks until the build ends - as Rider's own does. */
    override fun executeTask(
        context: DataContext,
        configuration: RunConfiguration,
        environment: ExecutionEnvironment,
        task: BeforeRunTask<*>
    ): Boolean {
        // This is registered for every Rider user, setting on or off - so if a Rider release
        // renames or moves its provider, the run goes ahead without the build rather than not at all.
        val rider = rider ?: run {
            LOG.warn("Rider's '${getId()}' before-launch step wasn't found; skipping the build")
            return true
        }
        if (IncredibuildSettings.getInstance().accelerateIdeBuilds) {
            val request = request(task, configuration)
            if (request != null) {
                DotNetBuildRunner.buildForIdeAndWait(project, request)?.let { return it }
            }
        }
        return rider.executeTask(context, configuration, environment, task)
    }

    /** What Rider's own step would build, or null to leave it to Rider. */
    private fun request(task: BeforeRunTask<*>, configuration: RunConfiguration): DotNetBuildRequest? {
        val (kind, selection) = when (step) {
            RiderBeforeRunBuild.SOLUTION -> DotNetBuildKind.BUILD_SOLUTION to null
            RiderBeforeRunBuild.PROJECT -> {
                val projectFile = RiderBuildHooks.beforeRunProjectFile(task, configuration) ?: return null
                DotNetBuildKind.BUILD_PROJECTS to SolutionSelection.Projects(listOf(projectFile))
            }
        }
        // Read on the EDT, where Rider's own actions read the same solution model.
        var capture: DotNetBuildCapture? = null
        ApplicationManager.getApplication().invokeAndWait(
            { capture = captureDotNetBuild(project, kind, selection) },
            ModalityState.any()
        )
        return (capture as? DotNetBuildCapture.Ready)?.request
    }

    private companion object {
        private val LOG = Logger.getInstance(RiderBeforeRunBuildOverride::class.java)
    }
}

/** Rider's "Build Solution" before-launch step. */
class RiderBuildSolutionBeforeRunOverride(project: Project) :
    RiderBeforeRunBuildOverride(project, RiderBeforeRunBuild.SOLUTION)

/** Rider's "Build Project" before-launch step - the default one for a .NET project's run configuration. */
class RiderBuildProjectBeforeRunOverride(project: Project) :
    RiderBeforeRunBuildOverride(project, RiderBeforeRunBuild.PROJECT)
