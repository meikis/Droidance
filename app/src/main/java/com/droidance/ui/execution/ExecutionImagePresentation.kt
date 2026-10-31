// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.execution

import com.droidance.core.agent.event.DetailAttachmentReadModel
import com.droidance.core.agent.event.DetailAttachmentRole

/** Show result frames without repeating the input observation or intermediate batch screenshots. */
internal fun orderedExecutionImages(attachments: List<DetailAttachmentReadModel>): List<DetailAttachmentReadModel> =
    attachments.filter {
        it.mimeType.startsWith("image/") &&
            it.role != DetailAttachmentRole.INPUT_OBSERVATION && it.batchActionIndex == null
    }
        .sortedWith(compareBy<DetailAttachmentReadModel> {
            when (it.role) {
                DetailAttachmentRole.RESULT -> 0
                DetailAttachmentRole.CURRENT_OBSERVATION -> 1
                else -> 4
            }
        }.thenBy { it.sequence })
        .distinctBy { it.contentRef to it.role }
