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

import com.intellij.execution.RunManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.incredibuild.ibplugin.settings.IncredibuildSettings
import org.rust.cargo.runconfig.command.CargoCommandConfiguration

/**
 * Runs the currently selected Cargo run configuration through Incredibuild's
 * BuildConsole, appending the configured "-j" job count to the cargo command.
 */
class RunWithIncredibuildAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val selected = RunManager.getInstance(project).selectedConfiguration?.configuration
        val cargoConfig = selected as? CargoCommandConfiguration
        if (cargoConfig == null) {
            Messages.showErrorDialog(
                project,
                "Select a Cargo run configuration first.",
                "Incredibuild"
            )
            return
        }

        val holder = cargoConfig.parametersHolder
        if (holder == null) {
            Messages.showErrorDialog(
                project,
                "Could not read Cargo command parameters from the selected run configuration.",
                "Incredibuild"
            )
            return
        }
        val toolchainPrefix = holder.toolchain?.takeIf { it.isNotBlank() }?.let { "+$it " } ?: ""
        val commandArgs = holder.commandParameters.printParameters().takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        val jobCount = IncredibuildSettings.getInstance().jobCount
        val cargoCommand = "cargo $toolchainPrefix${holder.command}$commandArgs -j $jobCount"

        val workingDirectory = cargoConfig.workingDirectory ?: project.basePath ?: return
        IncredibuildRunner.buildViaIncredibuild(project, cargoCommand, workingDirectory, "run_config")
    }
}
