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

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.ColoredProcessHandler
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

private const val MINIMUM_RUST_BUILD_VERSION = "10.37.0.0"

// The Linux team found an issue with an earlier released Linux Incredibuild
// version during RustRover integration testing, hence this gate. Deliberately a
// separate constant, not shared with the Windows one: Windows and Linux
// Incredibuild use entirely unrelated version-numbering schemes (e.g.
// "10.37.0.12597" vs "3.18.0").
private const val MINIMUM_RUST_BUILD_VERSION_LINUX = "4.29.4"

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
        actionType: String
    ) {
        if (SystemInfo.isLinux) {
            buildViaIncredibuildLinux(project, installFolder, cargoCommand, workingDirectory, actionType)
        } else {
            buildViaIncredibuildWindows(project, installFolder, cargoCommand, workingDirectory, actionType)
        }
    }

    private fun buildViaIncredibuildWindows(
        project: Project,
        installFolder: File,
        cargoCommand: String,
        workingDirectory: String,
        actionType: String
    ) {
        val buildConsolePath = File(installFolder, "BuildConsole.exe")
        if (!buildConsolePath.exists()) {
            showErrorOnEdt(
                project,
                "BuildConsole.exe was not found in the Incredibuild install folder:\n${installFolder.absolutePath}"
            )
            return
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

        launchBuild(project, commandLine, actionType) { scriptFile.delete() }
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
    private fun buildViaIncredibuildLinux(
        project: Project,
        installFolder: File,
        cargoCommand: String,
        workingDirectory: String,
        actionType: String
    ) {
        val consolePath = File(installFolder, "bin/ib_console")
        if (!consolePath.exists()) {
            showErrorOnEdt(
                project,
                "ib_console was not found in the Incredibuild install folder:\n${installFolder.absolutePath}"
            )
            return
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

        launchBuild(project, commandLine, actionType) { scriptFile.delete() }
    }

    private fun launchBuild(
        project: Project,
        commandLine: GeneralCommandLine,
        actionType: String,
        onFinished: () -> Unit
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
     * If [onFinished] is given, it runs after the process terminates (used to chain
     * a plain "cargo clean" into an Incredibuild-accelerated build for Rebuild).
     */
    fun run(project: Project, commandLine: GeneralCommandLine, contentName: String, onFinished: (() -> Unit)? = null) {
        // ColoredProcessHandler (not plain OSProcessHandler): decodes ANSI SGR escape
        // codes in the process's output into real ConsoleView coloring. Without it,
        // cargo's CARGO_TERM_COLOR=always output shows the raw escape sequences as
        // literal text instead of color, since nothing is interpreting them.
        val processHandler = ColoredProcessHandler(commandLine)
        activeProcessHandler = processHandler
        activeBuildId = null

        val console = ConsoleViewImpl(project, true)
        console.attachToProcess(processHandler)

        val toolWindow = requireNotNull(ToolWindowManager.getInstance(project).getToolWindow("Incredibuild")) {
            "The Incredibuild tool window is declared in plugin.xml and should always be registered."
        }
        toolWindow.contentManager.removeAllContents(true)
        val content = toolWindow.contentManager.factory.createContent(console.component, contentName, false)
        toolWindow.contentManager.addContent(content)
        toolWindow.show()

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
                if (onFinished != null) {
                    ApplicationManager.getApplication().invokeLater { onFinished() }
                }
            }
        })
        processHandler.startNotify()
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

        val buildId = activeBuildId
        if (buildId == null) {
            handler.destroyProcess()
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val installFolder = IncredibuildLocator.findInstallFolder()
                val buildConsolePath = installFolder?.let { File(it, "BuildConsole.exe") }
                if (buildConsolePath != null && buildConsolePath.exists()) {
                    val stopCommandLine = GeneralCommandLine(buildConsolePath.absolutePath, "/STOP={$buildId}")
                    ExecUtil.execAndGetOutput(stopCommandLine)
                } else {
                    ApplicationManager.getApplication().invokeLater { handler.destroyProcess() }
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater { handler.destroyProcess() }
            }
        }
    }
}
