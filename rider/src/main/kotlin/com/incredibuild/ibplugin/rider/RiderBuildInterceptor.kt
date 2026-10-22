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
package com.incredibuild.ibplugin.rider

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * Takes over the build Rider runs before a run configuration starts - see
 * [IncredibuildBuildProjectBeforeRunTaskProvider]. Declared here, implemented in the root module
 * (which owns Incredibuild and MSBuild), and wired together as an application service in
 * rider-support.xml - this module can't call into the root module directly, since the root module
 * depends on it.
 */
interface RiderBuildInterceptor {

    /**
     * Builds [projectFile] (and the projects it references) - or the whole solution when null -
     * through Incredibuild, blocking until it ends. Returns whether it succeeded, or null to let
     * Rider build it as usual instead (the setting is off, Incredibuild is unavailable, ...).
     * Called off the EDT.
     */
    fun buildBeforeRun(project: Project, projectFile: String?): Boolean?

    companion object {
        fun getInstance(): RiderBuildInterceptor? =
            ApplicationManager.getApplication().getService(RiderBuildInterceptor::class.java)
    }
}
