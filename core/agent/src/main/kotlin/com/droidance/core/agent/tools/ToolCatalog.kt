// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.RuntimeMode

enum class OperatorToolProfile {
    DEFAULT,
    LOCAL_KNOWLEDGE,
    BACKGROUND_KNOWLEDGE,
    BACKGROUND_AUTOMATION
}

object ToolCatalog {
    /** Tools whose successful result changes the turn lifecycle rather than the device state. */
    val selfManagementToolNames: Set<String> = setOf("present_result_card", "done")

    /** A generic guard stop may report the grounded outcome, but cannot invent a human handoff. */
    val finalizationToolNames: Set<String> = selfManagementToolNames

    val nativeActionToolNames: Set<String> = setOf(
        DeviceToolNames.ANDROID_READ,
        DeviceToolNames.ANDROID_ACT,
        DeviceToolNames.SHELL
    )

    val skillExecutionToolNames: Set<String> = setOf("run_skill")

    val shellBackedIntentToolNames: Set<String> = setOf(
        DeviceToolNames.ANDROID_ACT,
        DeviceToolNames.SHELL
    )


    /** 会改变屏幕的动作：执行后用模型自己声明的预期回灌验收，把"做了但没效果"变成可判定。 */
    val screenChangingToolNames: Set<String> = setOf(
        DeviceToolNames.ANDROID_ACT,
        DeviceToolNames.SCREEN_ACT,
    )

    /** Screen-dependent actions are rejected locally when no current observation exists. */
    val screenDependentActionToolNames: Set<String> = setOf(
        DeviceToolNames.SCREEN_ACT,
    )

    /** 操作当前画面或读取 Android 状态时，可附带字面事实到任务观察笔记。 */
    val screenEvidenceToolNames: Set<String> = screenDependentActionToolNames + setOf(
        DeviceToolNames.ANDROID_READ, "workspace"
    )

    /** Actions whose result may be compared with a concise model-declared expected effect. */
    val progressSensitiveToolNames: Set<String> =
        screenChangingToolNames + skillExecutionToolNames

    val operatorToolNames: Set<String> = linkedSetOf(
        "program", "workspace", "automation", "manage_app_takeover",
        DeviceToolNames.ANDROID_READ,
        DeviceToolNames.ANDROID_ACT,
        DeviceToolNames.SHELL,
        DeviceToolNames.SCREEN_ACT,
        "ask_human", "session_search", "session_read", "update_memory", "task_memo", "effect_checkpoint", "report_result", "experience",
        "recall_skill", "save_skill", "verify_skill", "run_skill", "read_skill_file", "skill_source",
        "present_result_card", "done"
    )

    val localKnowledgeToolNames: Set<String> = linkedSetOf(
        "session_search", "session_read", "recall_skill", "save_skill", "read_skill_file", "skill_source", "verify_skill", "run_skill", "update_memory", "task_memo", "effect_checkpoint", "report_result", "experience", "workspace", "program", "automation", "done"
    )

    val backgroundAutomationToolNames: Set<String> = setOf(
        "read_notifications", "click_notification", "dismiss_notification", "manage_app_takeover"
    )

    /** Historical reading and draft storage only; no device, script or scheduling side effects. */
    val backgroundKnowledgeToolNames: Set<String> = setOf(
        "session_read", "recall_skill", "read_skill_file", "skill_source", "save_skill", "done"
    )

    fun operatorToolNamesFor(
        mode: RuntimeMode?,
        executionSpace: ExecutionSpace?,
        profile: OperatorToolProfile = OperatorToolProfile.DEFAULT
    ): Set<String> {
        if (profile == OperatorToolProfile.LOCAL_KNOWLEDGE) return localKnowledgeToolNames
        if (profile == OperatorToolProfile.BACKGROUND_KNOWLEDGE) return backgroundKnowledgeToolNames
        requireNotNull(executionSpace) { "Android tools require an execution space" }
        if ((mode != null && mode != RuntimeMode.STANDARD) || executionSpace != ExecutionSpace.VIRTUAL_SPACE) {
            return localKnowledgeToolNames
        }
        // Keep the backend-agnostic device contract visible during bootstrap. A missing
        // runtime mode is a capability/preflight state, not a different model protocol:
        // hiding the tools makes the model hallucinate names that the Harness then rejects
        // as UNKNOWN_TOOL. The frozen execution preflight still prevents any unbound call
        // from reaching a device handler.
        val modeTools = operatorToolNames
        val profileTools = when (profile) {
            OperatorToolProfile.DEFAULT -> modeTools
            OperatorToolProfile.LOCAL_KNOWLEDGE -> localKnowledgeToolNames
            OperatorToolProfile.BACKGROUND_KNOWLEDGE -> backgroundKnowledgeToolNames
            OperatorToolProfile.BACKGROUND_AUTOMATION ->
                (modeTools - "present_result_card") + backgroundAutomationToolNames
        }
        // Device-wide commands belong to the frozen backend, not to a GUI display.
        return profileTools
    }
}
