// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm.models

import com.droidance.core.model.llm.session.ModelTransport
import kotlinx.serialization.Serializable

/**
 * 自定义 LLM Provider 配置
 * 支持用户添加任意数量的大模型提供商
 */
@Serializable
data class ProviderConfig(
    val id: String,                          // 唯一标识（UUID）
    val name: String,                        // 用户自定义显示名称
    val apiType: ApiType,                    // 用户明确选择的 API 协议类型
    val transportKind: ModelTransport? = null, // 实际请求 Adapter；CUSTOM 必须明确指定
    val transportDiagnostic: ProviderTransportDiagnostic? = null,
    val baseUrl: String,                     // API 地址
    val apiKey: String = "",                 // API Key（可选，部分本地模型不需要）
    val models: List<String> = emptyList(),  // 该 Provider 下可用的模型 ID 列表
    val defaultModel: String = "",           // 默认模型
    val isEnabled: Boolean = true,           // 是否启用
    val contextWindowTokens: Int = 128_000,  // 该模型上下文窗口（token）；自动压缩阈值按此设定，按模型不同（如 1M 模型设 1_000_000）
    /** Null lets the same Agent select effort; DEFAULT explicitly keeps provider defaults. */
    val reasoningPreference: ReasoningEffort? = null
) {
    /**
     * 仅在 protocol 与 Adapter 选择完全一致时返回可执行 transport。
     * 这里不猜测旧配置的协议；未确认条目必须由设置页重新确认后才可启用。
     */
    fun resolvedTransportOrNull(): ModelTransport? {
        if (transportDiagnostic != null) return null
        val configured = transportKind ?: return null
        return when (apiType) {
            ApiType.OPENAI_RESPONSES -> configured.takeIf { it == ModelTransport.OPENAI_RESPONSES }
            ApiType.OPENAI_COMPATIBLE -> configured.takeIf {
                it == ModelTransport.GENERIC_CHAT_COMPLETIONS
            }
            ApiType.CUSTOM -> configured.takeIf {
                it == ModelTransport.OPENAI_RESPONSES ||
                    it == ModelTransport.GENERIC_CHAT_COMPLETIONS
            }
        }
    }

    fun isTransportConfigured(): Boolean = resolvedTransportOrNull() != null

    fun isAvailableForRequests(): Boolean = isEnabled && baseUrl.isNotBlank() && isTransportConfigured()
}

/**
 * API 协议类型
 * 不同的 API 使用不同的请求/响应格式
 */
@Serializable
enum class ApiType {
    OPENAI_RESPONSES,
    OPENAI_COMPATIBLE,
    CUSTOM
}

/** 未确认的迁移条目必须以结构化状态留在设置中，不能猜测其请求协议。 */
@Serializable
enum class ProviderTransportDiagnostic {
    TRANSPORT_CONFIRMATION_REQUIRED
}
