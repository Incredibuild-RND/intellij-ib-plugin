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

import com.incredibuild.ibplugin.actions.IdeBuildAcceleration
import com.incredibuild.ibplugin.rider.RiderBuildInterceptor
import com.incredibuild.ibplugin.rider.RiderSolutionModel
import com.incredibuild.ibplugin.rider.SolutionSelection
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionWrapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** One of Rider's own build actions that is routed through Incredibuild when the setting is on. */
private class WrappedRiderAction(val actionId: String, val kind: DotNetBuildKind, val actionType: String)

/**
 * Rider's Build menu's Build Solution and Rebuild Solution, and the Solution Explorer's Build
 * Selection. Build Solution's id is also what the toolbar's build button (registered as
 * "BuildSolutionAction", carrying Rider's Build shortcuts) runs whenever no build is in progress, so
 * wrapping it covers that button and those shortcuts too.
 */
private val WRAPPED_RIDER_ACTIONS = listOf(
    WrappedRiderAction("BuildWholeSolutionAction", DotNetBuildKind.BUILD_SOLUTION, "dotnet_ide_build_solution"),
    WrappedRiderAction("RebuildSolutionAction", DotNetBuildKind.REBUILD_SOLUTION, "dotnet_ide_rebuild_solution"),
    WrappedRiderAction("BuildSelection", DotNetBuildKind.BUILD_PROJECTS, "dotnet_ide_build_selection"),
)

/**
 * Wraps Rider's own build actions (see [WRAPPED_RIDER_ACTIONS]) in [DotNetIdeBuildActionWrapper],
 * keeping their ids - so menus, toolbars, keymaps and anything else that refers to them by id get
 * the wrapper - and keeping Rider's original instance to delegate to.
 *
 * Rider has no extension point for intercepting a build: its actions hand it straight to the
 * ReSharper backend, which runs MSBuild in its own process tree where Incredibuild can't wrap it.
 *
 * Done when the first project opens rather than through `actionConfigurationCustomizer`, whose
 * interface is internal API the Plugin Verifier rejects; nothing can be built before a project is
 * open anyway. Idempotent, so later projects find the wrappers already in place.
 */
class DotNetIdeBuildActionInstaller : ProjectActivity {

    override suspend fun execute(project: Project) {
        val actionManager = ActionManager.getInstance()
        for (wrapped in WRAPPED_RIDER_ACTIONS) {
            val original = actionManager.getAction(wrapped.actionId) ?: continue
            if (original is DotNetIdeBuildActionWrapper) continue
            actionManager.replaceAction(
                wrapped.actionId,
                DotNetIdeBuildActionWrapper(original, wrapped.kind, wrapped.actionType)
            )
        }
    }
}

/**
 * One of Rider's own build actions, run through Incredibuild when "Use Incredibuild for the IDE's
 * own Build and Rebuild actions" is on - and exactly as Rider would run it otherwise: when the
 * setting is off, when there's no solution or selection to build, and (after a notification saying
 * why) when Incredibuild or MSBuild isn't available. Presentation is always Rider's.
 */
internal class DotNetIdeBuildActionWrapper(
    delegate: AnAction,
    private val kind: DotNetBuildKind,
    private val actionType: String
) : AnActionWrapper(delegate) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val request = if (project != null && IdeBuildAcceleration.isEnabled) requestFor(project, e) else null
        val ideBuild = request?.let { DotNetBuildRunner.prepareForIdeAction(project!!, it) }
        if (ideBuild == null) {
            super.actionPerformed(e)
            return
        }
        DotNetBuildRunner.launchIdeBuild(project!!, ideBuild, actionType)
    }

    /** What Rider's own action would build, or null to leave the build to it. */
    private fun requestFor(project: Project, e: AnActionEvent): DotNetBuildRequest? {
        if (!RiderSolutionModel.hasSolution(project)) return null
        if (kind != DotNetBuildKind.BUILD_PROJECTS) return dotNetBuildRequest(project, kind)
        return when (val selection = RiderSolutionModel.selection(e.dataContext)) {
            // As in Rider's own Build Selection, the solution node means the whole solution.
            SolutionSelection.WholeSolution -> dotNetBuildRequest(project, DotNetBuildKind.BUILD_SOLUTION)
            is SolutionSelection.Projects -> dotNetBuildRequest(project, kind, selection.projectFiles)
            SolutionSelection.Nothing -> null
        }
    }
}

/** The [RiderBuildInterceptor] behind the before-run build steps (rider-support.xml). */
class DotNetIdeBuildInterceptor : RiderBuildInterceptor {

    override fun buildBeforeRun(project: Project, projectFile: String?): Boolean? {
        if (!IdeBuildAcceleration.isEnabled) return null
        // Rider's solution model is read on the EDT, like the actions above read it.
        var request: DotNetBuildRequest? = null
        ApplicationManager.getApplication().invokeAndWait({
            if (RiderSolutionModel.hasSolution(project)) {
                request = if (projectFile == null) {
                    dotNetBuildRequest(project, DotNetBuildKind.BUILD_SOLUTION)
                } else {
                    dotNetBuildRequest(project, DotNetBuildKind.BUILD_PROJECTS, listOf(projectFile))
                }
            }
        }, ModalityState.any())
        val ready = request ?: return null
        return DotNetBuildRunner.buildBlockingForIde(project, ready, "dotnet_ide_before_run")
    }
}
