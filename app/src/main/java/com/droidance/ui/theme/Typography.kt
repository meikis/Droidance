// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.LineBreak

// Calibrate emphasis independently; body text keeps its regular weight in both themes.
fun droidanceTypography(isDark: Boolean): Typography {
    val medium = FontWeight(if (isDark) 480 else 500)
    val semibold = FontWeight(if (isDark) 580 else 600)
    val bold = FontWeight(if (isDark) 660 else 700)
    return Typography(
        displayLarge = TextStyle(
            fontWeight = bold,
            lineBreak = LineBreak.Heading,
            fontSize = 28.sp,
            lineHeight = 36.sp,
            letterSpacing = 0.sp
        ),
        headlineLarge = TextStyle(
            fontWeight = bold,
            lineBreak = LineBreak.Heading,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            letterSpacing = 0.sp
        ),
        headlineMedium = TextStyle(
            fontWeight = semibold,
            lineBreak = LineBreak.Heading,
            fontSize = 22.sp,
            lineHeight = 30.sp,
            letterSpacing = 0.sp
        ),
        titleLarge = TextStyle(
            fontWeight = semibold,
            lineBreak = LineBreak.Heading,
            fontSize = 18.sp,
            lineHeight = 26.sp,
            letterSpacing = 0.sp
        ),
        titleMedium = TextStyle(
            fontWeight = medium,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.sp
        ),
        bodyLarge = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.sp
        ),
        bodyMedium = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.sp
        ),
        bodySmall = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.sp,
            fontFeatureSettings = "tnum"
        ),
        labelLarge = TextStyle(
            fontWeight = medium,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.sp,
            fontFeatureSettings = "tnum"
        ),
        labelMedium = TextStyle(
            fontWeight = medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.sp,
            fontFeatureSettings = "tnum"
        ),
        labelSmall = TextStyle(
            fontWeight = medium,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.sp,
            fontFeatureSettings = "tnum"
        )
    )
}
