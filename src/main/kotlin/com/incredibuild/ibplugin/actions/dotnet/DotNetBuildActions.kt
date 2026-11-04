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

import com.incredibuild.ibplugin.rider.RiderSolutionModel
import com.incredibuild.ibplugin.rider.SolutionSelection
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * The shared shape of the three Rider .NET "... with Incredibuild" actions: each captures what
 * Rider's own equivalent would build (solution, active configuration|platform, selection) and
 * hands it to [DotNetBuildRunner], which runs it through Incredibuild instead of Rider's builder.
 *
 * Registered only in Rider (rider-support.xml), so [RiderSolutionModel] - which needs Rider's
 * own classes - is only ever reached there.
 */
abstract class DotNetBuildWithIncredibuildActionBase internal constructor(
    private val kind: DotNetBuildKind,
    private val actionType: String
) : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || !RiderSolutionModel.hasSolution(project)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        // The selection is only read where it matters - the context menu, and Build Selected
        // Projects - since this runs on every menu and toolbar update.
        if (e.isFromContextMenu) {
            // Same rule as Rider's own Build Selection: offered on the solution, solution
            // folders and projects in the Solution Explorer, not on files/references inside them.
            e.presentation.isEnabledAndVisible = RiderSolutionModel.selection(e.dataContext) != SolutionSelection.Nothing
            return
        }

        e.presentation.isVisible = true
        e.presentation.isEnabled = kind != DotNetBuildKind.BUILD_PROJECTS ||
            RiderSolutionModel.selection(e.dataContext) != SolutionSelection.Nothing
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = if (kind == DotNetBuildKind.BUILD_PROJECTS) RiderSolutionModel.selection(e.dataContext) else null
        when (val capture = captureDotNetBuild(project, kind, selection)) {
            is DotNetBuildCapture.Ready -> DotNetBuildRunner.build(project, capture.request, actionType)
            is DotNetBuildCapture.NoSolution -> Messages.showErrorDialog(project, capture.message, "Incredibuild")
            is DotNetBuildCapture.NothingSelected -> Messages.showInfoMessage(project, capture.message, "Incredibuild")
        }
    }
}

/** What [captureDotNetBuild] came back with. */
internal sealed interface DotNetBuildCapture {
    class Ready(val request: DotNetBuildRequest) : DotNetBuildCapture

    class NoSolution(val message: String) : DotNetBuildCapture

    class NothingSelected(val message: String) : DotNetBuildCapture
}

/**
 * What Rider's own [kind] build would build right now - the solution (or open filter), its active
 * configuration|platform, and for [DotNetBuildKind.BUILD_PROJECTS] the projects in [selection] -
 * captured on the calling thread so the background build never touches Rider's models. Shared by
 * the Incredibuild actions above and the overrides of Rider's own (see DotNetIdeBuild.kt).
 */
internal fun captureDotNetBuild(
    project: Project,
    kind: DotNetBuildKind,
    selection: SolutionSelection?
): DotNetBuildCapture {
    val entryPoint = RiderSolutionModel.buildEntryPoint(project)
    val solutionFile = RiderSolutionModel.solutionFile(project)
    val configuration = RiderSolutionModel.activeConfiguration(project)
    if (entryPoint == null || solutionFile == null || configuration == null) {
        return DotNetBuildCapture.NoSolution(
            "Could not determine what to build. Is a solution loaded, with an active configuration selected?"
        )
    }

    var effectiveKind = kind
    var projectFiles = emptyList<String>()
    if (kind == DotNetBuildKind.BUILD_PROJECTS) {
        when (selection) {
            // The solution node itself selected: like Rider's own Build Selection, that's
            // just a solution build.
            SolutionSelection.WholeSolution -> effectiveKind = DotNetBuildKind.BUILD_SOLUTION
            is SolutionSelection.Projects -> projectFiles = selection.projectFiles
            SolutionSelection.Nothing, null -> return DotNetBuildCapture.NothingSelected(
                "Select one or more projects in the Solution Explorer to build them with Incredibuild."
            )
        }
    }

    return DotNetBuildCapture.Ready(
        DotNetBuildRequest(
            effectiveKind,
            entryPoint,
            solutionFile,
            configuration.configuration,
            configuration.platform,
            projectFiles
        )
    )
}

/** The accelerated equivalent of Rider's own Build Solution. */
class BuildSolutionWithIncredibuildAction :
    DotNetBuildWithIncredibuildActionBase(DotNetBuildKind.BUILD_SOLUTION, "dotnet_build_solution")

/** The accelerated equivalent of Rider's own Rebuild Solution. */
class RebuildSolutionWithIncredibuildAction :
    DotNetBuildWithIncredibuildActionBase(DotNetBuildKind.REBUILD_SOLUTION, "dotnet_rebuild_solution")

/** The accelerated equivalent of Rider's own Build Selection. */
class BuildSelectedProjectsWithIncredibuildAction :
    DotNetBuildWithIncredibuildActionBase(DotNetBuildKind.BUILD_PROJECTS, "dotnet_build_projects")
