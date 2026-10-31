// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LLMUsageAccountingSourceContractTest {
    @Test
    fun chatCompletionsDrainsFinalUsageAndAllTransportsRecordRequests() {
        val router = projectFile("core/model/src/main/kotlin/com/droidance/core/model/llm/LLMRouter.kt").readText()
        val genericStream = router.substringAfter("private fun streamOpenAICompatible(")
            .substringBefore("private fun streamOpenAIResponses(")
        val responsesStream = router.substringAfter("private fun streamOpenAIResponses(")
            .substringBefore("private class RequestCancelledBeforeRegistrationException")

        assertTrue(genericStream.contains("usageState.needsFinalUsageChunk"))
        assertTrue(genericStream.contains("FINAL_USAGE_GRACE_MS"))
        assertFalse(genericStream.contains("if (terminalReceived) break@streamLoop"))
        assertTrue(genericStream.contains("tokenUsageRepository.recordRequest("))
        assertTrue(responsesStream.contains("tokenUsageRepository.recordRequest("))
    }

    @Test
    fun repositoryPublishesPersistentLongCountersToTheSettingsViewModel() {
        val repository = projectFile("app/src/main/java/com/droidance/config/TokenUsageRepository.kt").readText()
        val viewModel = projectFile("app/src/main/java/com/droidance/ui/viewmodel/SettingsViewModel.kt").readText()
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/SettingsDataManagementScreen.kt").readText()
        val strings = projectFile("app/src/main/res/values-zh/strings.xml").readText()
        val chart = projectFile("app/src/main/java/com/droidance/ui/screens/SettingsUsageChart.kt").readText()
            .substringAfter("internal fun UsageBarChart(")
            .substringBefore("private fun ChartLegendSolid(")

        assertTrue(repository.contains("StateFlow<List<DailyUsage>>"))
        assertTrue(repository.contains("@Synchronized\n    fun recordRequest("))
        assertTrue(repository.contains("val promptTokens: Long"))
        assertTrue(viewModel.contains("tokenUsageRepository.records.collect"))
        assertTrue(viewModel.contains("cachedInputTokens = entries.sumOf"))
        assertTrue(viewModel.contains("cacheReportedInputTokens = entries.sumOf"))
        assertTrue(viewModel.contains("val cacheHitRate: Float?"))
        assertTrue(screen.contains("collectAsStateWithLifecycle"))
        assertTrue(screen.contains("settings_cache_hit_rate"))
        assertTrue(chart.contains("settings_total_tokens"))
        assertTrue(chart.contains("drawRoundRect"))
        assertTrue(chart.contains("settings_cache_hit_rate"))
        assertTrue(chart.contains("usage.cacheHitRate"))
        assertTrue(chart.contains("drawPath(cachePath"))
        assertTrue(chart.contains("clipPath(cachePath"))
        assertTrue(chart.contains("cacheHatchColor"))
        assertTrue(chart.contains("val cacheTop = top + hTotal - hCache"))
        assertTrue(chart.contains("val cacheBottom = size.height"))
        assertFalse(chart.contains("HorizontalUsageBar"))
        assertFalse(screen.contains("ChartLegendLine"))
        assertFalse(chart.contains("settings_prompt_tokens"))
        assertFalse(chart.contains("settings_completion_tokens"))
        assertFalse(screen.contains("settings_cached_input_tokens"))
        assertFalse(screen.contains("settings_cache_hit_calls"))
        assertFalse(screen.contains("settings_usage_unavailable_calls"))
        assertTrue(strings.contains("settings_total_calls\">模型调用</string>"))
        assertTrue(strings.contains("settings_cache_hit_rate\">缓存命中率</string>"))
    }

    private fun projectFile(path: String): File {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            File(current, path).takeIf { it.exists() }?.let { return it }
            current = current.parentFile ?: error("Cannot locate $path")
        }
    }
}
