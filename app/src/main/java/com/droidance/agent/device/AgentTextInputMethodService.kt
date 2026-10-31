// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.inputmethodservice.InputMethodService
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.droidance.core.agent.tools.DeviceInputResult
import com.droidance.core.agent.tools.TextInputEvidence
import java.lang.ref.WeakReference

/** An explicitly selected device input driver. It has no keyboard UI or task decisions. */
class AgentTextInputMethodService : InputMethodService() {
    private var pendingCommit: Pair<InputConnection, String?>? = null
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return false
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        super.onEvaluateFullscreenMode()
        return false
    }

    private fun inputDisplayId(): Int? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        display?.displayId
    } else window.window?.decorView?.display?.displayId

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // If the app died during a temporary selection, the restarted IME gives
        // the user's keyboard back instead of leaving a headless driver selected.
        if (!hasSession(this)) {
            val previous = restorationTarget(this)
            runCatching {
                if (previous != null) switchInputMethod(previous) else switchToPreviousInputMethod()
            }
            return
        }
        active = WeakReference(this)
    }

    override fun onFinishInput() {
        pendingCommit = null
        if (active?.get() === this) active = null
        super.onFinishInput()
    }

    override fun onDestroy() {
        if (active?.get() === this) active = null
        super.onDestroy()
    }

    companion object {
        private var active: WeakReference<AgentTextInputMethodService>? = null
        private fun preferences(context: Context) = context.getSharedPreferences("native_ime_lease", Context.MODE_PRIVATE)
        internal fun restorationTarget(context: Context): String? = preferences(context).getString("restore_ime", null)
        private fun hasSession(context: Context): Boolean = preferences(context).let {
            it.getInt("owner_pid", -1) == android.os.Process.myPid() &&
                it.getLong("owner_start", -1) == android.os.Process.getStartElapsedRealtime()
        }

        internal fun beginSession(context: Context, original: String) {
            check(preferences(context).edit().putString("restore_ime", original)
                .putInt("owner_pid", android.os.Process.myPid())
                .putLong("owner_start", android.os.Process.getStartElapsedRealtime()).commit())
        }

        internal fun endSession(context: Context, selectionRestored: Boolean) {
            active = null
            preferences(context).edit().remove("owner_pid").remove("owner_start").apply {
                if (selectionRestored) remove("restore_ime")
            }.commit()
        }

        /** Why a commit cannot be dispatched to the execution editor now; null when it can. */
        private fun commitBlocker(service: AgentTextInputMethodService?, displayId: Int, packageName: String): String? = when {
            service == null -> "ime_inactive"
            !hasSession(service) -> "ime_session_not_owned"
            service.currentInputEditorInfo == null -> "no_editor"
            service.inputDisplayId() != displayId -> "display_mismatch"
            service.currentInputEditorInfo?.packageName != packageName -> "editor_package_mismatch"
            service.currentInputEditorInfo?.inputType == android.text.InputType.TYPE_NULL -> "editor_type_null"
            service.currentInputConnection == null -> "no_connection"
            else -> null
        }

        /**
         * Main-thread only; no editor text is exposed as screen perception. Null means the editor
         * connection is not ready yet; an editor without a text input type never becomes ready.
         */
        internal fun commit(displayId: Int, packageName: String, text: String, clear: Boolean): DeviceInputResult? {
            val candidate = active?.get()
            when (commitBlocker(candidate, displayId, packageName)) {
                null -> Unit
                "editor_type_null" -> return DeviceInputResult(false,
                    "Native IME commit unavailable: the focused editor declares no text input type, so it takes key " +
                        "events but not InputConnection text. Use method=shortcut or paste_key for this editor.",
                    textInputEvidence = TextInputEvidence(true, true, false, false, false),
                    diagnosticCode = "NATIVE_IME_INPUT_UNAVAILABLE")
                else -> return null
            }
            val service = checkNotNull(candidate)
            val connection = checkNotNull(service.currentInputConnection)
            val before = connection.getExtractedText(ExtractedTextRequest(), 0)
            val expected = if (clear) text else before?.takeIf {
                it.text != null && it.startOffset == 0 && it.partialStartOffset < 0 && it.selectionStart in 0..it.text.length &&
                    it.selectionEnd in 0..it.text.length
            }?.let {
                it.text.toString().replaceRange(minOf(it.selectionStart, it.selectionEnd), maxOf(it.selectionStart, it.selectionEnd), text)
            }
            var accepted = false
            var matched = false
            connection.beginBatchEdit()
            try {
                val alreadyEmpty = clear && connection.getExtractedText(ExtractedTextRequest(), 0)?.text?.isEmpty() == true
                if (clear && !alreadyEmpty && !connection.performContextMenuAction(android.R.id.selectAll)) {
                    return DeviceInputResult(false, "Native IME replacement selection was rejected",
                        textInputEvidence = TextInputEvidence(true, true, false, false, false))
                }
                accepted = connection.commitText(text, 1)
            } finally {
                connection.endBatchEdit()
            }
            if (accepted && clear) {
                matched = connection.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString() == text
            }
            service.pendingCommit = if (accepted) connection to expected else null
            return DeviceInputResult(accepted,
                "Native IME commit accepted=$accepted; editor_readback_matched=$matched; display=$displayId. Inspect the destination before submitting.",
                textInputEvidence = TextInputEvidence(true, true, accepted, false, false))
        }

        internal fun pendingCommitDelivered(): Boolean {
            val pending = active?.get()?.pendingCommit ?: return false
            val expected = pending.second ?: return false
            return pending.first.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString() == expected
        }

        internal fun connectionState(displayId: Int, packageName: String): String {
            val service = active?.get()
            val blocker = commitBlocker(service, displayId, packageName) ?: "none"
            return service?.let {
                "ime_display=${it.inputDisplayId()}; expected_display=$displayId; " +
                    "editor_package=${it.currentInputEditorInfo?.packageName}; expected_package=$packageName; " +
                    "input_type=${it.currentInputEditorInfo?.inputType}; connection=${it.currentInputConnection != null}; blocker=$blocker"
            } ?: "ime_inactive; blocker=$blocker"
        }
    }
}
