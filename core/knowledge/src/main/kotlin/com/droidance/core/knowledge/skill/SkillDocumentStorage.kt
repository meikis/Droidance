// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

/** Canonical Skill package file operations; implementations own directory replacement semantics. */
interface SkillDocumentStorage {
    fun readFile(relativePath: String): String?
    fun writeFile(relativePath: String, content: String)
    fun exists(relativePath: String): Boolean
    fun readFrontMatter(relativePath: String): String?
    fun listFiles(directoryPath: String): List<String>
    fun replaceDirectory(relativePath: String, files: Map<String, String>)
    fun deleteDirectory(relativePath: String): Boolean
}
