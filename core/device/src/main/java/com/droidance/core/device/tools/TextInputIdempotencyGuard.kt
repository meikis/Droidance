// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

/** Prevents reinjecting a payload already verified on the same semantic input destination. */
class TextInputIdempotencyGuard {
    private val verifiedRequests = mutableSetOf<String>()

    @Synchronized
    fun wasVerified(
        pageKey: String,
        targetKey: String,
        text: String
    ): Boolean = requestKey(pageKey, targetKey, text) in verifiedRequests

    @Synchronized
    fun record(
        pageKey: String,
        targetKey: String,
        text: String,
        deliveryVerified: Boolean
    ) {
        if (deliveryVerified) {
            verifiedRequests += requestKey(pageKey, targetKey, text)
        }
    }

    @Synchronized
    fun reset() {
        verifiedRequests.clear()
    }

    private fun requestKey(pageKey: String, targetKey: String, text: String): String =
        pageKey.trim() + "\u0000" + targetKey.trim() + "\u0000" + text
}
