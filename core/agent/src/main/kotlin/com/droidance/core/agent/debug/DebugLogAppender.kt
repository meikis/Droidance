// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.debug

import com.droidance.core.agent.debug.*

interface DebugLogAppender {
    fun appendEvent(event: String)
    fun appendPersistentEvent(stream: String, event: String)
}
