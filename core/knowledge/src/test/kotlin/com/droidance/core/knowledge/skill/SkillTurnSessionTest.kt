// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

import com.droidance.core.agent.RequestedSkill
import com.droidance.core.agent.TurnSessionContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SkillTurnSessionTest {
    @Test fun taskCleanupEndsUnassessedGuidedRunsWithoutPromotingThemToSuccess() {
        TempSkillDocumentStorage().use { storage ->
            val engine = SkillEngine(storage, RecordingSkillScriptValidator())
            engine.saveDraft("---\nname: demo\ndescription: Demo\n---\nInspect evidence.")
            val snapshot = engine.findExecutionSnapshot("demo", true)!!
            val context = TurnSessionContext("turn", "thread")
            val state = context.getOrPutTyped { SkillTurnSession(engine) }
            state.trackRun("a", snapshot, engine.beginExecution(snapshot, JsonObject(emptyMap()), "a"))
            state.trackRun("b", snapshot, engine.beginExecution(snapshot, JsonObject(emptyMap()), "b"))
            state.assessGuided("a", "failed")
            context.clear()
            assertEquals("ended", engine.executionRecord("demo", "a")!!["state"]!!.jsonPrimitive.content)
            assertEquals("failed", engine.executionRecord("demo", "a")!!["assessment_outcome"]!!.jsonPrimitive.content)
            assertEquals("ended", engine.executionRecord("demo", "b")!!["state"]!!.jsonPrimitive.content)
            assertEquals("unknown", engine.executionRecord("demo", "b")!!["assessment_outcome"]!!.jsonPrimitive.content)
            assertTrue(engine.assessments("demo", true).isEmpty())
            assertThrows(IllegalStateException::class.java) {
                engine.beginExecution(snapshot, JsonObject(emptyMap()), "b")
            }
        }
    }

    @Test fun explicitSelectionLoadsTheExactSourceWithPaginationAndDoesNotExecuteIt() {
        TempSkillDocumentStorage().use { storage ->
            val engine = SkillEngine(storage, RecordingSkillScriptValidator())
            val draft = engine.saveDraft("---\nname: demo\ndescription: Demo\n---\nInspect evidence.")
            val request = RequestedSkill("demo", draft.revision, true)
            val state = SkillTurnSession(engine)
            val context = state.preload(listOf(request), 32)
            val entry = Json.parseToJsonElement(context.substringAfter('\n').substringBeforeLast('\n')).jsonArray.single().jsonObject
            assertEquals(32, entry["next_offset"]!!.jsonPrimitive.int)
            assertTrue(entry["has_more"]!!.jsonPrimitive.boolean)
            assertTrue(state.isExplicit(engine.findExecutionSnapshot("demo", true)!!))
            assertTrue(storage.listFiles("skill-runs").isEmpty())
            engine.saveDraft("---\nname: demo\ndescription: Changed\n---\nNew method.", expectedRevision = draft.revision)
            val changed = state.preload(listOf(request), 1000)
            assertTrue(changed.contains("unavailable_or_changed"))
            assertFalse(changed.contains("New method."))
            assertFalse(state.isExplicit(engine.findExecutionSnapshot("demo", true)!!))
            assertTrue(state.preload(listOf(request), 0).contains("read_required"))
        }
    }
}
