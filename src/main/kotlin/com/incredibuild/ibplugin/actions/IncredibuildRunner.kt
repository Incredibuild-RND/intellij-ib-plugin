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

import com.incredibuild.ibplugin.actions.cmake.CMakeBuildKind
import com.incredibuild.ibplugin.actions.cmake.ResolvedCMakeBuild
import com.incredibuild.ibplugin.actions.cmake.deriveCMakeBuild
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindowManager
import com.incredibuild.ibplugin.analytics.PostHogClient
import java.io.File

/** Matches the "Build ID: {<guid>}" line BuildConsole prints to stdout when a build starts.
 * Windows only - ib_console on Linux never prints a build-id, so this simply never matches
 * there (see [IncredibuildRunner.stopActiveProcess]). */
private val BUILD_ID_REGEX = Regex("""Build ID:\s*\{([0-9A-Fa-f-]+)}""")

// Used for both Windows and Linux - same landing page for both.
private const val INCREDIBUILD_WEBSITE = "https://www.incredibuild.com/integrations/jetbrains-ides"

private const val INCREDIBUILD_DOWNLOAD_PAGE =
    "https://docs.incredibuild.com/site-landing/download-docs-center"

private const val MINIMUM_RUST_BUILD_VERSION = "10.37.1"

// The Linux team found an issue with an earlier released Linux Incredibuild
// version during RustRover integration testing, hence this gate. Deliberately a
// separate constant, not shared with the Windows one: Windows and Linux
// Incredibuild use entirely unrelated version-numbering schemes (e.g.
// "10.37.0.12597" vs "3.18.0").
private const val MINIMUM_RUST_BUILD_VERSION_LINUX = "4.29.3"

/**
 * Shared plumbing for locating Incredibuild, launching BuildConsole (or a plain
 * process, e.g. "cargo clean"), and streaming output into the Incredibuild tool window.
 */
object IncredibuildRunner {

    @Volatile
    private var activeProcessHandler: OSProcessHandler? = null

    /** The Incredibuild build-id of [activeProcessHandler], parsed from its own stdout, if any. */
    @Volatile
    private var activeBuildId: String? = null

    /**
     * Checks that Incredibuild is installed and at least the platform-appropriate
     * minimum version, showing the "not found" / "too old" dialogs and returning
     * null if the caller should not proceed. Must be called from a background
     * thread (queries the registry, or reads a file on Linux); shows dialogs via
     * [ApplicationManager.invokeLater]/`invokeAndWait`.
     *
     * Exposed (not private) so [RebuildProjectWithIncredibuildAction] can run this
     * check *before* its destructive "cargo clean" step, rather than only discovering
     * Incredibuild is missing/too old afterwards, once the build output is already gone.
     */
    internal fun ensureIncredibuildReady(project: Project, actionType: String): File? {
        val installFolder = IncredibuildLocator.findInstallFolder()
        if (installFolder == null) {
            PostHogClient.capture("incredibuild_not_found", mapOf("action" to actionType))
            ApplicationManager.getApplication().invokeLater {
                val result = Messages.showOkCancelDialog(
                    project,
                    "Incredibuild is required to accelerate this build, but it wasn't found on this machine.\n\n" +
                        "You'll be directed to the Incredibuild homepage to download it.",
                    "Incredibuild Not Found",
                    "Open Incredibuild.com",
                    "Cancel",
                    Messages.getWarningIcon()
                )
                if (result == Messages.OK) {
                    PostHogClient.capture("website_opened")
                    BrowserUtil.browse("$INCREDIBUILD_WEBSITE?user-id=${PostHogClient.distinctId}")
                }
            }
            return null
        }

        val minimumVersion = if (SystemInfo.isLinux) MINIMUM_RUST_BUILD_VERSION_LINUX else MINIMUM_RUST_BUILD_VERSION
        val installedVersion = IncredibuildLocator.findVersion()
        if (installedVersion != null &&
            IncredibuildLocator.compareVersions(installedVersion, minimumVersion) < 0
        ) {
            PostHogClient.capture("incredibuild_version_too_old", mapOf("installedVersion" to installedVersion))
            var proceed = true
            ApplicationManager.getApplication().invokeAndWait {
                val result = Messages.showDialog(
                    project,
                    "The installed Incredibuild version ($installedVersion) is older than the minimum " +
                        "version recommended for Rust build support ($minimumVersion).\n\n" +
                        "The build may not work correctly.",
                    "Incredibuild Version May Be Too Old",
                    arrayOf("Continue Anyway", "Download Latest Version", "Cancel"),
                    0,
                    Messages.getWarningIcon()
                )
                if (result == 1) {
                    PostHogClient.capture("download_latest_version_opened")
                    BrowserUtil.browse(INCREDIBUILD_DOWNLOAD_PAGE)
                }
                proceed = result == 0
            }
            if (!proceed) return null
        }

        return installFolder
    }

    /**
     * Runs [ensureIncredibuildReady] and builds the BuildConsole command line on a
     * background thread (this involves a blocking registry query and file-existence
     * checks, which must not run on the EDT), then hops back to the EDT to actually
     * launch it and wire up the tool window.
     */
    fun buildViaIncredibuild(project: Project, cargoCommand: String, workingDirectory: String, actionType: String) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Locating Incredibuild") {
            override fun run(indicator: ProgressIndicator) {
                val installFolder = ensureIncredibuildReady(project, actionType) ?: return
                buildViaIncredibuild(project, installFolder, cargoCommand, workingDirectory, actionType)
            }
        })
    }

    /**
     * As above, but with the invocation resolved inside the background task rather than by
     * the caller - [deriveCargoInvocation] reads the Cargo project model and resolves the
     * selected run configuration, neither of which may happen on the EDT where actions run.
     */
    internal fun buildViaIncredibuild(project: Project, actionType: String, invocation: () -> CargoInvocation) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Locating Incredibuild") {
            override fun run(indicator: ProgressIndicator) {
                val installFolder = ensureIncredibuildReady(project, actionType) ?: return
                val resolved = invocation()
                buildViaIncredibuild(project, installFolder, resolved.command, resolved.workingDirectory, actionType)
            }
        })
    }

    /**
     * Launches the build directly against an already-resolved, already-verified
     * install folder, skipping [ensureIncredibuildReady] entirely. Exposed so
     * [RebuildProjectWithIncredibuildAction] - which must call
     * [ensureIncredibuildReady] itself up front, before "cargo clean" runs - can
     * reuse that result instead of triggering the readiness check (and its "not
     * found"/"too old" dialogs) a second time once the clean finishes.
     */
    internal fun buildViaIncredibuild(
        project: Project,
        installFolder: File,
        cargoCommand: String,
        workingDirectory: String,
        actionType: String,
        onFinished: ((exitCode: Int) -> Unit)? = null
    ) {
        when (val prepared = prepareBuild(project, installFolder, cargoCommand, workingDirectory)) {
            is PreparedBuild.Failed -> showErrorOnEdt(project, prepared.message)
            is PreparedBuild.Ready -> launchBuild(project, prepared.commandLine, actionType) { exitCode ->
                prepared.cleanUp()
                onFinished?.invoke(exitCode)
            }
        }
    }

    /**
     * An Incredibuild command line that is ready to launch, or the reason one could not be
     * built. Kept separate from launching it so the same command can either be streamed
     * into our own tool window (the Incredibuild menu actions) or handed to the IDE's build
     * pipeline (see [IncredibuildProjectTaskRunner]) - and so a missing binary can be
     * reported with a dialog in the first case and silently declined in the second.
     */
    internal sealed interface PreparedBuild {
        /** [cleanUp] deletes the temporary wrapper script, and must run once the process exits. */
        class Ready(val commandLine: GeneralCommandLine, val cleanUp: () -> Unit) : PreparedBuild
        class Failed(val message: String) : PreparedBuild
    }

    internal fun prepareBuild(
        project: Project,
        installFolder: File,
        cargoCommand: String,
        workingDirectory: String
    ): PreparedBuild =
        if (SystemInfo.isLinux) {
            prepareBuildLinux(project, installFolder, cargoCommand, workingDirectory)
        } else {
            prepareBuildWindows(project, installFolder, cargoCommand, workingDirectory)
        }

    private fun prepareBuildWindows(
        project: Project,
        installFolder: File,
        cargoCommand: String,
        workingDirectory: String
    ): PreparedBuild {
        val buildConsolePath = File(installFolder, "BuildConsole.exe")
        if (!buildConsolePath.exists()) {
            return PreparedBuild.Failed(
                "BuildConsole.exe was not found in the Incredibuild install folder:\n${installFolder.absolutePath}"
            )
        }

        val profilePath = File(installFolder, "Profiles${File.separator}rust.ib_profile.xml")
        val buildCacheProfilePath =
            File(installFolder, "BuildCache Profiles${File.separator}BuildCache_profile_rust.xml")

        // BuildConsole needs "/profile=" and "/command=" with quotes around just the
        // value. Building that directly via GeneralCommandLine hits a real bug: its
        // Windows argument escaping re-wraps an already-quoted, space-containing value
        // into a shape ("starts with a quote, ends with backslash-quote") that a known
        // JDK/JBR native ProcessBuilder bug mis-encodes on x64 Windows specifically
        // (not reproduced on ARM64) - confirmed by testing the identical values launched
        // via PowerShell's own process invocation, which works correctly on both. So we
        // shell out through a small PowerShell script instead of invoking BuildConsole
        // directly: PowerShell's argument handling doesn't hit this bug, and the only
        // argument GeneralCommandLine needs to build here (a plain script file path) has
        // no embedded quotes of its own, so it never touches the buggy code path either.
        val scriptFile = File.createTempFile("incredibuild-build-", ".ps1")
        scriptFile.deleteOnExit()
        scriptFile.writeText(
            "& \"${buildConsolePath.absolutePath}\" " +
                "${powerShellSingleQuoted("/profile=\"${profilePath.absolutePath}\"")} " +
                "${powerShellSingleQuoted("/command=\"$cargoCommand\"")} " +
                "${powerShellSingleQuoted("/title=\"${project.name}\"")} " +
                "${powerShellSingleQuoted("/BuildCacheProfile=\"${buildCacheProfilePath.absolutePath}\"")}\n" +
                "exit \$LASTEXITCODE\n"
        )

        val commandLine = GeneralCommandLine("powershell")
            .withParameters("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptFile.absolutePath)
            .withWorkDirectory(workingDirectory)

        return PreparedBuild.Ready(commandLine) { scriptFile.delete() }
    }

    /**
     * PENDING CONFIRMATION with the Linux Incredibuild developer - built from reading
     * the ib_linux repo's ib_console source (cpp/XgConsole), not from documentation or
     * hands-on testing:
     *  - The binary is `ib_console`, not "BuildConsole"; its CLI is GNU getopt-style
     *    (`-p`/`--profile`, `-c`/`--caption`), not Windows' `/switch=value` style.
     *  - There is no `/command=` equivalent: the build command is trailing argv after
     *    the recognized options, not a single quoted string - relying on the shell to
     *    word-split it below is the closest match, and has the same "won't preserve
     *    embedded quoting inside cargoCommand" limitation the Windows /command= path
     *    already has today.
     *  - There is no `/BuildCacheProfile=` equivalent at all (Linux build-cache config
     *    is its own set of `--build-cache-*` flags, not a second profile file), so
     *    nothing is passed here pending a decision on whether/how to wire that up.
     *  - Points `--profile` at a dedicated RustRover profile,
     *    `<install>/data/custom_profiles/rustrover/ib_profile.xml`, rather than
     *    `ib_console`'s own shipped default profile (which already covers Rust -
     *    cargo, rustc, build_script_build, maturin - out of the box).
     *  - The script uses `exec` rather than a plain invocation so the shell process
     *    is replaced by ib_console in place (same PID) rather than staying a parent
     *    of it: [stopActiveProcess]'s plain [OSProcessHandler.destroyProcess] sends
     *    SIGTERM, which only reaches ib_console itself this way - and ib_console
     *    handles SIGTERM gracefully (see its `_onSignal`), the same signal their own
     *    Watchdog process sends internally to implement "Stop Build". No build-id or
     *    `/STOP`-style mechanism exists on Linux at all (nor does ib_console print
     *    one), so this is the only cancellation mechanism available.
     */
    private fun prepareBuildLinux(
        project: Project,
        installFolder: File,
        cargoCommand: String,
        workingDirectory: String
    ): PreparedBuild {
        val consolePath = File(installFolder, "bin/ib_console")
        if (!consolePath.exists()) {
            return PreparedBuild.Failed(
                "ib_console was not found in the Incredibuild install folder:\n${installFolder.absolutePath}"
            )
        }

        val profilePath = File(installFolder, "data/custom_profiles/rustrover/ib_profile.xml")

        val scriptFile = File.createTempFile("incredibuild-build-", ".sh")
        scriptFile.deleteOnExit()
        scriptFile.writeText(
            "#!/bin/sh\n" +
                "exec ${shellSingleQuoted(consolePath.absolutePath)} " +
                "--profile ${shellSingleQuoted(profilePath.absolutePath)} " +
                "--caption ${shellSingleQuoted(project.name)} " +
                // Suppresses ib_console's own "Trying to connect to ib_server..." /
                // "ib_server connected, start process execution..." status lines
                // (gated by ib_quiet in XgConsole_Session.cpp) - noise in an IDE
                // build console, not something a RustRover user needs to see.
                "--ib-quiet " +
                "--build-cache-local-shared " +
                // Double-quoted, not shellSingleQuoted(): single quotes would suppress shell
                // expansion entirely and pass the literal 4 characters "$PWD" as the value.
                // Resolved by /bin/sh at runtime on the build machine, not by us here - since
                // the process's own working directory is already set to workingDirectory
                // below, this should resolve to that same directory in practice.
                "--build-cache-basedir=\"\$PWD\" " +
                "-- $cargoCommand " +
                // Merges stderr into stdout, so there's only one stream and it's
                // always treated as normal output rather than IntelliJ's console
                // defaulting stderr-sourced text to red styling. cargo's build
                // status lines go to stderr by convention even though they're not
                // errors; a real terminal doesn't color-differentiate the two
                // streams at all, only cargo's own ANSI codes (forced on above) do -
                // this makes our console behave the same way.
                "2>&1\n"
        )

        val commandLine = GeneralCommandLine("/bin/sh", scriptFile.absolutePath)
            .withWorkDirectory(workingDirectory)
            // cargo/rustc auto-detect whether their output is a real terminal and
            // disable ANSI color entirely once it isn't - which is exactly what
            // happens once output is piped through ib_console/our own process
            // wrapper instead of a real TTY. Force it back on: our console already
            // decodes ANSI codes correctly (that's how the native Cargo console gets
            // "Compiling" etc. colored), it just needs cargo to actually emit them -
            // without this, cargo emits plain text and the whole line falls back to
            // IntelliJ's default "stderr = red" styling instead (cargo's build
            // status lines go to stderr by design).
            .withEnvironment("CARGO_TERM_COLOR", "always")
            // Forcing color on above appears to also enable cargo's OSC 8 terminal
            // hyperlink escape sequences (e.g. linking a profile name to its docs
            // page) as a side effect - a different mechanism than the SGR color
            // codes ColoredProcessHandler decodes, which it doesn't understand at
            // all, so they show up as raw "]8;;url\...\" text. Based on documented
            // cargo behavior (the "term.hyperlinks" config key), not verified against
            // cargo's own source the way the ib_console flags above were.
            .withEnvironment("CARGO_TERM_HYPERLINKS", "false")

        return PreparedBuild.Ready(commandLine) { scriptFile.delete() }
    }

    private fun launchBuild(
        project: Project,
        commandLine: GeneralCommandLine,
        actionType: String,
        onFinished: (exitCode: Int) -> Unit
    ) {
        PostHogClient.capture("build_started", mapOf("action" to actionType))
        PostHogClient.captureOnce(
            "com.incredibuild.ibplugin.analytics.firstBuildSent",
            "first_build",
            mapOf("action" to actionType)
        )

        ApplicationManager.getApplication().invokeLater {
            run(project, commandLine, "Build", onFinished)
        }
    }

    private fun showErrorOnEdt(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, "Incredibuild")
        }
    }

    /** Wraps [value] as a PowerShell single-quoted string literal (fully literal; only a
     * single quote itself needs escaping, done by doubling it). */
    private fun powerShellSingleQuoted(value: String): String = "'" + value.replace("'", "''") + "'"

    /** Wraps [value] as a POSIX shell single-quoted string literal (fully literal; a
     * single quote itself is escaped by closing the quote, inserting an escaped quote,
     * then reopening it - the standard `'\''` trick). */
    private fun shellSingleQuoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * Runs [commandLine], streaming its output into the Incredibuild tool window.
     * If [onFinished] is given, it runs after the process terminates, with the process's
     * exit code - used both to chain a plain "cargo clean" into an Incredibuild-accelerated
     * build for Rebuild, and to report build success back to the IDE's build pipeline when
     * the build was started from there (see [IncredibuildProjectTaskRunner]).
     */
    fun run(
        project: Project,
        commandLine: GeneralCommandLine,
        contentName: String,
        onFinished: ((exitCode: Int) -> Unit)? = null
    ) {
        val processHandler = createProcessHandler(commandLine)

        val console = ConsoleViewImpl(project, true)
        console.attachToProcess(processHandler)

        val toolWindow = requireNotNull(ToolWindowManager.getInstance(project).getToolWindow("Incredibuild")) {
            "The Incredibuild tool window is declared in plugin.xml and should always be registered."
        }
        toolWindow.contentManager.removeAllContents(true)
        val content = toolWindow.contentManager.factory.createContent(console.component, contentName, false)
        toolWindow.contentManager.addContent(content)
        toolWindow.show()

        if (onFinished != null) {
            processHandler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    val exitCode = event.exitCode
                    ApplicationManager.getApplication().invokeLater { onFinished(exitCode) }
                }
            })
        }
        processHandler.startNotify()
    }

    /**
     * The process handler to run any Incredibuild build with, registered as the active one
     * so [stopActiveProcess] can find it.
     *
     * Exposed so the IDE's build pipeline uses the same handler
     * ([IncredibuildProjectTaskRunner]) rather than a plain one. That matters twice over:
     * the Incredibuild menu's Stop Build can only act on a build it knows about, and the
     * Build tool window's own stop button calls [OSProcessHandler.destroyProcess], which
     * this handler turns into Incredibuild's graceful cancellation instead of a kill.
     *
     * Note the handler is returned unstarted - callers start it, or in the build-pipeline
     * case `CargoBuildAdapter.attachToProcessHandler` does.
     */
    internal fun createProcessHandler(
        commandLine: GeneralCommandLine,
        decodeAnsiColors: Boolean = true
    ): OSProcessHandler {
        val processHandler = if (decodeAnsiColors) {
            ColoredIncredibuildProcessHandler(commandLine)
        } else {
            RawIncredibuildProcessHandler(commandLine)
        }
        activeProcessHandler = processHandler
        activeBuildId = null

        processHandler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val match = BUILD_ID_REGEX.find(event.text) ?: return
                if (activeProcessHandler === processHandler) {
                    activeBuildId = match.groupValues[1]
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                if (activeProcessHandler === processHandler) {
                    activeProcessHandler = null
                    activeBuildId = null
                }
            }
        })
        return processHandler
    }

    /**
     * A build's handler for our own tool window, which decodes cargo's ANSI escape codes
     * into real console coloring - a [ConsoleViewImpl] is attached to it there, and reads
     * the color off the output type.
     *
     * [destroyProcessImpl] is overridden rather than only handling stops from our own menu
     * action, because the IDE's build tool window has its own stop button:
     * `ExecutionManagerImpl.stopProcess` calls `destroyProcess()` on it, and the default
     * implementation would kill the process - which Incredibuild reports as a crashed build
     * rather than a cancelled one.
     */
    private class ColoredIncredibuildProcessHandler(
        commandLine: GeneralCommandLine
    ) : ColoredProcessHandler(commandLine) {

        override fun destroyProcessImpl() {
            if (!requestGracefulStop()) super.destroyProcessImpl()
        }
    }

    /**
     * A build's handler for the IDE's Build tool window, which deliberately does *not*
     * decode ANSI escape codes.
     *
     * `CargoBuildAdapterBase.onTextAvailable` forwards only `event.text` and discards the
     * output type, so the codes have to survive *in the text* for the build console to
     * color it - decoding them here would strip them and leave plain text. This mirrors the
     * Rust plugin's own handler, which builds its decoder as
     * `if (processColors && !hasPty) RsAnsiEscapeDecoder() else null` and which
     * `CargoBuildManager.build` deliberately constructs with processColors=false.
     *
     * Extends KillableProcessHandler (which does no decoding) because
     * ColoredProcessHandler's `notifyTextAvailable` is final and always decodes. That makes
     * this one a `KillableProcess`, so `ExecutionManagerImpl.stopProcess` can also reach
     * `killProcess` - hence both stop entry points are made graceful.
     */
    private class RawIncredibuildProcessHandler(
        commandLine: GeneralCommandLine
    ) : KillableProcessHandler(commandLine) {

        override fun destroyProcessImpl() {
            if (!requestGracefulStop()) super.destroyProcessImpl()
        }

        override fun killProcess() {
            if (!requestGracefulStop()) super.killProcess()
        }
    }

    /**
     * Asks Incredibuild to cancel the running build by build-id, returning false if there
     * is no build-id to cancel by or the request could not be dispatched - in which case
     * the caller must fall back to terminating the process.
     *
     * Runs synchronously: it is called from stop handling, which must not report the
     * process as stopped before the cancellation has actually been sent.
     */
    private fun requestGracefulStop(): Boolean {
        val buildId = activeBuildId ?: return false
        return try {
            val installFolder = IncredibuildLocator.findInstallFolder() ?: return false
            val buildConsolePath = File(installFolder, "BuildConsole.exe")
            if (!buildConsolePath.exists()) return false
            val stopCommandLine = GeneralCommandLine(buildConsolePath.absolutePath, "/STOP={$buildId}")
            ExecUtil.execAndGetOutput(stopCommandLine)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Stops whichever build/clean process is currently running, if any.
     *
     * When [activeBuildId] is known (an actual Incredibuild-coordinated build, as
     * opposed to e.g. Rebuild's plain "cargo clean" step), this must NOT force-kill
     * the process: Incredibuild's own clients (see BuildConsole.exe's `/STOP`
     * implementation and the Build Monitor's `TBuildProxy`) only ever send a graceful
     * cancel request identified by build-id and then let BuildSystem wind down and
     * exit **on its own** - they never call TerminateProcess in the normal stop path.
     * The Build Monitor treats an abandoned build synchronization object as a crash
     * ("Connection to Build System was terminated unexpectedly") *unless* it already
     * knows a cancel was requested; killing our wrapper process ourselves - especially
     * concurrently with the `/STOP` request, as an earlier version of this code did -
     * is exactly what makes it look like a crash instead of a normal cancellation.
     * [OSProcessHandler.destroyProcess] is only used as a fallback for when there is no
     * build-id to gracefully cancel by (plain "cargo clean") or the `/STOP` request
     * itself could not even be dispatched.
     *
     * On Linux, [activeBuildId] is always null - [BUILD_ID_REGEX] never matches ib_console's
     * output, since it doesn't print one - so this always takes the `destroyProcess()` path.
     * That's not a degraded fallback there, though: it's the *correct* mechanism, because
     * [buildViaIncredibuildLinux] launches ib_console via `exec` (replacing the wrapper
     * shell process rather than staying its parent), so `destroyProcess()`'s SIGTERM reaches
     * ib_console itself directly, which it already handles as a graceful stop request.
     */
    fun stopActiveProcess(project: Project) {
        val handler = activeProcessHandler
        val hadActiveProcess = handler != null && !handler.isProcessTerminated
        PostHogClient.capture("stop_build_clicked", mapOf("hadActiveProcess" to hadActiveProcess))
        if (!hadActiveProcess) {
            Messages.showInfoMessage(project, "No Incredibuild build is currently running.", "Incredibuild")
            return
        }

        // destroyProcess() rather than a cancellation request built here: the handler
        // itself turns this into Incredibuild's graceful stop where one is possible (see
        // [IncredibuildProcessHandler]), which keeps this action and the build tool
        // window's own stop button behaving identically. Dispatched off the EDT because
        // the graceful path shells out to BuildConsole.
        ApplicationManager.getApplication().executeOnPooledThread { handler.destroyProcess() }
    }

    // -----------------------------------------------------------------------------------
    // CMake (CLion) support - mirrors the Cargo/Rust flow above, but a cmake invocation is
    // built from a resolved executable + argument list + environment (see
    // com.incredibuild.ibplugin.actions.cmake.CMakeBuildCommand) rather than a single
    // command string, and needs no custom Incredibuild profile: unlike Rust/cargo,
    // Incredibuild's own default profile already covers C/C++ builds.
    // -----------------------------------------------------------------------------------

    /**
     * Checks that Incredibuild is installed, showing the "not found" dialog and returning
     * null if the caller should not proceed. Unlike [ensureIncredibuildReady], this has no
     * minimum-version gate: there is no known minimum Incredibuild version required for its
     * native CMake/C++ build support, unlike the specific version bump Rust build support
     * needed.
     */
    internal fun ensureIncredibuildInstalledForCMake(project: Project): File? {
        val installFolder = IncredibuildLocator.findInstallFolder()
        if (installFolder == null) {
            PostHogClient.capture("incredibuild_not_found", mapOf("action" to "cmake"))
            ApplicationManager.getApplication().invokeLater {
                val result = Messages.showOkCancelDialog(
                    project,
                    "Incredibuild is required to accelerate this build, but it wasn't found on this machine.\n\n" +
                        "You'll be directed to the Incredibuild homepage to download it.",
                    "Incredibuild Not Found",
                    "Open Incredibuild.com",
                    "Cancel",
                    Messages.getWarningIcon()
                )
                if (result == Messages.OK) {
                    PostHogClient.capture("website_opened")
                    BrowserUtil.browse("$INCREDIBUILD_WEBSITE?user-id=${PostHogClient.distinctId}")
                }
            }
        }
        return installFolder
    }

    /**
     * Resolves [kind]'s cmake invocation and, if Incredibuild is installed, runs it through
     * Incredibuild. Everything here - locating Incredibuild, reading the CMake project model -
     * is blocking work that must not run on the EDT, so the whole thing runs as a background
     * task; only the dialogs it may show hop back to the EDT.
     */
    internal fun buildCMakeViaIncredibuild(project: Project, actionType: String, kind: CMakeBuildKind) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Locating Incredibuild") {
            override fun run(indicator: ProgressIndicator) {
                val installFolder = ensureIncredibuildInstalledForCMake(project) ?: return
                val resolved = deriveCMakeBuild(project, kind)
                if (resolved == null) {
                    showErrorOnEdt(
                        project,
                        "Could not determine what to build. Is a CMake project loaded, and a run " +
                            "configuration selected?"
                    )
                    return
                }
                when (val prepared = prepareCMakeBuild(project, installFolder, resolved)) {
                    is PreparedBuild.Failed -> showErrorOnEdt(project, prepared.message)
                    is PreparedBuild.Ready -> launchBuild(project, prepared.commandLine, actionType) { _ ->
                        prepared.cleanUp()
                    }
                }
            }
        })
    }

    private fun prepareCMakeBuild(
        project: Project,
        installFolder: File,
        resolved: ResolvedCMakeBuild
    ): PreparedBuild =
        if (SystemInfo.isLinux) {
            prepareCMakeBuildLinux(installFolder, resolved)
        } else {
            prepareCMakeBuildWindows(project, installFolder, resolved)
        }

    /**
     * BuildConsole needs "/command=" with a single quoted value. Rather than hand it the whole
     * `cmake --build ... --target ...` invocation as one string - which would need its own
     * space-containing arguments (the build directory, a target name) individually quoted
     * *inside* that already-quoted value, an ambiguous nested-quoting shape BuildConsole's own
     * parsing for /command= is not documented to support - this writes a tiny wrapper .cmd that
     * runs the real invocation with ordinary single-level quoting, and points /command= at just
     * that script's path (one simple quoted path, the same shape /profile= above already uses
     * uncontroversially). The toolchain environment (PATH/INCLUDE/LIB for an MSVC profile, ...)
     * is set inside the same script via `set`, so it doesn't depend on whether BuildConsole
     * passes its own parent environment through to what it launches.
     *
     * No `/profile=`/`/BuildCacheProfile=` here, unlike [prepareBuildWindows]: those point at a
     * bespoke Incredibuild profile built specifically for Cargo/rustc, which C/C++ needs no
     * equivalent of - Incredibuild's own default profile already covers cmake/ninja/make/nmake
     * and the compilers they invoke.
     */
    private fun prepareCMakeBuildWindows(
        project: Project,
        installFolder: File,
        resolved: ResolvedCMakeBuild
    ): PreparedBuild {
        val buildConsolePath = File(installFolder, "BuildConsole.exe")
        if (!buildConsolePath.exists()) {
            return PreparedBuild.Failed(
                "BuildConsole.exe was not found in the Incredibuild install folder:\n${installFolder.absolutePath}"
            )
        }

        val wrapperCmd = File.createTempFile("incredibuild-cmake-", ".cmd")
        wrapperCmd.deleteOnExit()
        val cmdLines = StringBuilder("@echo off\r\n")
        for ((key, value) in resolved.environment) {
            cmdLines.append("set \"").append(key).append('=').append(value).append("\"\r\n")
        }
        cmdLines.append(cmdQuoted(resolved.executable))
        for (arg in resolved.arguments) {
            cmdLines.append(' ').append(cmdQuoted(arg))
        }
        cmdLines.append("\r\nexit /b %ERRORLEVEL%\r\n")
        wrapperCmd.writeText(cmdLines.toString())

        val scriptFile = File.createTempFile("incredibuild-build-", ".ps1")
        scriptFile.deleteOnExit()
        scriptFile.writeText(
            "& \"${buildConsolePath.absolutePath}\" " +
                "${powerShellSingleQuoted("/command=\"${wrapperCmd.absolutePath}\"")} " +
                "${powerShellSingleQuoted("/title=\"${project.name}\"")} " +
                // Explicitly opts this session into Build Cache, the same way the Linux
                // ib_console invocation below already does via --build-cache-local-shared - no
                // /BuildCacheProfile= is passed alongside it because cl.exe/clang/gcc are
                // already covered by BuildConsole's own built-in default profile (the bespoke
                // *.xml sample profiles in the Incredibuild install's "BuildCache Profiles"
                // folder are only for tools that default profile doesn't already know, e.g.
                // rustc/lib for the Cargo build above, or nvcc/perl).
                "/BUILDCACHELOCAL=ON\n" +
                "exit \$LASTEXITCODE\n"
        )

        val commandLine = GeneralCommandLine("powershell")
            .withParameters("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptFile.absolutePath)
            .withWorkDirectory(resolved.workingDirectory)

        return PreparedBuild.Ready(commandLine) {
            scriptFile.delete()
            wrapperCmd.delete()
        }
    }

    /**
     * ib_console has no `/command=`-style text field - the build command is trailing argv
     * after its own recognized options - so, unlike Windows, there's no nested-quoting
     * concern to work around here: the whole invocation (environment exports, then the cmake
     * command) is written into one script, each token quoted individually and safely via
     * [shellSingleQuoted].
     *
     * No custom `--profile` here, unlike [prepareBuildLinux]'s RustRover-specific one: C/C++
     * needs no equivalent, ib_console's own shipped default profile already covers it.
     */
    private fun prepareCMakeBuildLinux(installFolder: File, resolved: ResolvedCMakeBuild): PreparedBuild {
        val consolePath = File(installFolder, "bin/ib_console")
        if (!consolePath.exists()) {
            return PreparedBuild.Failed(
                "ib_console was not found in the Incredibuild install folder:\n${installFolder.absolutePath}"
            )
        }

        val scriptFile = File.createTempFile("incredibuild-build-", ".sh")
        scriptFile.deleteOnExit()
        val script = StringBuilder("#!/bin/sh\n")
        for ((key, value) in resolved.environment) {
            script.append("export ").append(key).append('=').append(shellSingleQuoted(value)).append('\n')
        }
        script.append("exec ").append(shellSingleQuoted(consolePath.absolutePath))
            .append(" --ib-quiet --build-cache-local-shared --build-cache-basedir=\"\$PWD\" -- ")
            .append(shellSingleQuoted(resolved.executable))
        for (arg in resolved.arguments) {
            script.append(' ').append(shellSingleQuoted(arg))
        }
        // Merges stderr into stdout, for the same reason prepareBuildLinux's Cargo build does.
        script.append(" 2>&1\n")
        scriptFile.writeText(script.toString())

        val commandLine = GeneralCommandLine("/bin/sh", scriptFile.absolutePath)
            .withWorkDirectory(resolved.workingDirectory)

        return PreparedBuild.Ready(commandLine) { scriptFile.delete() }
    }

    /** Wraps [value] as a cmd.exe double-quoted token, needed only when it contains whitespace. */
    private fun cmdQuoted(value: String): String = if (value.any { it.isWhitespace() }) "\"$value\"" else value
}
