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

/** What [CompilerOutputParser.parse] recognised in one line of build output. */
internal sealed interface BuildOutputLine {

    /**
     * A compiler, linker or MSBuild error/warning. [file] is as printed (possibly relative), null
     * for one that names no file (e.g. a linker error); [line]/[column] are 1-based.
     * [locationRange] is where the file location is in the line, for hyperlinking it.
     */
    data class Diagnostic(
        val isError: Boolean,
        val message: String,
        val file: String? = null,
        val line: Int? = null,
        val column: Int? = null,
        val locationRange: IntRange? = null,
    ) : BuildOutputLine

    /** A build step starting or ending; [done] of [total] when the build tool says, else null. */
    data class Progress(val message: String, val done: Long? = null, val total: Long? = null) : BuildOutputLine
}

/**
 * Recognises errors, warnings and progress in the output of the builds accelerated as the IDE's
 * own (see [IdeBuildView]): CMake's generators (Ninja, Make, NMake) running GCC, Clang or MSVC,
 * and MSBuild. Line by line, on text with ANSI escape codes already removed.
 */
internal object CompilerOutputParser {

    private const val KIND = """(?<kind>fatal error|error|warning)"""

    /** MSVC and MSBuild: `C:\src\a.cpp(12,5): error C2065: ...`, optionally led by MSBuild's
     * `3>` node prefix and followed by the ` [project]` it appends. The file is matched lazily,
     * so a path containing parentheses (`Program Files (x86)`) still ends at the line number. */
    private val MSVC_LOCATED = Regex(
        """^\s*(?:\d+>)?\s*(?<loc>(?<file>[^<>|"*?\s][^<>|"*?]*?)\((?<line>\d+)(?:,(?<col>\d+))?(?:,\d+){0,2}\))""" +
            """\s*:\s*$KIND\s*(?<code>[A-Za-z]+\d+)?\s*:\s*(?<msg>.*?)(?:\s+\[[^\]]+])?\s*$"""
    )

    /** GCC and Clang: `src/a.cpp:12:5: error: ...` (the column is optional), a Windows drive allowed. */
    private val GCC_LOCATED = Regex(
        """^(?<loc>(?<file>(?:[A-Za-z]:)?[^:<>|"*?\s][^:<>|"*?]*?):(?<line>\d+)(?::(?<col>\d+))?):\s*$KIND:\s*(?<msg>.*)$"""
    )

    /** MSVC/MSBuild without a line: `LINK : fatal error LNK1104: ...`, `MSBUILD : error MSB1009: ...`,
     * `cl : Command line warning D9002: ...`, a project-level `C:\src\App.csproj : error NU1101: ...`
     * (its origin may start with a drive) and code-less `EXEC : error : ...`. Without a code, only
     * MSBuild's own form of that - `error :`, the space left by the empty code - so other tools'
     * `...: error: ...` lines (CTest's `Test #3: error: ...`, say) aren't taken for build errors. */
    private val MSVC_UNLOCATED = Regex(
        """^\s*(?:\d+>)?\s*(?:(?:[A-Za-z]:)?[^:\s][^:]*?\s*:\s*)?(?:Command line\s+)?$KIND(?:\s+(?<code>[A-Za-z]+\d+)\s*|\s+):\s*""" +
            """(?<msg>.*?)(?:\s+\[[^\]]+])?\s*$"""
    )

    /** A GCC-style tool without a file: `clang: error: linker command failed ...`, `ld: error: ...`. */
    private val TOOL_UNLOCATED = Regex("""^(?<tool>[^\s:][^\s:]*):\s*$KIND:\s*(?<msg>.*)$""")

    /** Ninja: `[12/345] Building CXX object ...`. */
    private val NINJA_PROGRESS = Regex("""^\[(\d+)/(\d+)]\s+(.*)$""")

    /** Make: `[ 45%] Building CXX object ...`. */
    private val MAKE_PROGRESS = Regex("""^\[\s*(\d+)%]\s+(.*)$""")

    /** MSBuild, as each project finishes: `  MyLib -> C:\src\MyLib\bin\Debug\MyLib.dll`. The output
     * has to be an absolute path, which MSBuild always prints - so any other line that happens to
     * contain an arrow isn't taken for one. */
    private val MSBUILD_PROJECT_BUILT =
        Regex("""^\s*(?:\d+>)?\s*(?<project>[^\s>][^>]*?) -> (?<output>(?:[A-Za-z]:[\\/]|/)\S.*)$""")

    private val ANSI_ESCAPE = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")

    fun stripAnsi(text: String): String = text.replace(ANSI_ESCAPE, "")

    fun parse(line: String): BuildOutputLine? {
        if (line.isBlank()) return null
        located(MSVC_LOCATED, line)?.let { return it }
        located(GCC_LOCATED, line)?.let { return it }
        // Ahead of MSVC_UNLOCATED, so a GCC-style tool's line keeps its tool's name.
        TOOL_UNLOCATED.matchEntire(line)?.let { match ->
            return diagnostic(match, "${match.groups["tool"]!!.value}: ${match.groups["msg"]!!.value}")
        }
        MSVC_UNLOCATED.matchEntire(line)?.let { match ->
            val code = match.groups["code"]?.value
            val message = match.groups["msg"]!!.value
            return diagnostic(match, if (code != null) "$code: $message" else message)
        }
        NINJA_PROGRESS.matchEntire(line)?.let { match ->
            val (done, total, message) = match.destructured
            return BuildOutputLine.Progress(message, done.toLong(), total.toLong())
        }
        MAKE_PROGRESS.matchEntire(line)?.let { match ->
            val (percent, message) = match.destructured
            return BuildOutputLine.Progress(message, percent.toLong(), 100)
        }
        MSBUILD_PROJECT_BUILT.matchEntire(line)?.let { match ->
            return BuildOutputLine.Progress("Built ${match.groups["project"]!!.value.trim()}")
        }
        return null
    }

    private fun located(regex: Regex, line: String): BuildOutputLine.Diagnostic? {
        val match = regex.matchEntire(line) ?: return null
        // Only the MSVC pattern has a code group - and asking for a group a pattern doesn't
        // declare throws rather than returning null.
        val code = if (regex === MSVC_LOCATED) match.groups["code"]?.value else null
        val message = match.groups["msg"]!!.value
        return diagnostic(match, if (code != null) "$code: $message" else message).copy(
            file = match.groups["file"]!!.value.trim(),
            line = match.groups["line"]!!.value.toInt(),
            column = match.groups["col"]?.value?.toInt(),
            locationRange = match.groups["loc"]!!.range,
        )
    }

    private fun diagnostic(match: MatchResult, message: String) =
        BuildOutputLine.Diagnostic(isError = match.groups["kind"]!!.value != "warning", message = message.trim())
}
