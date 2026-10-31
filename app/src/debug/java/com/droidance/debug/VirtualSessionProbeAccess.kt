// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import com.droidance.agent.RuntimeCapabilityRegistry
import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*
import com.droidance.agent.device.*
import com.droidance.core.device.air.AirModePairingManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug verification uses the production Standard virtual-display services. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface VirtualSessionProbeAccess {
    fun executionContextStore(): ExecutionContextStore
    fun sidecarBridge(): VirtualSpaceSidecarBridge
    fun deviceBackend(): AndroidDeviceBackend
    fun airModePairingManager(): AirModePairingManager
    fun capabilityRegistry(): RuntimeCapabilityRegistry
    fun virtualSpaceReadiness(): VirtualSpaceReadiness
    fun virtualSpaceManager(): VirtualSpaceManager
}
