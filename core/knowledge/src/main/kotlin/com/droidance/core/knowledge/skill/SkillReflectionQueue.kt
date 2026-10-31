// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable source references only. The Agent decides whether a reusable method exists. */
@Singleton
class SkillReflectionQueue @Inject constructor(private val storage: SkillDocumentStorage) {
    @Serializable
    data class Source(val threadId: String, val turnId: String, val failure: String? = null)
    private val mutableSources = MutableStateFlow<List<Source>>(emptyList())
    val sources = mutableSources.asStateFlow()
    private var loaded = false

    @Synchronized
    fun pending(): List<Source> = read().filter { it.failure == null }

    @Synchronized
    fun enqueue(threadId: String, turnId: String) {
        require(threadId.isNotBlank() && turnId.isNotBlank())
        val current = read()
        if (current.none { it.turnId == turnId }) write(current + Source(threadId, turnId))
    }

    @Synchronized
    fun complete(turnId: String) = write(read().filterNot { it.turnId == turnId })

    @Synchronized
    fun fail(turnId: String, diagnostic: String) = write(read().map {
        if (it.turnId == turnId) it.copy(failure = diagnostic) else it
    })

    @Synchronized
    fun clear() = write(emptyList())

    private fun read(): List<Source> {
        if (!loaded) {
            mutableSources.value = storage.readFile(PATH)?.let { Json.decodeFromString<List<Source>>(it) }.orEmpty()
            loaded = true
        }
        return mutableSources.value
    }

    private fun write(entries: List<Source>) {
        storage.writeFile(PATH, Json.encodeToString(entries))
        loaded = true
        mutableSources.value = entries
    }

    private companion object { const val PATH = "skill-reflections/pending.json" }
}
