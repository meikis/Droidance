// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.theme

import android.app.Activity
import com.droidance.settings.AppThemeMode
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// 深色配色方案：近黑底 + 近白主操作（按钮）+ 蓝色功能强调
private val DarkColorScheme = darkColorScheme(
    primary = Neutral50,          // 主按钮/Material 填充：深色下用近白，文字深色
    onPrimary = Neutral950,
    primaryContainer = Neutral800,
    onPrimaryContainer = Neutral50,
    secondary = Neutral400,
    onSecondary = Neutral900,
    secondaryContainer = Neutral800,
    onSecondaryContainer = Neutral300,
    tertiary = AccentBlue,        // 功能强调蓝
    onTertiary = Neutral0,
    tertiaryContainer = AccentBlueContainerDark,
    onTertiaryContainer = AccentBlue,
    background = DroidanceBackground,
    surface = DroidanceSurface,
    surfaceVariant = Neutral800,
    surfaceDim = Neutral950,
    surfaceBright = Neutral800,
    surfaceContainerLowest = Neutral950,
    surfaceContainerLow = Neutral850,
    surfaceContainer = Neutral850,
    surfaceContainerHigh = Neutral800,
    surfaceContainerHighest = Neutral750,
    surfaceTint = Color.Transparent,
    onBackground = DroidanceOnBackground,
    onSurface = DroidanceOnSurface,
    onSurfaceVariant = Neutral400,
    outline = Neutral700,
    outlineVariant = Neutral750,
    error = StatusError,
    onError = Neutral0,
    errorContainer = StatusErrorContainerDark,
    onErrorContainer = StatusOnErrorContainerDark,
)

// 浅色配色方案：极简浅底 + 石墨黑主操作（按钮）+ 蓝色功能强调
private val LightColorScheme = lightColorScheme(
    primary = Neutral900,         // 主按钮/强调：石墨黑
    onPrimary = Neutral0,
    primaryContainer = Neutral200,
    onPrimaryContainer = Neutral900,
    secondary = Neutral600,
    onSecondary = Neutral0,
    secondaryContainer = Neutral100,
    onSecondaryContainer = Neutral700,
    tertiary = AccentBlue,        // 功能强调蓝
    onTertiary = Neutral0,
    tertiaryContainer = AccentBlueContainerLight,
    onTertiaryContainer = AccentBlueVariant,
    // 画布 < 卡片 < 浮层逐级只亮一小步；浮层 LightFloating 见 droidanceFloatingSurfaceColor。
    background = LightCanvas,
    surface = LightCard,
    surfaceVariant = LightContainerHigh,
    surfaceDim = LightContainerHigh,
    surfaceBright = LightFloating,
    surfaceContainerLowest = LightFloating,
    surfaceContainerLow = LightContainerLow,
    surfaceContainer = LightContainer,
    surfaceContainerHigh = LightContainerHigh,
    surfaceContainerHighest = LightContainerHighest,
    surfaceTint = Color.Transparent,
    onBackground = Neutral900,
    onSurface = Neutral900,
    onSurfaceVariant = Neutral500,
    outline = LightContainerHighest,
    outlineVariant = LightContainerHigh,
    error = StatusError,
    onError = Neutral0,
)

/**
 * 让子组件感知当前是否深色模式（用于玻璃效果等）
 */
val LocalAppIsDark = staticCompositionLocalOf { false }

/**
 * Droidance 主题
 * Consumes the theme mode supplied by the settings repository.
 */
@Composable
fun DroidanceTheme(
    themeMode: AppThemeMode,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

    // 设置状态栏样式
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
        }
    }

    // Material3 未覆盖时，文字选中高亮默认继承 colorScheme.primary——浅色主题下 primary 是石墨黑
    // (见上方 LightColorScheme)，选中文字会像被涂了一块深黑色，又深又难看。选中本就是 AccentBlue
    // 注释里写明的用途之一，这里显式覆盖成蓝色半透明高亮，深浅主题都统一好看。
    val selectionColors = TextSelectionColors(
        handleColor = AccentBlue,
        backgroundColor = AccentBlue.copy(alpha = 0.35f)
    )

    CompositionLocalProvider(
        LocalAppIsDark provides isDark,
        LocalAppMotionEnabled provides rememberAppMotionEnabled(),
        LocalTextSelectionColors provides selectionColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = remember(isDark) { droidanceTypography(isDark) },
            shapes = DroidanceShapes,
            content = content
        )
    }
}
