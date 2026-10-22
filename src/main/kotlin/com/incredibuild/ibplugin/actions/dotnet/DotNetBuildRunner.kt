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

import com.incredibuild.ibplugin.actions.BuildFlavor
import com.incredibuild.ibplugin.actions.IdeBuildAcceleration
import com.incredibuild.ibplugin.actions.IncredibuildRunner
import com.incredibuild.ibplugin.actions.IncredibuildRunner.AcceleratedBuildPresentation
import com.incredibuild.ibplugin.actions.IncredibuildRunner.IdeBuildOutcome
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import java.io.File
import java.util.concurrent.CompletableFuture

private val LOG = Logger.getInstance("com.incredibuild.ibplugin.actions.dotnet.DotNetBuildRunner")

/** The standard builder a fallback build runs with, as named in the fallback notification. */
private const val RIDER_BUILD = "Rider's own build"

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

    /** The explicit "... with Incredibuild" actions: report problems with dialogs, never fall back. */
    fun build(project: Project, request: DotNetBuildRequest, actionType: String) {
        // Rider's own build saves unsaved editors first, so the build sees what's on screen;
        // MSBuild reads from disk, so this has to as well.
        FileDocumentManager.getInstance().saveAllDocuments()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Locating Incredibuild") {
            override fun run(indicator: ProgressIndicator) {
                val installFolder = IncredibuildRunner.ensureIncredibuildInstalled(project, actionType) ?: return
                when (val prepared = prepare(request)) {
                    is PreparedDotNetBuild.Failed -> showError(project, prepared.message)
                    is PreparedDotNetBuild.Ready -> IncredibuildRunner.launchNativeBuild(
                        project,
                        installFolder,
                        prepared.build,
                        actionType,
                        presentation(request, prepared.tool)
                    )
                }
            }
        })
    }

    /**
     * For Rider's own build actions (see [DotNetIdeBuildActionWrapper]): checks Incredibuild and
     * prepares the MSBuild invocation under a short modal progress - synchronously, because if
     * anything is missing the caller must hand the very same action event back to Rider's own
     * action, which it can only do before its own actionPerformed returns. Returns null, after
     * telling the user why, when the build should fall back to Rider's. Must be called on the EDT.
     */
    fun prepareForIdeAction(project: Project, request: DotNetBuildRequest): IdeBuild? {
        FileDocumentManager.getInstance().saveAllDocuments()
        return ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<IdeBuild?, RuntimeException> { prepareForIde(project, request) },
            "Preparing Incredibuild build",
            false,
            project
        )
    }

    /** Starts a build [prepareForIdeAction] prepared, in the Incredibuild tool window. */
    fun launchIdeBuild(project: Project, ideBuild: IdeBuild, actionType: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            IncredibuildRunner.launchNativeBuild(
                project,
                ideBuild.installFolder,
                ideBuild.prepared.build,
                actionType,
                presentation(ideBuild.request, ideBuild.prepared.tool)
            )
        }
    }

    /**
     * For the build Rider runs before a run configuration starts: builds through Incredibuild and
     * blocks until it ends, returning whether it succeeded - or null, after telling the user why,
     * when Rider should build instead. Must be called off the EDT.
     */
    fun buildBlockingForIde(project: Project, request: DotNetBuildRequest, actionType: String): Boolean? {
        ApplicationManager.getApplication().invokeAndWait(
            { FileDocumentManager.getInstance().saveAllDocuments() },
            ModalityState.any()
        )
        val ideBuild = prepareForIde(project, request) ?: return null
        val outcome = CompletableFuture<IdeBuildOutcome>()
        IncredibuildRunner.launchNativeBuildForIde(
            project,
            ideBuild.installFolder,
            ideBuild.prepared.build,
            actionType,
            presentation(request, ideBuild.prepared.tool)
        ) { outcome.complete(it) }
        return when (val result = outcome.get()) {
            IdeBuildOutcome.Succeeded -> true
            IdeBuildOutcome.Failed, IdeBuildOutcome.Stopped, IdeBuildOutcome.NotStarted -> false
            is IdeBuildOutcome.NotPrepared -> {
                IdeBuildAcceleration.notifyFallback(project, result.message, RIDER_BUILD)
                null
            }
        }
    }

    /** A build of Rider's own that is ready to go through Incredibuild. */
    internal class IdeBuild(
        val request: DotNetBuildRequest,
        val installFolder: File,
        val prepared: PreparedDotNetBuild.Ready
    )

    private fun prepareForIde(project: Project, request: DotNetBuildRequest): IdeBuild? {
        val installFolder = IdeBuildAcceleration.installFolderOrFallBack(project, BuildFlavor.NATIVE, RIDER_BUILD)
            ?: return null
        return when (val prepared = prepare(request)) {
            is PreparedDotNetBuild.Failed -> {
                IdeBuildAcceleration.notifyFallback(project, prepared.message, RIDER_BUILD)
                null
            }
            is PreparedDotNetBuild.Ready -> IdeBuild(request, installFolder, prepared)
        }
    }

    /** The MSBuild invocation for a request, or why there isn't one. */
    internal sealed interface PreparedDotNetBuild {
        class Ready(val tool: MsBuildTool, val build: IncredibuildRunner.NativeBuild) : PreparedDotNetBuild
        class Failed(val message: String) : PreparedDotNetBuild
    }

    /** Locates MSBuild and builds the command for [request]. Blocking - reads the solution file. */
    private fun prepare(request: DotNetBuildRequest): PreparedDotNetBuild {
        val tool = when (val location = MsBuildLocator.locate()) {
            is MsBuildLocation.NotFound -> return PreparedDotNetBuild.Failed(location.reason)
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
                    return PreparedDotNetBuild.Failed(
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
        return PreparedDotNetBuild.Ready(tool, build)
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
