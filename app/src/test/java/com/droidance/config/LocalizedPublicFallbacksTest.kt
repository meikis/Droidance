// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import com.droidance.core.agent.config.*
import com.droidance.core.device.config.*

import com.droidance.config.*

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LocalizedPublicFallbacksTest {
    private val notices = Json.decodeFromString<LocalizedPublicFallbacks>(markdownSection(
        File("src/main/assets/rules/public_fallbacks.md").readText(), "translations"
    ))

    @Test fun bothLanguagesHaveCompleteNonemptyRuntimeNotices() {
        notices.validate()
        assertTrue(notices.termination("zh", "guard_stop").contains("尚未完成"))
        assertTrue(notices.termination("en", "guard_stop").contains("execution guard stopped"))
        assertEquals(notices.text("zh", "termination_default"), notices.termination("zh", "unknown_reason"))
        assertEquals(notices.text("en", "termination_default"), notices.termination("en", "unknown_reason"))
    }

    @Test fun billingNoticeExplainsTheAccountCheckInBothLanguagesWithoutProviderDetails() {
        notices.validate()
        val chinese = notices.text("zh", "termination_model_billing")
        val english = notices.text("en", "termination_model_billing")
        assertTrue(chinese.contains("额度"))
        assertTrue(chinese.contains("请检查"))
        assertTrue(chinese.contains("尚未完成"))
        assertTrue(english.contains("credits"))
        assertTrue(english.contains("Check"))
        assertTrue(english.contains("incomplete"))
        for (text in listOf(chinese, english)) {
            assertFalse(text.contains("402"))
            assertFalse(text.contains("OpenRouter"))
            assertFalse(text.contains("LLM call failed"))
            assertFalse(text.contains("http"))
        }
    }

    @Test fun imageInputNoticeRequiresAnImageCapableServiceWithoutChangingUserSettings() {
        notices.validate()
        val chinese = notices.text("zh", "termination_model_image_unavailable")
        val english = notices.text("en", "termination_model_image_unavailable")
        assertTrue(chinese.contains("请选择支持图像输入"))
        assertTrue(chinese.contains("尚未完成"))
        assertTrue(english.contains("Select a service that supports image input"))
        assertTrue(english.contains("incomplete"))
        for (text in listOf(chinese, english)) {
            assertFalse(text.contains("404"))
            assertFalse(text.contains("OpenRouter"))
            assertFalse(text.contains("OCR"))
            assertFalse(text.contains("http"))
        }
    }

    @Test fun missingTranslationsCannotSilentlyFallBackToAnotherLanguage() {
        val missing = notices.copy(translations = notices.translations +
            ("zh" to notices.translations.getValue("zh").minus("termination_guard_stop")))
        assertThrows(IllegalArgumentException::class.java) { missing.validate() }
        assertThrows(IllegalStateException::class.java) { notices.text("zh", "unknown_key") }
    }
}
