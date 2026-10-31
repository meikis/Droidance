// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import com.droidance.ui.screens.home.*
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTimestampFormatterTest {
    private val zone = ZoneId.systemDefault()

    @Test
    fun same_day_messages_use_time_only() {
        val now = LocalDateTime.of(2026, 5, 16, 20, 30).atZone(zone).toInstant().toEpochMilli()
        val messageTime = LocalDateTime.of(2026, 5, 16, 9, 5).atZone(zone).toInstant().toEpochMilli()

        assertEquals("09:05", formatMessageTimestamp(messageTime, "09:05", now))
    }

    @Test
    fun previous_day_messages_follow_the_english_locale() {
        val now = LocalDateTime.of(2026, 5, 16, 20, 30).atZone(zone).toInstant().toEpochMilli()
        val messageTime = LocalDateTime.of(2026, 5, 15, 22, 19).atZone(zone).toInstant().toEpochMilli()

        assertEquals("May 15, 2026, 10:19 PM", formatMessageTimestamp(messageTime, "22:19", now, java.util.Locale.ENGLISH).replace('\u202f', ' '))
    }

    @Test
    fun messages_older_than_one_year_include_year() {
        val now = LocalDateTime.of(2026, 6, 16, 20, 30).atZone(zone).toInstant().toEpochMilli()
        val messageTime = LocalDateTime.of(2025, 5, 15, 22, 19).atZone(zone).toInstant().toEpochMilli()

        assertEquals("2025年5月15日 22:19", formatMessageTimestamp(messageTime, "22:19", now, java.util.Locale.SIMPLIFIED_CHINESE).replace('\u202f', ' '))
    }

    @Test
    fun legacy_messages_without_epoch_keep_original_label() {
        assertEquals("22:19", formatMessageTimestamp(0L, "22:19", 1L))
    }
}
