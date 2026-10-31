// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.execution

import com.droidance.ui.icons.DroidanceIcons
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.droidance.R
import com.droidance.core.agent.event.DetailAttachmentReadModel
import com.droidance.core.agent.event.DetailAttachmentRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class ExecutionGallerySelection(val images: List<DetailAttachmentReadModel>, val initialPage: Int)

@Composable
internal fun executionImageCaption(attachment: DetailAttachmentReadModel): String {
    if (attachment.historical) {
        val time = attachment.sourceOccurredAt?.let {
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM).format(java.util.Date(it))
        }
        return if (time == null) stringResource(R.string.execution_image_historical)
            else stringResource(R.string.execution_image_historical_at, time)
    }
    return stringResource(when (attachment.role) {
    DetailAttachmentRole.INPUT_OBSERVATION -> R.string.execution_image_before
    DetailAttachmentRole.RESULT -> R.string.execution_image_after
    DetailAttachmentRole.CURRENT_OBSERVATION -> R.string.execution_image_current
    DetailAttachmentRole.DIAGNOSTIC -> R.string.execution_image_related
    })
}

private data class ExecutionImageState(val loading: Boolean = true, val bytes: ByteArray? = null)

@Composable
private fun executionImageState(
    attachment: DetailAttachmentReadModel,
    loadAttachment: suspend (DetailAttachmentReadModel) -> ByteArray?
): State<ExecutionImageState> = produceState(ExecutionImageState(), attachment.contentRef) {
    value = ExecutionImageState()
    val bytes = try { loadAttachment(attachment) } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { null }
    value = ExecutionImageState(loading = false, bytes = bytes)
}

@Composable
internal fun ExecutionImageThumbnail(
    attachment: DetailAttachmentReadModel,
    caption: String,
    loadAttachment: suspend (DetailAttachmentReadModel) -> ByteArray?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by executionImageState(attachment, loadAttachment)
    val openLabel = stringResource(R.string.execution_open_screen)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().height(190.dp).testTag("execution-image:${attachment.attachmentId}")
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .clickable(onClickLabel = openLabel, onClick = onClick), contentAlignment = Alignment.Center) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                state.bytes != null -> AsyncImage(state.bytes, contentDescription = caption,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else -> Text(stringResource(R.string.execution_detail_screen_unavailable),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

/** A frozen selection keeps page identity stable while the underlying task receives new frames. */
@Composable
internal fun ExecutionImageGallery(
    selection: ExecutionGallerySelection,
    loadAttachment: suspend (DetailAttachmentReadModel) -> ByteArray?,
    onClose: () -> Unit
) {
    if (selection.images.isEmpty()) return
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        }
        val pager = rememberPagerState(initialPage = selection.initialPage.coerceIn(selection.images.indices)) {
            selection.images.size
        }
        val scope = rememberCoroutineScope()
        val previousLabel = stringResource(R.string.execution_image_previous)
        val nextLabel = stringResource(R.string.execution_image_next)
        Column(Modifier.fillMaxSize().background(Color.Black).testTag("execution-image-gallery").safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(executionImageCaption(selection.images[pager.currentPage]), color = Color.White,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.execution_image_position, pager.currentPage + 1, selection.images.size),
                        color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("execution-image-position"))
                }
                IconButton(onClick = onClose) {
                    Icon(DroidanceIcons.Close, stringResource(R.string.execution_close_screen), tint = Color.White)
                }
            }
            HorizontalPager(state = pager, key = { selection.images[it].attachmentId },
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("execution-image-pager").semantics {
                    customActions = buildList {
                        if (pager.currentPage > 0) add(CustomAccessibilityAction(previousLabel) {
                            scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }; true
                        })
                        if (pager.currentPage < selection.images.lastIndex) add(CustomAccessibilityAction(nextLabel) {
                            scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }; true
                        })
                    }
                }) { page ->
                val attachment = selection.images[page]
                val state by executionImageState(attachment, loadAttachment)
                Box(Modifier.fillMaxSize().testTag("execution-gallery-page:${attachment.attachmentId}"), contentAlignment = Alignment.Center) {
                    when {
                        state.loading -> CircularProgressIndicator(Modifier.size(24.dp), color = Color.White)
                        state.bytes != null -> AsyncImage(state.bytes, contentDescription = executionImageCaption(attachment),
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        else -> Text(stringResource(R.string.execution_detail_screen_unavailable), color = Color.White,
                            modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }
    }
}
