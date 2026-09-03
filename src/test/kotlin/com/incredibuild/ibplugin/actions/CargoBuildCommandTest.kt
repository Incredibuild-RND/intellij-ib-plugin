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

/** Covers [withJobCount]: our "-j" must win over whatever the derived command already had. */
class CargoBuildCommandTest {

    @Test
    fun `appends the job count when none is present`() {
        assertEquals(
            listOf("--all", "--all-targets", "-j", "300"),
            withJobCount(listOf("--all", "--all-targets"), 300)
        )
    }

    @Test
    fun `replaces a job count given as two arguments`() {
        assertEquals(listOf("--release", "-j", "300"), withJobCount(listOf("-j", "8", "--release"), 300))
        assertEquals(listOf("--release", "-j", "300"), withJobCount(listOf("--jobs", "8", "--release"), 300))
    }

    @Test
    fun `replaces a job count given inline`() {
        assertEquals(listOf("--release", "-j", "300"), withJobCount(listOf("-j8", "--release"), 300))
        assertEquals(listOf("--release", "-j", "300"), withJobCount(listOf("-j=8", "--release"), 300))
        assertEquals(listOf("--release", "-j", "300"), withJobCount(listOf("--jobs=8", "--release"), 300))
    }

    @Test
    fun `replaces every job count when the command has more than one`() {
        assertEquals(
            listOf("--all", "-j", "300"),
            withJobCount(listOf("-j", "2", "--all", "--jobs=4", "-j8"), 300)
        )
    }

    @Test
    fun `keeps a flag that merely starts like the job count`() {
        assertEquals(listOf("--job-server", "-j", "300"), withJobCount(listOf("--job-server"), 300))
        assertEquals(listOf("-jobs", "-j", "300"), withJobCount(listOf("-jobs"), 300))
    }

    @Test
    fun `does not consume a following argument that is not a count`() {
        assertEquals(listOf("--release", "-j", "300"), withJobCount(listOf("-j", "--release"), 300))
    }

    @Test
    fun `leaves arguments after the separator untouched and inserts before it`() {
        assertEquals(
            listOf("--all", "-j", "300", "--", "-j", "2", "--cfg", "foo"),
            withJobCount(listOf("--all", "-j", "9", "--", "-j", "2", "--cfg", "foo"), 300)
        )
    }

    @Test
    fun `handles an empty argument list`() {
        assertEquals(listOf("-j", "300"), withJobCount(emptyList(), 300))
    }

    @Test
    fun `forces colour on when none is requested`() {
        assertEquals(listOf("--profile", "dev", "--color=always"), withAlwaysColor(listOf("--profile", "dev")))
    }

    @Test
    fun `replaces a colour flag given inline or as two arguments`() {
        assertEquals(listOf("--all", "--color=always"), withAlwaysColor(listOf("--color=never", "--all")))
        assertEquals(listOf("--all", "--color=always"), withAlwaysColor(listOf("--color", "never", "--all")))
        assertEquals(listOf("--all", "--color=always"), withAlwaysColor(listOf("--color", "auto", "--all")))
    }

    @Test
    fun `does not consume a following argument that is not a colour value`() {
        assertEquals(listOf("--release", "--color=always"), withAlwaysColor(listOf("--color", "--release")))
    }

    @Test
    fun `leaves a colour flag after the separator untouched`() {
        assertEquals(
            listOf("--all", "--color=always", "--", "--color", "never"),
            withAlwaysColor(listOf("--all", "--color=never", "--", "--color", "never"))
        )
    }

    @Test
    fun `switches the message format to the one the build pane parses`() {
        assertEquals(
            listOf("--all", "--message-format=json-diagnostic-rendered-ansi"),
            withBuildPaneMessageFormat(listOf("--all"))
        )
    }

    @Test
    fun `replaces a message format given inline or as two arguments`() {
        assertEquals(
            listOf("--all", "--message-format=json-diagnostic-rendered-ansi"),
            withBuildPaneMessageFormat(listOf("--message-format=short", "--all"))
        )
        assertEquals(
            listOf("--all", "--message-format=json-diagnostic-rendered-ansi"),
            withBuildPaneMessageFormat(listOf("--message-format", "human", "--all"))
        )
    }

    @Test
    fun `does not consume a following flag as a message format value`() {
        assertEquals(
            listOf("--release", "--message-format=json-diagnostic-rendered-ansi"),
            withBuildPaneMessageFormat(listOf("--message-format", "--release"))
        )
    }

    @Test
    fun `combines colour and job count as the build actions do`() {
        assertEquals(
            listOf("--profile", "dev", "--color=always", "-j", "300"),
            withJobCount(withAlwaysColor(listOf("--profile", "dev", "-j", "8", "--color=never")), 300)
        )
    }
}
