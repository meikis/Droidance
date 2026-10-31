// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

/** SemVer 2.0 precedence for public versions and v-prefixed release tags. */
object VersionComparator {
    private data class Version(val core: List<String>, val prerelease: List<String>)
    private val pattern = Regex(
        "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
            "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
            "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"
    )

    fun isValid(version: String): Boolean = parse(version) != null

    fun isNewer(remoteVersion: String, currentVersion: String): Boolean {
        val remote = parse(remoteVersion) ?: return false
        val current = parse(currentVersion) ?: return false
        remote.core.zip(current.core).forEach { (r, c) ->
            compareNumber(r, c).takeIf { it != 0 }?.let { return it > 0 }
        }
        if (remote.prerelease.isEmpty() || current.prerelease.isEmpty()) {
            return remote.prerelease.isEmpty() && current.prerelease.isNotEmpty()
        }
        remote.prerelease.zip(current.prerelease).forEach { (r, c) ->
            val rn = r.all(Char::isDigit)
            val cn = c.all(Char::isDigit)
            val comparison = when {
                rn && cn -> compareNumber(r, c)
                rn -> -1
                cn -> 1
                else -> r.compareTo(c)
            }
            if (comparison != 0) return comparison > 0
        }
        return remote.prerelease.size > current.prerelease.size
    }

    private fun compareNumber(left: String, right: String): Int =
        left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)

    private fun parse(raw: String): Version? {
        val match = pattern.matchEntire(raw.trim().removePrefix("v").removePrefix("V")) ?: return null
        val prerelease = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.').orEmpty()
        if (prerelease.any { it.length > 1 && it.startsWith('0') && it.all(Char::isDigit) }) return null
        return Version(match.groupValues.subList(1, 4), prerelease)
    }
}
