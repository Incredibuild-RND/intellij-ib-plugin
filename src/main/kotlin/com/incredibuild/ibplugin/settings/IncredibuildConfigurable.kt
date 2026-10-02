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
package com.incredibuild.ibplugin.settings

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

private val RUST_PLUGIN_ID = PluginId.getId("com.jetbrains.rust")

/** Settings > Tools > Incredibuild - the "-j"/"--parallel" job count and (RustRover only)
 * IDE build acceleration. */
class IncredibuildConfigurable : BoundConfigurable("Incredibuild") {

    override fun createPanel() = panel {
        // "-j" for a Cargo build, "--parallel" for a CMake one - same setting either way.
        row("Parallel jobs:") {
            intTextField(1..999).bindIntText(IncredibuildSettings.getInstance()::jobCount)
        }
        // Only meaningful where the Rust plugin's own ProjectTaskRunner exists for this to
        // stand in for (see rust-support.xml) - in CLion this checkbox would do nothing at
        // all, so it's hidden there rather than shown as a dead setting.
        row {
            checkBox("Accelerate the IDE's own build actions")
                .bindSelected(IncredibuildSettings.getInstance()::accelerateIdeBuilds)
                .comment(
                    "When on, <b>Build Project</b> and the build that runs before <b>Run</b> and " +
                        "<b>Test</b> go through Incredibuild instead of Cargo.<br>" +
                        "When off, those actions behave exactly as they do without this plugin.<br>" +
                        "The actions in the <b>Incredibuild</b> menu always use Incredibuild, either way."
                )
        }.visible(PluginManagerCore.isLoaded(RUST_PLUGIN_ID))
    }
}
