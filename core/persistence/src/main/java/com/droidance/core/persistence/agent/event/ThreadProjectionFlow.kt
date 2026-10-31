// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.persistence.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.persistence.agent.event.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Table invalidation is not a thread change. Read and replay only a changed revision, off UI. */
internal fun <T> Flow<Long?>.projectThreadRevisions(project: suspend () -> T): Flow<T> =
    distinctUntilChanged()
        .map { project() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
