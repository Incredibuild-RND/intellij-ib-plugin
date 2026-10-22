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
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.jetbrains.rider.build.tasks.BuildProjectBeforeRunTask
import com.jetbrains.rider.build.tasks.BuildProjectBeforeRunTaskProvider
import com.jetbrains.rider.build.tasks.BuildSolutionBeforeRunTask
import com.jetbrains.rider.build.tasks.BuildSolutionBeforeRunTaskProvider
import com.jetbrains.rider.run.configurations.IProjectBasedRunConfiguration
import com.jetbrains.rider.run.configurations.multiPlatform.RiderMultiPlatformConfiguration
import org.jetbrains.concurrency.Promise
import javax.swing.Icon

private val LOG = Logger.getInstance("com.incredibuild.ibplugin.rider.IncredibuildBeforeRunTaskProviders")

/**
 * Stands in for one of Rider's own before-run build steps, so the build that runs before a run
 * configuration starts can go through Incredibuild.
 *
 * Rider offers no hook into that build - its providers hand it straight to the ReSharper backend
 * (`BuildTaskThrottler`/`BuildHost`, both final) - so this is registered with Rider's provider's own
 * [Key] and order="first" (rider-support.xml): the platform runs a before-run task with the first
 * provider registered for its key (`BeforeRunTaskProvider.getProvider`), so it reaches this one.
 * Everything else is left to Rider's provider, so existing run configurations, their saved
 * before-run steps and the step's editor are untouched:
 *  - [createTask] returns null, which keeps this provider out of the "Add before-run task" list and
 *    out of the default steps a new run configuration gets (both skip a provider that creates
 *    nothing) - Rider's provider still creates every task, and loading a saved step resolves to it
 *    as well (the platform's id-to-provider map keeps the last one registered);
 *  - presentation and configuration are delegated to [rider];
 *  - [executeTask] asks [RiderBuildInterceptor] to build, and hands the task back to Rider's own
 *    provider whenever that declines (the setting is off, Incredibuild is unavailable, ...).
 */
abstract class IncredibuildBeforeRunTaskProvider<T : BeforeRunTask<*>> : BeforeRunTaskProvider<T>() {

    /** Rider's own provider. Stateless, so a private instance behaves exactly like the registered one. */
    protected abstract val rider: BeforeRunTaskProvider<T>

    /** The project to build for [configuration]/[task], or null for the whole solution. */
    protected abstract fun projectFile(configuration: RunConfiguration, task: T): String?

    override fun getId(): Key<T> = rider.id

    override fun getName(): String = rider.name

    override fun getIcon(): Icon? = rider.icon

    override fun getTaskIcon(task: T): Icon? = rider.getTaskIcon(task)

    override fun getDescription(task: T): String = rider.getDescription(task)

    override fun isConfigurable(): Boolean = rider.isConfigurable

    override fun configureTask(context: DataContext, configuration: RunConfiguration, task: T): Promise<Boolean> =
        rider.configureTask(context, configuration, task)

    override fun createTask(runConfiguration: RunConfiguration): T? = null

    override fun canExecuteTask(configuration: RunConfiguration, task: T): Boolean =
        rider.canExecuteTask(configuration, task)

    override fun executeTask(
        context: DataContext,
        configuration: RunConfiguration,
        environment: ExecutionEnvironment,
        task: T
    ): Boolean {
        val accelerated = try {
            RiderBuildInterceptor.getInstance()?.buildBeforeRun(configuration.project, projectFile(configuration, task))
        } catch (e: Exception) {
            LOG.warn("Incredibuild before-run build failed to start; falling back to Rider's build", e)
            null
        }
        return accelerated ?: rider.executeTask(context, configuration, environment, task)
    }
}

/** Rider's default "Build Project" before-run step - builds the run configuration's project. */
class IncredibuildBuildProjectBeforeRunTaskProvider : IncredibuildBeforeRunTaskProvider<BuildProjectBeforeRunTask>() {

    override val rider = BuildProjectBeforeRunTaskProvider()

    /** Resolved the way Rider's own provider resolves it: the step's own project, else the run configuration's. */
    override fun projectFile(configuration: RunConfiguration, task: BuildProjectBeforeRunTask): String? =
        task.selectedProject?.path?.toString()
            ?: (configuration as? RiderMultiPlatformConfiguration)?.parameters?.projectFilePath?.value
            ?: (configuration as? IProjectBasedRunConfiguration)?.getProjectFilePath()
            ?: throw IllegalStateException("No project to build for ${configuration.name}")
}

/** Rider's "Build Solution" before-run step. */
class IncredibuildBuildSolutionBeforeRunTaskProvider : IncredibuildBeforeRunTaskProvider<BuildSolutionBeforeRunTask>() {

    override val rider = BuildSolutionBeforeRunTaskProvider()

    override fun projectFile(configuration: RunConfiguration, task: BuildSolutionBeforeRunTask): String? = null
}
