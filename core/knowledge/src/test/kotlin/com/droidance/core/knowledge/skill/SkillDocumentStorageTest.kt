// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SkillDocumentStorageTest {
    @Test
    fun pendingUpdateDoesNotHideThePublishedSkillFromManualSelection() = withEngine { _, engine ->
        val draft = engine.saveDraft(markdown(description = "Published method"))
        engine.publishDraft(draft.id, draft.revision)
        val active = requireNotNull(engine.loadSkill(draft.id))
        engine.saveDraft(markdown(description = "Pending method"), expectedRevision = active.revision)
        val choices = engine.loadAllSkills()
        assertEquals(2, choices.size)
        assertEquals(listOf(false, true), choices.map { it.isDraft })
        assertEquals(2, choices.map { it.selectionKey }.distinct().size)
        assertEquals("Published method", choices.first().description)
        assertEquals("Pending method", choices.last().description)
        assertEquals(listOf("Published method"), engine.loadSkillCatalog().map { it.description })
    }

    @Test
    fun localizedTitlesSurviveDraftPublishExportAndEditing() = withEngine { _, engine ->
        val source = markdown().replace("description: \"Reusable workflow\"", """
            description: "Reusable workflow"
            display_names: {zh: "商品搜索总结", en: "Product search summary"}
            display_descriptions: {zh: "按价格范围比较商品", en: "Compare products within a price range"}
        """.trimIndent())
        val draft = engine.saveDraft(source)
        assertEquals("商品搜索总结", draft.displayName("zh"))
        assertEquals("Product search summary", draft.displayName("en"))
        assertEquals("demo", draft.displayName("fr"))
        engine.publishDraft(draft.id, draft.revision)
        val active = requireNotNull(engine.loadSkill(draft.id))
        assertEquals(draft.displayNames, active.displayNames)
        assertEquals(listOf(active.id), engine.searchSkills("商品搜索").map { it.id })
        assertEquals(listOf(active.id), engine.searchSkills("按价格比较商品").map { it.id })
        val exported = SkillPackageArchive.decode(engine.exportPackage(draft.id, false))
        assertTrue(exported.getValue("SKILL.md").contains("商品搜索总结"))
        engine.updateSkill(active.id, active.description, active.rawContent, active.revision,
            "zh", "商品对比", "比较本次价格区间的商品")
        val updated = requireNotNull(engine.loadSkill(active.id, true))
        assertEquals("商品对比", updated.displayName("zh"))
        assertEquals("Product search summary", updated.displayName("en"))
        assertEquals("商品搜索总结", engine.loadSkill(active.id)?.displayName("zh"))
    }

    @Test
    fun automaticUseCanBeDisabledWithoutBlockingExplicitReadOrFreshRepeatedRuns() = withEngine { _, engine ->
        val draft = engine.saveDraft(markdown())
        engine.publishDraft(draft.id, draft.revision)
        engine.setImplicitInvocation(draft.id, false)
        assertTrue(engine.catalogContext(setOf("android_read"), 4000).isEmpty())
        assertNotNull(engine.recallSkill(draft.id))
        val snapshot = requireNotNull(engine.findExecutionSnapshot(draft.id))
        val parameters = buildJsonObject { }
        val one = engine.beginExecution(snapshot, parameters, "turn-one:invocation-one")
        engine.settleExecution(one, "exited", "")
        val two = engine.beginExecution(snapshot, parameters, "turn-two:invocation-one")
        assertFalse(one == two)
        assertThrows(IllegalStateException::class.java) {
            engine.beginExecution(snapshot, parameters, "turn-one:invocation-one")
        }
        engine.setImplicitInvocation(draft.id, true)
        assertFalse(engine.catalogContext(setOf("android_read"), 4000).isEmpty())
    }

    @Test
    fun draftRemainsSeparateUntilPublishedAndUpdatesArchiveTheInstalledRevision() = withEngine { storage, engine ->
        engine.saveDraft(
            markdown(description = "First instructions", body = "# First"),
            attachments = mapOf(
                "scripts/workflow.js" to "print('first');",
                "references/guide.md" to "First reference"
            )
        )
        val draft = requireNotNull(engine.findExecutionSnapshot("demo", true))

        assertTrue(draft.skill.isDraft)
        assertTrue(draft.skill.enabled)
        assertEquals(1, draft.skill.version)
        assertEquals(
            listOf("SKILL.md", "references/guide.md", "scripts/workflow.js"),
            draft.files.keys.sorted()
        )
        assertNull(engine.findExecutionSnapshot("demo"))
        assertEquals("print('first');", engine.readFile("demo", "scripts/workflow.js", true, draft.revision))

        engine.publishDraft("demo", draft.revision)
        val published = requireNotNull(engine.findExecutionSnapshot("demo"))
        assertFalse(published.skill.isDraft)
        assertTrue(published.skill.enabled)
        assertTrue(published.skill.hasInstalledVersion)
        assertEquals(1, published.skill.version)
        assertFalse(storage.exists("skill-drafts/demo"))

        engine.saveDraft(
            markdown(description = "Second instructions", body = "# Second"),
            attachments = mapOf("scripts/workflow.js" to "print('second');"),
            expectedRevision = published.revision
        )
        val nextDraft = requireNotNull(engine.findExecutionSnapshot("demo", true))
        assertEquals(2, nextDraft.skill.version)
        assertEquals(1, requireNotNull(engine.findExecutionSnapshot("demo")).skill.version)
        assertTrue(storage.exists("skill-history/demo/${published.revision}/SKILL.md"))

        engine.publishDraft("demo", nextDraft.revision)
        val current = requireNotNull(engine.findExecutionSnapshot("demo"))
        assertEquals(2, current.skill.version)
        assertEquals("print('second');", engine.readFile("demo", "scripts/workflow.js", false, current.revision))
        assertFalse(storage.exists("skill-drafts/demo"))
    }

    @Test
    fun explicitMetadataFlagsSurviveDraftPublishAndImport() = withEngine { _, engine ->
        engine.saveDraft(markdown(enabled = false, allowImplicitInvocation = false))
        val draft = requireNotNull(engine.findExecutionSnapshot("demo", true))
        assertFalse(draft.skill.enabled)
        assertFalse(draft.skill.allowImplicitInvocation)

        engine.publishDraft("demo", draft.revision)
        val published = requireNotNull(engine.findExecutionSnapshot("demo"))
        assertFalse(published.skill.enabled)
        assertFalse(published.skill.allowImplicitInvocation)

        val imported = SkillPackageArchive.encode(
            mapOf("SKILL.md" to markdown(name = "imported", enabled = false, allowImplicitInvocation = false))
        )
        engine.installSkillPackage(imported)
        val importedDraft = requireNotNull(engine.findExecutionSnapshot("imported", true))
        assertFalse(importedDraft.skill.enabled)
        assertFalse(importedDraft.skill.allowImplicitInvocation)
    }

    @Test
    fun staleRevisionCannotReplaceARealDraftOrPublishIt() = withEngine { storage, engine ->
        engine.saveDraft(markdown())
        val before = storage.readFile("skill-drafts/demo/SKILL.md")

        val saveError = assertThrows(IllegalArgumentException::class.java) {
            engine.saveDraft(markdown(description = "wrong writer"), expectedRevision = "0".repeat(64))
        }
        assertEquals("SKILL_REVISION_REQUIRED_OR_CHANGED", saveError.message)
        assertEquals(before, storage.readFile("skill-drafts/demo/SKILL.md"))

        val publishError = assertThrows(IllegalStateException::class.java) {
            engine.publishDraft("demo", "1".repeat(64))
        }
        assertEquals("SKILL_REVISION_CHANGED", publishError.message)
        assertNotNull(engine.findExecutionSnapshot("demo", true))
    }

    @Test
    fun malformedDraftIsRejectedBeforeItsPreviousRevisionIsArchived() = withEngine { storage, engine ->
        engine.saveDraft(
            markdown(),
            attachments = mapOf("scripts/workflow.js" to "print('stable');")
        )
        val first = requireNotNull(engine.findExecutionSnapshot("demo", true))

        val error = assertThrows(IllegalArgumentException::class.java) {
            engine.saveDraft(
                markdown(description = "invalid update"),
                attachments = mapOf("README.md" to "unsupported"),
                expectedRevision = first.revision
            )
        }

        assertEquals("SKILL_FILE_UNSUPPORTED: README.md", error.message)
        assertEquals(first.revision, requireNotNull(engine.findExecutionSnapshot("demo", true)).revision)
        assertFalse(storage.exists("skill-history/demo/${first.revision}"))
    }

    @Test
    fun draftImportAndBuiltinValidateEveryScriptAndLeaveReferencesUntouched() {
        val scripts = mapOf("scripts/nested/second.js" to "print('second');", "scripts/first.js" to "print('first');")
        for (operation in listOf("draft", "import", "builtin")) TempSkillDocumentStorage().use { storage ->
            val validator = RecordingSkillScriptValidator()
            val engine = SkillEngine(storage, validator)
            val source = markdown(source = if (operation == "builtin") "system" else "user")
            val files = scripts + mapOf("SKILL.md" to source, "references/guide.md" to "Not JavaScript: {")
            when (operation) {
                "draft" -> engine.saveDraft(source, files - "SKILL.md")
                "import" -> engine.installSkillPackage(SkillPackageArchive.encode(files))
                "builtin" -> engine.installBuiltinPackage(files)
            }
            assertEquals(operation, scripts.toSortedMap().toList(), validator.calls)
        }
    }

    @Test
    fun rejectedScriptDoesNotArchiveOrReplaceAnExistingDraft() {
        TempSkillDocumentStorage().use { storage ->
            val validator = SkillScriptValidator { _, source -> require(source != "invalid") { "SCRIPT_REJECTED" } }
            val engine = SkillEngine(storage, validator)
            val original = engine.saveDraft(markdown(), mapOf("scripts/workflow.js" to "print('stable');"))
            val before = engine.exportPackage(original.id, true)

            val error = assertThrows(IllegalArgumentException::class.java) {
                engine.saveDraft(markdown(description = "Rejected update"), mapOf("scripts/workflow.js" to "invalid"), original.revision)
            }

            assertEquals("SCRIPT_REJECTED", error.message)
            assertTrue(before.contentEquals(engine.exportPackage(original.id, true)))
            assertEquals(original.revision, engine.loadSkill(original.id, true)!!.revision)
            assertFalse(storage.exists("skill-history/${original.id}/${original.revision}"))
        }
    }

    @Test
    fun rejectedImportedScriptPreservesTheActivePackageWithoutCreatingADraftOrHistory() {
        TempSkillDocumentStorage().use { storage ->
            val validator = SkillScriptValidator { _, source -> require(source != "invalid") { "SCRIPT_REJECTED" } }
            val engine = SkillEngine(storage, validator)
            val original = engine.saveDraft(markdown(), mapOf("scripts/workflow.js" to "print('stable');"))
            engine.publishDraft(original.id, original.revision)
            val before = engine.exportPackage(original.id, false)
            val incoming = SkillPackageArchive.encode(mapOf("SKILL.md" to markdown(version = 2), "scripts/workflow.js" to "invalid"))

            assertThrows(IllegalArgumentException::class.java) { engine.installSkillPackage(incoming) }

            assertTrue(before.contentEquals(engine.exportPackage(original.id, false)))
            assertNull(engine.loadSkill(original.id, true))
            assertFalse(storage.exists("skill-history/${original.id}/${original.revision}"))
        }
    }

    @Test
    fun rejectedBuiltinScriptPreservesThePreviousBuiltinPackage() {
        TempSkillDocumentStorage().use { storage ->
            val validator = SkillScriptValidator { _, source -> require(source != "invalid") { "SCRIPT_REJECTED" } }
            val engine = SkillEngine(storage, validator)
            engine.installBuiltinPackage(mapOf("SKILL.md" to markdown(source = "system"), "scripts/workflow.js" to "print('stable');"))
            val before = engine.exportPackage("demo", false)

            assertThrows(IllegalArgumentException::class.java) {
                engine.installBuiltinPackage(mapOf("SKILL.md" to markdown(version = 2, source = "system"), "scripts/workflow.js" to "invalid"))
            }

            assertTrue(before.contentEquals(engine.exportPackage("demo", false)))
        }
    }

    @Test
    fun exportAndImportRoundTripPreservesTheRealPackageFiles() = withEngine { _, engine ->
        val packageBytes = SkillPackageArchive.encode(
            mapOf(
                "SKILL.md" to markdown(name = "portable", version = 3, body = "# Portable"),
                "scripts/prepare.js" to "print('prepare');",
                "references/notes.md" to "Keep the current target bound to the request."
            )
        )

        val result = engine.installSkillPackage(packageBytes)
        assertEquals("portable", result.skillId)
        assertFalse(result.updated)
        val draft = requireNotNull(engine.findExecutionSnapshot("portable", true))
        assertEquals(draft.files, SkillPackageArchive.decode(engine.exportPackage("portable", true)))
        assertEquals("print('prepare');", draft.files["scripts/prepare.js"])
        assertEquals("Keep the current target bound to the request.", draft.files["references/notes.md"])
        assertTrue(draft.skill.enabled)

        engine.publishDraft("portable", draft.revision)
        val published = requireNotNull(engine.findExecutionSnapshot("portable"))
        val exported = SkillPackageArchive.decode(engine.exportPackage("portable", false))
        assertEquals(published.files, exported)
        assertTrue(published.skill.enabled)
    }

    @Test
    fun importingAnEqualOrOlderVersionCannotOverwriteAnExistingDraft() = withEngine { _, engine ->
        val bytes = SkillPackageArchive.encode(
            mapOf("SKILL.md" to markdown(name = "portable", version = 1, body = "# v1"))
        )
        engine.installSkillPackage(bytes)

        val error = assertThrows(IllegalArgumentException::class.java) {
            engine.installSkillPackage(bytes)
        }
        assertEquals("SKILL_IMPORT_VERSION_NOT_NEWER", error.message)
    }

    @Test
    fun parametersAreTypeCheckedAndSkillFilesRequireThePortableNamespace() = withEngine { _, engine ->
        engine.saveDraft(
            markdown(
                parameters = """
                    parameters:
                      query:
                        type: string
                        required: true
                      limit:
                        type: integer
                      ratio:
                        type: number
                      enabled:
                        type: boolean
                      metadata:
                        type: object
                      tags:
                        type: array
                """.trimIndent(),
                body = "# Parameters"
            ),
            attachments = mapOf(
                "scripts/workflow.js" to "print('ok');",
                "references/guide.md" to "guide"
            )
        )
        val draft = requireNotNull(engine.findExecutionSnapshot("demo", true))

        engine.validateParameters(
            draft.skill,
            buildJsonObject {
                put("query", "books")
                put("limit", 3)
                put("ratio", 1.5)
                put("enabled", true)
                put("metadata", buildJsonObject { put("source", "user") })
                put("tags", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("one")) })
            }
        )
        assertEquals("guide", engine.readFile("demo", "references/guide.md", true, draft.revision))

        val missing = assertThrows(IllegalArgumentException::class.java) {
            engine.validateParameters(draft.skill, buildJsonObject { put("limit", 3) })
        }
        assertEquals("SKILL_PARAMETER_REQUIRED: query", missing.message)

        val wrongType = assertThrows(IllegalArgumentException::class.java) {
            engine.validateParameters(draft.skill, buildJsonObject { put("query", 7) })
        }
        assertEquals("SKILL_PARAMETER_TYPE: query", wrongType.message)

        val unknown = assertThrows(IllegalArgumentException::class.java) {
            engine.validateParameters(draft.skill, buildJsonObject { put("query", "ok"); put("extra", "no") })
        }
        assertEquals("SKILL_PARAMETER_UNKNOWN", unknown.message)

        listOf("../SKILL.md", "scripts/../SKILL.md", "scripts/workflow.txt", "SKILL.yaml", "references/guide.md/").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                engine.readFile("demo", path, true, draft.revision)
            }
        }
        val absent = assertThrows(IllegalStateException::class.java) {
            engine.readFile("demo", "references/missing.md", true, draft.revision)
        }
        assertEquals("SKILL_FILE_NOT_FOUND", absent.message)
    }

    @Test
    fun assessmentsStayBoundToTheSnapshotRevision() = withEngine { storage, engine ->
        engine.saveDraft(markdown())
        val draft = requireNotNull(engine.findExecutionSnapshot("demo", true))
        engine.publishDraft("demo", draft.revision)
        val snapshot = requireNotNull(engine.findExecutionSnapshot("demo"))

        engine.recordAssessment(
            snapshot,
            buildJsonObject {
                put("semantic_success", false)
                put("evidence_ref", "observation:one")
            }
        )
        assertEquals(1, engine.assessments("demo", false).size)

        storage.writeFile("skills/demo/SKILL.md", markdown(description = "edited outside the engine"))
        val error = assertThrows(IllegalStateException::class.java) {
            engine.recordAssessment(snapshot, buildJsonObject { put("semantic_success", true) })
        }
        assertEquals("SKILL_CHANGED_DURING_VERIFICATION", error.message)
    }

    @Test
    fun executionRecordSurvivesEngineReopenAndPendingEffectsCannotBeReplayed() = withEngine { storage, engine ->
        engine.saveDraft(markdown(), attachments = mapOf("scripts/workflow.js" to "print('stable');"))
        val draft = requireNotNull(engine.findExecutionSnapshot("demo", true))
        engine.publishDraft("demo", draft.revision)
        val snapshot = requireNotNull(engine.findExecutionSnapshot("demo"))
        val parameters = buildJsonObject { put("query", "books") }
        val token = engine.beginExecution(snapshot, parameters, "workflow-1", entryPath = "scripts/workflow.js")

        val reopened = SkillEngine(storage, RecordingSkillScriptValidator())
        assertEquals("pending", reopened.executionRecord("demo", "workflow-1")!!.getValue("state").toString().trim('"'))
        assertThrows(IllegalStateException::class.java) {
            reopened.beginExecution(snapshot, parameters, "workflow-1", entryPath = "scripts/workflow.js")
        }

        val firstEffect = reopened.checkpoint(token, "item-1", "begin")
        assertTrue(firstEffect.getValue("execute").toString().toBoolean())
        val pendingReplay = assertThrows(IllegalStateException::class.java) {
            reopened.checkpoint(token, "item-1", "begin")
        }
        assertEquals("SKILL_CHECKPOINT_EFFECT_UNKNOWN", pendingReplay.message)
        reopened.settleExecution(token, "exited", "program-1")

        val resumedReplay = assertThrows(IllegalStateException::class.java) {
            reopened.beginExecution(snapshot, parameters, "workflow-1", resume = true, entryPath = "scripts/workflow.js")
        }
        assertEquals("SKILL_CHECKPOINT_RECONCILIATION_REQUIRED", resumedReplay.message)
    }

    @Test
    fun completedCheckpointResumesAsAlreadyExecutedAndIdentityChangesAreRejected() = withEngine { storage, engine ->
        engine.saveDraft(markdown(), attachments = mapOf("scripts/workflow.js" to "print('stable');"))
        val draft = requireNotNull(engine.findExecutionSnapshot("demo", true))
        engine.publishDraft("demo", draft.revision)
        val snapshot = requireNotNull(engine.findExecutionSnapshot("demo"))
        val parameters = buildJsonObject { put("query", "books") }
        val token = engine.beginExecution(snapshot, parameters, "workflow-2", entryPath = "scripts/workflow.js")
        assertTrue(engine.checkpoint(token, "item-1", "begin").getValue("execute").toString().toBoolean())
        engine.checkpoint(token, "item-1", "complete")
        engine.settleExecution(token, "exited", "program-2")

        val reopened = SkillEngine(storage, RecordingSkillScriptValidator())
        val resumed = reopened.beginExecution(
            snapshot, parameters, "workflow-2", resume = true, entryPath = "scripts/workflow.js"
        )
        assertFalse(reopened.checkpoint(resumed, "item-1", "begin").getValue("execute").toString().toBoolean())

        val changedParameters = buildJsonObject { put("query", "different") }
        val parameterError = assertThrows(IllegalStateException::class.java) {
            reopened.beginExecution(snapshot, changedParameters, "workflow-2", resume = true, entryPath = "scripts/workflow.js")
        }
        assertEquals("SKILL_CHECKPOINT_IDENTITY_CHANGED", parameterError.message)
        val pathError = assertThrows(IllegalStateException::class.java) {
            reopened.beginExecution(snapshot, parameters, "workflow-2", resume = true, entryPath = "scripts/other.js")
        }
        assertEquals("SKILL_CHECKPOINT_IDENTITY_CHANGED", pathError.message)
        val changedRevision = snapshot.copy(revision = "0".repeat(64), skill = snapshot.skill.copy(revision = "0".repeat(64)))
        val revisionError = assertThrows(IllegalStateException::class.java) {
            reopened.beginExecution(changedRevision, parameters, "workflow-2", resume = true, entryPath = "scripts/workflow.js")
        }
        assertEquals("SKILL_CHECKPOINT_IDENTITY_CHANGED", revisionError.message)
    }

    @Test
    fun replannedExecutionCanResumeWithoutReplayingItsCompletedCheckpoint() = withEngine { storage, engine ->
        engine.saveDraft(markdown(), attachments = mapOf("scripts/workflow.js" to "print('stable');"))
        val draft = requireNotNull(engine.findExecutionSnapshot("demo", true))
        engine.publishDraft("demo", draft.revision)
        val snapshot = requireNotNull(engine.findExecutionSnapshot("demo"))
        val parameters = buildJsonObject { put("query", "books") }
        val token = engine.beginExecution(snapshot, parameters, "workflow-replanned", entryPath = "scripts/workflow.js")
        assertTrue(engine.checkpoint(token, "item-1", "begin").getValue("execute").toString().toBoolean())
        engine.checkpoint(token, "item-1", "complete")
        engine.settleExecution(token, "replanned", "program-replanned")

        val reopened = SkillEngine(storage, RecordingSkillScriptValidator())
        val resumed = reopened.beginExecution(
            snapshot, parameters, "workflow-replanned", resume = true, entryPath = "scripts/workflow.js"
        )
        assertFalse(reopened.checkpoint(resumed, "item-1", "begin").getValue("execute").toString().toBoolean())
    }

    @Test
    fun readOnlySystemSkillsCannotBeReplacedByDrafts() = withEngine { storage, engine ->
        storage.replaceDirectory(
            "skills/system-skill",
            mapOf("SKILL.md" to markdown(name = "system-skill", source = "system"))
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            engine.saveDraft(markdown(name = "system-skill", description = "replacement"))
        }
        assertEquals("SKILL_READ_ONLY", error.message)
    }

    @Test
    fun catalogDoesNotExposeLegacyScriptPackagesAsUnifiedSkills() = withEngine { storage, engine ->
        storage.replaceDirectory(
            "skills/legacy",
            mapOf(
                "SKILL.md" to markdown(name = "legacy"),
                "SCRIPT.json" to "{\"steps\":[]}"
            )
        )

        assertNull(engine.loadSkill("legacy"))
        assertTrue(engine.loadSkillCatalog().none { it.id == "legacy" })
    }

    @Test
    fun searchSkillsReturnsMatchingEnabledSkills() = withEngine { _, engine ->
        val draft1 = engine.saveDraft(markdown(name = "wifi-manager", description = "Control Wi-Fi network states"))
        engine.publishDraft("wifi-manager", draft1.revision)

        val draft2 = engine.saveDraft(markdown(name = "bluetooth-tool", description = "Pair and connect Bluetooth devices"))
        engine.publishDraft("bluetooth-tool", draft2.revision)

        val results = engine.searchSkills("wifi")
        assertEquals(1, results.size)
        assertEquals("wifi-manager", results.first().name)

        val all = engine.searchSkills("")
        assertEquals(2, all.size)
    }

    @Test
    fun metadataSearchHandlesPunctuationAndChinesePhrasesWithoutTranslation() = withEngine { storage, engine ->
        storage.replaceDirectory("skills/wifi-manager", mapOf("SKILL.md" to
            markdown(name = "wifi-manager", description = "Control Wi-Fi network states")))
        storage.replaceDirectory("skills/bluetooth-tool", mapOf("SKILL.md" to
            markdown(name = "bluetooth-tool", description = "Open the observed 蓝牙 settings and inspect connection state")))

        assertEquals(listOf("wifi-manager"), engine.searchSkills("Wi-Fi, network!").map { it.name })
        assertEquals(listOf("bluetooth-tool"), engine.searchSkills("帮我打开蓝牙设置。").map { it.name })
        assertTrue(engine.searchSkills("无线网络").isEmpty())
    }

    @Test
    fun candidateFeedbackReusesOneSnapshotWithoutExposingPackageBodies() {
        TempSkillDocumentStorage().use { storage ->
            storage.replaceDirectory("skills/demo", mapOf(
                "SKILL.md" to markdown(description = "Current observations", body = "PRIVATE WORKFLOW BODY"),
                "references/guide.md" to "PRIVATE REFERENCE"
            ))
            val fullReads = mutableListOf<String>()
            val headerReads = mutableListOf<String>()
            val observedStorage = object : SkillDocumentStorage by storage {
                override fun readFile(relativePath: String): String? {
                    fullReads += relativePath
                    return storage.readFile(relativePath)
                }

                override fun readFrontMatter(relativePath: String): String? {
                    headerReads += relativePath
                    return storage.readFrontMatter(relativePath)
                }
            }
            val engine = SkillEngine(observedStorage, RecordingSkillScriptValidator())

            val catalog = engine.catalogContext(setOf("android_read"), 1000)
            val search = engine.searchSkills("observations")

            assertTrue(catalog.contains("Current observations"))
            assertFalse(catalog.contains("PRIVATE"))
            assertEquals(listOf("demo"), search.map { it.name })
            assertTrue(search.single().rawContent.isBlank())
            assertEquals(2, fullReads.count { it == "skills/demo/SKILL.md" })
            assertEquals(2, fullReads.count { it == "skills/demo/references/guide.md" })
            assertEquals(listOf("skills/demo/SKILL.md", "skills/demo/SKILL.md"), headerReads)

            assertTrue(requireNotNull(engine.loadSkill("demo")).rawContent.contains("PRIVATE WORKFLOW BODY"))
            assertTrue(fullReads.contains("skills/demo/SKILL.md"))
        }
    }

    private fun <T> withEngine(block: (TempSkillDocumentStorage, SkillEngine) -> T): T =
        TempSkillDocumentStorage().use { storage -> block(storage, SkillEngine(storage, RecordingSkillScriptValidator())) }

    private fun markdown(
        name: String = "demo",
        version: Int = 1,
        description: String = "Reusable workflow",
        source: String = "user",
        enabled: Boolean? = null,
        allowImplicitInvocation: Boolean? = null,
        parameters: String = "",
        body: String = "# Demo\n\nUse current evidence before acting."
    ): String = buildString {
        appendLine("---")
        appendLine("name: $name")
        appendLine("version: $version")
        appendLine("description: \"$description\"")
        appendLine("source: $source")
        enabled?.let { appendLine("enabled: $it") }
        allowImplicitInvocation?.let { appendLine("allow_implicit_invocation: $it") }
        appendLine("required_capabilities: [android_read]")
        if (parameters.isNotBlank()) appendLine(parameters)
        appendLine("---")
        appendLine(body)
    }
}
