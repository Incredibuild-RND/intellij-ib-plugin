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

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * Declares the "Incredibuild" tool window via plugin.xml instead of registering it at
 * runtime through [com.intellij.openapi.wm.ToolWindowManager.registerToolWindow], whose
 * `RegisterToolWindowTask` overload is `@ApiStatus.OverrideOnly` and must not be invoked
 * by client code. [IncredibuildRunner] populates the actual content per build.
 */
class IncredibuildToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Content is added dynamically by IncredibuildRunner.run() each time a build starts.
    }

    override fun shouldBeAvailable(project: Project) = true
}
