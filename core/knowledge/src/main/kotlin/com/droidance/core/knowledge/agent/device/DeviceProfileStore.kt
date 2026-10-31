// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.knowledge.agent.device.*

import com.droidance.core.knowledge.memory.MemoryDocumentStorage
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the onboarding device profile; live facts belong to frozen execution contexts and observations. */
@Singleton
class DeviceProfileStore @Inject constructor(
    private val fileManager: MemoryDocumentStorage
) {
    fun isOnboardingComplete(): Boolean =
        fileManager.exists(PATH) && fileManager.readFile(PATH)?.isNotBlank() == true

    fun save(content: String) {
        fileManager.writeFile(PATH, content)
    }

    companion object {
        const val PATH = "agents/default/DEVICE.md"
    }
}
