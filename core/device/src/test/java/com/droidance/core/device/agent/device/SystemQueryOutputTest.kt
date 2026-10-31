// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import java.io.ByteArrayInputStream
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class SystemQueryOutputTest {
    @Test fun selectedNativeFieldsAreBoundedAndUnrelatedOversizedLinesAreDrained() {
        val source = "noise=" + "x".repeat(200_000) + "\n" + "state=" + "a".repeat(30_000) + "\n"
        val stream = ByteArrayInputStream(source.toByteArray())
        val result = SystemQueryOutput.readSelected(stream) { it.startsWith("state=") }
        assertEquals(0, stream.available())
        assertTrue(result.truncated)
        assertTrue(result.stdout.startsWith("state="))
        assertTrue(result.stdout.length < 20_000)
        assertEquals(source.length.toLong(), result.originalChars)
    }
    @Test fun literalSelectionCannotAlterTheTypedPlatformCommand() {
        val query = SystemStateQuery("audio", lineFilter = "'; echo injected; #", contextLines = 2)
        assertEquals("dumpsys -t 5 audio", query.command)
        assertThrows(IllegalArgumentException::class.java) { SystemStateQuery("audio; echo injected") }
        assertThrows(IllegalArgumentException::class.java) { SystemStateQuery("audio", lineFilter = "", contextLines = 1) }
    }

    @Test fun selectedTailIsRecoveredBeyondTheTransportPrefixWithOriginalContext() {
        val prefix = (1..12_000).joinToString("\n") { "unrelated row $it" }
        val dump = "$prefix\nOBJECT_X\nrequired value: 7\nstate: active\nOBJECT_Y\nother: 3"
        assertFalse(BoundedShellOutput.read(ByteArrayInputStream(dump.toByteArray())).text.contains("OBJECT_X"))
        val input = ByteArrayInputStream(dump.toByteArray())
        val selected = SystemQueryOutput.read(input, "REQUIRED VALUE", 1)
        assertEquals(0, input.available())
        assertEquals("12001: OBJECT_X\n12002: required value: 7\n12003: state: active\n", selected.stdout)
        assertEquals(dump.length.toLong(), selected.originalChars)
        assertEquals(12_005, selected.originalLines)
        assertEquals(3, selected.selectedLines)
        assertEquals(selected.stdout.length.toLong(), selected.selectedChars)
        assertFalse(selected.truncated)
    }

    @Test fun overlappingMatchesDoNotRepeatContextAndLiteralSyntaxIsNotExecuted() {
        val selected = SystemQueryOutput.read(StringReader("first\nA.[x]\na.[x]\nlast"), ".[x]", 1)
        assertEquals("1: first\n2: A.[x]\n3: a.[x]\n4: last", selected.stdout)
        assertEquals(4, selected.selectedLines)
        assertFalse(selected.truncated)
    }

    @Test fun platformFailureOutsideTheSelectionCannotBecomeEmptySuccess() {
        for ((text, code) in listOf(
            "Permission Denial: access refused" to "SYSTEM_QUERY_PERMISSION_DENIED",
            "Can't find service: missing" to "SYSTEM_QUERY_SERVICE_UNAVAILABLE",
            "*** SERVICE DUMP TIMEOUT EXPIRED ***" to "SYSTEM_QUERY_TIMEOUT"
        )) {
            val selected = SystemQueryOutput.read(StringReader("noise\n$text\nend"), "unmatched", 0)
            assertEquals("", selected.stdout)
            assertEquals(0, selected.selectedLines)
            assertEquals(code, selected.diagnosticCode)
        }
    }

    @Test fun oversizedSelectionIsBoundedButCountsAndStreamDrainRemainComplete() {
        val dump = "值=中文😀\n".repeat(30_000)
        val input = ByteArrayInputStream(dump.toByteArray())
        val selected = SystemQueryOutput.read(input, "", 0)
        assertEquals(0, input.available())
        assertEquals(dump.length.toLong(), selected.originalChars)
        assertEquals(dump.length.toLong(), selected.selectedChars)
        assertEquals(30_000, selected.originalLines)
        assertEquals(30_000, selected.selectedLines)
        assertTrue(selected.truncated)
        val prefix = selected.stdout
        assertTrue(prefix.toByteArray().size <= BoundedShellOutput.MAX_BYTES)
        assertFalse(prefix.contains('\uFFFD'))
        assertTrue(dump.startsWith(prefix))
    }

    @Test fun smallUtf8CrlfAndFinalLineArePreservedWithoutSelection() {
        val dump = "value=中文😀\r\nstate=4\nfinal"
        val selected = SystemQueryOutput.read(StringReader(dump), "", 0)
        assertEquals(dump, selected.stdout)
        assertEquals(3, selected.originalLines)
        assertFalse(selected.truncated)
    }

    @Test fun matchAcrossReaderBuffersAndInAnOversizedLineIsExplicitlyIncomplete() {
        val dump = "x".repeat(150_000) + "NEEDLE\nend"
        val selected = SystemQueryOutput.read(StringReader(dump), "needle", 0)
        assertEquals(1, selected.selectedLines)
        assertEquals(150_007L, selected.selectedChars)
        assertTrue(selected.truncated)
        assertTrue(selected.stdout.length < dump.length)
        val crossing = "x".repeat(4094) + "NEEDLE\n"
        assertEquals(crossing, SystemQueryOutput.read(StringReader(crossing), "needle", 0).stdout)
    }

    @Test fun emptySourceAndNoMatchRetainDifferentFacts() {
        val empty = SystemQueryOutput.read(StringReader(""), "missing", 0)
        val noMatch = SystemQueryOutput.read(StringReader("real data\n"), "missing", 0)
        assertEquals(0L, empty.originalChars)
        assertEquals(10L, noMatch.originalChars)
        assertEquals("", noMatch.stdout)
        assertFalse(noMatch.truncated)
    }

    @Test fun unicodeBlankSelectionPreservesAnUnfilteredQueryOnAllSupportedApis() {
        val dump = "first\n中文😀\nlast"
        assertEquals(dump, SystemQueryOutput.read(StringReader(dump), " \t\u00a0\u3000", 0).stdout)
        assertThrows(IllegalArgumentException::class.java) {
            SystemQueryOutput.read(StringReader(dump), "\u00a0", 1)
        }
    }

    @Test fun printedTruncationMarkerCannotChangeTypedMetadata() {
        val selected = SystemQueryOutput.read(StringReader("kept\n[SHELL_OUTPUT_TRUNCATED]"), "kept", 0)
        assertFalse(selected.truncated)
        assertEquals(1, selected.selectedLines)
        val noMatch = SystemQueryOutput.read(StringReader("kept\n[SHELL_OUTPUT_TRUNCATED]"), "absent", 0)
        assertFalse(noMatch.truncated)
        assertEquals(0, noMatch.selectedLines)
    }

    @Test fun anOversizedLineNeverLeavesAnUnpairedSurrogateInItsRetainedPrefix() {
        val dump = "x".repeat(16_383) + "😀tail"
        val selected = SystemQueryOutput.read(StringReader(dump), "", 0)
        assertTrue(selected.truncated)
        assertEquals("x".repeat(16_383), selected.stdout)
        assertFalse(selected.stdout.contains('?'))
    }
}
