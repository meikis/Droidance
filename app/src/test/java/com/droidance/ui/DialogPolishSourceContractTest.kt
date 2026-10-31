// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogPolishSourceContractTest {
    private val bareAlertDialogCall = Regex("""(^|\s)AlertDialog\(""")

    @Test
    fun providerModelMenuAndDeleteDialogUseDrawerStyleSurfaces() {
        val providerList = projectFile("app/src/main/java/com/droidance/ui/screens/ProviderListScreen.kt").readText()

        assertTrue(providerList.contains("GlassAlertDialog("))
        assertFalse(providerList.contains("R.string.provider_default_scope"))
        assertFalse(bareAlertDialogCall.containsMatchIn(providerList))
        assertTrue(providerList.contains("DroidanceDropdownMenu("))
        val components = projectFile("app/src/main/java/com/droidance/ui/components/DroidanceDesignComponents.kt").readText()
        val menu = components.substringAfter("fun DroidanceDropdownMenu(").substringBefore("fun DroidanceSnackbar(")
        assertTrue(menu.contains("containerColor = droidanceFloatingSurfaceColor()"))
        assertTrue(menu.contains("tonalElevation = 0.dp"))
        assertTrue(menu.contains("shadowElevation = 4.dp"))
    }

    @Test
    fun conversationDeleteAndDeveloperClearDialogsUseGlassAlertDialog() {
        val conversationList = projectFile("app/src/main/java/com/droidance/ui/navigation/AppDrawerContent.kt").readText()
        val settings = projectFile("app/src/main/java/com/droidance/ui/screens/SettingsDataManagementScreen.kt").readText()

        assertTrue(conversationList.contains("DroidanceDialog("))
        assertFalse(bareAlertDialogCall.containsMatchIn(conversationList))
        val clearDialogBlock = settings.substringAfter("GlassAlertDialog(").substringBefore("SubScreenScaffold(")
        assertTrue(clearDialogBlock.contains("destructive = true"))
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
