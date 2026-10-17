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

        val selection = RiderSolutionModel.selection(e.dataContext)
        if (e.isFromContextMenu) {
            // Same rule as Rider's own Build Selection: offered on the solution, solution
            // folders and projects in the Solution Explorer, not on files/references inside them.
            e.presentation.isEnabledAndVisible = selection != SolutionSelection.Nothing
            return
        }

        e.presentation.isVisible = true
        e.presentation.isEnabled = kind != DotNetBuildKind.BUILD_PROJECTS || selection != SolutionSelection.Nothing
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val entryPoint = RiderSolutionModel.buildEntryPoint(project)
        val solutionFile = RiderSolutionModel.solutionFile(project)
        val configuration = RiderSolutionModel.activeConfiguration(project)
        if (entryPoint == null || solutionFile == null || configuration == null) {
            Messages.showErrorDialog(
                project,
                "Could not determine what to build. Is a solution loaded, with an active configuration selected?",
                "Incredibuild"
            )
            return
        }

        var effectiveKind = kind
        var projectFiles = emptyList<String>()
        if (kind == DotNetBuildKind.BUILD_PROJECTS) {
            when (val selection = RiderSolutionModel.selection(e.dataContext)) {
                // The solution node itself selected: like Rider's own Build Selection, that's
                // just a solution build.
                SolutionSelection.WholeSolution -> effectiveKind = DotNetBuildKind.BUILD_SOLUTION
                is SolutionSelection.Projects -> projectFiles = selection.projectFiles
                SolutionSelection.Nothing -> {
                    Messages.showInfoMessage(
                        project,
                        "Select one or more projects in the Solution Explorer to build them with Incredibuild.",
                        "Incredibuild"
                    )
                    return
                }
            }
        }

        DotNetBuildRunner.build(
            project,
            DotNetBuildRequest(
                effectiveKind,
                entryPoint,
                solutionFile,
                configuration.configuration,
                configuration.platform,
                projectFiles
            ),
            actionType
        )
    }
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
