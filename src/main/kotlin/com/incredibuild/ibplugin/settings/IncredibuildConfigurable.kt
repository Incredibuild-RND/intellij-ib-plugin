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

import com.incredibuild.ibplugin.actions.AcceleratedBuildTool
import com.incredibuild.ibplugin.actions.IncredibuildInstallation
import com.incredibuild.ibplugin.actions.IncredibuildStatus
import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil

private val LOG = Logger.getInstance(IncredibuildConfigurable::class.java)

private val RUST_PLUGIN_ID = PluginId.getId("com.jetbrains.rust")

// Registered by cmake-support.xml, so present exactly when the CMake half - and with it the
// project task runner this setting switches on - is loaded.
private const val CMAKE_ACTION_ID = "Incredibuild.CMake.Build"

// Registered by rider-support.xml, so present exactly when the .NET half is loaded. Checked instead
// of the com.intellij.modules.rider alias it's gated on: PluginManagerCore.isLoaded() only matches
// real plugin ids, not module aliases, and PlatformUtils.isRider() is internal API.
private const val RIDER_ACTION_ID = "Incredibuild.DotNet.BuildSolution"

/** Settings > Tools > Incredibuild - the Incredibuild installation in use, IDE build
 * acceleration, the "-j"/"--parallel" job count, and (Rider only) the MSBuild to use. */
class IncredibuildConfigurable : BoundConfigurable("Incredibuild") {

    private val isRider = ActionManager.getInstance().getAction(RIDER_ACTION_ID) != null
    private val hasRust = PluginManagerCore.isLoaded(RUST_PLUGIN_ID)
    private val hasCMake = ActionManager.getInstance().getAction(CMAKE_ACTION_ID) != null

    /** Cargo's minimum Incredibuild version is the only one there is - see [AcceleratedBuildTool]. */
    private val buildTool = if (hasRust) AcceleratedBuildTool.CARGO else AcceleratedBuildTool.NATIVE

    private val installationLabel = JBLabel()

    override fun createPanel() = panel {
        row("Incredibuild:") {
            cell(installationLabel)
            link("Check again") { showInstallation(refresh = true) }
        }
        // Only where there are IDE build actions for this to take over (see rust-support.xml,
        // cmake-support.xml and rider-support.xml) - anywhere else this checkbox would do
        // nothing at all, so it's hidden rather than shown as a dead setting.
        row {
            checkBox("Accelerate the IDE's own build actions")
                .bindSelected(IncredibuildSettings.getInstance()::accelerateIdeBuilds)
                .comment(
                    "When on, the IDE's own <b>Build</b> and <b>Rebuild</b> actions go through Incredibuild - " +
                        "from the Build menu, the toolbar and keyboard shortcuts alike - and so does the build " +
                        "a run configuration performs before it starts.<br>" +
                        "If Incredibuild isn't installed, isn't licensed or its version isn't supported, those " +
                        "builds run as standard local builds instead, and a notification says why.<br>" +
                        "When off, those actions behave exactly as they do without this plugin. " +
                        "The actions in the <b>Incredibuild</b> menu always use Incredibuild, either way."
                )
        }.visible(hasRust || hasCMake || isRider)
        // "-j" for a Cargo build, "--parallel" for a CMake one - same setting either way.
        row("Parallel jobs:") {
            intTextField(1..999).bindIntText(IncredibuildSettings.getInstance()::jobCount)
        }
        // Only meaningful for the .NET actions (see rider-support.xml), so only shown in Rider.
        row("MSBuild executable:") {
            textFieldWithBrowseButton(
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select MSBuild or dotnet")
            )
                .align(AlignX.FILL)
                .bindText(IncredibuildSettings.getInstance()::msBuildPath)
                .comment(
                    "Used by the <b>... with Incredibuild</b> .NET build actions. Leave empty to detect it " +
                        "automatically: Visual Studio's MSBuild.exe on Windows if it has the .NET SDK component, otherwise " +
                        "<code>dotnet msbuild</code>. Either MSBuild.exe or the dotnet executable can be given."
                )
        }.visible(isRider)
    }.also { showInstallation(refresh = false) }

    /**
     * Shows what [IncredibuildInstallation] finds. Looked up off the EDT - it reads the registry
     * and may start BuildConsole to check the licence - and shown once it's known.
     */
    private fun showInstallation(refresh: Boolean) {
        val cached = if (refresh) null else IncredibuildInstallation.cachedStatus(buildTool)
        if (cached != null) return show(cached)
        installationLabel.icon = AnimatedIcon.Default.INSTANCE
        installationLabel.text = "Checking..."
        AppExecutorUtil.getAppExecutorService().execute {
            // Anything but a status leaves the label on "Checking..." unless it's shown too.
            val update = try {
                val status = IncredibuildInstallation.check(buildTool, refresh)
                Runnable { show(status) }
            } catch (e: Exception) {
                LOG.warn("Could not check the Incredibuild installation", e)
                Runnable { showError("Could not check the Incredibuild installation: ${e.message ?: e.javaClass.simpleName}") }
            }
            ApplicationManager.getApplication().invokeLater(update, ModalityState.any())
        }
    }

    private fun showError(message: String) {
        installationLabel.icon = AllIcons.General.Error
        installationLabel.text = message
    }

    private fun show(status: IncredibuildStatus) {
        installationLabel.icon = if (status is IncredibuildStatus.Ready) AllIcons.General.InspectionsOK else AllIcons.General.Warning
        installationLabel.text = status.summary
    }
}
