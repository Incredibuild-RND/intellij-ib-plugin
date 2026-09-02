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

    companion object {
        fun getInstance(): IncredibuildSettings =
            ApplicationManager.getApplication().getService(IncredibuildSettings::class.java)
    }
}
