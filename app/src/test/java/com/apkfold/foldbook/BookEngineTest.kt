package com.apkfold.foldbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookEngineTest {
    @Test fun extensionDetectionIsCaseInsensitive() {
        assertEquals("epub", extensionOf("My Book.EPUB"))
        assertEquals("pdf", extensionOf("chapter.final.PDF"))
        assertEquals("", extensionOf("README"))
    }

    @Test fun textIsEscapedBeforeItIsRenderedAsHtml() {
        val html = BookEngine.plainToHtml("<script>alert(1)</script>\n\nSecond paragraph", "Title")
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(!html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("Second paragraph"))
    }

    @Test fun markdownHeadingsListsAndEmphasisAreConverted() {
        val html = BookEngine.markdownToHtml("# Heading\n\nA **bold** word and *italic*.\n\n- First\n- Second")
        assertTrue(html.contains("<h1>Heading</h1>"))
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("<em>italic</em>"))
        assertTrue(html.contains("<li>First</li>"))
    }

    @Test fun supportedTypesAreExplicit() {
        assertTrue(supportedInternally("pdf"))
        assertTrue(supportedInternally("epub"))
        assertTrue(supportedInternally("cbz"))
        assertTrue(!supportedInternally("xps"))
    }
}
