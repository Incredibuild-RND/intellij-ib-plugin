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
package com.incredibuild.ibplugin.rider

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.jetbrains.rider.projectView.SolutionConfigurationManager
import com.jetbrains.rider.projectView.isExistingSolution
import com.jetbrains.rider.projectView.solutionFile
import com.jetbrains.rider.projectView.solutionFilterPath
import com.jetbrains.rider.projectView.workspace.ProjectModelEntity
import com.jetbrains.rider.projectView.workspace.getProjectModelEntities
import com.jetbrains.rider.projectView.workspace.isProject
import com.jetbrains.rider.projectView.workspace.isSolution
import com.jetbrains.rider.projectView.workspace.isSolutionFolder
import com.jetbrains.rider.projectView.workspace.isUnloadedProject

/** The solution configuration Rider's own build would use, e.g. "Debug" / "Any CPU". */
class SolutionConfiguration(val configuration: String, val platform: String)

/** What the Solution Explorer selection asks to be built - see [RiderSolutionModel.selection]. */
sealed interface SolutionSelection {
    /** The solution node itself is selected: build everything. */
    data object WholeSolution : SolutionSelection

    /** [projectFiles] are absolute paths of project files (.csproj, .vbproj, .fsproj, ...). */
    class Projects(val projectFiles: List<String>) : SolutionSelection

    /** Nothing buildable is selected (e.g. a plain file or folder, or no Solution Explorer focus). */
    data object Nothing : SolutionSelection
}

/**
 * Read-only access to the parts of Rider's frontend solution model the Incredibuild .NET actions
 * need. Everything here mirrors what Rider's own build actions consult
 * ([com.jetbrains.rider.build.actions.BuildSolutionActionBase],
 * [com.jetbrains.rider.build.actions.BuildSelectionActionBase]), so the accelerated build targets
 * exactly what Rider's own one would - but deliberately stops short of Rider's
 * [com.jetbrains.rider.build.BuildHost]: that hands the build to the ReSharper backend, which runs
 * MSBuild in its own process tree, where Incredibuild can't wrap it.
 *
 * Only platform types cross this API, so the root module (compiled against IntelliJ IDEA, not
 * Rider) can call it without Rider classes on its own classpath.
 */
object RiderSolutionModel {

    /**
     * Whether [project] is a real .sln/.slnx solution - not a folder opened as a "directory
     * solution", a CMake project opened in Rider, or the temporary placeholder solution Rider
     * opens with no solution at all, none of which have a solution file MSBuild could build.
     */
    fun hasSolution(project: Project): Boolean = project.isExistingSolution

    /**
     * The file to hand MSBuild for a whole-solution build. A solution filter (.slnf) wins when one
     * is open, because that's what Rider's own build is scoped to then - and MSBuild accepts a
     * .slnf directly, building only the projects it includes.
     */
    fun buildEntryPoint(project: Project): String? {
        if (!hasSolution(project)) return null
        project.solutionFilterPath?.let { return it.toString() }
        return project.solutionFile.absolutePath
    }

    /** The actual .sln/.slnx file, even when a filter is open - see [buildEntryPoint]. */
    fun solutionFile(project: Project): String? =
        if (hasSolution(project)) project.solutionFile.absolutePath else null

    /** The configuration|platform selected in Rider's toolbar, the one its own Build uses. */
    fun activeConfiguration(project: Project): SolutionConfiguration? {
        val active = SolutionConfigurationManager.tryGetInstance(project)?.activeConfigurationAndPlatform ?: return null
        return SolutionConfiguration(active.configuration, active.platform)
    }

    /**
     * What the selection in [dataContext] asks to build, the same way Rider's own "Build Selection"
     * reads it: the solution node means the whole solution, project nodes mean those projects,
     * and a solution folder means every project nested in it. Anything else (files, folders,
     * references) isn't buildable by itself, matching Rider - which also only offers its own
     * build-selection action on those three kinds of node.
     */
    fun selection(dataContext: DataContext): SolutionSelection {
        val entities = dataContext.getProjectModelEntities()
        if (entities.isEmpty()) return SolutionSelection.Nothing
        if (entities.any { it.isSolution() }) return SolutionSelection.WholeSolution

        val projectFiles = LinkedHashSet<String>()
        for (entity in entities) {
            when {
                entity.isProject() -> entity.projectFilePath()?.let(projectFiles::add)
                entity.isSolutionFolder() -> collectProjects(entity, projectFiles)
            }
        }
        return if (projectFiles.isEmpty()) SolutionSelection.Nothing else SolutionSelection.Projects(projectFiles.toList())
    }

    private fun collectProjects(folder: ProjectModelEntity, into: MutableSet<String>) {
        for (child in folder.childrenEntities) {
            when {
                child.isProject() -> child.projectFilePath()?.let(into::add)
                child.isSolutionFolder() -> collectProjects(child, into)
            }
        }
    }

    /** An unloaded project can't be built by Rider either - skipped rather than handed to MSBuild. */
    private fun ProjectModelEntity.projectFilePath(): String? =
        if (isUnloadedProject()) null else url?.presentableUrl
}
