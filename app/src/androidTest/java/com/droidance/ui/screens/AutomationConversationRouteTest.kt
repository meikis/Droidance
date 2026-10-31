// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.droidance.ui.navigation.SubRoutes
import org.junit.Rule
import org.junit.Test

class AutomationConversationRouteTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chatRouteOpensTheExistingThreadAndBoundTurnWithEncodedIdentifiers() {
        compose.setContent {
            val navigation = rememberNavController()
            NavHost(navigation, startDestination = "fixture") {
                composable("fixture") {
                    LaunchedEffect(Unit) { navigation.navigate(SubRoutes.buildChatRoute("thread/with space", "turn?with&query")) }
                }
                composable(SubRoutes.CHAT_ID, arguments = listOf(
                    navArgument("conversationId") { type = NavType.StringType },
                    navArgument("turnId") { type = NavType.StringType; nullable = true; defaultValue = null }
                )) { entry ->
                    Text("${entry.arguments?.getString("conversationId")}|${entry.arguments?.getString("turnId")}")
                }
            }
        }
        compose.onNodeWithText("thread/with space|turn?with&query").assertIsDisplayed()
    }

    @Test fun ordinaryHistoryNavigationStillOpensTheThreadWithoutATargetTurn() {
        compose.setContent {
            val navigation = rememberNavController()
            NavHost(navigation, startDestination = "fixture") {
                composable("fixture") {
                    LaunchedEffect(Unit) { navigation.navigate(SubRoutes.buildChatRoute("retained-thread")) }
                }
                composable(SubRoutes.CHAT_ID, arguments = listOf(
                    navArgument("conversationId") { type = NavType.StringType },
                    navArgument("turnId") { type = NavType.StringType; nullable = true; defaultValue = null }
                )) { entry ->
                    Text("${entry.arguments?.getString("conversationId")}|${entry.arguments?.getString("turnId")}")
                }
            }
        }
        compose.onNodeWithText("retained-thread|null").assertIsDisplayed()
    }
}
