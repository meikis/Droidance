// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

enum class DurableMemoryScope {
    USER_PROFILE,
    LONG_TERM
}

enum class DurableMemoryOperation {
    UPSERT,
    DELETE
}

@kotlinx.serialization.Serializable
data class DurableMemoryUpdate(
    val scope: DurableMemoryScope,
    val operation: DurableMemoryOperation,
    val key: String,
    val category: String = "general",
    val content: String = "",
    val sourceQuote: String,
    val expectedRevision: String? = null,
    val operationId: String? = null,
    val sourceRef: String? = null,
    val expiresAt: String? = null
)

data class DurableMemoryApplyResult(
    val requested: Int,
    val grounded: Int,
    /** Unique scope/key entries whose final state differs from the pre-batch state. */
    val changed: Int,
    val items: List<MemoryItemResult> = emptyList()
)

data class MemoryItemResult(val key: String, val status: String, val revision: String?, val error: String? = null)

/** Pure Markdown projection for model-proposed, user-grounded durable memory updates. */
object DurableMemoryDocument {
    data class ApplyResult(val document: String, val changed: Int)

    private const val AUDIT_PREFIX = "<!-- memory-audit:"
    fun revision(document: String, key: String): String = document.lineSequence()
        .firstOrNull { matchesKey(it, key) }?.let(::stableLineHash) ?: "absent"

    private fun matchesKey(line: String, key: String): Boolean {
        parseManagedLine(line)?.let { return it.key == key }
        val content = bulletLine.matchEntire(line)?.groupValues?.get(2)?.trim() ?: return false
        return key == "manual_${stableLineHash(content).take(16)}"
    }

    private val validKey = Regex("^[a-z0-9][a-z0-9._-]{0,79}$")
    private val managedLine = Regex(
        """^(\s*[-*]\s+)(\[memory:key=([^]]+)\]\[category=([^]]+)\]\[updated=([^]]+)\])\s*(.*)$"""
    )
    private val bulletLine = Regex("""^(\s*[-*]\s+)(.*)$""")

    private data class ManagedLine(
        val bullet: String,
        val metadata: String,
        val key: String,
        val category: String,
        val updatedAt: String,
        val content: String
    )

    private data class StoredManagedLine(
        val lineIndex: Int,
        val managed: ManagedLine,
        val displayLine: String
    )

    fun isGrounded(update: DurableMemoryUpdate, trustedUserText: String): Boolean {
        val quote = normalizeWhitespace(update.sourceQuote)
        val trusted = normalizeWhitespace(trustedUserText)
        return quote.length >= 2 && trusted.contains(quote, ignoreCase = true)
    }

    fun validate(update: DurableMemoryUpdate) {
        update.expiresAt?.let { java.time.OffsetDateTime.parse(it) }
        require(validKey.matches(update.key)) { "Memory key must be a lowercase stable identifier" }
        require(update.operation != DurableMemoryOperation.UPSERT || update.content.isNotBlank()) {
            "Upsert memory content cannot be blank"
        }
    }

    fun apply(
        existing: String,
        header: String,
        updates: List<DurableMemoryUpdate>,
        updatedAt: String
    ): ApplyResult {
        updates.forEach(::validate)
        var changed = 0
        var lines = existing.ifBlank { "$header\n" }.lines().toMutableList()
        updates.associateBy { it.key }.values.forEach { update ->
            val auditId = (update.operationId ?: update.expiresAt?.let { "$updatedAt:${update.key}:$it" })?.let(::stableLineHash)
            if (auditId != null && lines.any { it.startsWith("$AUDIT_PREFIX$auditId:") }) return@forEach
            require(update.expectedRevision == null || update.expectedRevision == revision(lines.joinToString("\n"), update.key)) {
                "MEMORY_REVISION_CONFLICT"
            }
            val marker = "[memory:key=${update.key}]"
            if (update.operation == DurableMemoryOperation.UPSERT) {
                val content = MemorySecretRedactor.redact(update.content)
                    .lineSequence()
                    .joinToString(" ") { it.trim() }
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(500)
                require(content.isNotBlank()) { "Upsert memory content cannot be blank" }
                val category = update.category.lowercase()
                    .replace(Regex("[^a-z0-9._-]+"), "_")
                    .trim('_')
                    .ifBlank { "general" }
                    .take(48)
                val existingManaged = lines.firstOrNull { matchesKey(it, update.key) }
                val unchanged = existingManaged
                    ?.substringAfter("$marker[category=", missingDelimiterValue = "")
                    ?.let { managed ->
                        val existingCategory = managed.substringBefore(']')
                        val existingContent = managed.substringAfter("] ", missingDelimiterValue = "")
                        existingCategory == category && existingContent == content
                    } == true
                if (unchanged && expiresAt(lines.joinToString("\n"), update.key) == update.expiresAt) return@forEach
                lines = lines.filterNot { matchesKey(it, update.key) }.toMutableList()
                while (lines.lastOrNull()?.isBlank() == true) lines.removeLast()
                if (lines.isNotEmpty()) lines += ""
                lines += "- $marker[category=$category][updated=$updatedAt] $content"
            } else {
                if (lines.none { matchesKey(it, update.key) }) return@forEach
                lines = lines.filterNot { matchesKey(it, update.key) }.toMutableList()
            }
            if (auditId != null) {
                val source = java.util.Base64.getEncoder().encodeToString(
                    "${update.sourceRef.orEmpty()}\n${MemorySecretRedactor.redact(update.sourceQuote)}\n${update.expiresAt.orEmpty()}".toByteArray())
                lines += "$AUDIT_PREFIX$auditId:${update.key}:${update.operation.name}:$source -->"
            }
            changed++
        }
        return ApplyResult(
            document = if (changed == 0) existing else lines.joinToString("\n").trimEnd() + "\n",
            changed = changed
        )
    }

    /** User-facing projection: storage indexes and audit timestamps stay hidden. */
    fun displayProjection(document: String): String = project(document) { managed ->
        "${managed.bullet}${managed.content}"
    }

    /** Model projection: retain only the stable update/delete key plus semantic content. */
    fun promptProjection(document: String): String = project(document) { managed ->
        "${managed.bullet}[memory:key=${managed.key}] ${managed.content}"
    }

    /** Bounds prompt context on complete semantic lines so hidden metadata is never sliced open. */
    fun promptProjection(document: String, maxChars: Int): String {
        require(maxChars >= 32) { "Prompt memory budget must be at least 32 characters" }
        val content = promptProjection(document).trim()
        if (content.length <= maxChars) return content
        val lines = logicalLines(content)
        val heading = lines.firstOrNull { it.trimStart().startsWith('#') }
        val prefix = heading
            ?.takeIf { it.length + 5 <= maxChars }
            ?.let { "$it\n\n..." }
            ?: "..."
        var used = prefix.length
        val retained = ArrayDeque<String>()
        for (line in lines.asReversed()) {
            if (line == heading || line.isBlank()) continue
            val added = line.length + 1
            if (used + added > maxChars) break
            retained.addFirst(line)
            used += added
        }
        return buildString {
            append(prefix)
            retained.forEach { line ->
                append('\n')
                append(line)
            }
        }
    }

    fun contextEntries(
        document: String,
        scope: ProgressiveMemoryScope
    ): List<ProgressiveMemoryEntry> = logicalLines(document).mapNotNull { line ->
        parseManagedLine(line)?.let { managed ->
            val expires = expiresAt(document, managed.key)
            if (expires != null && java.time.OffsetDateTime.parse(expires).toInstant() <= java.time.Instant.now()) return@mapNotNull null
            return@mapNotNull ProgressiveMemoryEntry(
                ref = "${scope.refPrefix}:${managed.key}",
                scope = scope,
                category = managed.category,
                content = managed.content,
                updatedAt = managed.updatedAt,
                revision = revision(document, managed.key),
                expiresAt = expires
            )
        }
        val manual = bulletLine.matchEntire(line)?.groupValues?.get(2)?.trim().orEmpty()
        if (manual.isBlank()) return@mapNotNull null
        ProgressiveMemoryEntry(
            ref = "${scope.refPrefix}:manual_${stableLineHash(manual).take(16)}",
            scope = scope,
            category = "general",
            content = MemorySecretRedactor.redact(manual),
            revision = stableLineHash(line)
        )
    }.distinctBy(ProgressiveMemoryEntry::ref)

    /**
     * Preserve hidden metadata for ordinary line edits made through the semantic editor.
     * Ambiguous multi-entry rewrites are rejected instead of silently rebinding stable keys.
     */
    fun mergeDisplayEdit(
        existing: String,
        editedDisplay: String,
        updatedAt: String
    ): String? {
        val storedLines = logicalLines(existing)
        val editedLines = logicalLines(editedDisplay)
        val editedBullets = editedLines.mapIndexedNotNull { index, line ->
            val match = bulletLine.matchEntire(line) ?: return@mapIndexedNotNull null
            val content = match.groupValues[2].trim()
            if (content.isBlank()) return null
            index to content
        }.toMap()
        val storedManaged = storedLines.mapIndexedNotNull { index, line ->
            parseManagedLine(line)?.let { managed ->
                StoredManagedLine(
                    lineIndex = index,
                    managed = managed,
                    displayLine = "${managed.bullet}${managed.content}"
                )
            }
        }
        val assignments = mutableMapOf<Int, StoredManagedLine>()
        val usedStoredIndexes = mutableSetOf<Int>()
        val storedByDisplay = storedManaged.groupBy(StoredManagedLine::displayLine)
        val editedByLine = editedLines.withIndex().groupBy({ it.value }, { it.index })

        storedByDisplay.forEach { (displayLine, records) ->
            val editedIndexes = editedByLine[displayLine].orEmpty()
            if (records.size == 1 && editedIndexes.size == 1) {
                val record = records.single()
                assignments[editedIndexes.single()] = record
                usedStoredIndexes += record.lineIndex
            }
        }

        val originalManualCounts = storedLines.mapIndexedNotNull { index, line ->
            line.takeIf { parseManagedLine(it) == null }?.let { index to it }
        }.groupingBy(Pair<Int, String>::second).eachCount().toMutableMap()
        val unmatchedEditedBulletIndexes = editedBullets.keys
            .filterNot(assignments::containsKey)
            .filter { index ->
                val line = editedLines[index]
                val remaining = originalManualCounts[line] ?: 0
                if (remaining > 0) {
                    originalManualCounts[line] = remaining - 1
                    false
                } else {
                    true
                }
            }
        val unmatchedStored = storedManaged.filterNot { it.lineIndex in usedStoredIndexes }
        val originalNonBulletCounts = storedLines.filter { line ->
            parseManagedLine(line) == null && bulletLine.matchEntire(line) == null
        }.groupingBy { line -> line }.eachCount().toMutableMap()
        val hasNewNonBulletLine = editedLines.any { line ->
            if (bulletLine.matchEntire(line) != null) return@any false
            val remaining = originalNonBulletCounts[line] ?: 0
            if (remaining > 0) {
                originalNonBulletCounts[line] = remaining - 1
                false
            } else {
                line.isNotBlank()
            }
        }
        val exactPairs = assignments.map { (editedIndex, stored) -> stored.lineIndex to editedIndex }
            .sortedBy(Pair<Int, Int>::first)
        val mixedReorder = exactPairs.zipWithNext().any { (before, after) ->
            before.second > after.second
        }

        if (unmatchedStored.isNotEmpty() && hasNewNonBulletLine) return null
        if (unmatchedStored.isNotEmpty() && unmatchedEditedBulletIndexes.isNotEmpty()) {
            if (mixedReorder ||
                unmatchedStored.size != unmatchedEditedBulletIndexes.size ||
                unmatchedStored.size > 1
            ) {
                return null
            }
            unmatchedStored.sortedBy(StoredManagedLine::lineIndex)
                .zip(unmatchedEditedBulletIndexes.sorted())
                .forEach { (stored, editedIndex) -> assignments[editedIndex] = stored }
        }

        val rendered = editedLines.mapIndexed { index, line ->
            val stored = assignments[index] ?: return@mapIndexed line
            val match = bulletLine.matchEntire(line) ?: return null
            val content = match.groupValues[2].trim().takeIf(String::isNotBlank) ?: return null
            val metadata = if (content == stored.managed.content) {
                stored.managed.metadata
            } else {
                "[memory:key=${stored.managed.key}]" +
                    "[category=${stored.managed.category}]" +
                    "[updated=$updatedAt]"
            }
            "${match.groupValues[1]}$metadata $content"
        }
        val retainedAudit = storedLines.filter { it.startsWith(AUDIT_PREFIX) }.filterNot(rendered::contains)
        val removedKeys = storedLines.mapNotNull { line ->
            parseManagedLine(line)?.key ?: bulletLine.matchEntire(line)?.groupValues?.get(2)?.trim()
                ?.takeIf(String::isNotBlank)?.let { "manual_${stableLineHash(it).take(16)}" }
        }.distinct().filter { key -> rendered.none { matchesKey(it, key) } }
        val deletionAudit = removedKeys.map { key ->
            val id = stableLineHash("$updatedAt:editor-delete:$key")
            val source = java.util.Base64.getEncoder().encodeToString("memory_editor\n\n".toByteArray())
            "$AUDIT_PREFIX$id:$key:DELETE:$source -->"
        }
        return (rendered + retainedAudit + deletionAudit).joinToString("\n").let { content ->
            if (content.isBlank()) "" else "$content\n"
        }
    }

    /** Deleted keys remain a content-free suppression marker for historical recall. */
    fun retiredKeys(document: String): Set<String> = document.lineSequence()
        .filter { it.startsWith(AUDIT_PREFIX) }
        .mapNotNull { it.removePrefix(AUDIT_PREFIX).split(':', limit = 4).getOrNull(1) }
        .filter { revision(document, it) == "absent" }.toSet()

    private fun expiresAt(document: String, key: String): String? {
        val audit = document.lineSequence().lastOrNull { it.startsWith(AUDIT_PREFIX) && ":$key:UPSERT:" in it }
        return audit?.substringAfter(":$key:UPSERT:")?.substringBefore(" -->")?.let { encoded ->
            runCatching { String(java.util.Base64.getDecoder().decode(encoded)).lines().lastOrNull()?.takeIf(String::isNotBlank) }.getOrNull()
        }
    }

    private fun project(document: String, transform: (ManagedLine) -> String): String =
        document.lines().filterNot { it.startsWith(AUDIT_PREFIX) }.joinToString("\n") { line ->
            parseManagedLine(line)?.let(transform) ?: line
        }

    private fun parseManagedLine(line: String): ManagedLine? {
        val match = managedLine.matchEntire(line) ?: return null
        return ManagedLine(
            bullet = match.groupValues[1],
            metadata = match.groupValues[2],
            key = match.groupValues[3],
            category = match.groupValues[4],
            updatedAt = match.groupValues[5],
            content = match.groupValues[6].trim()
        )
    }

    private fun stableLineHash(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.trim().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun logicalLines(document: String): List<String> = document
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
        .dropLastWhile(String::isEmpty)

    private fun normalizeWhitespace(value: String): String =
        value.trim().replace(Regex("\\s+"), " ")
}
