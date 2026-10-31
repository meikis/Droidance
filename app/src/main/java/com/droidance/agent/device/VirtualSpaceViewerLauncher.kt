// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.content.ComponentName
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VirtualSpaceViewerLauncher internal constructor(
    private val hasLiveDisplay: () -> Boolean,
    private val prepareDisplay: () -> Boolean,
    private val launchViewer: (RetainedVirtualExecution?) -> Boolean
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        virtualSpaceManager: VirtualSpaceManager,
        virtualSpaceReadiness: VirtualSpaceReadiness
    ) : this(
        hasLiveDisplay = {
            virtualSpaceManager.activeBinding()?.let { binding ->
                virtualSpaceManager.hasLiveSessionForMode(binding.runtimeMode)
            } == true
        },
        prepareDisplay = {
            virtualSpaceReadiness.ensureReadyResult().ready &&
                virtualSpaceManager.activeBinding() != null
        },
        launchViewer = { expectedOwner ->
            val component = ComponentName(
                context.packageName,
                VirtualSpaceViewerActivity::class.java.name
            ).flattenToString()
            virtualSpaceManager.launchHostActivity(component, expectedOwner)
        }
    )

    fun open(expectedOwner: RetainedVirtualExecution? = null): Boolean {
        if (!hasLiveDisplay() && (expectedOwner != null || !prepareDisplay())) return false
        return launchViewer(expectedOwner)
    }
}
