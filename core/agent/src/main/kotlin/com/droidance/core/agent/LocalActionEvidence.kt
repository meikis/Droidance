// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.ToolValidationEvidence

/** Observation and action validation facts shared across execution implementations. */
object LocalActionEvidence {
    enum class ProgressState {
        ACTION_CONFIRMED,
        RAW_CHANGED,
        STATE_CHANGED,
        GOAL_PROGRESS,
        CHANGED,
        UNCHANGED,
        CONTEXT_CHANGED,
        PARTIAL,
        ROUTE_LAUNCHED_UNVERIFIED,
        ROUTE_VERIFIED,
        ROUTE_FAILED,
        UNKNOWN
    }

    data class TextFingerprint(
        val topTexts: List<String> = emptyList(),
        val bottomTexts: List<String> = emptyList(),
        val allTextHash: Int = 0,
        val lineCount: Int = 0
    )

    data class ObservationLite(
        val screenHash: Long?,
        val foregroundPackage: String?,
        val visualFingerprint: Long? = null,
        val textFingerprint: TextFingerprint? = null,
        val semanticState: ScreenSemanticState? = null,
        val observationId: String? = null,
        val backendSessionId: String? = null,
        val displayId: Int? = null,
        val capturedAtMs: Long = System.currentTimeMillis()
    )

    data class ValidationResult(
        val toolName: String,
        val progressState: ProgressState,
        val reason: String,
        val beforeHash: Long?,
        val afterHash: Long?,
        val foregroundPackage: String?,
        val expectedPackage: String? = null,
        val noProgressCount: Int = 0,
        val rawChanged: Boolean = false,
        val semanticChanged: Boolean = false,
        val semanticObservable: Boolean = true,
        val runtimeProgress: Boolean = false,
        val goalProgress: Boolean = false,
        val screenSemanticState: ScreenSemanticState? = null,
        val visualFingerprint: Long? = null
    ) {
        fun toEvidence(): ToolValidationEvidence = ToolValidationEvidence(
            progressState = progressState.name,
            reason = reason,
            foregroundPackage = foregroundPackage,
            expectedPackage = expectedPackage,
            noProgressCount = noProgressCount,
            rawChanged = rawChanged,
            semanticChanged = semanticChanged.takeIf { semanticObservable },
            runtimeProgress = runtimeProgress,
            goalProgress = goalProgress || progressState == ProgressState.GOAL_PROGRESS,
            pageKey = screenSemanticState?.pageKey,
            goalStage = screenSemanticState?.goalStage,
            routeKey = screenSemanticState?.routeKey(actionIntent = null, targetKey = null, normalizedTextIntent = null),
            visualFingerprint = visualFingerprint
        )

        fun toToolLine(): String {
            val parts = mutableListOf<String>()
            parts += "state=$progressState"
            parts += "reason=$reason"
            parts += "raw_changed=$rawChanged"
            // 语义不可观测时不输出 semantic_changed：没有文字指纹时，
            // 语义比较只剩包名/Activity，而把所有页面塞进一个 Activity 的 App（QQ 的
            // SplashActivity 承载消息列表/聊天窗口/表情面板）里页面身份恒等，这个字段
            // 物理上只会是 false。报出去等于把每一次点击都喂成"无进展"，连点四下必被
            // 无进展守卫杀掉。判不了就不报，让无进展判定回落到工具结果，重复动作交给
            // 屏幕无关的空间计数兜底。
            if (semanticObservable) parts += "semantic_changed=$semanticChanged"
            if (runtimeProgress) parts += "runtime_progress=true"
            // 不输出 goal_progress 布尔字段：state=GOAL_PROGRESS 已经表达"本页内容被大面积替换"
            // 这一可观察事实，而"用户目标是否已达成"仍由证据账本和 done gate 判定，本地验证器
            // 不做这个断言。
            if (!foregroundPackage.isNullOrBlank()) parts += "foreground=$foregroundPackage"
            if (!expectedPackage.isNullOrBlank()) parts += "target=$expectedPackage"
            if (noProgressCount > 0) parts += "noProgress=$noProgressCount"
            // route_key repeats foreground/page_key/goal_stage and visual_fingerprint is a local
            // comparison value; both stay in the structured evidence, not in the model-facing line.
            screenSemanticState?.let { state ->
                parts += "page_key=${state.pageKey}"
                if (!state.goalStage.isNullOrBlank()) parts += "goal_stage=${state.goalStage}"
            }
            return "[LOCAL_VALIDATION] " + parts.joinToString("; ")
        }
    }
}
