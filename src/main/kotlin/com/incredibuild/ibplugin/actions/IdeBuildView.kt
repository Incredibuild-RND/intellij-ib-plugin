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

import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.MessageEvent
import com.intellij.build.process.BuildProcessHandler
import com.intellij.build.progress.BuildProgress
import com.intellij.build.progress.BuildProgressDescriptor
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Reports an accelerated build of the IDE's own (see [IncredibuildRunner.runNativeBuildAndWait])
 * into the IDE's Build tool window, the way a local build reports there - as the Cargo build
 * already does through the Rust plugin's own build adapter:
 *  - the output, live, with file locations in it clickable;
 *  - each compiler error and warning as an entry in the build tree that opens its file and line;
 *  - the build's progress (Ninja's and Make's step counts, MSBuild's finished projects) on the
 *    build itself while it runs;
 *  - the result and how long it took; and
 *  - a stop button that cancels the Incredibuild build gracefully, as Stop Build does.
 *
 * Attach it before the process starts: [start] adds itself as the process's listener.
 *
 * [baseDirectories] resolve the relative paths compilers print - for a CMake build, the build
 * directory Ninja/Make run the compiler from comes first.
 */
internal class IdeBuildView(
    private val project: Project,
    private val title: String,
    private val workingDirectory: String,
    private val baseDirectories: List<String>,
    private val processHandler: ProcessHandler,
) : ProcessListener {

    private val progress: BuildProgress<BuildProgressDescriptor> = BuildViewManager.createBuildProgress(project)

    /** Partial last lines, per stream, until their line ends - output arrives in arbitrary chunks. */
    private val pendingLines = mutableMapOf<Key<*>, StringBuilder>()

    /** MSBuild repeats every error and warning in its closing summary - one tree entry each is enough. */
    private val reported = mutableSetOf<BuildOutputLine.Diagnostic>()

    private var errors = 0
    private var warnings = 0
    private var startedAt = 0L

    fun start(banner: String) {
        startedAt = System.currentTimeMillis()
        val descriptor = DefaultBuildDescriptor(Any(), title, workingDirectory, startedAt)
            .withProcessHandler(StopButtonHandler(processHandler, title), null)
            .withExecutionFilter(LocationFilter())
        descriptor.isActivateToolWindowWhenAdded = true
        descriptor.isActivateToolWindowWhenFailed = true
        progress.start(object : BuildProgressDescriptor {
            override fun getTitle() = this@IdeBuildView.title
            override fun getBuildDescriptor() = descriptor
        })
        progress.output("$banner\n\n", ProcessOutputType.SYSTEM)
        processHandler.addProcessListener(this)
    }

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        // Forwarded as-is, escape codes and all: the Build tool window's console colours output by
        // reading them from the text (as for Cargo - see IncredibuildRunner.RawIncredibuildProcessHandler).
        progress.output(event.text, outputType as? ProcessOutputType ?: ProcessOutputType.STDOUT)
        if (outputType == ProcessOutputType.SYSTEM) return

        val pending = synchronized(pendingLines) { pendingLines.getOrPut(outputType) { StringBuilder() } }
        val lines = synchronized(pending) {
            pending.append(event.text)
            val end = pending.lastIndexOf("\n")
            if (end < 0) return
            pending.substring(0, end).also { pending.delete(0, end + 1) }
        }
        lines.split('\n').forEach { report(it.trimEnd('\r')) }
    }

    override fun processTerminated(event: ProcessEvent) {
        synchronized(pendingLines) { pendingLines.values.toList() }.forEach { report(it.toString()) }

        val finishedAt = System.currentTimeMillis()
        val duration = StringUtil.formatDuration(finishedAt - startedAt)
        val stopped = IncredibuildRunner.wasStopRequested(processHandler)
        val counts = listOfNotNull(
            StringUtil.pluralize("error", errors).let { "$errors $it" }.takeIf { errors > 0 },
            StringUtil.pluralize("warning", warnings).let { "$warnings $it" }.takeIf { warnings > 0 },
        ).joinToString(", ").let { if (it.isEmpty()) "" else " with $it" }

        when {
            stopped -> {
                progress.output("\nBuild cancelled after $duration\n", ProcessOutputType.SYSTEM)
                progress.cancel(finishedAt, "Build cancelled")
            }
            event.exitCode == 0 -> {
                progress.output("\nBuild succeeded$counts in $duration\n", ProcessOutputType.SYSTEM)
                progress.finish(finishedAt, false, "Build succeeded$counts")
            }
            else -> {
                progress.output("\nBuild failed$counts in $duration (exit code ${event.exitCode})\n", ProcessOutputType.SYSTEM)
                progress.fail(finishedAt, "Build failed$counts")
            }
        }
    }

    private fun report(line: String) {
        when (val parsed = CompilerOutputParser.parse(CompilerOutputParser.stripAnsi(line))) {
            is BuildOutputLine.Diagnostic -> {
                synchronized(reported) {
                    if (!reported.add(parsed.copy(locationRange = null))) return
                    if (parsed.isError) errors++ else warnings++
                }
                val kind = if (parsed.isError) MessageEvent.Kind.ERROR else MessageEvent.Kind.WARNING
                val path = parsed.file?.let(::resolve)
                if (path != null && parsed.line != null) {
                    // FilePosition is 0-based; compilers print 1-based positions.
                    val position = FilePosition(path, parsed.line - 1, (parsed.column ?: 1) - 1)
                    progress.fileMessage(parsed.message, line, kind, position)
                } else {
                    progress.message(parsed.message, line, kind, null)
                }
            }
            is BuildOutputLine.Progress -> {
                if (parsed.done != null && parsed.total != null) {
                    progress.progress(parsed.message, parsed.total, parsed.done, "")
                } else {
                    progress.progress(parsed.message)
                }
            }
            null -> Unit
        }
    }

    /**
     * [file] as an absolute path: as printed if it already is one, else against the first of
     * [baseDirectories] it exists in - or the first one, if none, so it still points somewhere
     * sensible. Null if it isn't a valid path at all.
     */
    private fun resolve(file: String): Path? = try {
        val path = Path.of(file)
        if (path.isAbsolute) {
            path.normalize()
        } else {
            val candidates = baseDirectories.map { Path.of(it).resolve(path).normalize() }
            candidates.firstOrNull { Files.exists(it) } ?: candidates.firstOrNull()
        }
    } catch (e: InvalidPathException) {
        null
    }

    /** Makes the `file(line)` / `file:line` locations in the output clickable. */
    private inner class LocationFilter : Filter {
        override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
            val diagnostic = CompilerOutputParser.parse(line.trimEnd('\n', '\r')) as? BuildOutputLine.Diagnostic
                ?: return null
            val range = diagnostic.locationRange ?: return null
            val path = diagnostic.file?.let(::resolve) ?: return null
            val file = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return null
            val lineStart = entireLength - line.length
            val hyperlink = OpenFileHyperlinkInfo(
                project,
                file,
                (diagnostic.line ?: 1) - 1,
                (diagnostic.column ?: 1) - 1
            )
            return Filter.Result(lineStart + range.first, lineStart + range.last + 1, hyperlink)
        }
    }

    /**
     * What the Build tool window's stop button acts on: it calls [destroyProcess], which is
     * passed on to the build's own handler - turning it into Incredibuild's graceful cancellation,
     * as Stop Build does (see [IncredibuildRunner.stopActiveProcess]). Off the EDT, because that
     * graceful path shells out to BuildConsole.
     */
    private class StopButtonHandler(private val build: ProcessHandler, private val name: String) : BuildProcessHandler() {
        init {
            build.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) = notifyProcessTerminated(event.exitCode)
            })
            startNotify()
        }

        override fun getExecutionName() = name

        override fun destroyProcessImpl() {
            ApplicationManager.getApplication().executeOnPooledThread { build.destroyProcess() }
        }

        override fun detachProcessImpl() = notifyProcessDetached()

        override fun detachIsDefault() = false

        override fun getProcessInput(): OutputStream? = null
    }
}
