// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.ports

/** Host clock uses the same epoch as device observation timestamps. */
class TaskRuntimeInfo(val buildVersion: String, val elapsedRealtimeMs: () -> Long)
