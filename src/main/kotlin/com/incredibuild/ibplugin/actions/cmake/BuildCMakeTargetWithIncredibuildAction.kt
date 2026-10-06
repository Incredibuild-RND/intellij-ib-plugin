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
package com.incredibuild.ibplugin.actions.cmake

import com.incredibuild.ibplugin.actions.IncredibuildRunner
import com.incredibuild.ibplugin.actions.disambiguatedLabel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Builds the target behind the currently selected run configuration through Incredibuild -
 * the Incredibuild-accelerated equivalent of CLion's own "Build"
 * ([com.jetbrains.cidr.execution.build.CidrBuildTargetAction]).
 */
class BuildCMakeTargetWithIncredibuildAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // Hidden rather than just disabled: with both this and the Rust plugin's actions
        // potentially registered in the same IDE (see cmake-support.xml/rust-support.xml),
        // only the one matching the open project's actual workspace should ever show.
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && hasCMakeWorkspace(project)
        // See BuildProjectWithIncredibuildAction.update for why this is unconditional.
        if (project != null && e.presentation.isEnabledAndVisible) {
            e.presentation.text = disambiguatedLabel(project, "Build", "CMake")
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        IncredibuildRunner.buildCMakeViaIncredibuild(project, "cmake_build", CMakeBuildKind.TARGET)
    }
}
