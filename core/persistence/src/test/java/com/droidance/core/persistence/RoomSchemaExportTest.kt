// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.persistence

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RoomSchemaExportTest {
    @Test
    fun agentEventDatabaseSchemaVersion1Exists() {
        val rootSchema = File("schemas/com.droidance.core.persistence.agent.event.AgentEventDatabase/1.json")
        val altSchema = File("core/persistence/schemas/com.droidance.core.persistence.agent.event.AgentEventDatabase/1.json")
        val schemaFile = if (rootSchema.exists()) rootSchema else altSchema
        assertTrue("AgentEventDatabase schema version 1 must exist", schemaFile.exists())
        val content = schemaFile.readText()
        assertTrue("Schema must define agent_events table", content.contains("\"tableName\": \"agent_events\""))
        assertTrue("Schema version must be 1", content.contains("\"version\": 1"))
    }

    @Test
    fun memoryCandidateDatabaseSchemaVersion1Exists() {
        val rootSchema = File("schemas/com.droidance.core.persistence.memory.MemoryCandidateDatabase/1.json")
        val altSchema = File("core/persistence/schemas/com.droidance.core.persistence.memory.MemoryCandidateDatabase/1.json")
        val schemaFile = if (rootSchema.exists()) rootSchema else altSchema
        assertTrue("MemoryCandidateDatabase schema version 1 must exist", schemaFile.exists())
        val content = schemaFile.readText()
        assertTrue("Schema must define memory_candidates table", content.contains("\"tableName\": \"memory_candidates\""))
        assertTrue("Schema must define memory_candidate_sources table", content.contains("\"tableName\": \"memory_candidate_sources\""))
        assertTrue("Schema version must be 1", content.contains("\"version\": 1"))
    }

    @Test
    fun memoryCandidateDatabaseSchemaVersion3ContainsExperienceProjectionTables() {
        val rootSchema = File("schemas/com.droidance.core.persistence.memory.MemoryCandidateDatabase/3.json")
        val altSchema = File("core/persistence/schemas/com.droidance.core.persistence.memory.MemoryCandidateDatabase/3.json")
        val schemaFile = if (rootSchema.exists()) rootSchema else altSchema
        assertTrue("MemoryCandidateDatabase schema version 3 must exist", schemaFile.exists())
        val content = schemaFile.readText()
        assertTrue(content.contains("\"version\": 3"))
        listOf("experiences", "experience_revisions", "experience_receipts", "experience_search").forEach { table ->
            assertTrue("Schema must define $table", content.contains("\"tableName\": \"$table\""))
        }
    }
}
