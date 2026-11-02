// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    private val notes = mapOf("en" to "### Fixed\n\n- Input.", "zh" to "### 修复\n\n- 输入。")

    @Test fun selectsExactlyOneLanguageAndCanChangeWithoutRefetching() {
        assertEquals("### Fixed\n\n- Input.", ReleaseNotes.forLanguage(notes, "en"))
        assertEquals("### 修复\n\n- 输入。", ReleaseNotes.forLanguage(notes, "zh"))
        assertEquals("### Fixed\n\n- Input.", ReleaseNotes.forLanguage(notes, "en"))
    }

    @Test fun chineseRegionalTagsUseChineseAndOtherLanguagesUseEnglish() {
        for (tag in listOf("zh-CN", "zh-TW", "ZH-Hans")) {
            assertEquals(notes["zh"], ReleaseNotes.forLanguage(notes, tag))
        }
        assertEquals(notes["en"], ReleaseNotes.forLanguage(notes, "fr"))
    }
}
