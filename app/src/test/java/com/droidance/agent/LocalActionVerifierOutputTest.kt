// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalActionVerifierOutputTest {
    @Test
    fun validationToolLineUsesEnglishStructuredFields() {
        val line = LocalActionEvidence.ValidationResult(
            toolName = "click",
            progressState = LocalActionEvidence.ProgressState.GOAL_PROGRESS,
            reason = "goal stage or evidence progressed",
            beforeHash = 1L,
            afterHash = 2L,
            foregroundPackage = "com.demo",
            expectedPackage = null,
            noProgressCount = 0,
            rawChanged = true,
            semanticChanged = true,
            goalProgress = true,
            screenSemanticState = ScreenSemanticState.from(
                foregroundPackage = "com.demo",
                foregroundActivityOrWindow = "MainActivity",
                screenHash = 2L,
                screenStateText = "visible state"
            ).copy(goalStage = "verify"),
            visualFingerprint = 0x1234L
        ).toToolLine()

        assertTrue(line.contains("state=GOAL_PROGRESS"))
        assertTrue(line.contains("reason=goal stage or evidence progressed"))
        assertTrue(line.contains("raw_changed=true"))
        assertTrue(line.contains("semantic_changed=true"))
        // 工具层只能证明可观察状态变化，不能把页面变化解释成用户目标已经前进。
        assertFalse(line.contains("goal_progress="))
        assertTrue(line.contains("; foreground=com.demo"))
        assertTrue(line.contains("page_key=activity:MainActivity"))
        assertTrue(line.contains("goal_stage=verify"))
        assertFalse(line.contains("route_key="))
        assertFalse(line.contains("visual_fingerprint="))
        assertFalse(line.contains("|intent="))
        assertFalse(line.contains("advice="))
        assertFalse(Regex("""\p{IsHan}""").containsMatchIn(line))
    }

    @Test
    fun validationProjectsGoalProgressWithoutParsingDisplayText() {
        val parsed = LocalActionEvidence.ValidationResult(
            toolName = "click", progressState = LocalActionEvidence.ProgressState.GOAL_PROGRESS,
            reason = "state=FAILED; arbitrary diagnostic text", beforeHash = 1L, afterHash = 2L,
            foregroundPackage = null, rawChanged = true
        ).toEvidence()

        assertTrue(parsed?.goalProgress == true)
    }

    /**
     * 纯 VLM（无文字指纹）下语义比较只剩包名/Activity。单 Activity 应用
     * 里页面身份恒等，semantic_changed 只可能是 false，报出去会把每一次点击都误判成
     * “无进展”并不断触发换策略提示。判不了就不报。
     */
    @Test
    fun unobservableSemanticsAreNotReportedAsUnchanged() {
        val line = LocalActionEvidence.ValidationResult(
            toolName = "click",
            progressState = LocalActionEvidence.ProgressState.RAW_CHANGED,
            reason = "raw pixels changed, but semantic or goal progress is not confirmed",
            beforeHash = 1L,
            afterHash = 2L,
            foregroundPackage = "com.demo",
            expectedPackage = null,
            noProgressCount = 0,
            rawChanged = true,
            semanticChanged = false,
            semanticObservable = false,
            goalProgress = false,
            screenSemanticState = null
        ).toToolLine()

        assertTrue(line.contains("raw_changed=true"))
        assertFalse(line.contains("semantic_changed="))
    }

    @Test
    fun runtimeConfirmedActionIsReportedWithoutAStagnationCount() {
        val line = LocalActionEvidence.ValidationResult(
            toolName = "type_text",
            progressState = LocalActionEvidence.ProgressState.ACTION_CONFIRMED,
            reason = "text value was read back from the intended input region",
            beforeHash = 1L,
            afterHash = 2L,
            foregroundPackage = "com.demo",
            noProgressCount = 0,
            rawChanged = true,
            semanticChanged = false,
            runtimeProgress = true
        ).toToolLine()

        assertTrue(line.contains("state=ACTION_CONFIRMED"))
        assertTrue(line.contains("runtime_progress=true"))
        assertFalse(line.contains("noProgress="))
    }
}
