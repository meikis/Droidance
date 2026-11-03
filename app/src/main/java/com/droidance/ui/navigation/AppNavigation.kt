// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.navigation

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.droidance.R
import com.droidance.agent.AgentRuntime
import com.droidance.core.agent.AgentState
import com.droidance.core.knowledge.agent.device.DeviceProfileStore
import com.droidance.ui.screens.*
import com.droidance.ui.components.DroidanceSnackbar
import com.droidance.ui.connection.DeviceConnectionDialog
import com.droidance.ui.connection.DeviceConnectionStatus
import com.droidance.ui.connection.DeviceConnectionViewModel
import com.droidance.ui.theme.AppMotion
import com.droidance.ui.theme.DroidanceSurfaceVariant
import com.droidance.ui.theme.LocalAppIsDark
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 子页面路由
 */
object SubRoutes {
    const val ONBOARDING = "onboarding"
    const val CHAT_NEW = "home/new-chat?threadId={threadId}&prompt={prompt}&autoSend={autoSend}&skillId={skillId}&skillDraft={skillDraft}"
    const val CHAT_ID = "home/chat/{conversationId}?turnId={turnId}"
    const val TASKS = "tasks"
    const val AUTOMATION_CREATE = "tasks/create?prompt={prompt}"
    const val SKILLS = "skills"
    const val SKILL_DETAIL = "skills/detail?key={key}"
    const val SETTINGS = "settings"
    const val PROVIDER_LIST = "settings/providers"
    const val PROVIDER_EDIT = "settings/providers/edit"
    const val PROVIDER_EDIT_ID = "settings/providers/edit/{id}"
    const val MEMORY = "settings/memory"
    const val AGENTS = "settings/agents"
    const val SETTINGS_DEVICE_CAPABILITIES = "settings/device-capabilities"
    const val AIR_PAIRING = "device/air-pairing"
    const val SETTINGS_PERMISSIONS = "settings/permissions"
    const val SETTINGS_THEME = "settings/theme"
    const val SETTINGS_LANGUAGE = "settings/language"
    const val SETTINGS_DATA_MANAGEMENT = "settings/data-management"
    const val SETTINGS_ABOUT = "settings/about"

    fun buildNewChatRoute(
        prompt: String? = null,
        autoSend: Boolean = false,
        threadId: String = UUID.randomUUID().toString(),
        skillId: String? = null,
        skillDraft: Boolean = false
    ): String {
        val encodedPrompt = prompt?.takeIf { it.isNotBlank() }?.let(Uri::encode)
        return buildString {
            append("home/new-chat")
            val params = mutableListOf("threadId=${Uri.encode(threadId)}")
            if (encodedPrompt != null) params += "prompt=$encodedPrompt"
            if (autoSend) params += "autoSend=true"
            if (skillId != null) {
                params += "skillId=${Uri.encode(skillId)}"
                params += "skillDraft=$skillDraft"
            }
            append("?${params.joinToString("&")}")
        }
    }

    fun buildChatRoute(conversationId: String, turnId: String? = null): String =
        "home/chat/${Uri.encode(conversationId)}" +
            (turnId?.takeIf { it.isNotBlank() }?.let { "?turnId=${Uri.encode(it)}" } ?: "")
}

private fun NavHostController.navigateToNewConversation(
    prompt: String? = null,
    autoSend: Boolean = false,
    skillId: String? = null,
    skillDraft: Boolean = false
) {
    val currentDestinationId = currentDestination?.id
    navigate(SubRoutes.buildNewChatRoute(prompt, autoSend, skillId = skillId, skillDraft = skillDraft)) {
        // 显式新对话必须得到新的 back-stack entry，不能复用旧 HomeViewModel。
        if (currentDestinationId != null) {
            popUpTo(currentDestinationId) { inclusive = true }
        }
        launchSingleTop = true
    }
}

/**
 * App 主导航 — 抽屉式布局，无底部导航栏
 */
@Composable
fun AppNavigation(
    deviceProfileStore: DeviceProfileStore,
    agentRuntime: AgentRuntime,
    lastConversationId: String? = null,
    initialPrompt: String? = null,
    initialAutoSend: Boolean = false
) = Box(Modifier.fillMaxSize()) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val connectionViewModel: DeviceConnectionViewModel = hiltViewModel()
    val connectionState by connectionViewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(lifecycleOwner, connectionViewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            connectionViewModel.monitor()
        }
    }
    val normalizedInitialPrompt = initialPrompt?.trim()?.takeIf { it.isNotBlank() }

    // 启动决策：任务正在跑 → resume；有历史对话 → resume 最近一条；否则新建
    val startDestination = remember(normalizedInitialPrompt, initialAutoSend, lastConversationId) {
        when {
            !deviceProfileStore.isOnboardingComplete() -> SubRoutes.ONBOARDING
            normalizedInitialPrompt != null ->
                SubRoutes.buildNewChatRoute(normalizedInitialPrompt, initialAutoSend)
            agentRuntime.currentConversationId.value != null &&
                    agentRuntime.state.value != AgentState.IDLE ->
                SubRoutes.buildChatRoute(requireNotNull(agentRuntime.currentConversationId.value))
            lastConversationId != null -> SubRoutes.buildChatRoute(lastConversationId)
            else -> SubRoutes.buildNewChatRoute()
        }
    }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: ""
    val isChatRoute = currentRoute.startsWith("home/chat") || currentRoute.startsWith("home/new-chat")
    val showConnectionFeedback = deviceProfileStore.isOnboardingComplete() &&
        currentRoute != SubRoutes.AIR_PAIRING && lifecycle == Lifecycle.State.RESUMED
    LaunchedEffect(connectionViewModel, context, showConnectionFeedback) {
        connectionViewModel.events.collect { message ->
            // Pairing has its own status; do not replay old recovery messages after leaving it.
            if (showConnectionFeedback && (message != R.string.connection_restored ||
                    connectionViewModel.state.value.status == DeviceConnectionStatus.READY)) {
                snackbarHostState.showSnackbar(
                    androidx.core.content.ContextCompat.getContextForLanguage(context).getString(message),
                    duration = SnackbarDuration.Short
                )
            }
        }
    }
    val currentConversationId = remember(navBackStackEntry) {
        navBackStackEntry?.arguments?.getString("conversationId")
            ?: navBackStackEntry?.arguments?.getString("threadId")?.takeIf { it.isNotBlank() }
    }

    // 在对话页拦截系统返回手势 → 先弹出抽屉；抽屉已开时由 ModalNavigationDrawer 自动关闭
    BackHandler(enabled = isChatRoute && !drawerState.isOpen) {
        scope.launch { drawerState.open() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isChatRoute,
        // A lighter light-mode scrim keeps the sheet and the dimmed page close in brightness.
        scrimColor = Color.Black.copy(alpha = if (LocalAppIsDark.current) 0.32f else 0.18f),
        drawerContent = {
            val isDark = LocalAppIsDark.current
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val drawerWidth = (configuration.screenWidthDp * 3 / 4).dp
            // The scrim and shadow already separate the sheet; the edge is only a faint hairline.
            val drawerEdgeColor = if (isDark) {
                Color.White.copy(alpha = 0.07f)
            } else {
                Color.Black.copy(alpha = 0.05f)
            }
            ModalDrawerSheet(
                drawerShape = RectangleShape,
                // Light mode paints this large sheet in the page's own canvas grey; a near-white sheet beside the
                // scrimmed page reads as a glaring panel. Shadow and hairline edge still lift it.
                drawerContainerColor = if (isDark) DroidanceSurfaceVariant else MaterialTheme.colorScheme.background,
                windowInsets = WindowInsets(0),
                modifier = Modifier
                    .width(drawerWidth)
                    .shadow(
                        elevation = if (isDark) 18.dp else 10.dp,
                        shape = RectangleShape,
                        ambientColor = Color.Black.copy(alpha = if (isDark) 0.52f else 0.14f),
                        spotColor = Color.Black.copy(alpha = if (isDark) 0.52f else 0.14f)
                    )
                    .border(1.dp, drawerEdgeColor, RectangleShape)
            ) {
                AppDrawerContent(
                    navController = navController,
                    currentRoute = currentRoute,
                    currentConversationId = currentConversationId,
                    visible = drawerState.currentValue == DrawerValue.Open || drawerState.targetValue == DrawerValue.Open,
                    onClose = { scope.launch { drawerState.close() } }
                )
            }
        }
    ) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize(),
            // Short shared-axis motion: the incoming page drifts a few percent while the outgoing one fades away.
            enterTransition = { pageEnter(forward = true) },
            exitTransition = { pageExit() },
            popEnterTransition = { pageEnter(forward = false) },
            popExitTransition = { pageExit() + slideOutHorizontally(pageSlideSpec()) { it / PageSlideFraction } }
        ) {
            // ====== 首次引导 ======
            composable(SubRoutes.ONBOARDING) {
                OnboardingScreen(
                    onComplete = {
                        navController.navigateToNewConversation()
                    },
                    onNavigateToProviders = { navController.navigate(SubRoutes.PROVIDER_LIST) },
                    onNavigateToAirPairing = { navController.navigate(SubRoutes.AIR_PAIRING) },
                )
            }

            // ====== 自动化 ======
            composable(SubRoutes.TASKS) {
                AutomationsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenConversation = { threadId, turnId -> navController.navigate(SubRoutes.buildChatRoute(threadId, turnId)) }
                )
            }
            composable(
                route = SubRoutes.AUTOMATION_CREATE,
                arguments = listOf(
                    navArgument("prompt") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                AutomationsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    initialPrompt = entry.arguments?.getString("prompt").orEmpty(),
                    onOpenConversation = { threadId, turnId -> navController.navigate(SubRoutes.buildChatRoute(threadId, turnId)) }
                )
            }

            // ====== 技能 ======
            composable(SubRoutes.SKILLS) {
                SkillsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onAuthorSkill = { prompt ->
                        // Keep an unsaved skill editor available when returning from assistance.
                        navController.navigate(SubRoutes.buildNewChatRoute(prompt, autoSend = true)) {
                            launchSingleTop = true
                        }
                    },
                    onSelectSkill = { skill ->
                        navController.navigateToNewConversation(skillId = skill.id, skillDraft = skill.isDraft)
                    }
                )
            }

            composable(
                route = SubRoutes.SKILL_DETAIL,
                arguments = listOf(navArgument("key") { type = NavType.StringType })
            ) { entry ->
                SkillsScreen(
                    initialSelectedSkillKey = entry.arguments?.getString("key"),
                    onNavigateBack = { navController.popBackStack() },
                    onAuthorSkill = { prompt -> navController.navigate(SubRoutes.buildNewChatRoute(prompt, autoSend = true)) },
                    onSelectSkill = { skill -> navController.navigateToNewConversation(skillId = skill.id, skillDraft = skill.isDraft) }
                )
            }

            // ====== 设置主页 ======
            composable(SubRoutes.SETTINGS) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToProviders    = { navController.navigate(SubRoutes.PROVIDER_LIST) },
                    onNavigateToMemory     = { navController.navigate(SubRoutes.MEMORY) },
                    onNavigateToDeviceExecution = { navController.navigate(SubRoutes.SETTINGS_DEVICE_CAPABILITIES) },
                    onNavigateToPermissions = { navController.navigate(SubRoutes.SETTINGS_PERMISSIONS) },
                    onNavigateToTheme = { navController.navigate(SubRoutes.SETTINGS_THEME) },
                    onNavigateToLanguage = { navController.navigate(SubRoutes.SETTINGS_LANGUAGE) },
                    onNavigateToDataManagement = { navController.navigate(SubRoutes.SETTINGS_DATA_MANAGEMENT) },
                    onNavigateToAbout        = { navController.navigate(SubRoutes.SETTINGS_ABOUT) }
                )
            }

            // ====== 设置子页面 ======
            composable(SubRoutes.SETTINGS_DEVICE_CAPABILITIES) {
                SettingsDeviceCapabilitiesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAirPairing = { navController.navigate(SubRoutes.AIR_PAIRING) }
                )
            }
            composable(SubRoutes.AIR_PAIRING) {
                AirPairingScreen(onBack = { navController.popBackStack() })
            }
            composable(SubRoutes.SETTINGS_PERMISSIONS) {
                SettingsPermissionsScreen(onBack = { navController.popBackStack() })
            }
            composable(SubRoutes.SETTINGS_THEME) {
                SettingsThemeScreen(onBack = { navController.popBackStack() })
            }
            composable(SubRoutes.SETTINGS_LANGUAGE) {
                SettingsLanguageScreen(onBack = { navController.popBackStack() })
            }
            composable(SubRoutes.SETTINGS_DATA_MANAGEMENT) {
                SettingsDataManagementScreen(onBack = { navController.popBackStack() })
            }
            composable(SubRoutes.SETTINGS_ABOUT) {
                SettingsAboutScreen(onBack = { navController.popBackStack() })
            }

            // ====== 记忆管理 ======
            composable(SubRoutes.MEMORY) {
                MemoryScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenExecutionPreferences = { navController.navigate(SubRoutes.AGENTS) }
                )
            }

            // ====== 智能体管理 ======
            composable(SubRoutes.AGENTS) {
                AgentsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // ====== 对话 — 新建 ======
            composable(
                route = SubRoutes.CHAT_NEW,
                arguments = listOf(
                    navArgument("threadId") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("prompt") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("autoSend") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                    navArgument("skillId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("skillDraft") {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                )
            ) { backStackEntry ->
                val chatVm: com.droidance.ui.viewmodel.HomeViewModel = hiltViewModel()
                val routePrompt = backStackEntry.arguments?.getString("prompt")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                val routeAutoSend = backStackEntry.arguments?.getBoolean("autoSend") ?: false
                LaunchedEffect(routePrompt, routeAutoSend) {
                    if (routePrompt != null && routeAutoSend) {
                        chatVm.triggerAutoSend(routePrompt)
                    }
                }
                HomeScreen(
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onNewConversation = { navController.navigateToNewConversation() },
                    onConnectDevice = connectionViewModel::showDetails,
                    connectionState = connectionState,
                    onOpenSkill = { skill -> navController.navigate("skills/detail?key=${Uri.encode(skill.selectionKey)}") },
                    onManageSkills = { navController.navigate(SubRoutes.SKILLS) },
                    initialSkillId = backStackEntry.arguments?.getString("skillId"),
                    initialSkillDraft = backStackEntry.arguments?.getBoolean("skillDraft") ?: false
                )
            }

            // ====== 对话 — 已有 ======
            composable(
                route = SubRoutes.CHAT_ID,
                arguments = listOf(
                    navArgument("conversationId") { type = NavType.StringType },
                    navArgument("turnId") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { entry ->
                HomeScreen(
                    initialTurnId = entry.arguments?.getString("turnId"),
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onNewConversation = { navController.navigateToNewConversation() },
                    onConnectDevice = connectionViewModel::showDetails,
                    connectionState = connectionState,
                    onOpenSkill = { skill -> navController.navigate("skills/detail?key=${Uri.encode(skill.selectionKey)}") },
                    onManageSkills = { navController.navigate(SubRoutes.SKILLS) }
                )
            }

            // ====== Provider 管理 ======
            composable(SubRoutes.PROVIDER_LIST) {
                ProviderListScreen(
                    onNavigateToEdit = { id ->
                        if (id != null) navController.navigate("settings/providers/edit/$id")
                        else navController.navigate(SubRoutes.PROVIDER_EDIT)
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(SubRoutes.PROVIDER_EDIT) {
                ProviderEditScreen(providerId = null, onNavigateBack = { navController.popBackStack() })
            }
            composable(
                route = SubRoutes.PROVIDER_EDIT_ID,
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { backStackEntry ->
                ProviderEditScreen(
                    providerId = backStackEntry.arguments?.getString("id"),
                    onNavigateBack = { navController.popBackStack() }
                )
            }

        }
    }
    if (showConnectionFeedback) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(16.dp),
            snackbar = { DroidanceSnackbar(it) }
        )
        if (connectionState.dialogVisible) {
            DeviceConnectionDialog(
                status = connectionState.status,
                onDismiss = connectionViewModel::dismiss,
                onConfirm = {
                    connectionViewModel.resolve {
                        navController.navigate(SubRoutes.AIR_PAIRING) { launchSingleTop = true }
                    }
                }
            )
        }
    }
}

private const val PageSlideFraction = 12

private fun pageSlideSpec(): FiniteAnimationSpec<IntOffset> =
    tween(AppMotion.PageMillis, easing = AppMotion.Settle)

private fun pageEnter(forward: Boolean): EnterTransition =
    fadeIn(tween(AppMotion.ContentMillis, delayMillis = 40, easing = AppMotion.Settle)) +
        slideInHorizontally(pageSlideSpec()) { if (forward) it / PageSlideFraction else -it / PageSlideFraction }

private fun pageExit(): ExitTransition = fadeOut(tween(AppMotion.PressMillis))
