package com.apkfold.foldbook

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class Book(
    val uri: String,
    val name: String,
    val extension: String,
    val lastPosition: Int = 0,
    val addedAt: Long = System.currentTimeMillis()
)

internal class BookLibrary(context: Context) {
    private val prefs = context.getSharedPreferences("foldbook-library-v1", Context.MODE_PRIVATE)

    @Synchronized fun all(): List<Book> {
        val array = runCatching { JSONArray(prefs.getString("books", "[]") ?: "[]") }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val uri = item.optString("uri").takeIf { it.isNotBlank() } ?: continue
                add(Book(uri, item.optString("name", "Livre sans titre"), item.optString("extension", ""),
                    item.optInt("position", 0), item.optLong("addedAt", 0L)))
            }
        }.sortedByDescending { it.addedAt }
    }

    @Synchronized fun add(book: Book): Boolean {
        val books = all()
        if (books.any { it.uri == book.uri }) return false
        persist(listOf(book) + books)
        return true
    }

    @Synchronized fun updatePosition(uri: String, position: Int) =
        persist(all().map { if (it.uri == uri) it.copy(lastPosition = position.coerceAtLeast(0)) else it })

    @Synchronized fun remove(uri: String) = persist(all().filterNot { it.uri == uri })

    private fun persist(books: List<Book>) {
        val array = JSONArray()
        books.forEach { book -> array.put(JSONObject().apply {
            put("uri", book.uri); put("name", book.name); put("extension", book.extension)
            put("position", book.lastPosition); put("addedAt", book.addedAt)
        }) }
        prefs.edit().putString("books", array.toString()).apply()
    }
}

internal fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase().trim()
internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun supportedInternally(extension: String): Boolean = extension.lowercase() in setOf(
    "pdf", "epub", "txt", "text", "md", "markdown", "html", "htm", "xhtml", "fb2", "rtf",
    "docx", "odt", "cbz", "jpg", "jpeg", "png", "webp", "gif"
)
