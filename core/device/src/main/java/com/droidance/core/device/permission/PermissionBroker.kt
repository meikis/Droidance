// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class PermissionRequestOutcome {
    GRANTED,
    DENIED,
    POLICY_RESTRICTED,
    INSTALLER_RESTRICTED,
    TIMED_OUT,
    CANCELED,
    LAUNCH_FAILED
}

data class PendingPermissionRequest(
    val requestId: String,
    val turnId: String,
    val callId: String,
    val permissions: List<String>,
    val reason: String,
    val requestedAtMs: Long,
    val launched: Boolean = false,
    val settingsRequired: Boolean = false
)

/**
 * One process-wide Android runtime-permission rendezvous.
 *
 * The request is persisted before UI launch, while the active coroutine waits on an in-memory
 * continuation. Activity recreation therefore keeps the same request id and does not lose the
 * system dialog. Process recovery can redisplay the pending request; the durable Turn recovery
 * path remains responsible for restarting interrupted model execution.
 */
class PermissionBroker(
    private val context: Context,
    private val launchPermissionHost: (requestId: String) -> Unit
) {
    private val lock = Any()
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _pendingRequest = MutableStateFlow<PendingPermissionRequest?>(null)
    val pendingRequest: StateFlow<PendingPermissionRequest?> = _pendingRequest
    private val _systemDialogPromptRequested = MutableStateFlow<String?>(null)
    val systemDialogPromptRequested: StateFlow<String?> = _systemDialogPromptRequested
    private val deniedInTurn = preferences.getStringSet("denied_in_turn", emptySet()).orEmpty().toMutableSet()
    private var activeWaiter: CompletableDeferred<PermissionRequestOutcome>? = null

    fun missingPermissions(permissions: Collection<String>): List<String> = permissions
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        .toList()

    fun platformStatus(permission: String): String {
        if (missingPermissions(listOf(permission)).isEmpty()) return "GRANTED"
        if (runCatching { context.packageManager.isPermissionRevokedByPolicy(permission, context.packageName) }.getOrDefault(false)) return "POLICY_RESTRICTED"
        if (Build.VERSION.SDK_INT >= 29) {
            val info = runCatching { context.packageManager.getPermissionInfo(permission, 0) }.getOrNull()
                ?: return "UNKNOWN"
            if (info.flags and android.content.pm.PermissionInfo.FLAG_HARD_RESTRICTED != 0) {
                val allowed = runCatching { context.packageManager.getWhitelistedRestrictedPermissions(
                    context.packageName, PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER or
                        PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM or PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE)
                }.getOrNull() ?: return "HARD_RESTRICTED_ALLOWLIST_UNKNOWN"
                if (permission !in allowed) return "INSTALLER_RESTRICTED"
            }
        }
        return "RUNTIME_REQUEST_AVAILABLE"
    }

    suspend fun request(
        permissions: Collection<String>,
        turnId: String,
        callId: String,
        reason: String,
        timeoutMillis: Long,
        onWaiting: suspend (PendingPermissionRequest) -> Unit,
        onResolved: suspend (PendingPermissionRequest, PermissionRequestOutcome) -> Unit
    ): PermissionRequestOutcome {
        val missing = missingPermissions(permissions)
        if (missing.isEmpty()) return PermissionRequestOutcome.GRANTED
        require(turnId.isNotBlank() && callId.isNotBlank()) { "Permission request requires a turn and call owner" }
        if (missing.any { runCatching { context.packageManager.isPermissionRevokedByPolicy(it, context.packageName) }.getOrDefault(false) }) {
            return PermissionRequestOutcome.POLICY_RESTRICTED
        }
        if (missing.any { platformStatus(it) == "INSTALLER_RESTRICTED" }) return PermissionRequestOutcome.INSTALLER_RESTRICTED
        synchronized(lock) {
            deniedInTurn.removeAll { !it.startsWith("$turnId:") }
            if (missing.any { "$turnId:$it" in deniedInTurn }) return PermissionRequestOutcome.DENIED
        }
        require(timeoutMillis >= 0L) { "Permission request timeout cannot be negative" }

        val request: PendingPermissionRequest
        val waiter: CompletableDeferred<PermissionRequestOutcome>
        synchronized(lock) {
            check(activeWaiter == null) { "Another runtime permission request is already active" }
            val restored = loadPendingRequest()?.takeIf { it.permissions == missing && it.turnId == turnId && it.callId == callId }
            request = restored?.copy(reason = reason.trim(), launched = false)
                ?: PendingPermissionRequest(
                    requestId = UUID.randomUUID().toString(),
                    turnId = turnId,
                    callId = callId,
                    permissions = missing,
                    reason = reason.trim(),
                    requestedAtMs = System.currentTimeMillis()
                )
            waiter = CompletableDeferred()
            activeWaiter = waiter
            persist(request)
        }

        var cancellation: CancellationException? = null
        val outcome = try {
            onWaiting(request)
            showPermissionHost(request)
            if (timeoutMillis > 0L) {
                withTimeoutOrNull(timeoutMillis) { waiter.await() }
                    ?: PermissionRequestOutcome.TIMED_OUT
            } else {
                waiter.await()
            }
        } catch (error: CancellationException) {
            cancellation = error
            PermissionRequestOutcome.CANCELED
        } catch (_: Exception) {
            PermissionRequestOutcome.LAUNCH_FAILED
        }

        withContext(NonCancellable) {
            synchronized(lock) {
                if (_pendingRequest.value?.requestId == request.requestId) {
                    clearPersistedRequest()
                    _pendingRequest.value = null
                    _systemDialogPromptRequested.value = null
                }
                if (activeWaiter === waiter) activeWaiter = null
                if (outcome == PermissionRequestOutcome.DENIED) {
                    request.permissions.forEach { deniedInTurn += "${request.turnId}:$it" }
                    preferences.edit().putStringSet("denied_in_turn", deniedInTurn.toSet()).commit()
                }
            }
            onResolved(request, outcome)
        }
        cancellation?.let { throw it }
        return outcome
    }

    fun requestSystemPermissionDialog(requestId: String) {
        synchronized(lock) {
            val current = _pendingRequest.value?.takeIf { it.requestId == requestId } ?: return
            if (current.launched || current.settingsRequired) return
            _systemDialogPromptRequested.value = current.requestId
        }
    }

    fun consumeSystemDialogRequest(requestId: String) {
        synchronized(lock) {
            if (_systemDialogPromptRequested.value == requestId) {
                _systemDialogPromptRequested.value = null
            }
        }
    }

    fun markLaunched(requestId: String) {
        synchronized(lock) {
            val current = _pendingRequest.value?.takeIf { it.requestId == requestId } ?: return
            if (current.launched) return
            val launched = current.copy(launched = true)
            persist(launched)
            _pendingRequest.value = launched
        }
    }

    fun resolve(requestId: String, settingsRequired: Boolean) {
        synchronized(lock) {
            val current = _pendingRequest.value?.takeIf { it.requestId == requestId } ?: return
            _systemDialogPromptRequested.value = null
            // A suppressed dialog is still an unresolved request. Keep the same tool call
            // suspended while the user fixes access in app settings.
            if (settingsRequired && missingPermissions(current.permissions).isNotEmpty()) {
                val waitingForSettings = current.copy(launched = false, settingsRequired = true)
                persist(waitingForSettings)
                _pendingRequest.value = waitingForSettings
                return
            }
            val outcome = if (missingPermissions(current.permissions).isEmpty()) {
                PermissionRequestOutcome.GRANTED
            } else {
                PermissionRequestOutcome.DENIED
            }
            val waiter = activeWaiter
            if (waiter != null) {
                waiter.complete(outcome)
            } else {
                clearPersistedRequest()
                _pendingRequest.value = null
            }
        }
    }

    /** Returning from settings without a grant must never resume the Agent. */
    fun recheckGrantedPermissions(requestId: String) {
        synchronized(lock) {
            val current = _pendingRequest.value?.takeIf { it.requestId == requestId } ?: return
            if (missingPermissions(current.permissions).isEmpty()) {
                resolve(requestId, settingsRequired = false)
            }
        }
    }

    fun launchFailed(requestId: String) {
        resolve(requestId, settingsRequired = true)
    }

    private suspend fun showPermissionHost(request: PendingPermissionRequest) {
        withContext(Dispatchers.Main.immediate) {
            launchPermissionHost(request.requestId)
            // Publish only after MainActivity has been moved to the front. Publishing first can
            // launch PermissionController and then immediately cover it with REORDER_TO_FRONT.
            synchronized(lock) {
                if (activeWaiter != null) {
                    _pendingRequest.value = request
                }
            }
        }
    }

    private fun persist(request: PendingPermissionRequest) {
        preferences.edit()
            .putString(KEY_REQUEST_ID, request.requestId)
            .putString("turn_id", request.turnId)
            .putString("call_id", request.callId)
            .putString(KEY_PERMISSIONS, request.permissions.joinToString(PERMISSION_SEPARATOR))
            .putString(KEY_REASON, request.reason)
            .putLong(KEY_REQUESTED_AT, request.requestedAtMs)
            .putBoolean(KEY_LAUNCHED, request.launched)
            .putBoolean(KEY_SETTINGS_REQUIRED, request.settingsRequired)
            .commit()
    }

    private fun loadPendingRequest(): PendingPermissionRequest? {
        val requestId = preferences.getString(KEY_REQUEST_ID, null)?.takeIf(String::isNotBlank)
            ?: return null
        val permissions = preferences.getString(KEY_PERMISSIONS, null)
            ?.split(PERMISSION_SEPARATOR)
            ?.filter(String::isNotBlank)
            .orEmpty()
        if (permissions.isEmpty()) return null
        return PendingPermissionRequest(
            requestId = requestId,
            turnId = preferences.getString("turn_id", null)?.takeIf(String::isNotBlank) ?: return null,
            callId = preferences.getString("call_id", null)?.takeIf(String::isNotBlank) ?: return null,
            permissions = permissions,
            reason = preferences.getString(KEY_REASON, "").orEmpty(),
            requestedAtMs = preferences.getLong(KEY_REQUESTED_AT, 0L),
            launched = preferences.getBoolean(KEY_LAUNCHED, false),
            settingsRequired = preferences.getBoolean(KEY_SETTINGS_REQUIRED, false)
        )
    }

    private fun clearPersistedRequest() {
        preferences.edit().remove(KEY_REQUEST_ID).remove(KEY_PERMISSIONS).remove(KEY_REASON)
            .remove(KEY_REQUESTED_AT).remove(KEY_LAUNCHED).remove(KEY_SETTINGS_REQUIRED)
            .remove("turn_id").remove("call_id").commit()
    }

    companion object {
        const val EXTRA_PERMISSION_REQUEST_ID = "runtimePermissionRequestId"
        private const val PREFS_NAME = "droidance_permission_broker"
        private const val KEY_REQUEST_ID = "request_id"
        private const val KEY_PERMISSIONS = "permissions"
        private const val KEY_REASON = "reason"
        private const val KEY_REQUESTED_AT = "requested_at"
        private const val KEY_LAUNCHED = "launched"
        private const val KEY_SETTINGS_REQUIRED = "settings_required"
        private const val PERMISSION_SEPARATOR = "\u001f"
    }
}

/** Android platform ContentProvider families mapped to their runtime read grants. */
object AndroidContentPermissionResolver {
    fun requiredPermissions(
        uri: Uri,
        write: Boolean = false,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Set<String> = requiredPermissions(
        authority = uri.authority.orEmpty(),
        pathSegments = uri.pathSegments,
        write = write,
        sdkInt = sdkInt
    )

    internal fun requiredPermissions(
        authority: String,
        pathSegments: List<String>,
        sdkInt: Int
    ): Set<String> = requiredPermissions(authority, pathSegments, write = false, sdkInt = sdkInt)

    internal fun requiredPermissions(
        authority: String,
        pathSegments: List<String>,
        write: Boolean = false,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Set<String> {
        val normalizedAuthority = authority.lowercase()
        val segments = pathSegments.map(String::lowercase)
        return when {
            normalizedAuthority in setOf("sms", "mms-sms", "telephony") ->
                if (write) setOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS)
                else setOf(Manifest.permission.READ_SMS)
            normalizedAuthority == "com.android.contacts" ->
                if (write) setOf(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS)
                else setOf(Manifest.permission.READ_CONTACTS)
            normalizedAuthority == "call_log" ->
                if (write) setOf(Manifest.permission.WRITE_CALL_LOG, Manifest.permission.READ_CALL_LOG)
                else setOf(Manifest.permission.READ_CALL_LOG)
            normalizedAuthority == "com.android.calendar" ->
                if (write) setOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)
                else setOf(Manifest.permission.READ_CALENDAR)
            normalizedAuthority == "media" -> mediaPermissions(segments, sdkInt)
            else -> emptySet()
        }
    }

    private fun mediaPermissions(segments: List<String>, sdkInt: Int): Set<String> {
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) {
            return setOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return when {
            "images" in segments -> setOf(Manifest.permission.READ_MEDIA_IMAGES)
            "video" in segments -> setOf(Manifest.permission.READ_MEDIA_VIDEO)
            "audio" in segments -> setOf(Manifest.permission.READ_MEDIA_AUDIO)
            else -> setOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        }
    }
}
