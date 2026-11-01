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

import com.incredibuild.ibplugin.actions.AcceleratedBuildTool
import com.incredibuild.ibplugin.actions.IdeBuildFallback
import com.incredibuild.ibplugin.actions.IncredibuildInstallation
import com.incredibuild.ibplugin.actions.IncredibuildRunner
import com.incredibuild.ibplugin.actions.IncredibuildRunner.AcceleratedBuildPresentation
import com.incredibuild.ibplugin.actions.IncredibuildRunner.IdeBuildOutcome
import com.incredibuild.ibplugin.actions.IncredibuildStatus
import com.incredibuild.ibplugin.analytics.PostHogClient
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.File

private val LOG = Logger.getInstance("com.incredibuild.ibplugin.actions.dotnet.DotNetBuildRunner")

/** The analytics action type for a .NET build started from Rider's own build actions or steps. */
private const val IDE_ACTION_TYPE = "ide_build_dotnet"

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

    /** An Incredibuild menu action's build: any problem is reported with a dialog. */
    fun build(project: Project, request: DotNetBuildRequest, actionType: String) {
        launch(project, request, actionType) {
            val installFolder = IncredibuildRunner.ensureIncredibuildInstalled(project, actionType)
                ?: return@launch null
            when (val location = MsBuildLocator.locate()) {
                is MsBuildLocation.NotFound -> null.also { showError(project, location.reason) }
                is MsBuildLocation.Found -> installFolder to location.tool
            }
        }
    }

    /**
     * One of Rider's own build actions, overridden while Settings | Tools | Incredibuild routes
     * them through Incredibuild - with [check] already done by [checkIdeBuild], so there's nothing
     * left to ask the user about. Reports into the Build tool window, like the before-launch build
     * ([buildForIdeAndWait]) and like Rider's own build. Call it on the EDT, as actions run.
     */
    fun buildForIde(project: Project, request: DotNetBuildRequest, check: IdeBuildCheck.Ready) {
        FileDocumentManager.getInstance().saveAllDocuments()
        ApplicationManager.getApplication().executeOnPooledThread {
            when (val outcome = runForIde(project, request, check)) {
                // Too late to hand back to Rider's own action (its event is long gone), so it's
                // reported like the Incredibuild menu's own build would report it.
                is IdeBuildOutcome.NotPrepared -> showError(project, outcome.message)
                else -> Unit
            }
        }
    }

    private fun runForIde(project: Project, request: DotNetBuildRequest, check: IdeBuildCheck.Ready): IdeBuildOutcome {
        val build = when (val plan = plan(request, check.tool)) {
            is DotNetBuildPlan.Failed -> return IdeBuildOutcome.NotPrepared(plan.message)
            is DotNetBuildPlan.Ready -> plan.build
        }
        PostHogClient.capture("ide_build_accelerated", mapOf("action" to IDE_ACTION_TYPE))
        return IncredibuildRunner.runNativeBuildAndWait(
            project,
            check.installFolder,
            build,
            IDE_ACTION_TYPE,
            presentation(request, check.tool),
            outputBaseDirectories = listOf(File(request.solutionFile).absoluteFile.parent)
        )
    }

    private fun launch(
        project: Project,
        request: DotNetBuildRequest,
        actionType: String,
        locate: () -> Pair<File, MsBuildTool>?
    ) {
        // Rider's own build saves unsaved editors first, so the build sees what's on screen;
        // MSBuild reads from disk, so this has to as well.
        FileDocumentManager.getInstance().saveAllDocuments()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Locating Incredibuild") {
            override fun run(indicator: ProgressIndicator) {
                // Locating Incredibuild and MSBuild is blocking work too - hence inside the task.
                val (installFolder, tool) = locate() ?: return
                when (val plan = plan(request, tool)) {
                    is DotNetBuildPlan.Failed -> showError(project, plan.message)
                    is DotNetBuildPlan.Ready -> IncredibuildRunner.launchNativeBuild(
                        project,
                        installFolder,
                        plan.build,
                        actionType,
                        presentation(request, tool)
                    )
                }
            }
        })
    }

    /** What an IDE build found when [checkIdeBuild] checked whether it can be accelerated. */
    sealed interface IdeBuildCheck {
        class Ready(val installFolder: File, val tool: MsBuildTool) : IdeBuildCheck

        /** It can't - [IdeBuildFallback] has already told the user why, and Rider builds it instead. */
        data object FallBack : IdeBuildCheck
    }

    /**
     * Whether one of Rider's own builds can go through Incredibuild: Incredibuild usable, and an
     * MSBuild to run. If not, says why (a notification, never a dialog - the build still goes
     * ahead, as Rider's own). Blocking: never call it on the EDT.
     */
    fun checkIdeBuild(project: Project): IdeBuildCheck {
        val status = IncredibuildInstallation.check(AcceleratedBuildTool.NATIVE)
        if (status !is IncredibuildStatus.Ready) {
            IdeBuildFallback.notify(project, status)
            return IdeBuildCheck.FallBack
        }
        return when (val location = MsBuildLocator.locate()) {
            is MsBuildLocation.NotFound -> IdeBuildCheck.FallBack.also { IdeBuildFallback.notify(project, location.reason) }
            is MsBuildLocation.Found -> IdeBuildCheck.Ready(status.installFolder, location.tool)
        }
    }

    /**
     * A build for one of Rider's own before-launch steps, which reports the outcome itself: runs
     * [request] and waits for it. True or false for a build that ran (it succeeded or not), null
     * if it couldn't be accelerated after all - the caller then has Rider build it instead.
     * Blocking: never call it on the EDT.
     */
    fun buildForIdeAndWait(project: Project, request: DotNetBuildRequest): Boolean? {
        val check = checkIdeBuild(project) as? IdeBuildCheck.Ready ?: return null
        ApplicationManager.getApplication().invokeAndWait(
            { FileDocumentManager.getInstance().saveAllDocuments() },
            ModalityState.any()
        )
        return when (val outcome = runForIde(project, request, check)) {
            is IdeBuildOutcome.NotPrepared -> null.also { IdeBuildFallback.notify(project, outcome.message) }
            IdeBuildOutcome.NotStarted -> false
            is IdeBuildOutcome.Finished -> outcome.exitCode == 0 && !outcome.stopped
        }
    }

    /** What [plan] came back with. */
    private sealed interface DotNetBuildPlan {
        class Ready(val build: IncredibuildRunner.NativeBuild) : DotNetBuildPlan

        /** [message] is shown to the user as-is. */
        class Failed(val message: String) : DotNetBuildPlan
    }

    /** The MSBuild invocation for [request]. Reads the solution from disk, so not on the EDT. */
    private fun plan(request: DotNetBuildRequest, tool: MsBuildTool): DotNetBuildPlan =
        when (request.kind) {
            DotNetBuildKind.BUILD_SOLUTION, DotNetBuildKind.REBUILD_SOLUTION -> DotNetBuildPlan.Ready(
                solutionBuild(
                    tool,
                    request.entryPoint,
                    request.kind,
                    request.configuration,
                    request.platform,
                    restore = needsRestore(readEntryPointProjectFiles(request.entryPoint))
                )
            )

            DotNetBuildKind.BUILD_PROJECTS -> {
                val targetNames = readProjectTargets(request.solutionFile)
                val missing = request.projectFiles.filter { normalizedPath(it) !in targetNames }
                if (missing.isNotEmpty()) {
                    DotNetBuildPlan.Failed(
                        "Could not find these projects in ${File(request.solutionFile).name}, so they can't be " +
                            "built through it:\n" + missing.joinToString("\n")
                    )
                } else {
                    DotNetBuildPlan.Ready(
                        projectsBuild(
                            tool,
                            request.solutionFile,
                            request.projectFiles,
                            request.configuration,
                            request.platform,
                            targetNames,
                            restore = needsRestore(request.projectFiles)
                        )
                    )
                }
            }
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
