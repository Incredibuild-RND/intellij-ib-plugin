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

import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers [DiagnosticTally] - the error and warning counts and tree entries of an accelerated build. */
class DiagnosticTallyTest {

    private val warning = """C:\src\App\Program.cs(10,13): warning CS0168: The variable 'e' is declared but never used [C:\src\App\App.csproj]"""
    private val error = """C:\src\App\Program.cs(12,1): error CS1002: ; expected [C:\src\App\App.csproj]"""

    /** Feeds [lines] in, returning how many got a tree entry. */
    private fun DiagnosticTally.feed(vararg lines: String): Int =
        lines.count { add(it, CompilerOutputParser.parse(it)) }

    @Test
    fun `MSBuild's closing summary isn't counted again`() {
        val tally = DiagnosticTally()
        val entries = tally.feed(
            warning,
            error,
            "",
            "Build FAILED.",
            "",
            warning,
            error,
            "    1 Warning(s)",
            "    1 Error(s)",
            "",
            "Time Elapsed 00:00:01.23",
        )
        assertEquals(2, entries)
        assertEquals(1, tally.errors)
        assertEquals(1, tally.warnings)
    }

    @Test
    fun `each occurrence counts, with one tree entry`() {
        val tally = DiagnosticTally()
        val header = """C:\src\lib\util.h(7): warning C4244: conversion from 'double' to 'int'"""
        assertEquals(1, tally.feed(*Array(30) { header }))
        assertEquals(30, tally.warnings)
    }

    @Test
    fun `a summary start without a summary hides nothing after it`() {
        val tally = DiagnosticTally()
        val entries = tally.feed("Build succeeded.", "Running the next command...", warning)
        assertEquals(1, entries)
        assertEquals(1, tally.warnings)
    }

    @Test
    fun `each command's summary is skipped, and what comes between them counted`() {
        val tally = DiagnosticTally()
        tally.feed(
            warning, "Build succeeded.", warning, "    1 Warning(s)", "    0 Error(s)", "Time Elapsed 00:00:00.50",
            error, "Build FAILED.", error, "    0 Warning(s)", "    1 Error(s)", "Time Elapsed 00:00:00.70",
        )
        assertEquals(1, tally.errors)
        assertEquals(1, tally.warnings)
    }
}
