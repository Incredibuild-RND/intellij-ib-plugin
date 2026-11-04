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

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.util.Key
import com.jetbrains.rider.build.actions.BuildSelectionAction
import com.jetbrains.rider.build.actions.BuildSolutionAction
import com.jetbrains.rider.build.actions.RebuildSolutionAction
import com.jetbrains.rider.build.tasks.BeforeRunTaskWithProject
import com.jetbrains.rider.build.tasks.BuildProjectBeforeRunTaskProvider
import com.jetbrains.rider.build.tasks.BuildSolutionBeforeRunTaskProvider
import com.jetbrains.rider.run.configurations.IProjectBasedRunConfiguration

/** Which of Rider's own build before-launch steps - see [RiderBuildHooks.beforeRunTaskId]. */
enum class RiderBeforeRunBuild {
    /** "Build Solution". */
    SOLUTION,

    /** "Build Project": one project, the run configuration's own unless the step picks another. */
    PROJECT,
}

/**
 * The parts of Rider's own build machinery that routing its builds through Incredibuild has to
 * stand in for. Rider builds in its ReSharper backend, through a frontend `BuildHost` service with
 * no extension point to hand a build elsewhere, so instead the entry points are replaced: Rider's
 * build actions are overridden (rider-support.xml), each wrapping a fresh instance of Rider's own
 * from here to fall back on, and its build before-launch steps are shadowed by providers that take
 * the same ids.
 *
 * Like [RiderSolutionModel], only platform types cross this API.
 */
object RiderBuildHooks {

    /** Rider's own Build Solution - registered as "BuildWholeSolutionAction". */
    fun buildSolutionAction(): AnAction = BuildSolutionAction()

    /** Rider's own Rebuild Solution - "RebuildSolutionAction". */
    fun rebuildSolutionAction(): AnAction = RebuildSolutionAction()

    /** Rider's own Build Selection, in the Solution Explorer's context menu - "BuildSelection". */
    fun buildSelectionAction(): AnAction = BuildSelectionAction()

    @Suppress("UNCHECKED_CAST")
    fun beforeRunTaskId(step: RiderBeforeRunBuild): Key<BeforeRunTask<*>> = when (step) {
        RiderBeforeRunBuild.SOLUTION -> BuildSolutionBeforeRunTaskProvider.providerId
        RiderBeforeRunBuild.PROJECT -> BuildProjectBeforeRunTaskProvider.providerId
    } as Key<BeforeRunTask<*>>

    /** Whether [provider] is Rider's own provider of [step], rather than a stand-in for it. */
    fun isRiderProvider(provider: BeforeRunTaskProvider<*>, step: RiderBeforeRunBuild): Boolean = when (step) {
        RiderBeforeRunBuild.SOLUTION -> provider is BuildSolutionBeforeRunTaskProvider
        RiderBeforeRunBuild.PROJECT -> provider is BuildProjectBeforeRunTaskProvider
    }

    /**
     * The project file a "Build Project" step builds, resolved the way Rider's own
     * `BuildProjectBeforeRunTaskProvider.executeTask` does: the project picked in the step, else
     * the run configuration's own. Null where neither gives one - including Rider's multi-platform
     * (MAUI/Xamarin) configurations, which keep it elsewhere - in which case Rider builds it.
     */
    fun beforeRunProjectFile(task: BeforeRunTask<*>, configuration: RunConfiguration): String? {
        (task as? BeforeRunTaskWithProject<*>)?.selectedProject?.path?.let { return it.toString() }
        return (configuration as? IProjectBasedRunConfiguration)?.getProjectFilePath()?.takeIf { it.isNotBlank() }
    }
}
