# Public runtime notices

These local notices describe runtime termination only; they do not certify task completion. Select the current resolved interface language.

## translations
{
  "translations": {
    "en": {
      "termination_default": "The task stopped before completion could be confirmed.",
      "termination_task_time_limit": "The task reached its time limit before completion could be confirmed.",
      "termination_channel_down": "The device control channel became unavailable before the task finished.",
      "termination_virtual_display_unsupported": "This device cannot run apps on a background display. Droidance needs Android 11 or later on a phone that supports secondary displays, which low-memory and Android Go devices do not.",
      "termination_air_channel_recovery_required": "Wireless debugging pairing is still valid, but the background control service stopped. Connect Wi-Fi and enable wireless debugging, then retry. No new pairing is needed.",
      "termination_air_pairing_required": "Wireless debugging authorization is no longer valid. Open wireless debugging and pair again with a new code, then retry.",
      "termination_model_timeout": "The model timed out while generating the final summary. Device execution has stopped.",
      "termination_model_unavailable": "The model service became unavailable before the task finished. Device execution has stopped.",
      "termination_model_image_unavailable": "The selected model service could not accept the images for this request. Device execution has stopped and the task remains incomplete. Select a service that supports image input, then retry.",
      "termination_model_billing": "The model service has insufficient available credits for this request. The task has stopped and remains incomplete. Check your provider account balance or spending limits.",
      "termination_runtime_exception": "The agent runtime stopped because of an internal error.",
      "termination_internal_error": "The task stopped because of an execution error. Completion has not been verified; retained records must not be treated as a completed result.",
      "termination_app_screen_required": "A usable target-app screen was not obtained, so further screen actions were withheld. The task stopped before completion.",
      "termination_tool_timeout": "A device operation exceeded its time limit and execution stopped. Completed steps remain in the task details; the task is not confirmed complete.",
      "termination_observation_unavailable": "A fresh screen observation could not be obtained. Execution stopped; earlier receipts remain in the task details and completion is unverified.",
      "termination_tool_arguments": "An operation was rejected because its arguments were invalid. Execution stopped before task completion could be confirmed.",
      "termination_guard_stop": "An execution guard stopped the task before completion could be confirmed.",
      "termination_model_protocol_error": "The result could not be confirmed and execution has stopped. Earlier actions and evidence remain in the task details.",
      "termination_context_compaction_failed": "Context summarization could not finish within its time and retry budget. The task is incomplete; earlier actions and evidence are preserved.",
      "observation_unavailable_handoff": "The current execution screen is still unavailable after frame recovery. Resolve the obstruction on the same screen, then select Continue. The agent will inspect the screen again before continuing the task.",
      "app_takeover_start_failed": "The background check stopped unexpectedly before completion. Check the task history and execution channel before retrying.",
      "task_deadline_reached": "The task reached its deadline. Further execution has stopped; interrupted effects remain unverified in the task history.",
      "app_takeover_deadline_reached": "The persistent goal reached its deadline. Further execution has stopped; completion has not been confirmed. Effects interrupted at the deadline remain unverified in the task history.",
      "app_takeover_last_saved_progress": "Last saved progress (before the deadline; later effects may be unverified):"
    },
    "zh": {
      "termination_default": "任务已停止，尚未确认完成。",
      "termination_task_time_limit": "任务已达到时长上限，尚未确认完成。",
      "termination_channel_down": "设备控制通道已断开，任务尚未完成。",
      "termination_virtual_display_unsupported": "这台设备无法在后台屏幕运行 App。Droidance 需要 Android 11 及以上且支持副屏的手机，低内存设备和 Android Go 设备不支持。",
      "termination_air_channel_recovery_required": "无线调试配对仍有效，但后台控制服务已停止。请连接 Wi-Fi 并开启无线调试后重试，无需重新配对。",
      "termination_air_pairing_required": "无线调试授权已失效。请打开无线调试，使用新的配对码重新配对后重试。",
      "termination_model_timeout": "模型响应超时，设备操作已停止，任务尚未完成。",
      "termination_model_unavailable": "模型服务暂时不可用，设备操作已停止，任务尚未完成。",
      "termination_model_image_unavailable": "所选模型服务无法接收本次请求的图像，设备操作已停止，任务尚未完成。请选择支持图像输入的模型服务后重试。",
      "termination_model_billing": "模型服务的可用额度不足以完成本次请求，任务已停止，尚未完成。请检查服务商账户的余额或额度限制。",
      "termination_runtime_exception": "运行时出现内部错误，任务已停止。",
      "termination_internal_error": "任务因执行异常停止，尚未确认完成。已有记录已保留，但不能作为已完成的任务结果。",
      "termination_app_screen_required": "未取得可操作的目标应用画面，后续屏幕操作未执行。任务已停止，尚未完成。",
      "termination_tool_timeout": "设备操作超时，执行已停止。已完成步骤的回执保留在任务详情中，任务尚未确认完成。",
      "termination_observation_unavailable": "无法取得新的屏幕画面，执行已停止。已有操作回执保留在任务详情中，任务尚未确认完成。",
      "termination_tool_arguments": "操作参数不符合要求，被拒绝执行。任务已停止，尚未确认完成。",
      "termination_guard_stop": "执行检查阻止了后续操作，任务已停止，尚未完成。",
      "termination_model_protocol_error": "结果尚未确认，执行已停止。已有操作和证据保留在任务详情中。",
      "termination_context_compaction_failed": "上下文整理未能在时间和重试额度内完成，任务尚未完成。已有操作和证据已保留。",
      "observation_unavailable_handoff": "无法取得当前执行屏幕，画面恢复后仍不可观察。请在同一执行屏幕处理阻碍，然后点击继续；恢复后会重新观察并继续原任务。",
      "app_takeover_start_failed": "后台检查发生异常，未能完成。请检查任务记录和执行通道后重试。",
      "task_deadline_reached": "任务已到截止时间，已停止继续执行；截止时被中断的操作结果仍需从任务记录中核实。",
      "app_takeover_deadline_reached": "持续任务已到截止时间，已停止继续操作，尚未确认整个目标完成。截止时被中断的操作结果仍需从任务记录中核实。",
      "app_takeover_last_saved_progress": "最近一次保存的进展（保存后发生的操作可能尚未核实）："
    }
  }
}
