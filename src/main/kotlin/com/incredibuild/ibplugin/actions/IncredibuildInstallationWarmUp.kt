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

import com.incredibuild.ibplugin.settings.IncredibuildSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Checks the Incredibuild installation once a project opens, when Settings | Tools | Incredibuild
 * routes the IDE's own builds through it - so the first such build finds the answer cached instead
 * of waiting on BuildConsole's licence query. In Rider that wait would otherwise be a modal
 * progress (see RiderBuildActionOverride).
 */
class IncredibuildInstallationWarmUp : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!IncredibuildSettings.getInstance().accelerateIdeBuilds) return
        // Which tool doesn't matter: what's cached is the same for all of them.
        ApplicationManager.getApplication().executeOnPooledThread { IncredibuildInstallation.check(AcceleratedBuildTool.NATIVE) }
    }
}
