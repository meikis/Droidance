// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

/** One successful representation per frame; configuration changes replace, never mix, results. */
internal class FrameDerivationCache<K, V> {
    private var entry: Pair<K, V>? = null

    @Synchronized
    fun getOrCompute(key: K, compute: () -> V?): V? {
        entry?.takeIf { it.first == key }?.let { return it.second }
        return compute()?.also { entry = key to it }
    }
}
