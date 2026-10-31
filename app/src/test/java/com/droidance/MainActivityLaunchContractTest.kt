// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityLaunchContractTest {
    @Test
    fun debugBuildCanAutoSendPromptFromAdbIntent() {
        val mainActivity = projectFile("app/src/main/java/com/droidance/MainActivity.kt").readText()
        val navigation = projectFile("app/src/main/java/com/droidance/ui/navigation/AppNavigation.kt").readText()

        assertTrue(mainActivity.contains("BuildConfig.DEBUG"))
        assertTrue(mainActivity.contains("EXTRA_DEBUG_PROMPT"))
        assertTrue(mainActivity.contains("EXTRA_DEBUG_AUTO_SEND"))
        assertTrue(mainActivity.contains("initialPrompt = debugPrompt"))
        assertTrue(navigation.contains("initialPrompt: String? = null"))
        assertTrue(navigation.contains("const val CHAT_NEW = \"home/new-chat?threadId={threadId}&prompt={prompt}&autoSend={autoSend}&skillId={skillId}&skillDraft={skillDraft}\""))
        assertTrue(navigation.contains("route = SubRoutes.CHAT_NEW"))
        assertTrue(navigation.contains("val routePrompt = backStackEntry.arguments?.getString(\"prompt\")"))
        assertTrue(navigation.contains("val routeAutoSend = backStackEntry.arguments?.getBoolean(\"autoSend\") ?: false"))
        assertTrue(navigation.contains("LaunchedEffect(routePrompt, routeAutoSend)"))
        assertTrue(navigation.contains("if (routePrompt != null && routeAutoSend)"))
        assertTrue(navigation.contains("chatVm.triggerAutoSend(routePrompt)"))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
