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

    @Test fun epubHrefPathsDecodePercentEscapesAndIgnoreFragments() {
        assertEquals("OEBPS/Text/chapter 1.xhtml",
            BookEngine.decodeEpubHrefPath("OEBPS/Text/chapter%201.xhtml"))
        assertEquals("images/couverture #1.jpg",
            BookEngine.decodeEpubHrefPath("images/couverture%20%231.jpg#cover"))
    }

    @Test fun htmlEventHandlersAndScriptElementsAreRemoved() {
        val html = BookEngine.sanitizeHtml(
            "<p onclick=\"steal()\">Bonjour</p><img src=x onerror=alert(1)><script src=a.js/><a href=\"javascript:evil()\">lien</a>"
        )
        assertTrue(!html.contains("onclick", ignoreCase = true))
        assertTrue(!html.contains("onerror", ignoreCase = true))
        assertTrue(!html.contains("<script", ignoreCase = true))
        assertTrue(!html.contains("javascript:", ignoreCase = true))
        assertTrue(html.contains("Bonjour"))
    }

    @Test fun rtfHexEscapesDecodeAccentsAsWindows1252() {
        assertEquals("Café", BookEngine.rtfToText("{\\rtf1\\ansi Caf\\'e9}"))
    }

    @Test fun rtfUnicodeEscapesAreDecoded() {
        assertEquals("é", BookEngine.rtfToText("{\\rtf1 \\u233?}"))
    }

    @Test fun xmlEntityDeclarationsAreRejected() {
        val attack = "<?xml version=\"1.0\"?><!DOCTYPE a [<!ENTITY x \"boom\">]><a>&x;</a>".toByteArray()
        assertTrue(BookEngine.containsEntityDeclaration(attack))
        val utf16 = "<!DOCTYPE a [<!ENTITY x \"y\">]><a/>".toByteArray(Charsets.UTF_16LE)
        assertTrue(BookEngine.containsEntityDeclaration(utf16))
    }

    @Test fun ordinaryXmlIsNotRejected() {
        val opf = "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\"><metadata/></package>".toByteArray()
        assertTrue(!BookEngine.containsEntityDeclaration(opf))
    }

    @Test fun supportedTypesAreExplicit() {
        assertTrue(supportedInternally("pdf"))
        assertTrue(supportedInternally("epub"))
        assertTrue(supportedInternally("cbz"))
        assertTrue(!supportedInternally("xps"))
    }
}
