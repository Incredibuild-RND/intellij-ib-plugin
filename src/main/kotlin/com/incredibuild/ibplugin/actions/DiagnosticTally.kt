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

/** Where MSBuild's closing summary starts: the line after the build's last output. */
private val MSBUILD_SUMMARY_START = Regex("""^\s*Build (succeeded|FAILED)\.\s*$""")

/** What else MSBuild's closing summary holds besides the diagnostics it repeats: blank lines and its counts. */
private val MSBUILD_SUMMARY_BODY = Regex("""^\s*(?:\d+ (?:Warning|Error)\(s\))?\s*$""")

/**
 * Counts a build's errors and warnings for [IdeBuildView], and decides which get an entry in the
 * build tree. Fed every line of output, in order, with what [CompilerOutputParser] made of it.
 *
 *  - Each occurrence counts, as the compiler's own counts do - a header's warning hit by 30 source
 *    files is 30 warnings - but gets one tree entry, or the tree would fill up with copies.
 *  - MSBuild's closing summary, which repeats every error and warning already reported, counts for
 *    nothing: from its "Build succeeded."/"Build FAILED." up to the first line that can't be part
 *    of it (its "Time Elapsed", normally). So a start line that isn't followed by a summary hides
 *    nothing after it, and a build of several commands (a clean, then the build) can have a
 *    summary for each.
 */
internal class DiagnosticTally {

    @Volatile
    var errors = 0
        private set

    @Volatile
    var warnings = 0
        private set

    private val reported = mutableSetOf<BuildOutputLine.Diagnostic>()
    private var inMsBuildSummary = false

    /** Takes in one line; true if [parsed] is a diagnostic that should get a tree entry. */
    @Synchronized
    fun add(line: String, parsed: BuildOutputLine?): Boolean {
        if (MSBUILD_SUMMARY_START.matches(line)) {
            inMsBuildSummary = true
            return false
        }
        if (inMsBuildSummary && parsed !is BuildOutputLine.Diagnostic && !MSBUILD_SUMMARY_BODY.matches(line)) {
            inMsBuildSummary = false
        }
        if (parsed !is BuildOutputLine.Diagnostic || inMsBuildSummary) return false
        if (parsed.isError) errors++ else warnings++
        return reported.add(parsed.copy(locationRange = null))
    }
}
