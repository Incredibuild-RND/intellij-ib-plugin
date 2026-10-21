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

import com.incredibuild.ibplugin.actions.BuildFlavor
import com.incredibuild.ibplugin.actions.IncredibuildInstallation
import com.incredibuild.ibplugin.actions.InstallationStatus
import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

private val RUST_PLUGIN_ID = PluginId.getId("com.jetbrains.rust")
private val CMAKE_PLUGIN_ID = PluginId.getId("com.intellij.cmake")

// Registered by rider-support.xml, so present exactly when the .NET half is loaded. Checked instead
// of the com.intellij.modules.rider alias it's gated on: PluginManagerCore.isLoaded() only matches
// real plugin ids, not module aliases, and PlatformUtils.isRider() is internal API.
private const val RIDER_ACTION_ID = "Incredibuild.DotNet.BuildSolution"

/** Settings > Tools > Incredibuild - the installation in use, the "-j"/"--parallel" job count, IDE
 * build acceleration (RustRover, CLion and Rider), and (Rider only) the MSBuild to use. */
class IncredibuildConfigurable : BoundConfigurable("Incredibuild") {

    private val isRust = PluginManagerCore.isLoaded(RUST_PLUGIN_ID)
    private val isCMake = PluginManagerCore.isLoaded(CMAKE_PLUGIN_ID)
    private val isRider = ActionManager.getInstance().getAction(RIDER_ACTION_ID) != null

    private val installationLabel = JBLabel()

    override fun createPanel() = panel {
        row("Installation:") {
            cell(installationLabel).align(AlignX.FILL)
            button("Re-check") { refreshInstallation() }
        }
        // "-j" for a Cargo build, "--parallel" for a CMake one - same setting either way.
        row("Parallel jobs:") {
            intTextField(1..999).bindIntText(IncredibuildSettings.getInstance()::jobCount)
        }
        // Only meaningful where there is an IDE build for this to stand in for (see
        // rust-support.xml, cmake-support.xml and rider-support.xml) - anywhere else this
        // checkbox would do nothing at all, so it's hidden rather than shown as a dead setting.
        row {
            checkBox("Use Incredibuild for the IDE's own Build and Rebuild actions")
                .bindSelected(IncredibuildSettings.getInstance()::accelerateIdeBuilds)
                .comment(
                    "When on, the IDE's standard <b>Build</b> and <b>Rebuild</b> actions go through Incredibuild - " +
                        "from the Build menu, the toolbar, keyboard shortcuts, and the build that runs before a " +
                        "run configuration starts.<br>" +
                        "If Incredibuild is not installed, not licensed or too old, those builds run normally " +
                        "instead and a notification explains why.<br>" +
                        "The actions in the <b>Incredibuild</b> menu always use Incredibuild, either way."
                )
        }.visible(isRust || isCMake || isRider)
        // Only meaningful for the .NET actions (see rider-support.xml), so only shown in Rider.
        row("MSBuild executable:") {
            textFieldWithBrowseButton(
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select MSBuild or dotnet")
            )
                .align(AlignX.FILL)
                .bindText(IncredibuildSettings.getInstance()::msBuildPath)
                .comment(
                    "Used by the .NET builds that go through Incredibuild. Leave empty to detect it " +
                        "automatically: Visual Studio's MSBuild.exe on Windows if it has the .NET SDK component, otherwise " +
                        "<code>dotnet msbuild</code>. Either MSBuild.exe or the dotnet executable can be given."
                )
        }.visible(isRider)
    }.also { refreshInstallation() }

    /**
     * Shows the installation the plugin would use, checked on a background thread - locating it
     * reads the registry and queries the license, neither of which may block the settings dialog.
     */
    private fun refreshInstallation() {
        installationLabel.icon = AllIcons.Process.Step_1
        installationLabel.text = "Checking..."
        // Validated against the strictest flavor present: Rust builds need a minimum version,
        // native (CMake/MSBuild) ones don't.
        val flavor = if (isRust) BuildFlavor.RUST else BuildFlavor.NATIVE
        ApplicationManager.getApplication().executeOnPooledThread {
            val status = IncredibuildInstallation.check(flavor)
            ApplicationManager.getApplication().invokeLater({
                installationLabel.icon = when (status) {
                    is InstallationStatus.Ready -> AllIcons.General.InspectionsOK
                    InstallationStatus.NotInstalled -> AllIcons.General.Error
                    else -> AllIcons.General.Warning
                }
                installationLabel.text = IncredibuildInstallation.describe(status)
            }, ModalityState.any())
        }
    }
}
