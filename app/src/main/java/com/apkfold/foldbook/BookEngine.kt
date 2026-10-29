package com.apkfold.foldbook

import android.content.Context
import android.net.Uri
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

internal sealed class ReaderPayload {
    data class Pdf(val file: File, val pageCount: Int) : ReaderPayload()
    data class Web(val chapters: List<File>, val plainTexts: List<String>, val title: String) : ReaderPayload()
    data class Comic(val images: List<File>, val title: String) : ReaderPayload()
    data class Unsupported(val reason: String) : ReaderPayload()
}

internal object BookEngine {
    private const val MAX_INPUT_BYTES = 512L * 1024 * 1024
    private const val MAX_EXTRACTED_BYTES = 1024L * 1024 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 20_000
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")

    fun open(context: Context, uriString: String, displayName: String, extension: String): ReaderPayload {
        val ext = extension.lowercase(Locale.ROOT)
        val source = copyUriToCache(context, uriString, ext)
        return when (ext) {
            "pdf" -> {
                val pfd = android.os.ParcelFileDescriptor.open(source, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                val count = android.graphics.pdf.PdfRenderer(pfd).use { it.pageCount }
                require(count > 0) { "Ce PDF ne contient aucune page." }
                ReaderPayload.Pdf(source, count)
            }
            "epub" -> loadEpub(context, source, displayName)
            "cbz" -> ReaderPayload.Comic(extractImagesZip(source), displayName)
            "jpg", "jpeg", "png", "webp", "gif" -> ReaderPayload.Comic(listOf(source), displayName)
            "txt", "text" -> webFromHtml(context, displayName, plainToHtml(readText(source), displayName))
            "md", "markdown" -> webFromHtml(context, displayName, markdownToHtml(readText(source)))
            "html", "htm", "xhtml" -> webFromHtml(context, displayName, sanitizeHtml(readText(source)))
            "fb2" -> webFromHtml(context, displayName, plainToHtml(xmlToText(source, "body"), displayName))
            "rtf" -> webFromHtml(context, displayName, plainToHtml(rtfToText(readText(source)), displayName))
            "docx" -> webFromHtml(context, displayName, plainToHtml(readOfficeXml(source, "word/document.xml"), displayName))
            "odt" -> webFromHtml(context, displayName, plainToHtml(readOfficeXml(source, "content.xml"), displayName))
            else -> ReaderPayload.Unsupported("Le format .$ext n'est pas pris en charge par le lecteur intégré. Essayez « Ouvrir avec » depuis cette page.")
        }
    }

    /**
     * EPUB manifest paths are URL paths, not raw filesystem paths. Decoding percent-escapes
     * before resolving them is important for books with spaces, accented names, or # in names.
     */
    internal fun decodeEpubHrefPath(href: String): String {
        val path = href.substringBefore('#').substringBefore('?')
        return runCatching { URI(path.replace(" ", "%20")).path }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: path
    }

    private fun resolveEpubFile(root: File, base: File, href: String): File? {
        val candidate = runCatching { File(base, decodeEpubHrefPath(href)).canonicalFile }.getOrNull()
            ?: return null
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return null
        return candidate.takeIf {
            it.path.startsWith(rootPath + File.separator) && it.isFile
        }
    }

    private fun copyUriToCache(context: Context, uriString: String, ext: String): File {
        val directory = File(context.cacheDir, "imported-books").apply { mkdirs() }
        val target = File(directory, "${sha256(uriString)}.${ext.ifBlank { "bin" }}")
        if (target.isFile && target.length() > 0) return target
        val partial = File(directory, target.name + ".part").apply { delete() }
        try {
            val input = context.contentResolver.openInputStream(Uri.parse(uriString))
                ?: error("Android ne peut pas lire ce fichier. Réimportez-le depuis le sélecteur de fichiers.")
            input.use { source ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_INPUT_BYTES) { "Fichier trop volumineux (limite locale : 512 Mo)." }
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(partial.isFile && partial.length() > 0) { "Le fichier est vide ou illisible." }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "Impossible d'enregistrer le fichier dans le cache." }
            return target
        } catch (error: Exception) {
            partial.delete()
            target.delete()
            throw error
        }
    }

    private fun loadEpub(context: Context, source: File, fallbackTitle: String): ReaderPayload {
        val root = File(context.cacheDir, "epub-${sha256(source.absolutePath + source.length())}")
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()
        extractZip(source, root)
        val container = File(root, "META-INF/container.xml")
        require(container.isFile) { "EPUB invalide : META-INF/container.xml est absent." }
        val packagePath = elements(parseXml(container.readBytes()), "rootfile").firstOrNull()
            ?.getAttribute("full-path")?.takeIf { it.isNotBlank() }
            ?: error("EPUB invalide : fichier OPF introuvable.")
        val opf = resolveEpubFile(root, root, packagePath)
            ?: error("EPUB invalide : chemin du fichier OPF invalide ou absent.")
        val doc = parseXml(opf.readBytes())
        val manifest = elements(doc, "item").mapNotNull { item ->
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            if (id.isNotBlank() && href.isNotBlank()) id to href else null
        }.toMap()
        val base = opf.parentFile ?: root
        val chapters = elements(doc, "itemref").mapNotNull { item ->
            val href = manifest[item.getAttribute("idref")] ?: return@mapNotNull null
            val candidate = resolveEpubFile(root, base, href)
            if (candidate != null &&
                candidate.extension.lowercase(Locale.ROOT) in setOf("xhtml", "html", "htm", "xml", "svg")) candidate else null
        }
        require(chapters.isNotEmpty()) { "EPUB invalide ou sans chapitre lisible." }
        val plain = chapters.map { runCatching { htmlToText(it.readText(Charsets.UTF_8)) }.getOrDefault("") }
        val title = elements(doc, "title").firstOrNull()?.textContent?.trim().orEmpty().ifBlank { fallbackTitle }
        return ReaderPayload.Web(chapters, plain, title)
    }

    private fun extractImagesZip(source: File): List<File> {
        val dir = File(source.parentFile, "${source.nameWithoutExtension}-pages")
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        extractZip(source, dir)
        return dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in imageExtensions }
            .sortedBy { it.relativeTo(dir).path.lowercase(Locale.ROOT) }.toList()
            .also { require(it.isNotEmpty()) { "Cette archive ne contient aucune image lisible." } }
    }

    private fun extractZip(source: File, destination: File) {
        destination.mkdirs()
        var total = 0L
        var entries = 0
        ZipInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries++
                require(entries <= MAX_ARCHIVE_ENTRIES) { "Archive contenant trop d'éléments." }
                val output = File(destination, entry.name).canonicalFile
                require(output.path == destination.canonicalPath ||
                    output.path.startsWith(destination.canonicalPath + File.separator)) { "Archive refusée : chemin de fichier invalide." }
                if (entry.isDirectory) output.mkdirs() else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_EXTRACTED_BYTES) { "Archive trop volumineuse après extraction." }
                            out.write(buffer, 0, read)
                        }
                    }
                }
                input.closeEntry()
            }
        }
    }

    private fun webFromHtml(context: Context, title: String, html: String): ReaderPayload.Web {
        val directory = File(context.cacheDir, "rendered-books").apply { mkdirs() }
        val out = File(directory, "${sha256(title + html.length + html.hashCode())}.html")
        out.writeText(html, Charsets.UTF_8)
        return ReaderPayload.Web(listOf(out), listOf(htmlToText(html)), title)
    }

    internal fun plainToHtml(text: String, title: String): String {
        val content = escapeHtml(text).split(Regex("\\n\\s*\\n"))
            .joinToString("\n") { "<p>${it.replace("\n", "<br>")}</p>" }
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(title)}</title></head><body><main>$content</main></body></html>"""
    }

    internal fun markdownToHtml(markdown: String): String {
        val body = markdown.replace("\r\n", "\n").split(Regex("\\n{2,}")).joinToString("\n") { block ->
            val value = block.trim()
            when {
                value.isBlank() -> ""
                value.startsWith("### ") -> "<h3>${inlineMarkdown(value.drop(4))}</h3>"
                value.startsWith("## ") -> "<h2>${inlineMarkdown(value.drop(3))}</h2>"
                value.startsWith("# ") -> "<h1>${inlineMarkdown(value.drop(2))}</h1>"
                value.lines().all { it.trimStart().startsWith("- ") } ->
                    "<ul>" + value.lines().joinToString("") { "<li>${inlineMarkdown(it.trim().drop(2))}</li>" } + "</ul>"
                else -> "<p>${inlineMarkdown(value).replace("\n", "<br>")}</p>"
            }
        }
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body>$body</body></html>"""
    }

    private fun inlineMarkdown(value: String): String {
        var html = escapeHtml(value)
        html = html.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        return html.replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
    }

    internal fun escapeHtml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    internal fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style|svg|head)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)</(p|div|h[1-6]|li|tr|section|br|article)>"), "\n")
        .replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">").replace(Regex("[\\t\\r ]+"), " ")
        .replace(Regex(" *\\n *"), "\n").trim()

    private fun sanitizeHtml(input: String): String {
        var html = input.replace(Regex("(?is)<(script|iframe|object|embed|form|base)[^>]*>.*?</\\1\\s*>"), "")
        html = html.replace(Regex("""(?i)\\s+on[a-z]+\\s*=\\s*("[^"]*"|'[^']*'|[^\\s>]+)"""), "")
        html = html.replace(Regex("""(?i)(href|src)\\s*=\\s*(['"]?)\\s*javascript:[^'">\\s]*\\2""")) { match -> "${match.groupValues[1]}=\"#\"" }
        return if (html.contains("<html", true)) html else "<html><head><meta charset=\"utf-8\"></head><body>$html</body></html>"
    }

    private fun rtfToText(input: String): String = input
        .replace(Regex("(?s)\\{\\\\\\*.*?}"), "")
        .replace(Regex("\\\\'[0-9a-fA-F]{2}"), " ")
        .replace(Regex("\\\\(par|line)\\b ?"), "\n")
        .replace(Regex("\\\\tab\\b ?"), "\t")
        .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
        .replace(Regex("[{}]"), "").trim()

    private fun readOfficeXml(file: File, entryName: String): String {
        java.util.zip.ZipFile(file).use { zip ->
            val entry = zip.getEntry(entryName) ?: error("Document Office invalide : contenu textuel absent.")
            val doc = zip.getInputStream(entry).use { parseXml(it.readBytes()) }
            val paragraphs = elements(doc, "p").map { it.textContent.orEmpty().trim() }.filter { it.isNotBlank() }
            return paragraphs.joinToString("\n\n").ifBlank { doc.documentElement.textContent.orEmpty() }
        }
    }

    private fun xmlToText(file: File, containerName: String): String {
        val doc = parseXml(file.readBytes())
        return elements(doc, containerName).firstOrNull()?.textContent.orEmpty().trim()
            .ifBlank { doc.documentElement.textContent.orEmpty().trim() }
    }

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun elements(doc: Document, name: String): List<Element> {
        val all = doc.getElementsByTagName("*")
        return (0 until all.length).mapNotNull { all.item(it) as? Element }
            .filter { (it.localName ?: it.tagName.substringAfter(':')) == name }
    }

    private fun readText(file: File): String = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
}
