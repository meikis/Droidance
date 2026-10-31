// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.tools.DeviceToolNames

import com.droidance.core.agent.tools.UnifiedDeviceToolProtocol
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ObservationFreshnessPolicy @Inject constructor() {
    /** Unknown device mutations invalidate an existing frame without starting a GUI workflow. */
    fun requiresFrameAfterMutation(toolName: String, arguments: String, hasCurrentObservation: Boolean): Boolean =
        UnifiedDeviceToolProtocol.expectsScreenChange(toolName, arguments) &&
            (hasCurrentObservation || UnifiedDeviceToolProtocol.requirements(toolName, arguments).mayChangeDisplay)

    fun mustInvalidateAfter(
        toolName: String,
        toolSucceeded: Boolean,
        hasCurrentObservation: Boolean,
        refreshAfterTools: Set<String>
    ): Boolean = toolSucceeded && hasCurrentObservation && toolName in refreshAfterTools

    private val pageChangingTools = setOf(
        DeviceToolNames.ANDROID_ACT
    )
    // Actuating tools should attach a fresh observation immediately, so the
    // next model turn can choose the next action without spending a turn on observe_screen.
    private val actuatingTools = setOf(
        DeviceToolNames.SCREEN_ACT,
    )
    private val observationTools = setOf(
        DeviceToolNames.ANDROID_READ
    )

    fun shouldObserveAfter(
        toolName: String,
        validation: com.droidance.core.agent.event.ToolValidationEvidence?,
        arguments: String = "{}",
        visionPerception: Boolean = false,
        toolSucceeded: Boolean = true,
        launchAccepted: Boolean = false,
        postActionCaptureScheduled: Boolean = false
    ): Boolean {
        if (toolName in observationTools) return false
        // A page-changing launch can fail after changing the retained display (for example, the
        // command is accepted but the first virtual-display frame is late). A failure receipt
        // must therefore still trigger one fresh observation; otherwise the next model round
        // may plan from the pre-launch screenshot. Other failed actuators keep their existing
        // bounded-recovery behavior.
        if (toolName in pageChangingTools) {
            return (toolSucceeded || launchAccepted) &&
                UnifiedDeviceToolProtocol.expectsScreenChange(toolName, arguments)
        }
        if (!toolSucceeded) return false
        if (postActionCaptureScheduled) return false
        if (!UnifiedDeviceToolProtocol.expectsScreenChange(toolName, arguments)) return false
        if (toolName in actuatingTools) return true
        return validation?.noProgressCount?.let { it >= 2 } == true ||
            validation?.progressState in setOf(
                LocalActionEvidence.ProgressState.ROUTE_LAUNCHED_UNVERIFIED.name,
                LocalActionEvidence.ProgressState.ROUTE_FAILED.name,
                LocalActionEvidence.ProgressState.CONTEXT_CHANGED.name
            )
    }
}
