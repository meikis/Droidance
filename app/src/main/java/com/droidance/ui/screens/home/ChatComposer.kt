// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import com.droidance.ui.icons.DroidanceIcons
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.painter.Painter
import com.droidance.ui.theme.AppIconSize
import com.droidance.ui.theme.AppShapes
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.droidance.ui.components.DroidanceDropdownMenu
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.compose.AsyncImage
import com.droidance.R
import com.droidance.ui.components.rememberAppHaptics
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import com.droidance.core.knowledge.skill.SkillInfo
import com.droidance.agent.ConversationSubmissionMode
import com.droidance.ui.viewmodel.UIMessage
import com.droidance.ui.viewmodel.ComposerModelOption
import com.droidance.ui.viewmodel.ComposerPreferences
import com.droidance.ui.theme.LocalAppIsDark
import com.droidance.ui.theme.AppMotion
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first

import androidx.compose.ui.focus.onFocusChanged

private val ComposerIconButtonSize = 48.dp
private val ComposerActionControlSize = 32.dp

/**
 * 底部输入栏
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ChatInputBar(
    availableSkills: List<SkillInfo>,
    initialText: String = "",
    initialSkillId: String? = null,
    initialSkillDraft: Boolean = false,
    onManageSkills: () -> Unit = {},
    modelOptions: List<ComposerModelOption> = emptyList(),
    preferences: ComposerPreferences = ComposerPreferences(),
    onOpenModelPicker: () -> Unit = {},
    onModelSelected: (ComposerModelOption?) -> Unit = {},
    isProcessing: Boolean,
    isEnabled: Boolean = true,
    queuedMessages: List<UIMessage> = emptyList(),
    onDeleteQueuedMessage: (String) -> Unit = {},
    onSteerQueuedMessage: (String) -> Unit = {},
    onDraftChanged: (Boolean) -> Unit = {},
    onError: (String) -> Unit = {},
    onSendMessage: (String, ConversationSubmissionMode, List<com.droidance.core.agent.RequestedSkill>) -> Boolean,
    onSendImages: (String, List<Uri>, ConversationSubmissionMode, List<com.droidance.core.agent.RequestedSkill>) -> Boolean = { _, _, _, _ -> false }
) {
    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current
    val focusRequester = remember { FocusRequester() }
    val inputInteractionSource = remember { MutableInteractionSource() }
    var isInputFocused by remember { mutableStateOf(false) }
    // Opening the model menu clears focus to hide the keyboard; keep the controls composed meanwhile.
    var modelMenuOpen by remember { mutableStateOf(false) }
    var keyboardActivationRequested by remember { mutableStateOf(false) }
    var keyboardFocusAuthorized by remember { mutableStateOf(false) }
    var keyboardActivationId by remember { mutableStateOf(0L) }
    val editorState = rememberTextFieldState(initialText)
    val inputText = editorState.text.toString()
    val language = LocalConfiguration.current.locales[0].language
    val draftFormat = stringResource(R.string.skill_selected_draft, "%s")
    val draftLabel: (String) -> String = { draftFormat.replace("%s", it) }
    val referenceSaver = remember {
        androidx.compose.runtime.saveable.Saver<List<ComposerSkillReference>, String>(
            save = { Json.encodeToString(it) }, restore = { Json.decodeFromString(it) })
    }
    var selectedSkills by rememberSaveable(stateSaver = referenceSaver) {
        mutableStateOf(emptyList<ComposerSkillReference>())
    }
    var manualSkillPicker by remember { mutableStateOf(false) }
    var skillSearch by remember { mutableStateOf("") }
    var dismissedSkillQuery by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var highlightedSkill by remember { mutableStateOf(0) }
    var skillPreview by remember { mutableStateOf<ComposerSkillReference?>(null) }
    val mentionQuery = if (editorState.selection.collapsed)
        activeSkillQuery(inputText, editorState.selection.start, emptyList()) else null
    val queryIdentity = inputText to editorState.selection.start
    val showMentionPicker = mentionQuery != null && dismissedSkillQuery != queryIdentity && isInputFocused
    val skillQuery = if (manualSkillPicker) skillSearch else mentionQuery?.let {
        inputText.substring(it.first + 1, editorState.selection.start)
    }.orEmpty()
    val suggestions = selectableSkills(availableSkills, skillQuery)
    LaunchedEffect(skillQuery) { highlightedSkill = 0 }
    var initialSkillInserted by rememberSaveable(initialSkillId, initialSkillDraft) { mutableStateOf(false) }
    LaunchedEffect(initialSkillId, initialSkillDraft, availableSkills) {
        if (!initialSkillInserted && initialSkillId != null) {
            val skill = availableSkills.firstOrNull { !it.isReadOnly && it.id == initialSkillId && it.isDraft == initialSkillDraft }
            if (skill != null) {
                val reference = ComposerSkillReference.from(skill, language)
                if (selectedSkills.none { it.selectionKey == reference.selectionKey }) selectedSkills = selectedSkills + reference
                initialSkillInserted = true
            }
        }
    }

    var showAttachments by remember { mutableStateOf(false) }
    var pendingImageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var composerTop by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    LaunchedEffect(inputText, selectedSkills, pendingImageUris) {
        onDraftChanged(inputText.isNotEmpty() || selectedSkills.isNotEmpty() || pendingImageUris.isNotEmpty())
    }

    fun requestKeyboardActivation() {
        keyboardActivationId++
        keyboardActivationRequested = true
    }

    fun selectSkill(skill: SkillInfo) {
        val reference = ComposerSkillReference.from(skill, language)
        selectedSkills = selectedSkills.filterNot { it.selectionKey == reference.selectionKey } + reference
        if (!manualSkillPicker && mentionQuery != null) {
            val start = mentionQuery.first
            val end = editorState.selection.start
            editorState.edit { replace(start, end, ""); selection = TextRange(start) }
        }
        manualSkillPicker = false
        showAttachments = false
        dismissedSkillQuery = null
        requestKeyboardActivation()
    }
    fun dismissSkillPicker() {
        manualSkillPicker = false
        dismissedSkillQuery = queryIdentity
    }
    BackHandler(enabled = showMentionPicker && !manualSkillPicker) { dismissSkillPicker() }

    // Both editor and padding taps authorize exactly one activation. Loading messages,
    // restored focus and returning from another window must never authorize a show.
    LaunchedEffect(keyboardActivationRequested, keyboardActivationId, isEnabled, showAttachments) {
        if (!isEnabled || showAttachments) {
            keyboardActivationRequested = false
            keyboardFocusAuthorized = false
            return@LaunchedEffect
        }
        if (!keyboardActivationRequested) return@LaunchedEffect
        requestComposerWindowFocus(view)
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        keyboardFocusAuthorized = true
        val imm = view.context.getSystemService(InputMethodManager::class.java)
        val deadline = SystemClock.uptimeMillis() + 1_500L
        var sessionRenewed = false
        // Wait for focus and binding, never retry showing the keyboard. A late hide
        // or cancellation therefore cannot be reversed by an old show loop.
        withFrameNanos { }
        withFrameNanos { }
        while (keyboardActivationRequested && SystemClock.uptimeMillis() < deadline) {
            if (windowInfo.isWindowFocused && view.hasWindowFocus()) {
                if (!isInputFocused) {
                    focusRequester.requestFocus()
                } else if (imm?.isActive(view) == true && imm.isAcceptingText) {
                    keyboardController?.show()
                    break
                } else if (!sessionRenewed) {
                    // A locally focused editor may have lost its platform session
                    // when the IME was rebound to another display. Renew it once.
                    sessionRenewed = true
                    focusManager.clearFocus(force = true)
                    withFrameNanos { }
                    focusRequester.requestFocus()
                }
            }
            withFrameNanos { }
        }
        Log.i("DroidanceComposerIme", "activation=$keyboardActivationId windowFocused=${view.hasWindowFocus()}" +
            " editorFocused=$isInputFocused active=${imm?.isActive(view)} accepting=${imm?.isAcceptingText}")
        keyboardActivationRequested = false
    }

    LaunchedEffect(windowInfo.isWindowFocused) {
        if (!windowInfo.isWindowFocused && isInputFocused) {
            if (!keyboardActivationRequested) keyboardFocusAuthorized = false
            focusManager.clearFocus(force = true)
        }
    }
    val imeVisible = WindowInsets.isImeVisible
    var visibleActivationId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(imeVisible, isInputFocused, keyboardActivationId) {
        if (imeVisible && isInputFocused) {
            visibleActivationId = keyboardActivationId
        } else if (!imeVisible) {
            // Dismissal ends this activation, even if Android still reports editor focus.
            // An earlier window's dismissal must not cancel a newer user tap.
            if (visibleActivationId == keyboardActivationId) {
                keyboardActivationRequested = false
                keyboardFocusAuthorized = false
            }
            visibleActivationId = null
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            pendingImageUris = (pendingImageUris + uris).distinct()
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            pendingImageUris = pendingImageUris + uri
        }
        pendingCameraUri = null
    }
    val haptics = rememberAppHaptics()
    fun submitMessage() {
        if (!isEnabled ||
            (inputText.isBlank() && pendingImageUris.isEmpty() && selectedSkills.isEmpty())
        ) return
        val text = skillSubmissionText(inputText, selectedSkills, draftLabel)
        val requestedSkills = selectedSkills.map { it.requestedSkill() }
        val uris = pendingImageUris
        val accepted = if (uris.isNotEmpty()) onSendImages(text, uris, ConversationSubmissionMode.QUEUE, requestedSkills)
            else onSendMessage(text, ConversationSubmissionMode.QUEUE, requestedSkills)
        if (!accepted) return
        haptics.confirm()
        keyboardActivationRequested = false
        if (!isProcessing) {
            keyboardFocusAuthorized = false
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
        pendingImageUris = emptyList()
        selectedSkills = emptyList()
        manualSkillPicker = false
        dismissedSkillQuery = null
        editorState.edit { replace(0, length, "") }
        showAttachments = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(bottom = 8.dp)
    ) {
        if (queuedMessages.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    .heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                queuedMessages.forEach { message ->
                    val turnId = message.turnId ?: return@forEach
                    Surface(
                        shape = AppShapes.Card,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    ) {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(message.text.ifBlank {
                                stringResource(R.string.composer_selected_images, message.imageUris.size)
                            }, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                            TextButton(onClick = { onSteerQueuedMessage(turnId) }, enabled = isEnabled,
                                modifier = Modifier.height(ComposerIconButtonSize),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )) {
                                Icon(DroidanceIcons.Steer, stringResource(R.string.composer_steer_queued),
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.composer_steer_queued_label),
                                    style = MaterialTheme.typography.labelMedium)
                            }
                            IconButton(onClick = { onDeleteQueuedMessage(turnId) }, enabled = isEnabled,
                                modifier = Modifier.size(ComposerIconButtonSize)) {
                                Icon(DroidanceIcons.Trash, stringResource(R.string.composer_delete_queued),
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = pendingImageUris.isNotEmpty(),
            enter = fadeIn(tween(AppMotion.FeedbackMillis)) + expandVertically(tween(AppMotion.ContentMillis)),
            exit = fadeOut(tween(AppMotion.FeedbackMillis)) + shrinkVertically(tween(AppMotion.ContentMillis))
        ) {
            PendingImageStrip(pendingImageUris, onRemove = { uri -> pendingImageUris = pendingImageUris - uri })
        }

        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .onGloballyPositioned { composerTop = it.positionInWindow().y }) {
            ChatComposerSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(
                        animationSpec = tween(AppMotion.ContentMillis, easing = AppMotion.Settle),
                        alignment = Alignment.BottomStart
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (isEnabled) {
                            requestKeyboardActivation()
                        }
                    },
                shape = AppShapes.Large,
                isFocused = isInputFocused
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 6.dp)
                ) {
                    ComposerSkillReferences(selectedSkills, isEnabled,
                        onOpen = { skillPreview = it },
                        onRemove = { reference -> selectedSkills = selectedSkills.filterNot { it.selectionKey == reference.selectionKey } })
                    // Idle and empty: one row with attach, editor and send. Focus or content opens the
                    // full composer with the model controls; the editor keeps its slot either way.
                    val compact = !isInputFocused && !modelMenuOpen && inputText.isEmpty() && selectedSkills.isEmpty() &&
                        pendingImageUris.isEmpty()
                    val attachButton: @Composable () -> Unit = {
                        Box {
                            IconButton(
                                onClick = {
                                    keyboardActivationRequested = false
                                    keyboardFocusAuthorized = false
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    dismissSkillPicker()
                                    showAttachments = !showAttachments
                                },
                                enabled = isEnabled,
                                modifier = Modifier.size(ComposerIconButtonSize)
                            ) {
                                ConversationActionGlyph(
                                    R.drawable.ic_conversation_add,
                                    contentDescription = stringResource(R.string.composer_attachment),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            DroidanceDropdownMenu(showAttachments && isEnabled,
                                onDismissRequest = { showAttachments = false }) {
                                AttachmentPanel(
                                    onChooseSkill = { showAttachments = false; skillSearch = ""; manualSkillPicker = true },
                                    onTakePhoto = {
                                        runCatching { createComposerCameraUri(context) }.onSuccess { uri ->
                                            pendingCameraUri = uri; cameraLauncher.launch(uri)
                                        }.onFailure { pendingCameraUri = null; onError(context.getString(R.string.composer_camera_failed)) }
                                    },
                                    onPickImage = { imagePicker.launch("image/*") },
                                    onDismiss = { showAttachments = false }
                                )
                            }
                        }
                    }
                    val sendButton: @Composable () -> Unit = {
                        val sendInteractionSource = remember { MutableInteractionSource() }
                        val isSendPressed by sendInteractionSource.collectIsPressedAsState()
                        val sendScale by animateFloatAsState(
                            targetValue = if (isSendPressed) 0.97f else 1f,
                            animationSpec = tween(
                                if (isSendPressed) AppMotion.PressMillis else AppMotion.FeedbackMillis,
                                easing = AppMotion.Settle
                            ),
                            label = "sendButtonScale"
                        )
                        val canSubmit = isEnabled &&
                            (inputText.isNotBlank() || pendingImageUris.isNotEmpty() || selectedSkills.isNotEmpty())
                        val sendContainerColor by animateColorAsState(
                            if (canSubmit) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f),
                            tween(AppMotion.FeedbackMillis, easing = AppMotion.Settle),
                            label = "sendContainerColor"
                        )
                        val sendContentColor by animateColorAsState(
                            if (canSubmit) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.26f),
                            tween(AppMotion.FeedbackMillis, easing = AppMotion.Settle),
                            label = "sendContentColor"
                        )
                        val sendBorderColor by animateColorAsState(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = if (canSubmit) 0f else 0.06f),
                            tween(AppMotion.FeedbackMillis, easing = AppMotion.Settle),
                            label = "sendBorderColor"
                        )
                        IconButton(
                            onClick = { submitMessage() },
                            enabled = canSubmit,
                            interactionSource = sendInteractionSource,
                            modifier = Modifier.size(ComposerIconButtonSize),
                        ) {
                            Surface(
                                modifier = Modifier
                                    .size(ComposerActionControlSize)
                                    .graphicsLayer {
                                        scaleX = sendScale
                                        scaleY = sendScale
                                    },
                                shape = CircleShape,
                                color = sendContainerColor,
                                contentColor = sendContentColor,
                                border = BorderStroke(1.dp, sendBorderColor)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    ConversationActionGlyph(
                                        R.drawable.ic_conversation_send,
                                        stringResource(R.string.composer_send)
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 6.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (compact) attachButton()
                        val editorScroll = rememberScrollState()
                        BasicTextField(
                            state = editorState,
                            enabled = isEnabled,
                            interactionSource = inputInteractionSource,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .onFocusChanged { isInputFocused = it.isFocused }
                                .semantics {
                                    onClick {
                                        if (isEnabled) requestKeyboardActivation()
                                        true
                                    }
                                }
                                .pointerInput(isEnabled) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                                        if (up != null && isEnabled) requestKeyboardActivation()
                                    }
                                }
                                .onPreviewKeyEvent { event ->
                                    if (!showMentionPicker || manualSkillPicker || editorState.composition != null ||
                                        event.type != KeyEventType.KeyDown) false
                                    else when (event.key) {
                                        Key.Escape -> { dismissSkillPicker(); true }
                                        Key.DirectionDown, Key.DirectionUp -> if (suggestions.isEmpty()) false else {
                                            highlightedSkill = (highlightedSkill + if (event.key == Key.DirectionDown) 1 else -1)
                                                .mod(suggestions.size)
                                            true
                                        }
                                        Key.Enter -> suggestions.getOrNull(highlightedSkill.coerceAtMost(suggestions.lastIndex))
                                            ?.let { selectSkill(it); true } ?: false
                                        else -> false
                                    }
                                },
                            scrollState = editorScroll,
                            decorator = { innerTextField ->
                                OutlinedTextFieldDefaults.DecorationBox(
                                    value = inputText,
                                    innerTextField = innerTextField,
                                    enabled = isEnabled,
                                    singleLine = false,
                                    visualTransformation = VisualTransformation.None,
                                    interactionSource = inputInteractionSource,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    placeholder = {
                                        Text(
                                            text = stringResource(R.string.composer_input_hint),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                        )
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        disabledBorderColor = Color.Transparent,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent
                                    ),
                                    container = {}
                                )
                            },
                            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 6),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Send,
                                keyboardType = KeyboardType.Text,
                                showKeyboardOnFocus = keyboardFocusAuthorized
                            ),
                            onKeyboardAction = {
                                if (showMentionPicker && !manualSkillPicker && suggestions.isNotEmpty())
                                    selectSkill(suggestions[highlightedSkill.coerceIn(0, suggestions.lastIndex)])
                                else submitMessage()
                            }
                        )

                        if (compact) sendButton()
                    }

                    if (!compact) Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 6.dp, end = 6.dp, top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        attachButton()
                        ComposerSelectionControls(
                            modelOptions = modelOptions,
                            preferences = preferences,
                            enabled = isEnabled,
                            onOpenPicker = {
                                modelMenuOpen = true
                                keyboardActivationRequested = false
                                keyboardFocusAuthorized = false
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            },
                            onPickerClosed = { modelMenuOpen = false },
                            onOpenModelPicker = onOpenModelPicker,
                            onModelSelected = onModelSelected,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(4.dp))
                        sendButton()
                    }
                }
            }
            if (isEnabled && (manualSkillPicker || showMentionPicker)) {
                ComposerSkillPicker(
                    skills = suggestions,
                    selectedKeys = selectedSkills.map { it.selectionKey }.toSet(),
                    language = language,
                    width = maxWidth,
                    maxHeight = (with(density) { composerTop.toDp() } - 24.dp).coerceIn(120.dp, 360.dp),
                    query = skillQuery,
                    manual = manualSkillPicker,
                    highlightedIndex = highlightedSkill.coerceIn(0, suggestions.lastIndex.coerceAtLeast(0)),
                    onQueryChanged = { skillSearch = it },
                    onSelect = ::selectSkill,
                    onManage = { dismissSkillPicker(); onManageSkills() },
                    onBack = { manualSkillPicker = false; showAttachments = true },
                    onDismiss = ::dismissSkillPicker
                )
            }
        }
    }
    skillPreview?.let { reference -> ComposerSkillDetails(reference) { skillPreview = null } }

}

/** Fixed thumbnail geometry keeps image loading and removal from moving the editor. */
@Composable
internal fun PendingImageStrip(uris: List<Uri>, onRemove: (Uri) -> Unit) {
    val description = stringResource(R.string.composer_selected_images, uris.size)
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(72.dp).testTag("composer-pending-images")
            .semantics { contentDescription = description },
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(uris, key = { _, uri -> uri.toString() }) { index, uri ->
            Box(Modifier.size(72.dp).animateItem(
                fadeInSpec = tween(AppMotion.FeedbackMillis),
                placementSpec = AppMotion.controlSpring(),
                fadeOutSpec = tween(AppMotion.FeedbackMillis)
            )) {
                AsyncImage(
                    model = uri,
                    contentDescription = stringResource(R.string.composer_pending_image),
                    modifier = Modifier.align(Alignment.BottomStart).size(64.dp).clip(AppShapes.Control),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                IconButton(
                    onClick = { onRemove(uri) },
                    modifier = Modifier.align(Alignment.TopEnd).size(48.dp)
                ) {
                    Box(
                        Modifier.size(24.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(DroidanceIcons.Close, stringResource(R.string.composer_remove_image, index + 1),
                            Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/** A user tap may need to restore global window focus from an executing virtual display. */
private fun requestComposerWindowFocus(view: View) {
    var context = view.context
    while (context is ContextWrapper && context !is Activity) context = context.baseContext
    val activity = context as? Activity ?: return
    if (activity.isFinishing || activity.isDestroyed) return
    try {
        activity.getSystemService(ActivityManager::class.java)?.moveTaskToFront(activity.taskId, 0)
        Log.i("DroidanceComposerIme", "window focus requested task=${activity.taskId}")
    } catch (error: RuntimeException) {
        Log.w("DroidanceComposerIme", "window focus request failed", error)
    }
}

@Composable
private fun ChatComposerSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape,
    isFocused: Boolean = false,
    content: @Composable () -> Unit
) {
    // Focus is a single quiet cue: the hairline darkens. Size and elevation stay fixed.
    val borderColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isFocused) 0.20f else 0.08f),
        animationSpec = tween(AppMotion.ContentMillis, easing = AppMotion.Settle),
        label = "composerBorderColor"
    )

    Surface(
        modifier = modifier,
        shape = shape,
        // Both themes lift the composer one step above the canvas: the soft card surface in light, surfaceVariant in dark.
        color = if (LocalAppIsDark.current) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        content = content
    )
}

@Composable
private fun AttachmentPanel(
    onChooseSkill: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickImage: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(Modifier.width(208.dp)) {
        AttachmentOption(stringResource(R.string.composer_choose_skill), rememberVectorPainter(DroidanceIcons.Sparkles)) {
            onChooseSkill()
        }
        AttachmentOption(stringResource(R.string.composer_album), painterResource(R.drawable.ic_conversation_image)) {
            onDismiss(); onPickImage()
        }
        AttachmentOption(stringResource(R.string.composer_camera), painterResource(R.drawable.ic_conversation_camera)) {
            onDismiss(); onTakePhoto()
        }
    }
}

@Composable
private fun AttachmentOption(label: String, icon: Painter, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(icon, null, Modifier.size(AppIconSize.Leading)) },
        onClick = onClick
    )
}

private fun createComposerCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").also { it.mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    val file = File(dir, "droidance_camera_$stamp.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
}
