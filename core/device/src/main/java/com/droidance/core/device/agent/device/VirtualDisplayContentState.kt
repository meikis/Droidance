// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

/** Platform activity inventory for one frozen virtual display; pixels alone cannot establish it. */
enum class VirtualDisplayContentState {
    EMPTY,
    CONTENT_PRESENT,
    UNAVAILABLE;

    companion object {
        fun fromFacts(
            requestedDisplayId: Int,
            displayId: Int,
            displayValid: Boolean,
            querySucceeded: Boolean,
            activityCount: Int?
        ): VirtualDisplayContentState = when {
            requestedDisplayId <= 0 || displayId != requestedDisplayId || !displayValid ||
                !querySucceeded || activityCount == null || activityCount < 0 -> UNAVAILABLE
            activityCount == 0 -> EMPTY
            else -> CONTENT_PRESENT
        }
    }
}
