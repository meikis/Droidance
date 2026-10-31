// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.config.markdownSection
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ActiveOperatorRulesTest {
    @Test fun loadedOperatorSectionIncludesRecoveryAttributionAndCapacityRules() {
        val prompt = markdownSection(File("src/main/assets/rules/agent_core.md").readText(), "operator_behavior")
        val completion = markdownSection(File("src/main/assets/rules/completion_recovery.md").readText(), "contract")
        val originalImage = markdownSection(File("src/main/assets/rules/model_context.md").readText(), "raw_observation_reference")
        assertTrue(prompt.contains("Read actual execution and partial-effect receipts"))
        assertTrue(prompt.contains("preserve completed effects"))
        assertTrue(prompt.contains("inspect unknown effects before choosing remaining work"))
        assertTrue(prompt.contains("together when interpreting evidence"))
        assertTrue(prompt.contains("Structured records are optional memory for longer work"))
        assertTrue(prompt.contains("preserve the user's foreground app and the bound execution interface"))
        assertTrue(prompt.contains("Do not open Droidance, relaunch the target app, press HOME/BACK"))
        assertTrue(completion.contains("Citations, coverage notes and learning are optional"))
        assertTrue(originalImage.contains("runtime retains this original image for later retrieval"))
        assertFalse(prompt.contains("after every page, write"))
        assertFalse(prompt.contains("## default_soul"))
    }
}
