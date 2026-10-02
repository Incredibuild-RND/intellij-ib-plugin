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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [IncredibuildRunner.batchEscaped]/[IncredibuildRunner.cmdQuoted] (the Windows .cmd
 * wrapper's `%` escaping) and [VALID_SHELL_IDENTIFIER] (the Linux environment export filter). */
class IncredibuildRunnerTest {

    @Test
    fun `batchEscaped doubles a lone percent`() {
        assertEquals("%%TEMP%%", IncredibuildRunner.batchEscaped("%TEMP%"))
    }

    @Test
    fun `batchEscaped leaves a value without percent untouched`() {
        assertEquals("C-Program Files-App", IncredibuildRunner.batchEscaped("C-Program Files-App"))
    }

    @Test
    fun `batchEscaped doubles every percent in a value`() {
        assertEquals("100%%25 done", IncredibuildRunner.batchEscaped("100%25 done"))
    }

    @Test
    fun `cmdQuoted leaves a plain token unquoted`() {
        assertEquals("--target", IncredibuildRunner.cmdQuoted("--target"))
    }

    @Test
    fun `cmdQuoted quotes a value containing whitespace`() {
        assertEquals("\"C:\\Program Files\\App\"", IncredibuildRunner.cmdQuoted("C:\\Program Files\\App"))
    }

    @Test
    fun `cmdQuoted escapes percent before deciding whether to quote`() {
        assertEquals("%%TEMP%%", IncredibuildRunner.cmdQuoted("%TEMP%"))
        assertEquals("\"%%TEMP%% dir\"", IncredibuildRunner.cmdQuoted("%TEMP% dir"))
    }

    @Test
    fun `VALID_SHELL_IDENTIFIER accepts ordinary environment variable names`() {
        assertTrue(VALID_SHELL_IDENTIFIER.matches("PATH"))
        assertTrue(VALID_SHELL_IDENTIFIER.matches("_private"))
        assertTrue(VALID_SHELL_IDENTIFIER.matches("LD_LIBRARY_PATH_2"))
    }

    @Test
    fun `VALID_SHELL_IDENTIFIER rejects names export would choke on`() {
        assertFalse(VALID_SHELL_IDENTIFIER.matches("BASH_FUNC_foo%%"))
        assertFalse(VALID_SHELL_IDENTIFIER.matches("2LTR"))
        assertFalse(VALID_SHELL_IDENTIFIER.matches(""))
        assertFalse(VALID_SHELL_IDENTIFIER.matches("FOO BAR"))
    }
}
