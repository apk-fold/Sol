package com.apkfold.foldbook

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val library by lazy { BookLibrary(this) }
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private var lastLibraryWidthPx = -1

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importBook(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            setBackgroundColor(0xFFF8F6F0.toInt())
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val heading = TextView(this).apply {
            text = "FoldBook\nVotre bibliothèque, à votre rythme"
            textSize = 20f
            setTextColor(0xFF253027.toInt())
        }
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply { text = "＋ Importer"; setOnClickListener { picker.launch(arrayOf("*/*")) } })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Lire, partout. Pensé pour les grands écrans pliables, confortable aussi sur téléphone.\nPDF · EPUB · texte · BD · documents · hors ligne"
            textSize = 14f
            setTextColor(0xFF475B4C.toInt())
            setPadding(dp(16), dp(18), dp(16), dp(18))
            setBackgroundColor(0xFFE4E9DC.toInt())
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        search = EditText(this).apply {
            hint = "Rechercher un titre ou un format…"
            maxLines = 1
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderBooks()
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
            val widthPx = right - left
            if (widthPx > 0 && widthPx != lastLibraryWidthPx) {
                lastLibraryWidthPx = widthPx
                view.post { renderBooks() }
            }
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "Privé par défaut · les fichiers d’origine ne sont jamais modifiés"
            textSize = 11f
            setTextColor(0xFF7E8177.toInt())
        })
        setContentView(root)
        incoming(intent)
        renderBooks()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming(intent)
    }

    private fun incoming(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null) importBook(uri)
    }

    private fun importBook(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "Livre sans titre" }
        val ext = extensionOf(name)
        library.add(Book(uri.toString(), name, ext))
        renderBooks()
    }

    private fun renderBooks() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val query = search.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val books = library.all().filter { query.isBlank() || it.name.lowercase(Locale.ROOT).contains(query) || it.extension.contains(query) }
        if (books.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (query.isBlank()) "Votre prochaine lecture commence ici.\nImportez un PDF, un EPUB, du texte ou une bande dessinée." else "Aucun résultat."
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(0xFF475B4C.toInt())
                setPadding(dp(24), dp(40), dp(24), dp(40))
            })
            return
        }
        val width = if (list.width > 0) (list.width / resources.displayMetrics.density).toInt() else resources.configuration.screenWidthDp
        val columns = if (width >= 1000) 3 else if (width >= 600) 2 else 1
        books.chunked(columns).forEach { group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEach { book ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    setBackgroundColor(0xFFFFFFFF.toInt())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        startActivity(Intent(this@MainActivity, ReaderActivity::class.java).apply {
                            putExtra(ReaderActivity.EXTRA_URI, book.uri)
                            putExtra(ReaderActivity.EXTRA_NAME, book.name)
                            putExtra(ReaderActivity.EXTRA_EXTENSION, book.extension)
                            putExtra(ReaderActivity.EXTRA_POSITION, book.lastPosition)
                        })
                    }
                }
                card.addView(TextView(this).apply { text = "▤  " + book.extension.uppercase(Locale.ROOT); textSize = 12f; setTextColor(0xFF435B46.toInt()) })
                card.addView(TextView(this).apply { text = book.name; textSize = 16f; setTextColor(0xFF253027.toInt()); maxLines = 3 }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                card.addView(TextView(this).apply {
                    text = if (book.lastPosition > 0) "Reprendre la lecture · page/section ${book.lastPosition + 1}" else "Toucher pour ouvrir"
                    textSize = 12f
                    setTextColor(0xFF6F7469.toInt())
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                card.setOnLongClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Retirer ce livre ?")
                        .setMessage("Le fichier original ne sera pas supprimé.")
                        .setNegativeButton("Annuler", null)
                        .setPositiveButton("Retirer") { _, _ -> library.remove(book.uri); renderBooks() }
                        .show()
                    true
                }
                row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8); bottomMargin = dp(8) })
            }
            repeat(columns - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            list.addView(row)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
