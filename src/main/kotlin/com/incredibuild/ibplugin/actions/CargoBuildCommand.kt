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

import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.RunManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.incredibuild.ibplugin.settings.IncredibuildSettings
import org.rust.cargo.project.model.cargoProjectsIfCreated
import org.rust.cargo.runconfig.buildtool.CargoBuildManager
import org.rust.cargo.runconfig.command.CargoCommandConfiguration
import org.rust.cargo.runconfig.profiles.CargoBuildProfile
import org.rust.cargo.runconfig.profiles.RsDefaultProfileExecutionTarget
import org.rust.cargo.toolchain.CargoCommandLine
import org.rust.cargo.toolchain.RustChannel
import org.rust.cargo.toolchain.tools.Cargo

private val LOG = Logger.getInstance("com.incredibuild.ibplugin.actions.CargoBuildCommand")

/**
 * Cargo's job-count flag in every form it accepts: "-j", "-j8", "-j=8", "--jobs",
 * "--jobs 8" and "--jobs=8".
 */
private val JOB_COUNT_FLAG = Regex("""^(-j=?\d*|--jobs(=.*)?)$""")

/** Cargo's colour flag, as "--color" or "--color=always". */
private val COLOR_FLAG = Regex("""^--color(=.*)?$""")

private val COLOR_VALUES = setOf("always", "never", "auto")

/** Cargo's message-format flag, as "--message-format" or "--message-format=json". */
private val MESSAGE_FORMAT_FLAG = Regex("""^--message-format(=.*)?$""")

/**
 * The message format the IDE's Build tool window parses. Same value the Rust plugin's own
 * build passes (see the private `cargoBuildPatch` in CargoBuildManager, which adds it via
 * `addFormatJsonOption`): cargo emits its diagnostics as JSON with the human-readable
 * rendering embedded, which is what CargoBuildAdapter turns into build-tree events.
 */
private const val BUILD_PANE_MESSAGE_FORMAT = "json-diagnostic-rendered-ansi"

/**
 * Cargo commands that build tests rather than the crate itself, which the IDE's build
 * pipeline wants to know about so it can label and group the build correctly.
 *
 * Determined here rather than with the Rust plugin's own `isTestCommand`: that one is
 * `internal` to the plugin, so despite compiling to a public JVM method it is not visible
 * to us.
 */
private val TEST_COMMANDS = setOf("test", "bench", "nextest")

/** The registry key the Rust plugin's own Build Project consults for "--all-targets". */
private const val COMPILE_ALL_TARGETS_REGISTRY_KEY = "org.rust.cargo.compile.all.project.targets"

/**
 * Replaces any job count already present in [args] with [jobCount].
 *
 * The user's run configuration may well specify its own "-j" (or the derived command may
 * inherit one), and passing two of them leaves cargo using whichever it happens to
 * prefer - so existing ones are stripped rather than appended to. Only cargo's own
 * arguments are touched: everything from a bare "--" onwards is passed through to rustc
 * or to the built binary, where a "-j" means something else entirely and must survive
 * untouched.
 */
internal fun withJobCount(args: List<String>, jobCount: Int): List<String> =
    replacingCargoFlag(
        args,
        flag = JOB_COUNT_FLAG,
        // "-j" and "--jobs" carry the count as a separate following argument;
        // "-j8"/"-j=8"/"--jobs=8" carry it inline and consume nothing extra.
        flagsTakingSeparateValue = setOf("-j", "--jobs"),
        isValue = { it.toIntOrNull() != null },
        replacement = listOf("-j", jobCount.toString())
    )

/**
 * Forces cargo's colour output on, replacing any "--color" already present.
 *
 * Cargo turns ANSI colour off as soon as it sees its output is not a terminal, which is
 * exactly what happens once it runs under Incredibuild and its output is piped into the
 * tool window. RustRover's own build passes "--color=always" for the same reason, and the
 * tool-window console decodes the codes into real colouring.
 */
internal fun withAlwaysColor(args: List<String>): List<String> =
    replacingCargoFlag(
        args,
        flag = COLOR_FLAG,
        flagsTakingSeparateValue = setOf("--color"),
        isValue = { it in COLOR_VALUES },
        replacement = listOf("--color=always")
    )

/**
 * Switches cargo to the JSON message format the IDE's Build tool window parses, replacing
 * any "--message-format" already present.
 *
 * Only for builds whose output is handed to that tool window. It must never be used for
 * builds streamed into our own console, which shows raw output and would display the JSON
 * verbatim.
 */
internal fun withBuildPaneMessageFormat(args: List<String>): List<String> =
    replacingCargoFlag(
        args,
        flag = MESSAGE_FORMAT_FLAG,
        flagsTakingSeparateValue = setOf("--message-format"),
        // Any token that isn't itself a flag is this flag's value ("human", "short",
        // "json", "json-diagnostic-rendered-ansi", ...).
        isValue = { !it.startsWith("-") },
        replacement = listOf("--message-format=$BUILD_PANE_MESSAGE_FORMAT")
    )

/**
 * Drops every occurrence of [flag] (with its value) from cargo's own arguments and appends
 * [replacement] in its place.
 *
 * Only cargo's own arguments are touched: everything from a bare "--" onwards is passed
 * through to rustc or to the built binary, where these flags mean something else entirely
 * and must survive untouched.
 */
private fun replacingCargoFlag(
    args: List<String>,
    flag: Regex,
    flagsTakingSeparateValue: Set<String>,
    isValue: (String) -> Boolean,
    replacement: List<String>
): List<String> {
    val cargoArgs = mutableListOf<String>()
    val passthrough = mutableListOf<String>()
    var index = 0

    while (index < args.size) {
        val arg = args[index]
        if (arg == "--") {
            passthrough += args.subList(index, args.size)
            break
        }
        if (flag.matches(arg)) {
            val valueIsSeparateArgument = arg in flagsTakingSeparateValue &&
                args.getOrNull(index + 1)?.let(isValue) == true
            index += if (valueIsSeparateArgument) 2 else 1
            continue
        }
        cargoArgs += arg
        index++
    }

    return cargoArgs + replacement + passthrough
}

/** A cargo invocation to accelerate: the command to run and the directory to run it in. */
internal data class CargoInvocation(val command: String, val workingDirectory: String)

/**
 * Whether [project] has a Cargo workspace at all - used to hide the Cargo-flavoured
 * Incredibuild actions in a project that doesn't have one (e.g. a plain CMake project open in
 * an IDE where the Rust plugin also happens to be installed).
 *
 * Reads [cargoProjectsIfCreated] rather than the plain (always-creating) `cargoProjects`
 * extension property, so checking this from every action's `update()` never forces the Rust
 * plugin's project-model service into existence for a project that never otherwise touches it.
 */
internal fun hasCargoWorkspace(project: Project): Boolean =
    project.cargoProjectsIfCreated?.hasAtLeastOneValidProject == true

/**
 * The cargo invocation to accelerate, taken from what RustRover's own Build Project would
 * run for the current state of the IDE rather than reconstructed from scratch.
 *
 * Mirrors CargoBuildTaskRunner.expandTask: with a Cargo run configuration selected, the
 * command comes from that configuration by way of [CargoBuildManager.getBuildConfiguration]
 * (which is what turns a "run" configuration into a "build" and a "test" into
 * "test --no-run"); with none selected, it falls back to building the whole workspace the
 * same way the Rust plugin does.
 *
 * Falls back to the plain workspace command if anything about the derivation fails, so a
 * change inside the Rust plugin can degrade this to a less precise command rather than
 * breaking the build action outright.
 *
 * Must be called from a background thread: resolving a configuration reads the Cargo
 * project model.
 */
internal fun deriveCargoInvocation(project: Project): CargoInvocation {
    val jobCount = IncredibuildSettings.getInstance().jobCount
    return try {
        ReadAction.compute<CargoInvocation?, Throwable> {
            val selected = RunManager.getInstance(project).selectedConfiguration?.configuration
            val cargoConfiguration = selected as? CargoCommandConfiguration
            val buildConfiguration = cargoConfiguration?.let { CargoBuildManager.getBuildConfiguration(it) }
            buildConfiguration?.let { cargoInvocationFrom(it, jobCount, project) }
        } ?: workspaceBuildInvocation(project, jobCount)
    } catch (e: Exception) {
        LOG.warn("Could not derive the IDE's build command; falling back to a whole-workspace build", e)
        workspaceBuildInvocation(project, jobCount)
    }
}

/**
 * The invocation for one specific [configuration] - used when the IDE's build pipeline has
 * already told us which configuration it is building, rather than us having to work it out
 * from what is selected (see [IncredibuildProjectTaskRunner]).
 *
 * Must be called from a background thread.
 */
internal fun cargoInvocationForConfiguration(
    project: Project,
    configuration: CargoCommandConfiguration
): ResolvedCargoBuild? {
    val jobCount = IncredibuildSettings.getInstance().jobCount
    return try {
        ReadAction.compute<ResolvedCargoBuild?, Throwable> { resolveCargoBuild(configuration, jobCount, project) }
    } catch (e: Exception) {
        LOG.warn("Could not resolve the cargo command for ${configuration.name}", e)
        null
    }
}

/**
 * A resolved configuration: the invocation to run, and whether it is a test build - which
 * the IDE's build pipeline needs in order to label and group the build correctly.
 *
 * [CargoInvocation.command] carries the JSON message format the Build tool window parses,
 * so this must only be used for builds whose output goes to that window.
 */
internal class ResolvedCargoBuild(val invocation: CargoInvocation, val isTestBuild: Boolean)

private fun resolveCargoBuild(
    configuration: CargoCommandConfiguration,
    jobCount: Int,
    project: Project
): ResolvedCargoBuild? {
    val resolved = configuration.clean().ok ?: return null
    val invocation = renderInvocation(resolved.cmd, jobCount, project, forBuildPane = true) ?: return null
    return ResolvedCargoBuild(invocation, resolved.cmd.command in TEST_COMMANDS)
}

/**
 * Renders [configuration]'s own cargo invocation, with our job count substituted in.
 *
 * The arguments come from [CargoCommandConfiguration.clean], which is the same call
 * `CargoBuildManager.build` makes to turn a configuration into a runnable command line -
 * and crucially it is what applies the selected build profile (through the configuration's
 * private `additionalArgumentsWithProfile`). Reading the configuration's stored parameters
 * instead misses the profile entirely, since the profile lives on the execution target
 * until the configuration is resolved for execution.
 */
private fun cargoInvocationFrom(
    configuration: CargoCommandConfiguration,
    jobCount: Int,
    project: Project
): CargoInvocation? {
    val resolved = configuration.clean().ok ?: return null
    return renderInvocation(resolved.cmd, jobCount, project, forBuildPane = false)
}

/**
 * Renders a resolved [commandLine] into the command string we hand to Incredibuild.
 *
 * [forBuildPane] switches cargo to the JSON message format the IDE's Build tool window
 * parses; it must stay false for builds streamed into our own console, which would
 * otherwise show the raw JSON.
 */
private fun renderInvocation(
    commandLine: CargoCommandLine,
    jobCount: Int,
    project: Project,
    forBuildPane: Boolean
): CargoInvocation? {
    val patched = patchArgs(commandLine, project)
    val command = patched.command?.takeIf { it.isNotBlank() } ?: return null

    var args = withAlwaysColor(patched.additionalArguments)
    if (forBuildPane) args = withBuildPaneMessageFormat(args)

    val rendered = mutableListOf("cargo")
    toolchainPrefix(patched.toolchain, patched.channel)?.let { rendered += it }
    rendered += command
    rendered += withJobCount(args, jobCount)

    val workingDirectory = patched.workingDirectory?.toString()?.takeIf { it.isNotBlank() }
        ?: project.basePath
        ?: return null
    // Blank entries are dropped rather than joined: the resolved argument list can contain
    // empty strings, and those would render as stray double spaces in the command.
    return CargoInvocation(rendered.filter { it.isNotBlank() }.joinToString(" "), workingDirectory)
}

/**
 * Applies the Rust plugin's own argument patching, which is what the native build does on
 * its way to a process (`Cargo.toColoredCommandLine` calls it first).
 *
 * This is not cosmetic. Among other things it rewrites "--package foo" into
 * "--manifest-path .../foo/Cargo.toml", which is how RustRover actually scopes a build to
 * one crate; the two are not interchangeable, because cargo resolves features differently
 * for them, so a build that disagrees with the IDE's here recompiles everything each time
 * the two alternate. It also expands "--all-features"/"--nocapture" and adds the colour
 * flag.
 *
 * Falls back to the unpatched command line if this fails, which yields a less accurate
 * command rather than no build at all.
 */
private fun patchArgs(commandLine: CargoCommandLine, project: Project): CargoCommandLine =
    try {
        // patchArgs is an extension on CargoCommandLine declared inside Cargo's companion,
        // so the companion has to be brought into scope to call it.
        with(Cargo.Companion) { commandLine.patchArgs(project, true) }
    } catch (e: Exception) {
        LOG.warn("Could not apply the Rust plugin's argument patching to the build command", e)
        commandLine
    }

/** The "+toolchain" argument cargo needs, from an explicit toolchain or a non-default channel. */
private fun toolchainPrefix(toolchain: String?, channel: RustChannel?): String? {
    toolchain?.takeIf { it.isNotBlank() }?.let { return "+$it" }
    if (channel == null || channel == RustChannel.DEFAULT) return null
    return channel.channel?.takeIf { it.isNotBlank() }?.let { "+$it" }
}

/**
 * The whole-workspace build, matching what the Rust plugin runs when no Cargo run
 * configuration is selected: "--all", plus "--all-targets" when its own registry key says
 * so, plus the profile currently selected in the IDE.
 */
private fun workspaceBuildInvocation(project: Project, jobCount: Int): CargoInvocation {
    val args = mutableListOf("--all")
    if (Registry.`is`(COMPILE_ALL_TARGETS_REGISTRY_KEY, true)) args += "--all-targets"
    selectedProfile(project)?.let { args += listOf("--profile", it) }
    val command = "cargo build " + withJobCount(withAlwaysColor(args), jobCount).joinToString(" ")
    return CargoInvocation(command, project.basePath ?: ".")
}

/**
 * The build profile selected in the IDE's execution-target selector - one of Cargo's
 * built-ins (dev/release/test/bench) or a custom profile declared in Cargo.toml - or null
 * when none is selected, which leaves cargo on its own default profile.
 *
 * Read from the same place RustRover itself reads it (the active [ExecutionTargetManager]
 * target, contributed by the Rust plugin's RsExecutionTargetProvider) rather than from a
 * setting of our own.
 */
private fun selectedProfile(project: Project): String? {
    val activeTarget = ExecutionTargetManager.getInstance(project).activeTarget
    val profileId = (activeTarget as? RsDefaultProfileExecutionTarget)?.buildProfile?.id
    if (profileId.isNullOrBlank() || profileId == CargoBuildProfile.EmptyCargoBuildProfile.ID) return null
    return profileId
}
