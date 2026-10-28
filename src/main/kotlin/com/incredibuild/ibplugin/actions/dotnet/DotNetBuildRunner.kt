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

import com.incredibuild.ibplugin.actions.IncredibuildRunner
import com.incredibuild.ibplugin.actions.IncredibuildRunner.AcceleratedBuildPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.File

private val LOG = Logger.getInstance("com.incredibuild.ibplugin.actions.dotnet.DotNetBuildRunner")

/**
 * Everything about Rider's own state an accelerated .NET build needs, captured up front by the
 * action (on the thread it runs on) so the background part never touches Rider's models.
 */
internal class DotNetBuildRequest(
    val kind: DotNetBuildKind,
    /** The .sln/.slnx, or open .slnf - see [solutionBuild]. */
    val entryPoint: String,
    /** The actual .sln/.slnx even when a filter is open - see [projectsBuild]. */
    val solutionFile: String,
    val configuration: String,
    val platform: String,
    /** Only for [DotNetBuildKind.BUILD_PROJECTS]. */
    val projectFiles: List<String> = emptyList(),
)

internal object DotNetBuildRunner {

    fun build(project: Project, request: DotNetBuildRequest, actionType: String) {
        // Rider's own build saves unsaved editors first, so the build sees what's on screen;
        // MSBuild reads from disk, so this has to as well.
        FileDocumentManager.getInstance().saveAllDocuments()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Locating Incredibuild") {
            override fun run(indicator: ProgressIndicator) {
                val installFolder = IncredibuildRunner.ensureIncredibuildInstalled(project, actionType) ?: return

                val tool = when (val location = MsBuildLocator.locate()) {
                    is MsBuildLocation.NotFound -> return showError(project, location.reason)
                    is MsBuildLocation.Found -> location.tool
                }

                val build = when (request.kind) {
                    DotNetBuildKind.BUILD_SOLUTION, DotNetBuildKind.REBUILD_SOLUTION -> solutionBuild(
                        tool,
                        request.entryPoint,
                        request.kind,
                        request.configuration,
                        request.platform,
                        restore = needsRestore(readEntryPointProjectFiles(request.entryPoint))
                    )

                    DotNetBuildKind.BUILD_PROJECTS -> {
                        val targetNames = readProjectTargets(request.solutionFile)
                        val missing = request.projectFiles.filter { normalizedPath(it) !in targetNames }
                        if (missing.isNotEmpty()) {
                            return showError(
                                project,
                                "Could not find these projects in ${File(request.solutionFile).name}, so they can't be " +
                                    "built through it:\n" + missing.joinToString("\n")
                            )
                        }
                        projectsBuild(
                            tool,
                            request.solutionFile,
                            request.projectFiles,
                            request.configuration,
                            request.platform,
                            targetNames,
                            restore = needsRestore(request.projectFiles)
                        )
                    }
                }

                IncredibuildRunner.launchNativeBuild(
                    project,
                    installFolder,
                    build,
                    actionType,
                    presentation(request, tool)
                )
            }
        })
    }

    /** The solution's per-project build targets - see [solutionProjectTargets]. Empty if the
     * solution can't be read, which then reports every selected project as not found. */
    private fun readProjectTargets(solutionFile: String): Map<String, String> {
        val file = File(solutionFile)
        return try {
            solutionProjectTargets(
                file.readText(),
                file.absoluteFile.parentFile,
                isSlnx = file.extension.equals("slnx", ignoreCase = true)
            )
        } catch (e: Exception) {
            LOG.warn("Could not read the project list from $solutionFile", e)
            emptyMap()
        }
    }

    /**
     * The project files a whole-solution build of [entryPoint] builds, for [needsRestore]: the
     * solution's, or only those an open solution filter includes - so a project the filter leaves
     * out can't add `-restore` to every filtered build. Empty if they can't be read, which then
     * leaves restore to Rider, as it is for every already-restored solution.
     */
    private fun readEntryPointProjectFiles(entryPoint: String): List<String> {
        val file = File(entryPoint).absoluteFile
        return try {
            if (file.extension.equals("slnf", ignoreCase = true)) {
                solutionFilterProjectFiles(file.readText(), file.parentFile)
            } else {
                solutionProjectFiles(file.readText(), file.parentFile)
            }
        } catch (e: Exception) {
            LOG.warn("Could not read the project list from $entryPoint", e)
            emptyList()
        }
    }

    private fun presentation(request: DotNetBuildRequest, tool: MsBuildTool): AcceleratedBuildPresentation {
        val solutionName = File(request.solutionFile).nameWithoutExtension
        val actionName = when (request.kind) {
            DotNetBuildKind.BUILD_SOLUTION -> "Build Solution"
            DotNetBuildKind.REBUILD_SOLUTION -> "Rebuild Solution"
            DotNetBuildKind.BUILD_PROJECTS -> "Build Selected Projects"
        }
        val target = when (request.kind) {
            DotNetBuildKind.BUILD_PROJECTS ->
                cappedProjectList(request.projectFiles.map { File(it).nameWithoutExtension })
            else -> File(request.entryPoint).name
        }
        val progressVerb = if (request.kind == DotNetBuildKind.REBUILD_SOLUTION) "Rebuilding" else "Building"
        return AcceleratedBuildPresentation(
            contentName = actionName,
            banner = "Incredibuild: $actionName (accelerated)\n" +
                "  Building:      $target\n" +
                "  Configuration: ${request.configuration} | ${request.platform}\n" +
                "  MSBuild:       ${tool.description}",
            progressTitle = "$progressVerb $solutionName with Incredibuild (${request.configuration} | ${request.platform})"
        )
    }

    /**
     * [names] for the banner, cut short after [shown] so a large selection doesn't make it
     * unreadable: "App, Lib +12 more".
     */
    internal fun cappedProjectList(names: List<String>, shown: Int = 2): String {
        if (names.size <= shown + 1) return names.joinToString(", ")
        return names.take(shown).joinToString(", ") + " +${names.size - shown} more"
    }

    private fun showError(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, "Incredibuild")
        }
    }
}
