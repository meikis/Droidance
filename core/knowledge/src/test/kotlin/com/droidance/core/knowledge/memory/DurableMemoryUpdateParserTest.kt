// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DurableMemoryUpdateParserTest {
    @Test
    fun parsesTheProviderEnvelopeWithoutTouchingStorage() {
        val updates = DurableMemoryUpdateParser.parse(
            """
            {
              "updates": [
                {
                  "scope": "user_profile",
                  "operation": "upsert",
                  "key": "address_as",
                  "category": "communication",
                  "content": "以后称呼我为 Alex",
                  "source_quote": "以后称呼我为 Alex",
                  "expected_revision": "absent"
                },
                {
                  "scope": "long_term",
                  "operation": "delete",
                  "key": "obsolete_note",
                  "source_quote": "忘记 obsolete_note",
                  "expected_revision": "sha256:old"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, updates.size)
        assertEquals(DurableMemoryScope.USER_PROFILE, updates[0].scope)
        assertEquals(DurableMemoryOperation.UPSERT, updates[0].operation)
        assertEquals("address_as", updates[0].key)
        assertEquals("communication", updates[0].category)
        assertEquals("以后称呼我为 Alex", updates[0].content)
        assertEquals("absent", updates[0].expectedRevision)
        assertEquals(DurableMemoryScope.LONG_TERM, updates[1].scope)
        assertEquals(DurableMemoryOperation.DELETE, updates[1].operation)
        assertEquals("obsolete_note", updates[1].key)
        assertEquals("sha256:old", updates[1].expectedRevision)
    }

    @Test
    fun rejectsMalformedEnvelopesBeforeAnyMemoryStoreCanBeCalled() {
        val valid = """
            {"updates":[{"scope":"user_profile","operation":"upsert",
            "key":"address_as","content":"Alex","source_quote":"Alex",
            "expected_revision":"absent"}]}
        """.trimIndent()

        assertThrows(RuntimeException::class.java) {
            DurableMemoryUpdateParser.parse("{}")
        }
        assertThrows(RuntimeException::class.java) {
            DurableMemoryUpdateParser.parse("{\"updates\":[]}")
        }
        for (replacement in listOf(
            "\"scope\":\"unknown\"",
            "\"operation\":\"replace\"",
            "\"key\":\"Invalid-Key\"",
            "\"source_quote\":\"\"",
            "\"expected_revision\":\"\""
        )) {
            val malformed = when {
                replacement.startsWith("\"scope\"") -> valid.replace("\"scope\":\"user_profile\"", replacement)
                replacement.startsWith("\"operation\"") -> valid.replace("\"operation\":\"upsert\"", replacement)
                replacement.startsWith("\"key\"") -> valid.replace("\"key\":\"address_as\"", replacement)
                replacement.startsWith("\"source_quote\"") -> valid.replace("\"source_quote\":\"Alex\"", replacement)
                else -> valid.replace("\"expected_revision\":\"absent\"", replacement)
            }
            assertThrows(replacement, RuntimeException::class.java) {
                DurableMemoryUpdateParser.parse(malformed)
            }
        }
    }
}
