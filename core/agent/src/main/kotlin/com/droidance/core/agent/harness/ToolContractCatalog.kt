// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.harness

import com.droidance.core.agent.attempt.DeviceCapability
import com.droidance.core.agent.attempt.DeviceToolCapabilityRequirement
import com.droidance.core.agent.event.ResultImageRole
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolPresentationCategory
import com.droidance.core.agent.event.ToolPresentationSnapshot
import com.droidance.core.agent.ports.AgentConfiguration
import com.droidance.core.agent.config.ToolContractConfig
import com.droidance.core.agent.tools.Tool
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Event-sourced Harness 使用的工具静态契约。
 *
 * 工具名称、绑定方式和展示语义全部来自 rules/tool_contracts.yaml；代码不为任意
 * 工具补默认分类，配置与实际暴露的工具集合不一致时直接拒绝构造本轮计划。
 */
@Singleton
class ToolContractCatalog @Inject constructor(
    private val ruleConfigManager: AgentConfiguration
) {
    fun resolve(exposedTools: Collection<Tool>): List<RegisteredHarnessTool> =
        ToolContractConfiguration.resolve(
            configured = ruleConfigManager.toolContracts().toolContracts,
            exposedTools = exposedTools
        )
}

data class RegisteredHarnessTool(
    val tool: Tool,
    val contract: RegisteredToolContract,
    val presentation: ToolPresentationSnapshot,
    val capabilityRequirement: DeviceToolCapabilityRequirement?,
    val groundsCompletion: Boolean = false
) {
    init {
        require(tool.name == contract.name) { "Harness tool and contract names must match" }
        require(capabilityRequirement?.toolName == null || capabilityRequirement.toolName == tool.name) {
            "Harness capability requirement must use the tool name"
        }
        require(
            (contract.binding == ToolBinding.DEVICE_BOUND) == (capabilityRequirement != null)
        ) {
            "Only DEVICE_BOUND Harness tools may declare a capability requirement"
        }
    }
}

data class ToolContractSpec(
    val name: String,
    val binding: ToolBinding,
    val effect: ToolEffect,
    val groundsCompletion: Boolean,
    val requiredCapability: DeviceCapability?,
    val labelResourceKey: String,
    val category: ToolPresentationCategory,
    val resultImageRole: ResultImageRole,
    val sensitiveFieldPaths: List<String>,
    val requiresResultBeforeAction: Boolean = false
) {
    init {
        require(name.isNotBlank()) { "Tool contract name cannot be blank" }
        require(
            (binding == ToolBinding.DEVICE_BOUND) == (requiredCapability != null)
        ) {
            "Tool contract must declare requiredCapability exactly for DEVICE_BOUND tools"
        }
        require(labelResourceKey.isNotBlank()) { "Tool contract label cannot be blank" }
        require(sensitiveFieldPaths.all(String::isNotBlank)) {
            "Tool contract sensitive field paths cannot be blank"
        }
        require(sensitiveFieldPaths.toSet().size == sensitiveFieldPaths.size) {
            "Tool contract sensitive field paths must be unique"
        }
    }

    fun presentation(): ToolPresentationSnapshot = ToolPresentationSnapshot(
        labelResourceKey = labelResourceKey,
        category = category,
        resultImageRole = resultImageRole,
        sensitiveFieldPaths = sensitiveFieldPaths
    )
}

/** Pure parser/resolver so contract validation stays independently testable. */
object ToolContractConfiguration {
    fun resolve(
        configured: Map<String, ToolContractConfig>,
        exposedTools: Collection<Tool>
    ): List<RegisteredHarnessTool> {
        val specs = parse(configured)
        val toolsByName = exposedTools.associateBy(Tool::name)
        require(toolsByName.size == exposedTools.size) {
            "Exposed Harness tools must use unique names"
        }

        val configuredNames = specs.map(ToolContractSpec::name).toSet()
        val unconfiguredTools = toolsByName.keys - configuredNames
        require(unconfiguredTools.isEmpty()) {
            "Exposed Harness tools have no static contract: ${unconfiguredTools.sorted().joinToString()}"
        }

        return specs.filter { it.name in toolsByName }.sortedBy(ToolContractSpec::name).map { spec ->
            val tool = checkNotNull(toolsByName[spec.name])
            RegisteredHarnessTool(
                tool = tool,
                contract = RegisteredToolContract(
                    name = tool.name,
                    description = tool.definition.description,
                    binding = spec.binding,
                    effect = spec.effect,
                    parameters = tool.definition.parameters,
                    requiresResultBeforeAction = spec.requiresResultBeforeAction
                ),
                presentation = spec.presentation(),
                capabilityRequirement = spec.requiredCapability?.let { capability ->
                    DeviceToolCapabilityRequirement(tool.name, capability)
                },
                groundsCompletion = spec.groundsCompletion
            )
        }
    }

    fun parse(configured: Map<String, ToolContractConfig>): List<ToolContractSpec> {
        require(configured.isNotEmpty()) { "Tool contract configuration is empty" }
        return configured.map { (name, contract) ->
            contract.validate(name)
            if (name in com.droidance.core.agent.tools.ToolCatalog.operatorToolNames &&
                name !in com.droidance.core.agent.tools.UnifiedDeviceToolProtocol.argumentDependentTools) {
                val requiresBackend = com.droidance.core.agent.tools.UnifiedDeviceToolProtocol.requirements(name, "{}").requiresBackend
                require((contract.binding == ToolBinding.DEVICE_BOUND) == requiresBackend) {
                    "Tool binding disagrees with execution requirements: $name"
                }
            }
            ToolContractSpec(
                name = name,
                binding = contract.binding,
                effect = contract.effect,
                groundsCompletion = contract.groundsCompletion,
                requiredCapability = contract.requiredCapability,
                labelResourceKey = contract.label,
                category = contract.category,
                resultImageRole = contract.resultImageRole,
                sensitiveFieldPaths = contract.sensitiveFieldPaths,
                requiresResultBeforeAction = contract.requiresResultBeforeAction
            )
        }.also { specs ->
            require(specs.map(ToolContractSpec::name).toSet().size == specs.size) {
                "Tool contract names must be unique"
            }
        }
    }

}
