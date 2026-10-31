// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.theme

import androidx.compose.ui.graphics.Color

// ── 中性灰阶（黑白灰基础）────────────────────────────────────
// 简洁科技感的底子：背景/卡片/文字只走中性灰，不抢戏。
val Neutral0 = Color(0xFFFFFFFF)
val Neutral50 = Color(0xFFFAFAFA)
val Neutral75 = Color(0xFFF5F5F5)
val Neutral90 = Color(0xFFF2F2F2)
val Neutral100 = Color(0xFFF0F0F0)
val Neutral150 = Color(0xFFE6E6E6)
val Neutral200 = Color(0xFFDADADA)
val Neutral300 = Color(0xFFD4D4D4)
val Neutral400 = Color(0xFFA8A8A8)
val Neutral500 = Color(0xFF686868)
val Neutral600 = Color(0xFF525252)
val Neutral700 = Color(0xFF363636)
val Neutral750 = Color(0xFF303030)
val Neutral800 = Color(0xFF272727)
val Neutral850 = Color(0xFF1B1B1B)
val Neutral900 = Color(0xFF202020)
val Neutral950 = Color(0xFF101010)

// ── 功能强调色（克制使用，只在"有意义的地方"点一下）──────────
val AccentBlue = Color(0xFF0A84FF) // iOS 蓝：开关/选中/链接/进度等交互信号；深浅主题都清晰
val AccentBlueVariant = Color(0xFF007AFF)
val AccentBlueContainerLight = Color(0xFFDCEEFF)
val AccentBlueContainerDark = Color(0xFF163350)
val StatusSuccess = Color(0xFF34C759) // 绿：连接/开启
val StatusWarning = Color(0xFFFF9F0A) // 琥珀：警告
val StatusError = Color(0xFFFF3B30) // 红：错误
val StatusErrorContainerDark = Color(0xFF321C1B)
val StatusOnErrorContainerDark = Color(0xFFFFDAD6)

// ── Droidance 语义入口 ──────────────────────────────────────
// DroidancePrimary 是"功能强调色"(蓝)：图标/开关/进度/选中。主按钮的石墨黑走
// MaterialTheme.colorScheme.primary（见 Theme.kt），不再用本 token。
val DroidancePrimary = AccentBlue
val DroidanceBackground = Neutral950
val DroidanceSurface = Neutral850
val DroidanceSurfaceVariant = Neutral800
val DroidanceOnBackground = Color(0xFFEDEDED)
val DroidanceOnSurface = Color(0xFFEDEDED)

// 语义色
val DroidanceSuccess = StatusSuccess
val DroidanceWarning = StatusWarning
val DroidanceError = StatusError
val DroidanceInfo = AccentBlue

// 浅色主题色
// 画布压低一档以减少整屏亮度（OLED 上大面积近白很刺眼）；卡片与浮层逐级只亮一小步，全界面不出现纯白。
// 灰阶带极轻暖调，避免浅底上的冷硬感。
val LightCanvas = Color(0xFFEFEFEC)
val LightCard = Color(0xFFF8F8F5)
val LightFloating = Color(0xFFFCFCFA)
val LightContainerLow = Color(0xFFF3F3F0)
val LightContainer = Color(0xFFEAEAE7)
val LightContainerHigh = Color(0xFFE3E3E0)
val LightContainerHighest = Color(0xFFD8D8D5)
val DroidanceLightBackground = LightCanvas
