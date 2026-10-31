// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.navigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDrawerSourceContractTest {
    private val source = File("src/main/java/com/droidance/ui/navigation/AppNavigation.kt").readText()
    private val drawerSource = File("src/main/java/com/droidance/ui/navigation/AppDrawerContent.kt").readText()

    @Test
    fun drawer_uses_three_quarters_width() {
        assertTrue(source.contains("configuration.screenWidthDp * 3 / 4"))
    }

    @Test
    fun conversation_rows_offer_share_and_delete_actions_without_left_avatar() {
        assertTrue(drawerSource.contains("R.string.drawer_share_html"))
        assertTrue(drawerSource.contains("R.string.drawer_share_markdown"))
        assertTrue(drawerSource.contains("R.string.common_delete"))
        assertFalse(drawerSource.contains("Icons.Default.ChatBubble"))
    }

    @Test
    fun drawer_promotes_skills_and_tasks_without_duplicate_new_chat_card() {
        assertTrue(drawerSource.contains("R.string.drawer_skills"))
        assertTrue(drawerSource.contains("R.string.drawer_tasks"))
        val navChip = drawerSource.substringAfter("private fun DrawerNavChip(")
            .substringBefore("@OptIn(ExperimentalFoundationApi::class)")
        assertTrue(navChip.contains("MaterialTheme.colorScheme.onSurface"))
        assertTrue(navChip.contains("shadowElevation = 0.dp"))
        assertFalse(navChip.contains("Brush.verticalGradient"))
        assertFalse(navChip.contains("colorScheme.tertiary"))
        assertFalse(navChip.contains(".border("))
        assertFalse(drawerSource.contains("R.string.home_new_conversation"))
        assertFalse(drawerSource.contains("pendingNewChatWarning"))
    }

    @Test
    fun drawer_shows_public_version_and_update_arrow() {
        assertTrue(drawerSource.contains("\"v\${com.droidance.BuildConfig.VERSION_NAME}\""))
        assertTrue(drawerSource.contains("if (availableRelease != null)"))
        assertTrue(drawerSource.contains("DroidanceIcons.Download"))
    }

    @Test
    fun dark_drawer_uses_separate_surface_and_edge_border() {
        // Dark keeps its own lifted surface; light paints the sheet in the page canvas grey, never white.
        assertTrue(source.contains("drawerContainerColor = if (isDark) DroidanceSurfaceVariant else MaterialTheme.colorScheme.background"))
        assertFalse(source.contains("drawerContainerColor = if (isDark) DroidanceSurfaceVariant else Color.White"))
        assertTrue(source.contains("drawerEdgeColor"))
        assertTrue(source.contains(".border(1.dp, drawerEdgeColor, RectangleShape)"))
        assertTrue(source.contains(".shadow("))
    }

    @Test
    fun debug_launch_prompt_starts_a_fresh_chat_before_restoring_history() {
        val startDestinationBlock = source.substringAfter(
            "val startDestination = remember(normalizedInitialPrompt, initialAutoSend, lastConversationId) {"
        )
            .substringBefore("\n    }\n\n    val navBackStackEntry")

        assertTrue(
            startDestinationBlock.indexOf("normalizedInitialPrompt != null") <
                startDestinationBlock.indexOf("lastConversationId != null")
        )
    }

    @Test
    fun new_chat_route_uses_distinct_prefix_from_existing_chat_route() {
        assertTrue(source.contains("const val CHAT_NEW = \"home/new-chat?threadId={threadId}&prompt={prompt}&autoSend={autoSend}&skillId={skillId}&skillDraft={skillDraft}\""))
        assertTrue(source.contains("const val CHAT_ID = \"home/chat/{conversationId}?turnId={turnId}\""))
        assertTrue(source.contains("currentRoute.startsWith(\"home/new-chat\")"))
    }

    @Test
    fun explicit_new_chat_replaces_the_current_entry_and_thread() {
        assertTrue(source.contains("fun NavHostController.navigateToNewConversation("))
        assertTrue(source.contains("threadId=\${Uri.encode(threadId)}"))
        assertTrue(source.contains("popUpTo(currentDestinationId) { inclusive = true }"))
        val homeViewModel = projectFile("app/src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt").readText()
        assertTrue(homeViewModel.contains("savedStateHandle.get<String>(\"threadId\")"))
        assertFalse(homeViewModel.contains("fun startNewConversation()"))
    }

    @Test
    fun conversation_navigation_and_sidebar_use_event_store_projections() {
        val mainActivity = projectFile("app/src/main/java/com/droidance/MainActivity.kt").readText()
        val listViewModel = projectFile(
            "app/src/main/java/com/droidance/ui/viewmodel/ConversationListViewModel.kt"
        ).readText()
        val eventStore = projectFile(
            "core/persistence/src/main/java/com/droidance/core/persistence/agent/event/RoomAgentEventStore.kt"
        ).readText()

        assertTrue(mainActivity.contains("eventStore.latestConversationThreadId()"))
        assertFalse(mainActivity.contains("eventStore.activeThreadReadModels()"))
        assertFalse(mainActivity.contains("ConversationManager"))
        assertTrue(listViewModel.contains("eventStore.observeActiveThreadReadModels()"))
        assertTrue(listViewModel.contains("historyDeletion.delete(setOf(id))"))
        assertFalse(listViewModel.contains("ConversationManager"))
        assertTrue(eventStore.contains("AgentThreadProjectionResult.fromEvents(events)"))
        assertTrue(eventStore.contains("threadIds.map(::observeThreadProjectionResult)"))
    }

    @Test
    fun drawer_click_on_current_conversation_does_not_reload() {
        assertTrue(source.contains("currentConversationId = currentConversationId"))
        assertTrue(drawerSource.contains("currentConversationId: String? = null"))
        assertTrue(drawerSource.contains("if (conv.id == currentConversationId) {"))
        assertTrue(drawerSource.contains("conv.id == currentConversationId"))
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
