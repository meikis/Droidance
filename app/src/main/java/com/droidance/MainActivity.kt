// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.droidance.agent.AgentRuntime
import com.droidance.core.knowledge.agent.device.DeviceProfileStore
import com.droidance.core.agent.event.AgentEventStore
import com.droidance.core.device.permission.PermissionBroker
import com.droidance.ui.components.RuntimePermissionRequestHost
import com.droidance.ui.navigation.AppNavigation
import com.droidance.ui.screens.update.AppUpdateHost
import com.droidance.ui.theme.DroidanceTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Droidance 主界面 Activity
 * 负责主导航宿主和启动参数转发
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var navigationPresentation: com.droidance.ui.navigation.StartupNavigationStore
    @Inject lateinit var runtimeClient: com.droidance.runtime.RuntimeProcessClient
    @Inject lateinit var deviceProfileStore: DeviceProfileStore
    @Inject lateinit var agentRuntime: AgentRuntime
    @Inject lateinit var eventStore: AgentEventStore
    @Inject lateinit var permissionBroker: PermissionBroker
    @Inject lateinit var generalSettings: com.droidance.settings.GeneralSettingsRepository
    @Inject lateinit var appUpdateManager: com.droidance.update.AppUpdateManager

    companion object {
        const val EXTRA_DEBUG_PROMPT = "prompt"
        const val EXTRA_DEBUG_AUTO_SEND = "autoSend"
        /** Debug auto-send must present the per-install token stored in [DEBUG_TOKEN_FILE]. */
        const val EXTRA_DEBUG_TOKEN = "debugToken"
        private const val DEBUG_TOKEN_FILE = "debug_intent_token"
        const val EXTRA_OPEN_UPDATE = "openUpdate"
        private const val EXTRA_RESULT_CONVERSATION_ID = "resultConversationId"

        fun taskResultIntent(context: Context, conversationId: String): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_RESULT_CONVERSATION_ID, conversationId)

        fun permissionRequestIntent(context: Context, requestId: String, conversationId: String?): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(PermissionBroker.EXTRA_PERMISSION_REQUEST_ID, requestId)
                .apply { if (!conversationId.isNullOrBlank()) putExtra(EXTRA_RESULT_CONVERSATION_ID, conversationId) }
    }

    private var permissionNavigation by mutableStateOf(false)

    private var resultConversationId by mutableStateOf<String?>(null)

    private fun debugTokenMatches(presented: String?): Boolean {
        val file = File(filesDir, DEBUG_TOKEN_FILE)
        val expected = runCatching {
            if (!file.isFile) file.writeText(UUID.randomUUID().toString())
            file.readText().trim()
        }.getOrNull().orEmpty()
        return presented != null && expected.isNotEmpty() &&
            MessageDigest.isEqual(expected.toByteArray(), presented.trim().toByteArray())
    }

    private fun openUpdateIfRequested(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_UPDATE, false)) appUpdateManager.showDialog()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        permissionNavigation = intent.hasExtra(PermissionBroker.EXTRA_PERMISSION_REQUEST_ID)
        resultConversationId = intent.getStringExtra(EXTRA_RESULT_CONVERSATION_ID)?.takeIf { it.isNotBlank() }
        openUpdateIfRequested(intent)
    }

    override fun onStart() {
        super.onStart()
        appUpdateManager.checkIfDue(appOpen = true)
    }

    override fun onResume() {
        super.onResume()
        appUpdateManager.resumePendingInstall()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionNavigation = intent.hasExtra(PermissionBroker.EXTRA_PERMISSION_REQUEST_ID)
        resultConversationId = intent.getStringExtra(EXTRA_RESULT_CONVERSATION_ID)?.takeIf { it.isNotBlank() }
        openUpdateIfRequested(intent)
        if (BuildConfig.DEBUG) debugTokenMatches(null) // creates the token file on first debug launch
        val debugPrompt = if (BuildConfig.DEBUG) {
            intent.getStringExtra(EXTRA_DEBUG_PROMPT)?.trim()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        // MainActivity is exported, so any app could otherwise make a debug build send a prompt
        // (and thereby drive the Agent). Pre-filling stays open; auto-send needs the token that only
        // the shell can read via `run-as`.
        val debugAutoSend = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEBUG_AUTO_SEND, false) &&
            debugTokenMatches(intent.getStringExtra(EXTRA_DEBUG_TOKEN))
        val lastConversationId = navigationPresentation.lastConversationId
        setContent {
            val themeMode by generalSettings.themeMode.collectAsStateWithLifecycle()
            val onboardingComplete by navigationPresentation.onboardingComplete.collectAsStateWithLifecycle()
            DroidanceTheme(themeMode = themeMode) {
                RuntimePermissionRequestHost(permissionBroker)
                AppUpdateHost()
                AppNavigation(
                    onboardingComplete = onboardingComplete,
                    agentRuntime = agentRuntime,
                    lastConversationId = lastConversationId,
                    initialPrompt = debugPrompt,
                    initialAutoSend = debugAutoSend,
                    resultConversationId = resultConversationId,
                    permissionNavigation = permissionNavigation,
                    onConversationShown = navigationPresentation::recordConversation,
                    onOnboardingCompleted = { navigationPresentation.recordOnboarding(true) },
                    onResultOpened = {
                        resultConversationId = null
                        permissionNavigation = false
                        intent.removeExtra(EXTRA_RESULT_CONVERSATION_ID)
                        intent.removeExtra(PermissionBroker.EXTRA_PERMISSION_REQUEST_ID)
                    }
                )
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                var restoredRequest: String? = null
                kotlinx.coroutines.flow.combine(permissionBroker.pendingRequest, runtimeClient.currentConversationId) { request, conversation ->
                    request?.requestId to conversation
                }.collect { (requestId, conversation) ->
                    if (requestId != null && conversation != null && requestId != restoredRequest) {
                        restoredRequest = requestId
                        permissionNavigation = true
                        resultConversationId = conversation
                    }
                }
            }
        }
    }
}
