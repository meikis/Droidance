// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import com.droidance.R
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidance.agent.OperationRecorder
import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*
import com.droidance.agent.device.*
import com.droidance.core.device.config.InstalledAppsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/** Records facts only; skill authoring is dispatched through the existing conversation Agent. */
@HiltViewModel
class RecorderViewModel @Inject constructor(
    private val skillRules: com.droidance.config.RuleConfigManager,
    private val operationRecorder: OperationRecorder,
    private val installedAppsManager: InstalledAppsManager,
    private val virtualSpaceReadiness: VirtualSpaceReadiness,
    private val virtualSpaceManager: VirtualSpaceManager,
    private val virtualSpaceViewerLauncher: VirtualSpaceViewerLauncher
) : ViewModel() {
    val recordingState = operationRecorder.state
    val operations = operationRecorder.operations
    val sessionInfo = operationRecorder.sessionInfo
    val completionNonce = operationRecorder.completionNonce
    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing
    private val _recordingError = MutableStateFlow<UiOperationFailure?>(null)
    val recordingError: StateFlow<UiOperationFailure?> = _recordingError

    fun startDemonstration(
        name: String,
        goal: String,
        appQuery: String,
        callback: (Boolean) -> Unit
    ) {
        if (_preparing.value) return
        _recordingError.value = null
        _preparing.value = true
        viewModelScope.launch {
            var stageMessage = R.string.ui_input_rejected
            try {
                val result = runCatching {
                    require(goal.isNotBlank()) { "Describe the workflow goal first" }
                    require(appQuery.isNotBlank()) { "Choose a target app first" }
                    stageMessage = R.string.skill_target_not_found
                    val apps = withContext(Dispatchers.IO) { installedAppsManager.getAllAwait() }
                    val packageName = apps.firstOrNull { it.packageName == appQuery.trim() }?.packageName
                        ?: withContext(Dispatchers.IO) {
                            installedAppsManager.findPackageAwait(appQuery.trim())
                        }
                        ?: error("Target app was not found: ${appQuery.trim()}")
                    val appLabel = installedAppsManager.getLabel(packageName).orEmpty()
                        .ifBlank { appQuery.trim() }
                    stageMessage = R.string.skill_space_unavailable
                    val readiness = withContext(Dispatchers.IO) {
                        virtualSpaceReadiness.ensureReadyResult()
                    }
                    require(readiness.ready) { readiness.message }
                    val execution = withContext(Dispatchers.IO) {
                        UUID.randomUUID().toString().let { owner ->
                            virtualSpaceManager.beginExecution("skill-learning:$owner", "skill-learning:$owner")
                        }
                    }
                    require(execution.commandSent) { execution.detail }
                    stageMessage = R.string.skill_app_launch_failed
                    val launch = withContext(Dispatchers.IO) {
                        virtualSpaceManager.launchApp(
                            packageName = packageName,
                            launchMode = AppLaunchMode.RESUME,
                            intentFlags = Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }
                    val hasRenderableFrame = launch.frameReady || withContext(Dispatchers.IO) {
                        virtualSpaceManager.captureLatestPreviewJpeg() != null
                    }
                    require(launch.commandSent && hasRenderableFrame) {
                        launch.detail.ifBlank { "The target app did not render in virtual space" }
                    }
                    stageMessage = R.string.skill_recording_write_failed
                    operationRecorder.startRecording(
                        OperationRecorder.LearningSessionInfo(
                            skillName = name.trim(),
                            goal = goal.trim(),
                            packageName = packageName,
                            appLabel = appLabel,
                            captureMode = OperationRecorder.USER_DEMONSTRATION_CAPTURE_MODE
                        )
                    )
                    stageMessage = R.string.virtual_space_viewer_open_failed
                    require(virtualSpaceViewerLauncher.open()) {
                        "Could not open the virtual-space demonstration view"
                    }
                }
                if (result.isFailure) {
                    operationRecorder.stopRecording()
                    _recordingError.value = UiOperationFailure.from(requireNotNull(result.exceptionOrNull())).copy(messageRes = stageMessage)
                }
                callback(result.isSuccess)
            } finally { _preparing.value = false }
        }
    }

    fun stopRecording(onReady: () -> Unit) {
        operationRecorder.stopRecording()
        viewModelScope.launch {
            _recordingError.value = runCatching {
                operationRecorder.awaitPendingWrites()
                null
            }.getOrElse { UiOperationFailure.from(it).copy(messageRes = R.string.skill_recording_write_failed) }
            onReady()
        }
    }


    fun buildLearningPrompt(): String {
        val session = requireNotNull(operationRecorder.completedSessionId())
        return skillRules.readMarkdownSection("skills.md", "demonstration_request") +
            "\nsession_id=$session"
    }
}
