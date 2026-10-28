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

import com.incredibuild.ibplugin.actions.cmake.hasCMakeWorkspace
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project

private val RUST_PLUGIN_ID = PluginId.getId("com.jetbrains.rust")
private val CMAKE_PLUGIN_ID = PluginId.getId("com.intellij.cmake")

/**
 * [label] as-is, unless [project] has both a Cargo and a CMake workspace loaded at once (e.g.
 * Rust with a CMake-built C dependency, or CMake + Corrosion) - a case the plain per-workspace
 * `update()` checks don't disambiguate, since both the Cargo and CMake actions would otherwise
 * show the identical "Build"/"Rebuild" text side by side in the same Incredibuild menu. Only
 * reached once a caller's own `update()` has already confirmed its workspace is present, so
 * this never needs to decide which (if either) action should be hidden - just how to label the
 * one that is shown.
 *
 * Shared by the Cargo actions (rust-support.xml) and the CMake ones (cmake-support.xml), so it
 * lives here, with no Rust or CMake imports of its own, and asks about the *other* kind of
 * workspace only once that kind's plugin is known to be loaded. Without that, RustRover (no CMake
 * plugin) threw NoClassDefFoundError for CMakeWorkspace from every update() of the Cargo actions;
 * the JVM only resolves those classes when the guarded call actually runs.
 */
internal fun disambiguatedLabel(project: Project, label: String, suffix: String): String {
    val bothLoaded = PluginManagerCore.isLoaded(RUST_PLUGIN_ID) && PluginManagerCore.isLoaded(CMAKE_PLUGIN_ID)
    return if (bothLoaded && hasCargoWorkspace(project) && hasCMakeWorkspace(project)) "$label ($suffix)" else label
}
