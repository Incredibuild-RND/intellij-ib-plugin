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
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Cleans and rebuilds the target behind the currently selected run configuration through
 * Incredibuild - the accelerated equivalent of CLion's own "Rebuild"
 * ([com.jetbrains.cidr.cpp.execution.build.CLionRebuildTargetAction]).
 *
 * Unlike the Cargo/Rust Rebuild (a separate, unaccelerated "cargo clean" followed by an
 * accelerated build - cargo has no combined flag), this is a single cmake invocation:
 * `cmake --build ... --clean-first` cleans and builds in one accelerated step.
 */
class RebuildCMakeTargetWithIncredibuildAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        IncredibuildRunner.buildCMakeViaIncredibuild(project, "cmake_rebuild", CMakeBuildKind.TARGET_REBUILD)
    }
}
