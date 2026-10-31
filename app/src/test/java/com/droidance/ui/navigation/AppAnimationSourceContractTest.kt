// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.navigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAnimationSourceContractTest {
    @Test
    fun navigationUsesShortSharedAxisPageTransitions() {
        val source = File("src/main/java/com/droidance/ui/navigation/AppNavigation.kt").readText()

        assertTrue(source.contains("enterTransition = { pageEnter(forward = true) }"))
        assertTrue(source.contains("exitTransition = { pageExit() }"))
        assertTrue(source.contains("popEnterTransition = { pageEnter(forward = false) }"))
        assertTrue(source.contains("popExitTransition = { pageExit() + slideOutHorizontally("))
        // No library-default crossfade: every page fade and slide is bounded by the shared motion tokens.
        assertFalse(Regex("""fade(In|Out)\((?!tween\(AppMotion\.)""").containsMatchIn(source))
        assertTrue(source.contains("tween(AppMotion.PageMillis, easing = AppMotion.Settle)"))
    }

    @Test
    fun commonExpandedSurfacesUseSharedMotionTokensForFades() {
        val sources = listOf(
            "src/main/java/com/droidance/ui/screens/OnboardingScreen.kt",
            "src/main/java/com/droidance/ui/screens/home/ChatComposer.kt",
            "src/main/java/com/droidance/ui/screens/home/ChatMessageContent.kt"
        ).joinToString("\n") { File(it).readText() }

        // Fades are allowed on these surfaces only with the shared AppMotion durations.
        assertFalse(Regex("""fade(In|Out)\((?!tween\(AppMotion\.)""").containsMatchIn(sources))
    }

    @Test
    fun mainActivityRendersNavigationImmediately() {
        val mainActivity = File("src/main/java/com/droidance/MainActivity.kt").readText()

        assertTrue(mainActivity.contains("AppNavigation("))
        assertFalse(mainActivity.contains("mutableStateOf(true)"))
        assertFalse(mainActivity.contains("LaunchedEffect("))
    }

    @Test
    fun launchWindowUsesThemeColorWithoutVisibleSplashAnimation() {
        val theme = File("src/main/res/values/themes.xml").readText()
        val api31Theme = File("src/main/res/values-v31/themes.xml").readText()
        val emptyIcon = File("src/main/res/drawable/droidance_empty_splash_icon.xml").readText()

        assertTrue(theme.contains("android:windowBackground\">@color/droidance_launch_background"))
        assertTrue(theme.contains("android:windowAnimationStyle\">@android:style/Animation"))
        assertTrue(api31Theme.contains("android:windowSplashScreenBackground\">@color/droidance_launch_background"))
        assertTrue(api31Theme.contains("android:windowSplashScreenAnimatedIcon\">@drawable/droidance_empty_splash_icon"))
        assertTrue(api31Theme.contains("android:windowSplashScreenAnimationDuration\">0"))
        assertTrue(emptyIcon.contains("android:fillColor=\"@android:color/transparent\""))
    }
}
