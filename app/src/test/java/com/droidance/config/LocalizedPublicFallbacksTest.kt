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

    @Test fun authenticationNoticePointsAtTheKeyWithoutProviderDetails() {
        notices.validate()
        val chinese = notices.text("zh", "termination_model_authentication")
        val english = notices.text("en", "termination_model_authentication")
        assertTrue(chinese.contains("检查密钥"))
        assertTrue(chinese.contains("尚未完成"))
        assertTrue(english.contains("Check the key"))
        assertTrue(english.contains("incomplete"))
        for (text in listOf(chinese, english)) {
            assertFalse(text.contains("401"))
            assertFalse(text.contains("http"))
        }
    }

    @Test fun permissionNoticesPointAtKeyPermissionsOrPlanAndOnlyOneCarriesTheReason() {
        notices.validate()
        for (language in listOf("zh", "en")) {
            val plain = notices.text(language, "termination_model_permission_denied")
            val withReason = notices.text(language, "termination_model_permission_denied_reason")
            assertTrue(withReason.startsWith(plain))
            assertFalse(plain.contains("{reason}"))
            assertTrue(withReason.endsWith("{reason}"))
        }
        assertTrue(notices.text("zh", "termination_model_permission_denied").contains("权限或套餐"))
        assertTrue(notices.text("en", "termination_model_permission_denied").contains("permissions or plan"))
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
