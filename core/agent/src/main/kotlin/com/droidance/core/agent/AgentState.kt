// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

enum class AgentState {
    IDLE,            // 空闲，等待输入
    THINKING,        // 正在调用 LLM 推理
    EXECUTING_TOOL,  // 正在执行工具（点击/截图等）
    WAITING_PERMISSION, // 已持久暂停，等待 Android 系统权限结果
    ERROR            // 出错
}
