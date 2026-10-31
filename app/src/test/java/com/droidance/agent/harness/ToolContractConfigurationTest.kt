// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.harness

import com.droidance.core.agent.harness.*

import com.droidance.agent.harness.*

import com.droidance.core.agent.attempt.DeviceCapability
import com.droidance.core.agent.event.ResultImageRole
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolPresentationCategory
import com.droidance.core.agent.config.ToolContractConfig
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import com.droidance.core.agent.tools.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolContractConfigurationTest {
    @Test
    fun queryBarrierSurvivesConfigurationResolutionAndRejectsWrites() {
        val query = config().copy(requiresResultBeforeAction = true)
        val resolved = ToolContractConfiguration.resolve(
            mapOf("read" to query), listOf(tool("read"))
        ).single()
        assertTrue(resolved.contract.requiresResultBeforeAction)
        val invalid = runCatching {
            ToolContractConfiguration.parse(mapOf(
                "write" to config(effect = ToolEffect.STATE_CHANGING)
                    .copy(requiresResultBeforeAction = true)
            ))
        }.exceptionOrNull()
        assertTrue(invalid is IllegalArgumentException)
    }

    @Test
    fun resolvesOnlyExactlyConfiguredToolsWithStableSchemaOrder() {
        val resolved = ToolContractConfiguration.resolve(
            configured = linkedMapOf(
                "write" to config(
                    binding = ToolBinding.DEVICE_BOUND,
                    effect = ToolEffect.STATE_CHANGING,
                    category = ToolPresentationCategory.PRIMARY,
                    imageRole = ResultImageRole.BEFORE_AND_AFTER
                ),
                "read" to config(groundsCompletion = true)
            ),
            exposedTools = listOf(tool("write"), tool("read"))
        )

        assertEquals(listOf("read", "write"), resolved.map { it.contract.name })
        assertEquals(ToolBinding.TURN_LOCAL, resolved.first().contract.binding)
        assertEquals(ToolEffect.READ_ONLY, resolved.first().contract.effect)
        assertTrue(resolved.first().groundsCompletion)
        assertEquals(ToolPresentationCategory.PRIMARY, resolved.last().presentation.category)
        assertEquals(ResultImageRole.BEFORE_AND_AFTER, resolved.last().presentation.resultImageRole)
        assertEquals(DeviceCapability.CLICK, resolved.last().capabilityRequirement?.capability)
        assertEquals(null, resolved.first().capabilityRequirement)
    }

    @Test
    fun rejectsMissingContractsAndInvalidCapabilityBindingsWithoutDefaults() {
        val missing = runCatching {
            ToolContractConfiguration.resolve(
                configured = mapOf("read" to config()),
                exposedTools = listOf(tool("read"), tool("write"))
            )
        }.exceptionOrNull()
        assertTrue(missing is IllegalArgumentException)
        assertTrue(checkNotNull(missing).message.orEmpty().contains("no static contract"))

        val missingCapability = runCatching {
            ToolContractConfiguration.parse(
                mapOf(
                    "write" to config(
                        binding = ToolBinding.DEVICE_BOUND,
                        capability = null
                    )
                )
            )
        }.exceptionOrNull()
        assertTrue(missingCapability is IllegalArgumentException)
        assertTrue(checkNotNull(missingCapability).message.orEmpty().contains("required_capability"))

        val unexpectedCapability = runCatching {
            ToolContractConfiguration.parse(
                mapOf("read" to config(capability = DeviceCapability.CLICK))
            )
        }.exceptionOrNull()
        assertTrue(unexpectedCapability is IllegalArgumentException)
        assertTrue(checkNotNull(unexpectedCapability).message.orEmpty().contains("required_capability"))
    }

    @Test
    fun rejectsStaticOperatorContractWhenBindingDisagreesWithProtocolRequirement() {
        val invalid = runCatching {
            ToolContractConfiguration.parse(
                mapOf("screen_act" to config(binding = ToolBinding.TURN_LOCAL))
            )
        }.exceptionOrNull()

        assertTrue(invalid is IllegalArgumentException)
        assertTrue(checkNotNull(invalid).message.orEmpty().contains("screen_act"))
        assertTrue(checkNotNull(invalid).message.orEmpty().contains("binding"))
    }

    @Test
    fun acceptsStaticOperatorContractsThatMatchTheProtocolRequirement() {
        val resolved = ToolContractConfiguration.parse(
            linkedMapOf(
                "screen_act" to config(binding = ToolBinding.DEVICE_BOUND),
                "wait" to config(binding = ToolBinding.TURN_LOCAL)
            )
        )

        assertEquals(
            listOf("screen_act", "wait"),
            resolved.map(ToolContractSpec::name)
        )
    }

    @Test
    fun configuredContractMayBeASupersetOfTheContextualToolSurface() {
        val resolved = ToolContractConfiguration.resolve(
            configured = linkedMapOf(
                "read" to config(),
                "shell" to config(
                    binding = ToolBinding.DEVICE_BOUND,
                    effect = ToolEffect.STATE_CHANGING
                )
            ),
            exposedTools = listOf(tool("read"))
        )

        assertEquals(listOf("read"), resolved.map { it.contract.name })
    }

    private fun config(
        binding: ToolBinding = ToolBinding.TURN_LOCAL,
        effect: ToolEffect = ToolEffect.READ_ONLY,
        category: ToolPresentationCategory = ToolPresentationCategory.SUPPORTING,
        imageRole: ResultImageRole = ResultImageRole.NONE,
        groundsCompletion: Boolean = false,
        capability: DeviceCapability? =
            if (binding == ToolBinding.DEVICE_BOUND) DeviceCapability.CLICK else null
    ) = ToolContractConfig(
        binding = binding,
        effect = effect,
        groundsCompletion = groundsCompletion,
        requiredCapability = capability,
        label = "tool.example",
        category = category,
        resultImageRole = imageRole
    )

    private fun tool(name: String): Tool = object : Tool {
        override val name: String = name
        override val definition = ToolDefinition(
            name = name,
            description = "Tool $name",
            parameters = mapOf("value" to ToolParameter("string", "Value", false))
        )

        override suspend fun execute(arguments: String) = com.droidance.core.agent.tools.ToolProtocolResult.success(arguments)
    }
}
