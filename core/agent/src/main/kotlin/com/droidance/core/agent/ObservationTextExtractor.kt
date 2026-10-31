// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ObservationTextExtractor {
    fun extract(raw: String, preserveCoordinates: Boolean = false): List<String> {
        if (raw.trimStart().startsWith(PURE_VISION_TRANSPORT_MARKER)) {
            return emptyList()
        }
        // observe_screen 现在以 <untrusted_screen_content> 可读文本块为主体（CUA 单视图），
        // 优先从中抽取；老格式（裸 JSON）走 structuredTexts 兜底，纯文本行再兜底。
        readableContent(raw, preserveCoordinates)?.takeIf { it.isNotEmpty() }?.let { return it }
        structuredTexts(raw)?.takeIf { it.isNotEmpty() }?.let { return it }
        return raw.lineSequence()
            .mapNotNull { cleanVisibleLine(it, preserveCoordinates) }
            .distinct()
            .toList()
    }

    private fun readableContent(raw: String, preserveCoordinates: Boolean): List<String>? {
        val open = "<untrusted_screen_content>"
        val close = "</untrusted_screen_content>"
        val start = raw.indexOf(open)
        if (start < 0) return null
        val end = raw.indexOf(close, start)
        if (end < 0) return null
        return raw.substring(start + open.length, end)
            .lineSequence()
            .filterNot { it.trim().startsWith("[") }  // 跳过 [屏幕文字…]/[AIR…] 等说明性表头
            .mapNotNull { cleanVisibleLine(it, preserveCoordinates) }
            .distinct()
            .toList()
    }

    private fun structuredTexts(raw: String): List<String>? {
        // readableContent 已处理带文本块的新格式；这里只接受真正以 JSON 对象开头的旧格式。
        // 不在普通工具结果中搜索任意的 "{"uff0c避免把参数片段误当成屏幕 JSON。
        val jsonPart = raw.trimStart()
        if (!jsonPart.startsWith("{")) return null
        // 只取到末尾第一个完整 JSON 对象，避免把后续诊断文本混入状态摘要。
        val jsonOnly = jsonPart.substringBefore("\n\n")
        val root = runCatching { Json.parseToJsonElement(jsonOnly.trimStart()).jsonObject }
            .getOrNull() ?: return null
        val textBlocks = root["textBlocks"]?.jsonArray
            ?.mapNotNull { node -> node.jsonObject["text"]?.jsonPrimitive?.content }
            .orEmpty()
        val targets = root["targets"]?.jsonArray
            ?.mapNotNull { node -> node.jsonObject["text"]?.jsonPrimitive?.content }
            .orEmpty()
        return (textBlocks + targets)
            .mapNotNull { cleanVisibleLine(it, preserveCoordinates = false) }
            .distinct()
    }

    private fun cleanVisibleLine(line: String, preserveCoordinates: Boolean): String? {
        val trimmed = line.trim()
            .removePrefix("-")
            .trim()
        if (trimmed.isBlank()) return null

        val quoted = Regex("""「(.{2,160}?)」""").find(trimmed)?.groupValues?.getOrNull(1)
        val coordinateSuffix = if (preserveCoordinates) {
            Regex("""\s*@\(-?\d+,-?\d+\)""").find(trimmed)?.value.orEmpty()
        } else {
            ""
        }
        val targetIdRegex = Regex("""\[(text_\d+|t\d+|\d+)]\s*""")
        val candidate = quoted ?: run {
            val withoutCoords = trimmed.replace(Regex("""\s*@\(-?\d+,-?\d+\)"""), "")
            if (!preserveCoordinates) {
                withoutCoords.replace(targetIdRegex, "")
            } else {
                withoutCoords
            }
        }.trim().trim('"', '\'', '：', ':')

        return candidate
            .replace(Regex("""\s+"""), " ")
            .plus(coordinateSuffix)
            .takeIf { it.length in 2..180 }
    }

    private const val PURE_VISION_TRANSPORT_MARKER = "[PURE_VLM_SCREEN]"
}
