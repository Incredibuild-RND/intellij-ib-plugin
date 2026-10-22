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

import com.incredibuild.ibplugin.actions.dotnet.MsBuildLocator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** Persists the "-j" parallel job count used for Incredibuild-accelerated builds. */
@Service(Service.Level.APP)
@State(name = "IncredibuildSettings", storages = [Storage("incredibuild.xml")])
class IncredibuildSettings : PersistentStateComponent<IncredibuildSettings.State> {

    class State {
        var jobCount: Int = 300

        // Opt-in, and deliberately defaulted off: when enabled, the plugin takes over the
        // IDE's own Build/Rebuild actions and the build that runs before a run configuration
        // starts - in RustRover, CLion and Rider (see IdeBuildAcceleration) - which changes
        // what a standard IDE command does. The field keeps its original name so the saved
        // value survives the setting's rename. The Incredibuild menu
        // actions are unaffected by this flag - those always accelerate, since the user
        // asked for Incredibuild by name.
        var accelerateIdeBuilds: Boolean = false

        // Rider only: the MSBuild to run .NET builds with. Blank means auto-detect (see
        // MsBuildLocator) - Rider's own toolset choice lives in its backend settings, which the
        // frontend this plugin runs in has no API to read, so this is the way to point the
        // accelerated build at a specific one when the detected one isn't it.
        var msBuildPath: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var jobCount: Int
        get() = state.jobCount
        set(value) {
            state.jobCount = value
        }

    var accelerateIdeBuilds: Boolean
        get() = state.accelerateIdeBuilds
        set(value) {
            state.accelerateIdeBuilds = value
        }

    var msBuildPath: String
        get() = state.msBuildPath
        set(value) {
            val trimmed = value.trim()
            if (trimmed != state.msBuildPath) {
                // Any change - including clearing it back to auto-detect - re-detects on the next
                // build, which also picks up an MSBuild installed since the last detection.
                MsBuildLocator.clearCache()
            }
            state.msBuildPath = trimmed
        }

    companion object {
        fun getInstance(): IncredibuildSettings =
            ApplicationManager.getApplication().getService(IncredibuildSettings::class.java)
    }
}
