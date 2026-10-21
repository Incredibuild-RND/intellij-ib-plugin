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

/** Covers [IncredibuildInstallation.parseLicenseQuery] (reading `BuildConsole /QueryLicense`). */
class IncredibuildInstallationTest {

    private fun queryOutput(initiator: String, ciInitiator: String) = """
        -----------------------------------------------------------------------
        Build Acceleration Console 10.39.0 (build 18726)
        -----------------------------------------------------------------------

        Agent info:
        InitiatorRegType $initiator
        CIInitiatorRegType $ciInitiator
        HelperRegType Floating
        HelperCores 16
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun `a floating initiator license is licensed`() {
        assertEquals(true, IncredibuildInstallation.parseLicenseQuery(queryOutput("Floating", "None")))
    }

    @Test
    fun `a CI-only initiator license is licensed`() {
        assertEquals(true, IncredibuildInstallation.parseLicenseQuery(queryOutput("None", "Pinned")))
    }

    @Test
    fun `no initiator license of either kind is unlicensed`() {
        assertEquals(false, IncredibuildInstallation.parseLicenseQuery(queryOutput("None", "None")))
    }

    @Test
    fun `output without registration lines is inconclusive`() {
        assertNull(IncredibuildInstallation.parseLicenseQuery("Fatal Error: could not connect to the Coordinator"))
    }
}
