// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

import com.droidance.core.knowledge.memory.tokenizeMemoryQuery
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.*
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

private val skillExecutionTerminalStates = setOf(
    "exited", "failed", "canceled", "interrupted", "timed_out", "device_stopped", "replanned"
)

/** Source packages, explicit draft publication, immutable revisions and local run assessments. */
@Singleton
class SkillEngine @Inject constructor(
    private val fileManager: SkillDocumentStorage,
    private val scriptValidator: SkillScriptValidator
) {
    @Synchronized
    fun loadAllSkills(): List<SkillInfo> = (fileManager.listFiles("skills") + fileManager.listFiles("skill-drafts"))
        .distinct().flatMap { id -> listOfNotNull(loadSkill(id), loadSkill(id, true)) }
        .sortedWith(compareBy<SkillInfo> { it.name.lowercase() }.thenBy { it.isDraft })

    @Synchronized
    fun loadSkillCatalog(): List<SkillInfo> = loadCatalog("skills", false)

    @Synchronized
    fun authoringCatalogContext(maxChars: Int): String = buildString {
        appendLine("Known packages for authoring; inspect exact files and revisions before updating:")
        (loadCatalog("skills", false) + loadCatalog("skill-drafts", true)).forEach { skill ->
            val line = "name=${skill.name}; draft=${skill.isDraft}; ${skill.description.replace(Regex("\\s+"), " ").take(280)}\n"
            if (length + line.length <= maxChars) append(line)
        }
    }

    private fun loadCatalog(directory: String, draft: Boolean): List<SkillInfo> = fileManager.listFiles(directory).mapNotNull { id ->
        runCatching {
            if (fileManager.exists("$directory/$id/SCRIPT.json") || fileManager.exists("$directory/$id/SKILL.yaml")) return@mapNotNull null
            val header = fileManager.readFrontMatter("$directory/$id/SKILL.md") ?: return@mapNotNull null
            withLocalPreferences(parse(id, "---\n$header\n---\n", draft))
        }.getOrNull()
    }

    @Synchronized
    fun catalogContext(availableCapabilities: Set<String>, maxChars: Int, query: String = ""): String {
        val candidates = loadSkillCatalog().filter { it.enabled && it.allowImplicitInvocation &&
            missingRequiredCapabilities(it, availableCapabilities).isEmpty() }
            .sortedWith(compareByDescending<SkillInfo> { skillRelevance(it, query) }.thenBy { it.name })
            .mapIndexed { index, skill -> if (index < SkillCandidateFeedback.DEFAULT_WINDOW_LIMIT)
                withCandidateFeedback(skill) ?: skill else skill }
        return buildSkillCatalogContext(candidates, availableCapabilities, maxChars, query)
    }

    @Synchronized
    fun searchSkills(query: String, limit: Int = 5): List<SkillInfo> {
        val q = query.trim().lowercase(Locale.ROOT)
        val catalog = loadSkillCatalog().filter { it.enabled && !it.isDraft }
        if (q.isBlank() || q == "*") {
            return catalog.sortedBy { it.name.lowercase(Locale.ROOT) }.take(limit.coerceIn(1, 20))
                .mapNotNull(::withCandidateFeedback)
        }
        return catalog.map { skill -> skill to skillRelevance(skill, q) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<SkillInfo, Int>> { it.second }.thenBy { it.first.name.lowercase(Locale.ROOT) })
            .take(limit.coerceIn(1, 20))
            .mapNotNull { withCandidateFeedback(it.first) }
    }

    private fun withCandidateFeedback(skill: SkillInfo): SkillInfo? {
        val snapshot = findExecutionSnapshot(skill.id, skill.isDraft) ?: return null
        return snapshot.skill.copy(rawContent = "", recentFeedback = SkillCandidateFeedback.fromSkillAssessments(
            snapshot.revision, recentAssessments(snapshot)))
    }

    @Synchronized
    fun loadSkill(id: String, draft: Boolean = false): SkillInfo? =
        findExecutionSnapshot(id, draft)?.skill

    @Synchronized
    fun saveDraft(markdown: String, attachments: Map<String, String> = emptyMap(), expectedRevision: String? = null): SkillInfo {
        val parsed = parse("", markdown, true)
        require(parsed.rawContent.isNotBlank()) { "SKILL_BODY_REQUIRED" }
        require(parsed.source != "system") { "SKILL_SOURCE_RESERVED" }
        val id = parsed.name
        require(validId(id) && id != "skill-creator") { "SKILL_NAME_INVALID" }
        val old = findExecutionSnapshot(id, true) ?: findExecutionSnapshot(id)
        require(old == null || !old.skill.isReadOnly) { "SKILL_READ_ONLY" }
        require(old == null || expectedRevision == old.revision) { "SKILL_REVISION_REQUIRED_OR_CHANGED" }
        require(old != null || expectedRevision == null) { "SKILL_REVISION_CHANGED" }
        val version = (old?.skill?.version ?: 0) + 1
        val all = attachments + ("SKILL.md" to document(parsed.copy(id = id, version = version)))
        SkillPackageArchive.validate(all)
        validateScripts(all)
        old?.let(::archive)
        fileManager.replaceDirectory(root(id, true), all)
        return requireNotNull(loadSkill(id, true))
    }

    @Synchronized
    fun updateSkill(id: String, description: String, body: String, expectedRevision: String,
                    language: String, displayName: String, displayDescription: String) {
        val snapshot = findExecutionSnapshot(id, true) ?: requireNotNull(findExecutionSnapshot(id))
        require(snapshot.revision == expectedRevision) { "SKILL_REVISION_CHANGED" }
        require(description.isNotBlank() && body.isNotBlank())
        require(displayName.isNotBlank() && displayDescription.isNotBlank())
        val source = parse(id, snapshot.files.getValue("SKILL.md"), snapshot.skill.isDraft)
        saveDraft(document(source.copy(description = description, rawContent = body,
            displayNames = snapshot.skill.displayNames + (language to displayName),
            displayDescriptions = snapshot.skill.displayDescriptions + (language to displayDescription))),
            snapshot.files - "SKILL.md", snapshot.revision)
    }

    @Synchronized
    fun publishDraft(id: String, expectedRevision: String) {
        val draft = requireNotNull(findExecutionSnapshot(id, true)) { "SKILL_DRAFT_NOT_FOUND" }
        check(draft.revision == expectedRevision) { "SKILL_REVISION_CHANGED" }
        findExecutionSnapshot(id)?.let(::archive)
        val published = draft.files
        fileManager.replaceDirectory(root(id, false), published)
        check(fileManager.deleteDirectory(root(id, true))) { "SKILL_DRAFT_REMOVE_FAILED" }
    }

    @Synchronized
    fun setSkillEnabled(id: String, enabled: Boolean) {
        val skill = requireNotNull(loadSkill(id))
        writeLocalPreferences(skill.copy(enabled = enabled))
    }

    @Synchronized
    fun setImplicitInvocation(id: String, allowed: Boolean) {
        val skill = requireNotNull(loadSkill(id))
        writeLocalPreferences(skill.copy(enabled = true, allowImplicitInvocation = allowed))
    }

    private fun writeLocalPreferences(skill: SkillInfo) {
        fileManager.writeFile("skill-preferences/${skill.id}/policy.json", buildJsonObject {
            put("enabled", skill.enabled)
            put("allow_implicit_invocation", skill.allowImplicitInvocation)
        }.toString())
    }

    private fun withLocalPreferences(skill: SkillInfo): SkillInfo {
        if (skill.isDraft) return skill
        val path = "skill-preferences/${skill.id}/policy.json"
        if (!fileManager.exists(path)) return skill
        val policy = fileManager.readFile(path)
            ?.let { Json.parseToJsonElement(it).jsonObject } ?: return skill
        return skill.copy(enabled = policy.getValue("enabled").jsonPrimitive.boolean,
            allowImplicitInvocation = policy.getValue("allow_implicit_invocation").jsonPrimitive.boolean)
    }

    /** Reconciles the complete APK catalog, preserving user packages and retiring removed built-ins. */
    @Synchronized
    fun installBuiltinPackages(packages: List<Map<String, String>>) {
        val entries = packages.map { files ->
            SkillPackageArchive.validate(files)
            val parsed = parse("", files.getValue("SKILL.md"), false)
            require(validId(parsed.name) && parsed.source == "system") { "BUILTIN_SKILL_INVALID" }
            validateScripts(files)
            parsed.name to files
        }
        val names = entries.map { it.first }.toSet()
        require(names.size == entries.size) { "BUILTIN_SKILL_DUPLICATE" }
        entries.forEach { (name, files) ->
            val current = loadSkill(name)
            if (current == null || current.isReadOnly) fileManager.replaceDirectory(root(name, false), files)
        }
        loadSkillCatalog().filter { it.isReadOnly && it.id !in names }.forEach { retired ->
            check(fileManager.deleteDirectory(root(retired.id, false))) { "BUILTIN_SKILL_REMOVE_FAILED" }
            if (fileManager.exists("skill-preferences/${retired.id}")) {
                check(fileManager.deleteDirectory("skill-preferences/${retired.id}")) { "BUILTIN_SKILL_PREFERENCES_REMOVE_FAILED" }
            }
        }
    }

    @Synchronized
    fun installSkillPackage(input: InputStream): SkillInstallResult = install(SkillPackageArchive.decode(input))
    @Synchronized
    fun installSkillPackage(bytes: ByteArray): SkillInstallResult = install(SkillPackageArchive.decode(bytes))
    private fun install(files: Map<String, String>): SkillInstallResult {
        val parsed = parse("", files.getValue("SKILL.md"), true)
        require(parsed.rawContent.isNotBlank()) { "SKILL_BODY_REQUIRED" }
        require(validId(parsed.name) && parsed.name != "skill-creator") { "SKILL_NAME_INVALID" }
        val old = findExecutionSnapshot(parsed.name, true) ?: findExecutionSnapshot(parsed.name)
        require(old == null || parsed.version > old.skill.version) { "SKILL_IMPORT_VERSION_NOT_NEWER" }
        require(old == null || !old.skill.isReadOnly) { "SKILL_READ_ONLY" }
        val clean = parsed.copy(id = parsed.name, source = "user")
        val packageFiles = files + ("SKILL.md" to document(clean))
        validateScripts(packageFiles)
        old?.let(::archive)
        fileManager.replaceDirectory(root(parsed.name, true), packageFiles)
        return SkillInstallResult(parsed.name, parsed.name, parsed.version, old != null)
    }

    private fun validateScripts(files: Map<String, String>) {
        files.toSortedMap().forEach { (path, source) ->
            if (path.startsWith("scripts/") && path.endsWith(".js")) scriptValidator.validate(path, source)
        }
    }

    @Synchronized
    fun exportPackage(id: String, draft: Boolean): ByteArray =
        SkillPackageArchive.encode(requireNotNull(findExecutionSnapshot(id, draft)).files)

    @Synchronized
    fun deleteSkill(id: String) {
        val skill = loadSkill(id, true) ?: requireNotNull(loadSkill(id))
        require(!skill.isReadOnly)
        if (fileManager.exists(root(id, true))) check(fileManager.deleteDirectory(root(id, true)))
        if (fileManager.exists(root(id, false))) check(fileManager.deleteDirectory(root(id, false)))
        if (fileManager.exists("skill-preferences/$id")) check(fileManager.deleteDirectory("skill-preferences/$id"))
    }

    @Synchronized
    fun findExecutionSnapshot(reference: String, draft: Boolean = false): SkillExecutionSnapshot? {
        val name = normalizeSkillReference(reference)
        if (!validId(name)) return null
        val directory = root(name, draft)
        if (!fileManager.exists("$directory/SKILL.md")) return null
        return runCatching {
            require(!fileManager.exists("$directory/SCRIPT.json") && !fileManager.exists("$directory/SKILL.yaml")) { "SKILL_FORMAT_UNSUPPORTED" }
            val files = files(directory).toMap()
            val revision = packageRevision(files)
            val skill = withLocalPreferences(parse(name, files.getValue("SKILL.md"), draft)).copy(
                files = files.keys.sorted(), revision = revision,
                hasInstalledVersion = fileManager.exists("skills/$name/SKILL.md"))
            SkillExecutionSnapshot(skill, files, revision)
        }.getOrNull()
    }

    fun recallSkill(reference: String): String? = findExecutionSnapshot(reference)?.let { snapshot ->
        "revision=${snapshot.revision}\nfiles=${snapshot.files.keys.sorted().joinToString()}\n" +
            snapshot.files.getValue("SKILL.md")
    }

    fun readFile(reference: String, path: String, draft: Boolean, revision: String?): String {
        SkillPackageArchive.validatePath(path)
        val snapshot = requireNotNull(findExecutionSnapshot(reference, draft)) { "SKILL_NOT_FOUND" }
        require(revision == null || snapshot.revision == revision) { "SKILL_REVISION_CHANGED" }
        return snapshot.files[path] ?: error("SKILL_FILE_NOT_FOUND")
    }

    fun validateParameters(skill: SkillInfo, parameters: JsonObject) {
        require(parameters.keys.all { it in skill.parameters }) { "SKILL_PARAMETER_UNKNOWN" }
        skill.parameters.forEach { (key, definition) ->
            val spec = definition.jsonObject
            val value = parameters[key]
            require(value != null || spec["required"]?.jsonPrimitive?.booleanOrNull != true) { "SKILL_PARAMETER_REQUIRED: $key" }
            if (value != null) require(when (spec.getValue("type").jsonPrimitive.content) {
                "string" -> value is JsonPrimitive && value.isString
                "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
                "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
                "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                "object" -> value is JsonObject
                "array" -> value is JsonArray
                else -> false
            }) { "SKILL_PARAMETER_TYPE: $key" }
        }
    }

    /** Local assessment, not exported, and never an automatic claim of universal success. */
    @Synchronized
    fun recordAssessment(snapshot: SkillExecutionSnapshot, record: JsonObject) {
        val current = requireNotNull(findExecutionSnapshot(snapshot.skill.id, snapshot.skill.isDraft))
        check(current.revision == snapshot.revision) { "SKILL_CHANGED_DURING_VERIFICATION" }
        require(record["revision"] == null || record["revision"]?.jsonPrimitive?.content == snapshot.revision) { "SKILL_ASSESSMENT_REVISION_CHANGED" }
        val scoped = JsonObject(record + ("revision" to JsonPrimitive(snapshot.revision)))
        val id = digest(scoped.toString())
        fileManager.writeFile("skill-assessments/${snapshot.skill.id}/${snapshot.revision}/$id.json", scoped.toString())
        val hint = buildJsonObject {
            put("record_id", id); put("revision", snapshot.revision)
            listOf("outcome", "assessed_at", "run_id").forEach { key -> scoped[key]?.let { put(key, it) } }
            put("summary", scoped["summary"]?.jsonPrimitive?.contentOrNull.orEmpty().replace(Regex("\\s+"), " ").take(120))
        }
        val recent = (recentAssessments(snapshot).filter { it["record_id"]?.jsonPrimitive?.content != id } + hint)
            .sortedWith(compareByDescending<JsonObject> { it["assessed_at"]?.jsonPrimitive?.longOrNull ?: 0L }
                .thenBy { it["record_id"]?.jsonPrimitive?.content.orEmpty() }).take(20)
        fileManager.writeFile(feedbackPath(snapshot), JsonArray(recent).toString())
    }

    private fun feedbackPath(snapshot: SkillExecutionSnapshot) = "skill-feedback/${snapshot.skill.id}/${snapshot.revision}/recent.json"

    private fun recentAssessments(snapshot: SkillExecutionSnapshot): List<JsonObject> =
        fileManager.readFile(feedbackPath(snapshot))?.let { raw ->
            Json.parseToJsonElement(raw).jsonArray.also { require(it.size <= 20) { "SKILL_FEEDBACK_LIMIT" } }.map { it.jsonObject }
        }.orEmpty()

    fun assessments(id: String, draft: Boolean): List<String> {
        val snapshot = findExecutionSnapshot(id, draft) ?: return emptyList()
        return assessments(snapshot)
    }

    fun assessments(snapshot: SkillExecutionSnapshot): List<String> {
        val directory = "skill-assessments/${snapshot.skill.id}/${snapshot.revision}"
        return fileManager.listFiles(directory).mapNotNull { fileManager.readFile("$directory/$it") }
            .sortedByDescending { Json.parseToJsonElement(it).jsonObject["assessed_at"]?.jsonPrimitive?.longOrNull ?: 0L }.take(20)
    }

    /** Resume is explicit and only terminal runs with per-item checkpoints are resumable. */
    @Synchronized
    fun beginExecution(snapshot: SkillExecutionSnapshot, parameters: JsonObject, checkpointKey: String,
                       resume: Boolean = false, entryPath: String? = null,
                       sourceThreadId: String? = null, sourceTurnId: String? = null): String {
        require((sourceThreadId == null) == (sourceTurnId == null)) { "SKILL_EXECUTION_SOURCE_REQUIRED" }
        val token = executionToken(snapshot.skill.id, checkpointKey)
        val path = "skill-runs/$token.json"
        val previous = executionRecord(snapshot.skill.id, checkpointKey)
        if (previous != null) {
            check(resume) { "SKILL_EFFECT_ALREADY_RECORDED_INSPECT_BEFORE_RETRY" }
            check(previous["revision"]?.jsonPrimitive?.content == snapshot.revision &&
                previous["parameters"] == parameters && previous["entry_path"] == (entryPath?.let(::JsonPrimitive) ?: JsonNull)) { "SKILL_CHECKPOINT_IDENTITY_CHANGED" }
            check(previous["state"]?.jsonPrimitive?.content in skillExecutionTerminalStates) { "SKILL_RUN_NOT_SETTLED" }
            check((previous["items"] as? JsonObject)?.isNotEmpty() == true) { "SKILL_RESUME_REQUIRES_ITEM_CHECKPOINTS" }
            check(previous.getValue("items").jsonObject.values.none {
                it.jsonObject["state"]?.jsonPrimitive?.content == "pending"
            }) { "SKILL_CHECKPOINT_RECONCILIATION_REQUIRED" }
        } else check(!resume) { "SKILL_RUN_NOT_FOUND" }
        fileManager.writeFile(path, buildJsonObject {
            put("skill", snapshot.skill.id); put("revision", snapshot.revision)
            put("parameters", parameters); put("checkpoint_key", checkpointKey); put("state", "pending")
            put("attempt_id", UUID.randomUUID().toString())
            put("source_thread_id", sourceThreadId); put("source_turn_id", sourceTurnId)
            put("entry_path", entryPath); put("items", previous?.get("items") ?: JsonObject(emptyMap()))
        }.toString())
        return token
    }

    @Synchronized
    fun executionRecord(id: String, checkpointKey: String): JsonObject? =
        fileManager.readFile("skill-runs/${executionToken(normalizeSkillReference(id), checkpointKey)}.json")?.let { Json.parseToJsonElement(it).jsonObject }

    private fun executionToken(id: String, checkpointKey: String): String {
        require(validId(id) && checkpointKey.isNotBlank() && checkpointKey.length <= 256)
        return digest(id + "\u0000" + checkpointKey)
    }

    /** Item receipts remain independent of worker state. Only this attempt may complete its own work. */
    @Synchronized
    fun checkpoint(token: String, key: String, operation: String, programHandle: String? = null): JsonObject {
        require(token.matches(Regex("[a-f0-9]{64}")) && key.isNotBlank() && key.length <= 256)
        val path = "skill-runs/$token.json"
        val record = Json.parseToJsonElement(requireNotNull(fileManager.readFile(path))).jsonObject
        val items = (record["items"] as? JsonObject).orEmpty().toMutableMap()
        val item = (items[key] as? JsonObject) ?: JsonObject(emptyMap())
        val state = item["state"]?.jsonPrimitive?.content ?: "absent"
        val active = record["state"]?.jsonPrimitive?.content !in skillExecutionTerminalStates
        val next = when (operation) {
            "read" -> state
            "begin" -> {
                check(active) { "SKILL_RUN_NOT_ACTIVE" }
                check(state != "pending") { "SKILL_CHECKPOINT_EFFECT_UNKNOWN" }
                check(state == "completed" || items.values.none { (it as? JsonObject)?.get("state")?.jsonPrimitive?.content == "pending" }) {
                    "SKILL_CHECKPOINT_EFFECT_UNKNOWN"
                }
                if (state == "completed") "completed" else "pending"
            }
            "complete" -> {
                check(active && item["attempt_id"] == record["attempt_id"]) { "SKILL_CHECKPOINT_RECONCILIATION_REQUIRED" }
                check(state == "pending") { "SKILL_CHECKPOINT_NOT_PENDING" }
                check((item["calls"] as? JsonObject).orEmpty().values.all {
                    it.jsonObject["status"]?.jsonPrimitive?.content == "succeeded"
                }) { "SKILL_CHECKPOINT_EFFECT_UNKNOWN" }
                "completed"
            }
            else -> error("SKILL_CHECKPOINT_OPERATION_INVALID")
        }
        if (operation != "read") {
            items[key] = when {
                operation == "begin" && state == "absent" -> buildJsonObject {
                    put("state", next); put("attempt_id", record.getValue("attempt_id"))
                    put("source_thread_id", record["source_thread_id"] ?: JsonNull)
                    put("source_turn_id", record["source_turn_id"] ?: JsonNull)
                    put("program_handle", programHandle)
                    put("calls", JsonObject(emptyMap()))
                    put("resolutions", item["resolutions"] ?: JsonArray(emptyList()))
                }
                else -> JsonObject(item + ("state" to JsonPrimitive(next)))
            }
            require(items.size <= 256) { "SKILL_CHECKPOINT_LIMIT" }
            fileManager.writeFile(path, JsonObject(record + ("items" to JsonObject(items))).toString())
        }
        return buildJsonObject { put("state", next); put("execute", operation == "begin" && state == "absent") }
    }

    /** Host-authored child lineage for the one pending item begun by this worker attempt. */
    @Synchronized
    fun recordCheckpointCall(token: String, childId: String, tool: String, argumentsRef: String,
                             eventRef: String, status: String) {
        require(status in setOf("started", "succeeded", "failed", "unknown")) { "SKILL_CALL_STATUS_INVALID" }
        require(listOf(childId, tool, argumentsRef, eventRef).all(String::isNotBlank)) { "SKILL_CALL_REFERENCE_REQUIRED" }
        val path = executionPath(token)
        val record = Json.parseToJsonElement(requireNotNull(fileManager.readFile(path))).jsonObject
        val items = record.getValue("items").jsonObject.toMutableMap()
        val pending = items.entries.singleOrNull { (_, value) ->
            val item = value.jsonObject
            item["state"]?.jsonPrimitive?.content == "pending" && item["attempt_id"] == record["attempt_id"]
        } ?: return
        val item = pending.value.jsonObject
        val calls = (item["calls"] as? JsonObject).orEmpty().toMutableMap()
        val prior = calls[childId]?.jsonObject
        check(prior == null && status == "started" || prior != null &&
            prior["tool"]?.jsonPrimitive?.content == tool && prior["arguments_ref"]?.jsonPrimitive?.content == argumentsRef &&
            (prior["status"]?.jsonPrimitive?.content == "started" || prior["status"]?.jsonPrimitive?.content == status)) {
            "SKILL_CALL_IDENTITY_CHANGED"
        }
        calls[childId] = buildJsonObject {
            put("child_id", childId); put("tool", tool); put("arguments_ref", argumentsRef)
            put("start_event_ref", prior?.get("start_event_ref") ?: JsonPrimitive(eventRef))
            put("event_ref", eventRef); put("status", status)
        }
        require(calls.size <= 256) { "SKILL_CHECKPOINT_CALL_LIMIT" }
        items[pending.key] = JsonObject(item + ("calls" to JsonObject(calls)))
        fileManager.writeFile(path, JsonObject(record + ("items" to JsonObject(items))).toString())
    }

    /** The caller validates original/current evidence; this stores its scoped assessment, not business truth. */
    @Synchronized
    fun reconcileCheckpoint(id: String, checkpointKey: String, revision: String, key: String,
                            decision: String, resolution: JsonObject): JsonObject {
        require(decision in setOf("completed", "not_executed", "unknown")) { "SKILL_RECONCILIATION_INVALID" }
        val record = requireNotNull(executionRecord(id, checkpointKey)) { "SKILL_RUN_NOT_FOUND" }
        check(record["revision"]?.jsonPrimitive?.content == revision) { "SKILL_CHECKPOINT_IDENTITY_CHANGED" }
        check(record["state"]?.jsonPrimitive?.content in skillExecutionTerminalStates) { "SKILL_RUN_NOT_SETTLED" }
        val items = record.getValue("items").jsonObject.toMutableMap()
        val item = requireNotNull(items[key]?.jsonObject) { "SKILL_CHECKPOINT_NOT_FOUND" }
        check(item["state"]?.jsonPrimitive?.content == "pending") { "SKILL_CHECKPOINT_NOT_PENDING" }
        val refs = resolution["evidence_refs"] as? JsonArray ?: error("SKILL_RECONCILIATION_EVIDENCE_REQUIRED")
        require(refs.size <= 32 && (decision == "unknown" || refs.isNotEmpty())) { "SKILL_RECONCILIATION_EVIDENCE_REQUIRED" }
        val resolutions = (item["resolutions"] as? JsonArray).orEmpty()
        require(resolutions.size < 32) { "SKILL_RECONCILIATION_LIMIT" }
        val assessment = JsonObject(resolution + buildJsonObject {
            put("decision", decision); put("original_attempt_id", item.getValue("attempt_id"))
            put("original_calls", item["calls"] ?: JsonObject(emptyMap()))
            put("assessed_by", "task_agent")
        })
        val updated = JsonObject(item + buildJsonObject {
            put("state", when (decision) { "completed" -> "completed"; "not_executed" -> "absent"; else -> "pending" })
            put("resolutions", JsonArray(resolutions + assessment))
        })
        items[key] = updated
        fileManager.writeFile(executionPath(executionToken(id, checkpointKey)),
            JsonObject(record + ("items" to JsonObject(items))).toString())
        return updated
    }

    private fun executionPath(token: String): String {
        require(token.matches(Regex("[a-f0-9]{64}")))
        return "skill-runs/$token.json"
    }

    @Synchronized
    fun settleExecution(token: String, status: String, handle: String) {
        require(status in skillExecutionTerminalStates || status in setOf("pending", "running", "waiting_for_device", "waiting_for_input", "waiting_for_observation")) {
            "SKILL_EXECUTION_STATUS_INVALID"
        }
        val path = executionPath(token)
        val current = Json.parseToJsonElement(requireNotNull(fileManager.readFile(path))).jsonObject
        if (current["state"]?.jsonPrimitive?.content in skillExecutionTerminalStates) {
            check(current["state"]?.jsonPrimitive?.content == status) { "SKILL_EXECUTION_ALREADY_SETTLED" }
            return
        }
        fileManager.writeFile(path, JsonObject(current + mapOf("state" to JsonPrimitive(status), "handle" to JsonPrimitive(handle))).toString())
    }

    /** Closing a guided run never establishes business success without an Agent assessment. */
    @Synchronized
    fun settleGuidedExecution(token: String, outcome: String = "unknown") {
        require(outcome in setOf("succeeded", "failed", "unknown"))
        require(token.matches(Regex("[a-f0-9]{64}")))
        val path = "skill-runs/$token.json"
        val current = Json.parseToJsonElement(requireNotNull(fileManager.readFile(path))).jsonObject
        check(current["entry_path"] == JsonNull) { "SKILL_GUIDED_RUN_REQUIRED" }
        fileManager.writeFile(path, JsonObject(current + mapOf(
            "state" to JsonPrimitive("ended"), "assessment_outcome" to JsonPrimitive(outcome)
        )).toString())
    }

    private fun packageRevision(files: Map<String, String>) = digest(JsonObject(files.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString())

    private fun archive(snapshot: SkillExecutionSnapshot) {
        fileManager.replaceDirectory("skill-history/${snapshot.skill.id}/${snapshot.revision}", snapshot.files)
    }

    private fun files(directory: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        fun walk(dir: String, prefix: String, depth: Int) {
            require(depth <= 8) { "SKILL_PATH_DEPTH" }
            fileManager.listFiles(dir).sorted().forEach { child ->
                require(child.isNotBlank() && '/' !in child && '\\' !in child && child !in setOf(".", ".."))
                val path = prefix + child
                val value = fileManager.readFile("$dir/$child")
                if (value == null) walk("$dir/$child", "$path/", depth + 1)
                else result[path] = value
                require(result.size <= SkillPackageArchive.MAX_FILES)
            }
        }
        walk(directory, "", 0)
        SkillPackageArchive.validate(result)
        return result
    }

    private fun root(id: String, draft: Boolean) = "${if (draft) "skill-drafts" else "skills"}/$id"
    private fun validId(id: String) = id.matches(Regex("[a-z0-9][a-z0-9-]{0,59}"))

    private fun parse(id: String, text: String, draft: Boolean): SkillInfo {
        val lines = text.replace("\r\n", "\n").lines()
        require(lines.firstOrNull() == "---") { "SKILL_FRONT_MATTER_REQUIRED" }
        val end = lines.drop(1).indexOfFirst { it == "---" } + 1
        require(end > 0) { "SKILL_FRONT_MATTER_INVALID" }
        val header = lines.subList(1, end).joinToString("\n")
        val yaml = Load(LoadSettings.builder().setAllowDuplicateKeys(false).setMaxAliasesForCollections(0).setCodePointLimit(SkillPackageArchive.MAX_FILE_BYTES).build())
        val values = yaml.loadFromString(header) as? Map<*, *> ?: error("SKILL_METADATA_INVALID")
        fun string(key: String, default: String = "") = values[key]?.let { require(it is String); it } ?: default
        fun list(key: String): List<String> = values[key]?.let { value ->
            require(value is List<*>); value.map { require(it is String); it }
        } ?: emptyList()
        fun bool(key: String): Boolean = values[key]?.let { require(it is Boolean); it } ?: true
        fun localized(key: String): Map<String, String> = values[key]?.let { value ->
            require(value is Map<*, *>) { "SKILL_DISPLAY_METADATA_INVALID" }
            value.entries.associate { (locale, label) ->
                require(locale is String && locale.isNotBlank() && label is String && label.isNotBlank()) {
                    "SKILL_DISPLAY_METADATA_INVALID"
                }
                locale to label
            }
        } ?: emptyMap()
        val name = string("name")
        require(validId(name) && (id.isEmpty() || id == name)) { "SKILL_NAME_INVALID" }
        val description = string("description")
        require(description.isNotBlank()) { "SKILL_DESCRIPTION_REQUIRED" }
        val version = if ("version" in values) requireNotNull(values["version"]?.toString()?.toIntOrNull()) { "SKILL_VERSION_INVALID" } else 1
        require(version > 0)
        val params = toJson(values["parameters"] ?: emptyMap<String, Any>()) as? JsonObject ?: error("SKILL_PARAMETERS_INVALID")
        params.forEach { (key, value) ->
            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")))
            val spec = value as? JsonObject ?: error("SKILL_PARAMETER_INVALID")
            require(spec["type"]?.jsonPrimitive?.content in setOf("string", "number", "integer", "boolean", "array", "object"))
            require(spec["required"] == null || spec["required"]?.jsonPrimitive?.booleanOrNull != null)
        }
        return SkillInfo(id.ifEmpty { name }, name, version, description,
            enabled = bool("enabled"),
            allowImplicitInvocation = bool("allow_implicit_invocation"),
            source = string("source", "user"), appsRequired = list("apps_required"),
            requiredCapabilities = list("required_capabilities"), parameters = params, isDraft = draft,
            displayNames = localized("display_names"), displayDescriptions = localized("display_descriptions"),
            rawContent = lines.drop(end + 1).joinToString("\n").trim())
    }

    private fun document(skill: SkillInfo): String = buildString {
        appendLine("---")
        appendLine("name: ${JsonPrimitive(skill.name)}")
        appendLine("version: ${skill.version}")
        appendLine("description: ${JsonPrimitive(skill.description)}")
        appendLine("display_names: ${JsonObject(skill.displayNames.mapValues { JsonPrimitive(it.value) })}")
        appendLine("display_descriptions: ${JsonObject(skill.displayDescriptions.mapValues { JsonPrimitive(it.value) })}")
        appendLine("enabled: ${skill.enabled}")
        appendLine("allow_implicit_invocation: ${skill.allowImplicitInvocation}")
        appendLine("source: ${JsonPrimitive(skill.source)}")
        appendLine("apps_required: ${JsonArray(skill.appsRequired.map(::JsonPrimitive))}")
        appendLine("required_capabilities: ${JsonArray(skill.requiredCapabilities.map(::JsonPrimitive))}")
        appendLine("parameters: ${skill.parameters}")
        appendLine("---\n")
        appendLine(skill.rawContent)
    }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> require(k is String); k to toJson(v) })
        is List<*> -> JsonArray(value.map(::toJson))
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        else -> error("SKILL_METADATA_INVALID")
    }
}

fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

data class SkillInfo(
    val id: String, val name: String, val version: Int = 1, val description: String,
    val enabled: Boolean = true, val allowImplicitInvocation: Boolean = true, val source: String = "user",
    val appsRequired: List<String> = emptyList(), val requiredCapabilities: List<String> = emptyList(),
    val parameters: JsonObject = JsonObject(emptyMap()), val files: List<String> = emptyList(),
    val isDraft: Boolean = false, val hasInstalledVersion: Boolean = false, val revision: String = "", val rawContent: String,
    val displayNames: Map<String, String> = emptyMap(), val displayDescriptions: Map<String, String> = emptyMap(),
    val recentFeedback: SkillCandidateFeedback? = null
) {
    val isReadOnly: Boolean get() = source == "system"
    val selectionKey: String get() = "$id:$isDraft"
    fun displayName(language: String): String = displayNames[language] ?: name
    fun displayDescription(language: String): String = displayDescriptions[language] ?: description
}

internal fun skillRelevance(skill: SkillInfo, query: String): Int {
    val tokens = tokenizeMemoryQuery(query).ifEmpty { listOf(query.trim().lowercase(Locale.ROOT)).filter(String::isNotEmpty) }
    val searchable = (listOf(skill.name, skill.description) + skill.displayNames.values +
        skill.displayDescriptions.values).joinToString("\n").lowercase(Locale.ROOT)
    return com.droidance.core.knowledge.memory.MemoryQueryRelevance.score(searchable, tokens)
}

fun buildSkillCatalogContext(skills: List<SkillInfo>, availableCapabilities: Set<String>, maxChars: Int = Int.MAX_VALUE,
                            query: String = ""): String {
    require(maxChars >= 256)
    val prefix = "<available_skills>\n"
    val suffix = "</available_skills>"
    val lines = mutableListOf<String>()
    var used = prefix.length + suffix.length
    skills.filter { it.enabled && !it.isDraft && it.allowImplicitInvocation && missingRequiredCapabilities(it, availableCapabilities).isEmpty() }
        .sortedWith(compareByDescending<SkillInfo> { skillRelevance(it, query) }
            .thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
        .forEach { skill ->
            val localized = (skill.displayNames.values + skill.displayDescriptions.values)
                .distinct().joinToString("; ").replace(Regex("\\s+"), " ").take(160)
            val hint = localized.takeIf(String::isNotBlank)?.let { "; localized=$it" }.orEmpty()
            val feedback = skill.recentFeedback?.let {
                "; revision=${skill.revision}; ${it.promptSummary(240)}"
            }.orEmpty()
            val metadata = "- name=${skill.name}; version=${skill.version}: ${skill.description.replace(Regex("\\s+"), " ").take(280)}$hint"
            val line = if (used + metadata.length + feedback.length + 1 <= maxChars) "$metadata$feedback\n" else "$metadata\n"
            if (used + line.length <= maxChars) { lines += line; used += line.length }
        }
    return if (lines.isEmpty()) "" else prefix + lines.joinToString("") + suffix
}
fun missingRequiredCapabilities(skill: SkillInfo, availableCapabilities: Set<String>): List<String> =
    skill.requiredCapabilities.filterNot(availableCapabilities::contains).distinct()
internal fun normalizeSkillReference(reference: String): String = reference.trim().removePrefix("@").removePrefix("/skill ").trim().lowercase(Locale.ROOT)
