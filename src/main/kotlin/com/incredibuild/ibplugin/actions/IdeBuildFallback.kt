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

import com.incredibuild.ibplugin.analytics.PostHogClient
import com.incredibuild.ibplugin.settings.IncredibuildConfigurable
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key

/**
 * Tells the user that one of the IDE's own build actions - routed through Incredibuild by
 * Settings | Tools | Incredibuild - ran as a standard local build instead, and why. Never a
 * dialog: the build itself carries on (the caller hands it back to the IDE's own builder), so
 * this mustn't stand in its way, only explain it.
 *
 * Once per reason per project session: whatever kept one build from being accelerated (no
 * Incredibuild, a remote toolchain) usually keeps every one after it from being accelerated too,
 * and every Build, Run and Test saying so again would only be noise.
 */
internal object IdeBuildFallback {

    /** The reasons already shown in a project. */
    private val SHOWN_REASONS = Key.create<MutableSet<String>>("Incredibuild.IdeBuildFallback.shownReasons")

    /** For an installation [IncredibuildInstallation.check] found unusable. */
    fun notify(project: Project, status: IncredibuildStatus) {
        val reason = when (status) {
            is IncredibuildStatus.Ready -> return
            IncredibuildStatus.NotInstalled -> "Incredibuild is not installed on this machine."
            is IncredibuildStatus.NotLicensed -> "Incredibuild has no licence to start builds on this machine."
            is IncredibuildStatus.UnsupportedVersion ->
                "The installed Incredibuild version (${status.version}) is older than the minimum supported " +
                    "version (${status.minimumVersion})."
        }
        val offerDownload = status is IncredibuildStatus.NotInstalled || status is IncredibuildStatus.UnsupportedVersion
        show(project, reason, status::class.simpleName ?: "unknown", offerDownload)
    }

    /** For anything else that kept the build from being accelerated; [reason] is shown as-is. */
    fun notify(project: Project, reason: String) = show(project, reason, "other", offerDownload = false)

    private fun show(project: Project, reason: String, analyticsReason: String, offerDownload: Boolean) {
        PostHogClient.capture("ide_build_fallback", mapOf("reason" to analyticsReason))
        if (!isFirstTime(project, reason)) return
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                "Built without Incredibuild",
                "$reason This build ran as a standard local build instead.",
                NotificationType.WARNING
            )
        if (offerDownload) {
            notification.addAction(NotificationAction.createSimpleExpiring("Download Incredibuild") {
                PostHogClient.capture("download_latest_version_opened")
                BrowserUtil.browse(INCREDIBUILD_DOWNLOAD_PAGE)
            })
        }
        notification.addAction(NotificationAction.createSimple("Incredibuild settings") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, IncredibuildConfigurable::class.java)
        })
        notification.notify(project)
    }

    private fun isFirstTime(project: Project, reason: String): Boolean = synchronized(this) {
        val shown = project.getUserData(SHOWN_REASONS) ?: mutableSetOf<String>().also { project.putUserData(SHOWN_REASONS, it) }
        shown.add(reason)
    }
}
