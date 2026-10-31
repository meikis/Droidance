// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import org.junit.Assert.*
import org.junit.Test

class CompletionLinksTest {
    @Test fun inlineReferenceAutolinkAndEscapesFollowRenderedMarkdown() {
        val links = CompletionLinks.destinations("""
            [inline](artifact:report.md)
            [escaped](https://example.org/a\(b\))
            [report][local]
            <https://example.org/auto>

            [local]: artifact:other.md
            [unused]: artifact:unused.md
            ` [code](artifact:code.md) `
        """.trimIndent())
        assertEquals(setOf("artifact:report.md", "https://example.org/a(b)", "artifact:other.md", "https://example.org/auto"), links)
    }
    @Test fun fencedCodeIsContentAndNotAClickableLink() {
        assertTrue(CompletionLinks.destinations("```\n[example](artifact:fake)\n```").isEmpty())
    }
    @Test fun generatedVersionLinkIsPreservedByTheRenderer() {
        val link = WorkspaceDeliverable.link("turn", "报告.md", "sha256:abc_123")
        assertEquals(setOf(link), CompletionLinks.destinations("[报告.md]($link)"))
    }
    @Test fun bracketedLabelsWithoutLinkTextRemainPlainContent() {
        val content = """
            # Comparison
            - Name: [new] item
            - Configuration: Model[28 units / 1100 pressure]
            |Label|Variant|
            |---|---|
            |[featured]|[voice + recommended]|
        """.trimIndent()
        assertTrue(CompletionLinks.destinations(content).toString(),
            CompletionLinks.destinations(content).isEmpty())
        assertEquals(setOf("https://example.org/source"),
            CompletionLinks.destinations(content + "\n\n[source](https://example.org/source)"))
    }
}
