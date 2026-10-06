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

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import org.rust.cargo.project.settings.RustProjectSettingsService
import java.io.File

private val CARGO_EXECUTABLE_NAME = if (SystemInfo.isWindows) "cargo.exe" else "cargo"

/**
 * Cleans the whole Cargo workspace (plain "cargo clean", not accelerated -
 * it only deletes files) then builds it through Incredibuild.
 */
class RebuildProjectWithIncredibuildAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && hasCargoWorkspace(project)
        // See BuildProjectWithIncredibuildAction.update for why this is unconditional.
        if (project != null && e.presentation.isEnabledAndVisible) {
            e.presentation.text = disambiguatedLabel(project, "Rebuild", "Cargo")
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val workingDirectory = project.basePath ?: return

        // Resolving cargo's executable path involves file-existence checks
        // (and possibly a settings-service lookup), so keep it off the EDT.
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Locating Cargo") {
            override fun run(indicator: ProgressIndicator) {
                // Checked before "cargo clean" runs, not after: that step deletes the
                // existing build output, so finding out only afterwards that Incredibuild
                // is missing or too old would leave the user with nothing built at all.
                // The resolved folder is threaded through to startClean/buildViaIncredibuild
                // below so this check never runs a second time for the same Rebuild.
                val installFolder = IncredibuildRunner.ensureIncredibuildReady(project, "rebuild") ?: return

                val cargoExecutable = resolveCargoExecutable(project)
                if (cargoExecutable == null) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Could not find cargo. Checked the configured Rust toolchain and ~/.cargo/bin/$CARGO_EXECUTABLE_NAME.",
                            "Incredibuild"
                        )
                    }
                    return
                }

                // Derived here, on the background thread, for the same reason the cargo
                // lookup above is: it resolves the selected run configuration against the
                // Cargo project model. Derived before the clean rather than after, so the
                // command reflects the state the user saw when they triggered the rebuild.
                val invocation = deriveCargoInvocation(project)

                ApplicationManager.getApplication().invokeLater {
                    startClean(project, installFolder, cargoExecutable, workingDirectory, invocation)
                }
            }
        })
    }

    /**
     * Resolves cargo's executable path without depending on the current
     * process's inherited PATH: that can be stale on Windows (the shell that
     * launched the IDE may have cached its environment before rustup was
     * installed, so a fresh IDE restart alone doesn't guarantee an up to date
     * PATH). Prefers the Rust plugin's configured toolchain if present, then
     * falls back to rustup's well-known default install location.
     */
    private fun resolveCargoExecutable(project: Project): String? {
        project.service<RustProjectSettingsService>().toolchain?.let { toolchain ->
            val path = toolchain.pathToCargoExecutable("cargo")
            if (File(path.toString()).exists()) {
                return path.toString()
            }
        }

        val defaultCargo = File(System.getProperty("user.home"), ".cargo${File.separator}bin${File.separator}$CARGO_EXECUTABLE_NAME")
        if (defaultCargo.exists()) {
            return defaultCargo.absolutePath
        }

        return null
    }

    private fun startClean(
        project: Project,
        installFolder: File,
        cargoExecutable: String,
        workingDirectory: String,
        invocation: CargoInvocation
    ) {
        val cleanCommandLine = GeneralCommandLine(cargoExecutable, "clean").withWorkDirectory(workingDirectory)
        // The clean's exit code is deliberately ignored: "cargo clean" reports failure for
        // benign reasons (e.g. a target directory that was already absent), and refusing to
        // build afterwards would be a worse outcome than simply building.
        IncredibuildRunner.run(project, cleanCommandLine, "Cargo Clean") { _ ->
            IncredibuildRunner.buildViaIncredibuild(
                project,
                installFolder,
                invocation.command,
                invocation.workingDirectory,
                "rebuild"
            )
        }
    }
}
