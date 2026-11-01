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

/** Covers [IncredibuildInstallation.parseLicensed] - reading `BuildConsole /QueryLicense` output. */
class IncredibuildInstallationTest {

    private fun queryLicenseOutput(initiatorRegType: String) = """
        -----------------------------------------------------------------------
        Build Acceleration Console 10.39.0 (build 18726)
        Copyright (C) 2001-2026 Incredibuild Software Ltd. All rights reserved.
        -----------------------------------------------------------------------

        Agent info:
        InitiatorRegType $initiatorRegType
        CIInitiatorRegType None
        HelperRegType Floating
        HelperCores 16
    """.trimIndent()

    @Test
    fun `a floating initiator licence is licensed`() {
        assertEquals(true, IncredibuildInstallation.parseLicensed(queryLicenseOutput("Floating")))
    }

    @Test
    fun `an initiator licence of None is not licensed`() {
        assertEquals(false, IncredibuildInstallation.parseLicensed(queryLicenseOutput("None")))
    }

    @Test
    fun `the CI initiator licence is not mistaken for the initiator one`() {
        // Only CIInitiatorRegType present: its "None" says nothing about starting builds here.
        assertNull(IncredibuildInstallation.parseLicensed("Agent info:\nCIInitiatorRegType None\n"))
    }

    @Test
    fun `output without an initiator licence line is unknown`() {
        assertNull(IncredibuildInstallation.parseLicensed("Fatal Error: could not connect to the Coordinator"))
    }
}
