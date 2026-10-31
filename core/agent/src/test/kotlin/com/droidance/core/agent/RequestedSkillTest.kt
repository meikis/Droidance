// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import org.junit.Assert.*
import org.junit.Test

class RequestedSkillTest {
    @Test fun queuedRequestFreezesSelectionIdentitiesAcrossImagePreparation() {
        val selected = RequestedSkill("demo", "a".repeat(64), true)
        val source = mutableListOf(selected)
        val request = TurnRequest.create(MessageSource.DIRECT, "Use this method.", requestedSkills = source)
        source.clear()
        assertEquals(listOf(selected), request.requestedSkills)
        assertEquals(request.requestedSkills, request.copy(images = listOf("image")).requestedSkills)
        assertThrows(IllegalArgumentException::class.java) { RequestedSkill("../demo", "a".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { RequestedSkill("demo", "") }
    }
}
