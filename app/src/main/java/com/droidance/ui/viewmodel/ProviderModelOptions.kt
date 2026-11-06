// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

/** Discovery suggestions never replace explicitly selected or custom model IDs. */
internal fun providerModelOptions(
    custom: List<String>,
    selected: List<String>,
    discovered: List<String>,
    draft: List<String>,
    search: String
): List<String> = (custom + selected + discovered + draft).distinct()
    .filter { it.contains(search.trim(), ignoreCase = true) }

internal fun reconcileSelectedModels(previous: List<String>, requested: List<String>): List<String> {
    val clean = requested.map(String::trim).filter(String::isNotBlank).distinct()
    return (previous.filter { it in clean } + clean).distinct()
}
