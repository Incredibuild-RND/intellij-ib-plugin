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
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers [CompilerOutputParser] - what the Build tool window makes of an accelerated build's output. */
class CompilerOutputParserTest {

    private fun diagnostic(line: String) = CompilerOutputParser.parse(line) as BuildOutputLine.Diagnostic

    @Test
    fun `an MSVC compiler error has its file, line, column and code`() {
        val line = """C:\src\app\main.cpp(12,5): error C2065: 'x': undeclared identifier"""
        val parsed = diagnostic(line)
        assertEquals(true, parsed.isError)
        assertEquals("""C:\src\app\main.cpp""", parsed.file)
        assertEquals(12, parsed.line)
        assertEquals(5, parsed.column)
        assertEquals("C2065: 'x': undeclared identifier", parsed.message)
        assertEquals("""C:\src\app\main.cpp(12,5)""", line.substring(parsed.locationRange!!))
    }

    @Test
    fun `an MSVC warning without a column is a warning`() {
        val parsed = diagnostic("""C:\src\a.h(7): warning C4244: conversion from 'double' to 'int'""")
        assertEquals(false, parsed.isError)
        assertEquals(7, parsed.line)
        assertNull(parsed.column)
    }

    @Test
    fun `a path containing parentheses still ends at the line number`() {
        val parsed = diagnostic("""C:\Program Files (x86)\Lib\x.h(30,1): warning C4005: 'X': macro redefinition""")
        assertEquals("""C:\Program Files (x86)\Lib\x.h""", parsed.file)
        assertEquals(30, parsed.line)
    }

    @Test
    fun `an MSBuild error drops the node prefix and the trailing project`() {
        val parsed = diagnostic(
            """  3>C:\src\App\Program.cs(10,13): error CS0103: The name 'foo' does not exist [C:\src\App\App.csproj]"""
        )
        assertEquals("""C:\src\App\Program.cs""", parsed.file)
        assertEquals(10, parsed.line)
        assertEquals(13, parsed.column)
        assertEquals("CS0103: The name 'foo' does not exist", parsed.message)
    }

    @Test
    fun `a GCC error has its relative file, line and column`() {
        val line = "../src/main.cpp:42:7: error: 'foo' was not declared in this scope"
        val parsed = diagnostic(line)
        assertEquals(true, parsed.isError)
        assertEquals("../src/main.cpp", parsed.file)
        assertEquals(42, parsed.line)
        assertEquals(7, parsed.column)
        assertEquals("'foo' was not declared in this scope", parsed.message)
        assertEquals("../src/main.cpp:42:7", line.substring(parsed.locationRange!!))
    }

    @Test
    fun `a Clang warning on a Windows path keeps the drive`() {
        val parsed = diagnostic("""C:\src\util.c:3:10: warning: unused variable 'n' [-Wunused-variable]""")
        assertEquals(false, parsed.isError)
        assertEquals("""C:\src\util.c""", parsed.file)
        assertEquals(3, parsed.line)
    }

    @Test
    fun `a GCC fatal error without a column is an error`() {
        val parsed = diagnostic("src/a.c:1: fatal error: missing.h: No such file or directory")
        assertEquals(true, parsed.isError)
        assertEquals(1, parsed.line)
        assertNull(parsed.column)
    }

    @Test
    fun `a linker error names no file`() {
        val parsed = diagnostic("LINK : fatal error LNK1104: cannot open file 'foo.lib'")
        assertEquals(true, parsed.isError)
        assertNull(parsed.file)
        assertEquals("LNK1104: cannot open file 'foo.lib'", parsed.message)
    }

    @Test
    fun `an MSBuild error without a file is still an error`() {
        val parsed = diagnostic("MSBUILD : error MSB1009: Project file does not exist.")
        assertNull(parsed.file)
        assertEquals("MSB1009: Project file does not exist.", parsed.message)
    }

    @Test
    fun `an MSVC command line warning is a warning`() {
        val parsed = diagnostic("cl : Command line warning D9002: ignoring unknown option '/foo'")
        assertEquals(false, parsed.isError)
        assertNull(parsed.file)
        assertEquals("D9002: ignoring unknown option '/foo'", parsed.message)
    }

    @Test
    fun `a NuGet error on a Windows project file is an error`() {
        val expected = "NU1101: Unable to find package Foo."
        for (line in listOf(
            """C:\src\App\App.csproj : error NU1101: Unable to find package Foo. [C:\src\App.sln]""",
            """  3>C:\src\App\App.csproj : error NU1101: Unable to find package Foo. [C:\src\App.sln]""",
            """/home/me/src/App/App.csproj : error NU1101: Unable to find package Foo. [/home/me/src/App.sln]""",
        )) {
            val parsed = diagnostic(line)
            assertEquals(line, true, parsed.isError)
            assertNull(line, parsed.file)
            assertEquals(line, expected, parsed.message)
        }
    }

    @Test
    fun `a NuGet warning on a Windows project file is a warning`() {
        val parsed = diagnostic(
            """C:\src\App\App.csproj : warning NU1603: App depends on Foo (>= 1.0.0) but Foo 1.0.0 was not found. [C:\src\App.sln]"""
        )
        assertEquals(false, parsed.isError)
        assertEquals("NU1603: App depends on Foo (>= 1.0.0) but Foo 1.0.0 was not found.", parsed.message)
    }

    @Test
    fun `an MSBuild error without a code is still an error`() {
        val parsed = diagnostic("EXEC : error : something failed")
        assertEquals(true, parsed.isError)
        assertNull(parsed.file)
        assertEquals("something failed", parsed.message)
        assertEquals("something failed", diagnostic("""  2>C:\src\App\App.csproj : error : something failed""").message)
    }

    @Test
    fun `a GCC-style tool error names the tool`() {
        val parsed = diagnostic("clang: error: linker command failed with exit code 1 (use -v to see invocation)")
        assertNull(parsed.file)
        assertEquals("clang: linker command failed with exit code 1 (use -v to see invocation)", parsed.message)
    }

    @Test
    fun `Ninja's step counter is progress`() {
        assertEquals(
            BuildOutputLine.Progress("Building CXX object CMakeFiles/app.dir/main.cpp.obj", 3, 120),
            CompilerOutputParser.parse("[3/120] Building CXX object CMakeFiles/app.dir/main.cpp.obj")
        )
    }

    @Test
    fun `Make's percentage is progress out of 100`() {
        assertEquals(
            BuildOutputLine.Progress("Linking CXX executable app", 45, 100),
            CompilerOutputParser.parse("[ 45%] Linking CXX executable app")
        )
    }

    @Test
    fun `an MSBuild project finishing is progress`() {
        assertEquals(
            BuildOutputLine.Progress("Built MyLib"),
            CompilerOutputParser.parse("""  MyLib -> C:\src\MyLib\bin\Debug\net8.0\MyLib.dll""")
        )
        assertEquals(
            BuildOutputLine.Progress("Built MyLib"),
            CompilerOutputParser.parse("""2>MyLib -> /home/me/src/MyLib/bin/Debug/net8.0/MyLib.dll""")
        )
    }

    @Test
    fun `an arrow that isn't followed by an absolute path is not progress`() {
        assertNull(CompilerOutputParser.parse("-- Mapping a -> b"))
        assertNull(CompilerOutputParser.parse("  renaming foo -> bar"))
    }

    @Test
    fun `lines that only mention errors are not diagnostics`() {
        assertNull(CompilerOutputParser.parse("    0 Warning(s)"))
        assertNull(CompilerOutputParser.parse("Build FAILED."))
        assertNull(CompilerOutputParser.parse("FAILED: CMakeFiles/app.dir/main.cpp.obj"))
        assertNull(CompilerOutputParser.parse("src/main.cpp:40:3: note: declared here"))
        assertNull(CompilerOutputParser.parse("make[2]: *** [CMakeFiles/app.dir/build.make:76: app] Error 1"))
        assertNull(CompilerOutputParser.parse(""))
    }

    @Test
    fun `ANSI colour codes are stripped before parsing`() {
        val colored = "\u001B[1m../src/a.cpp:5:1: \u001B[0;1;31merror: \u001B[0mexpected ';'"
        assertEquals(5, diagnostic(CompilerOutputParser.stripAnsi(colored)).line)
    }
}
