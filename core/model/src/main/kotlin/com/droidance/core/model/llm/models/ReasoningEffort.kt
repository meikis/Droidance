// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm.models

import kotlinx.serialization.Serializable

/**
 * 大模型推理/思考强度等级配置
 */
@Serializable
enum class ReasoningEffort(val wireValue: String?) {
    DEFAULT(null),      // Omit the parameter and use the model's default.
    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),        // 低 / 快速响应
    MEDIUM("medium"),  // 中
    HIGH("high"),      // 高 / 深度思考
    XHIGH("xhigh"),
    MAX("max")
}
