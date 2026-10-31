// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.config

import com.droidance.core.agent.config.*

import com.droidance.core.device.config.*

/** Resolve device identities. Discovery is performed by the inventory query, not guessed here. */
object AppNameMatcher {
    fun findBestPackage(query: String, apps: List<AppInfo>): String? =
        findCandidates(query, apps).singleOrNull()?.packageName

    fun findCandidates(query: String, apps: List<AppInfo>): List<AppInfo> {
        val identity = query.trim()
        if (identity.isEmpty()) return emptyList()
        return apps.filter { app ->
            app.packageName.equals(identity, ignoreCase = true) || app.label.trim().equals(identity, ignoreCase = true)
        }.distinctBy(AppInfo::packageName).sortedBy(AppInfo::packageName)
    }
}
