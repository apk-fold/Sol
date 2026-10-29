package com.apkfold.foldbook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class ReaderActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
        const val EXTRA_EXTENSION = "extension"
        const val EXTRA_POSITION = "position"
        private const val PREFS = "foldbook-settings"
        private const val KEY_THEME = "theme"
        private const val KEY_ZOOM = "fontZoom"
        private const val MIN_ZOOM = 70
        private const val MAX_ZOOM = 180
    }

    private enum class ReaderTheme(
        val label: String,
        val background: String,
        val frame: String,
        val text: String,
        val link: String
    ) {
        LIGHT("Clair", "#FBF9F4", "#F1EEE5", "#1F2A22", "#2B6A8A"),
        SEPIA("Sépia", "#F4ECD8", "#EAE0C8", "#3B2F20", "#7A4B1E"),
        NIGHT("Nuit", "#1B1D1B", "#111311", "#D8DCD4", "#8CC4E8");

        fun next(): ReaderTheme = entries[(ordinal + 1) % entries.size]
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var sourceUri: String
    private lateinit var bookName: String
    private lateinit var extension: String
    private lateinit var rootView: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var contentFrame: FrameLayout
    private lateinit var pageLabel: TextView
    private lateinit var themeButton: Button
    private lateinit var fullscreenButton: Button
    private lateinit var library: BookLibrary
    private var theme = ReaderTheme.LIGHT
    private var fullscreen = false
    private var position = 0
    private var payload: ReaderPayload? = null
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var pdfImage: ImageView? = null
    private var webView: WebView? = null
    private var fontZoom = 100
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var currentText = ""
    private var lastContentWidth = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceUri = intent.getStringExtra(EXTRA_URI).orEmpty()
        bookName = intent.getStringExtra(EXTRA_NAME) ?: "Livre"
        extension = intent.getStringExtra(EXTRA_EXTENSION).orEmpty()
        position = intent.getIntExtra(EXTRA_POSITION, 0).coerceAtLeast(0)
        library = BookLibrary(this)
        theme = ReaderTheme.entries.getOrElse(prefs.getInt(KEY_THEME, 0)) { ReaderTheme.LIGHT }
        fontZoom = prefs.getInt(KEY_ZOOM, 100).coerceIn(MIN_ZOOM, MAX_ZOOM)
        // Lecture : l'écran ne doit pas s'éteindre pendant qu'on lit.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        topBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        topBar.addView(button("‹ Retour") { finish() })
        titleView = TextView(this).apply { text = bookName; textSize = 16f; maxLines = 2 }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        topBar.addView(button("A−") { setFontZoom(fontZoom - 10) })
        topBar.addView(button("A+") { setFontZoom(fontZoom + 10) })
        rootView.addView(topBar)

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(button("‹ Précédent") { goTo(position - 1) }, LinearLayout.LayoutParams(0, -2, 1f))
        pageLabel = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setOnClickListener { jumpToPage() }
        }
        controls.addView(pageLabel, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(button("Suivant ›") { goTo(position + 1) }, LinearLayout.LayoutParams(0, -2, 1f))
        rootView.addView(controls)

        contentFrame = FrameLayout(this).apply {
            addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                val newWidth = right - left
                if (newWidth > 0 && newWidth != lastContentWidth) {
                    val wasMeasured = lastContentWidth > 0
                    lastContentWidth = newWidth
                    if (wasMeasured && payload is ReaderPayload.Pdf && renderer != null) {
                        post { (payload as? ReaderPayload.Pdf)?.let { showPdfPage(it) } }
                    }
                }
            }
        }
        rootView.addView(contentFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) })

        themeButton = button("") { cycleTheme() }
        fullscreenButton = button("Plein écran") { setFullscreen(!fullscreen) }
        rootView.addView(buttonRow(
            button("Sommaire") { showToc() },
            button("Aller à") { jumpToPage() },
            themeButton,
            fullscreenButton
        ))
        rootView.addView(buttonRow(
            button("Rechercher") { searchInBook() },
            button("Marque-page") { bookmark() },
            button("Écouter") { speak() }
        ))
        setContentView(rootView)
        applyTheme()
        openBook()
    }

    private fun buttonRow(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        buttons.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }

    private fun applyTheme() {
        val background = Color.parseColor(theme.background)
        rootView.setBackgroundColor(background)
        contentFrame.setBackgroundColor(Color.parseColor(theme.frame))
        titleView.setTextColor(Color.parseColor(theme.text))
        pageLabel.setTextColor(Color.parseColor(theme.text))
        window.statusBarColor = background
        window.navigationBarColor = background
        themeButton.text = "Thème : ${theme.label}"
        webView?.setBackgroundColor(Color.parseColor(theme.frame))
        pdfImage?.setColorFilter(pageFilter())
    }

    private fun cycleTheme() {
        theme = theme.next()
        prefs.edit().putInt(KEY_THEME, theme.ordinal).apply()
        applyTheme()
        // Les chapitres HTML embarquent leurs couleurs : on les recharge avec le nouveau thème.
        (payload as? ReaderPayload.Web)?.let { openWeb(it) }
    }

    /** Les pages PDF sont blanches : en mode nuit on les inverse pour réduire l'éblouissement. */
    private fun pageFilter(): ColorFilter? = if (theme == ReaderTheme.NIGHT) {
        ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    } else {
        null
    }

    private fun setFullscreen(enabled: Boolean) {
        fullscreen = enabled
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        topBar.visibility = if (enabled) View.GONE else View.VISIBLE
        fullscreenButton.text = if (enabled) "Quitter" else "Plein écran"
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Retour quitte d'abord le plein écran, puis la lecture.
        if (fullscreen) setFullscreen(false) else super.onBackPressed()
    }

    private fun openBook() {
        contentFrame.removeAllViews()
        contentFrame.addView(TextView(this).apply {
            text = "Ouverture de votre document…"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.parseColor(theme.text))
        }, FrameLayout.LayoutParams(-1, -1))
        worker.execute {
            try {
                val result = BookEngine.open(this, sourceUri, bookName, extension)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    payload = result
                    when (result) {
                        is ReaderPayload.Pdf -> openPdf(result)
                        is ReaderPayload.Web -> openWeb(result)
                        is ReaderPayload.Comic -> openComic(result)
                        is ReaderPayload.Unsupported -> showUnsupported(result.reason)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { showUnsupported(error.localizedMessage ?: "Impossible d’ouvrir ce fichier.") }
            }
        }
    }

    private fun openPdf(document: ReaderPayload.Pdf) {
        position = position.coerceIn(0, document.pageCount - 1)
        updatePositionLabel(document.pageCount)
        worker.execute {
            try {
                val fd = ParcelFileDescriptor.open(document.file, ParcelFileDescriptor.MODE_READ_ONLY)
                val pdf = PdfRenderer(fd)
                runOnUiThread {
                    if (isFinishing) { pdf.close(); fd.close(); return@runOnUiThread }
                    renderer = pdf
                    descriptor = fd
                    showPdfPage(document)
                }
            } catch (error: Exception) {
                runOnUiThread { showUnsupported("Impossible de rendre ce PDF : fichier endommagé ou protégé.") }
            }
        }
    }

    private fun showPdfPage(document: ReaderPayload.Pdf) {
        val pdf = renderer ?: return
        val index = position.coerceIn(0, document.pageCount - 1)
        worker.execute {
            val bitmap = runCatching {
                pdf.openPage(index).use { page ->
                    val availableWidth = (contentFrame.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) * 0.95f
                    val scale = minOf(2f, availableWidth / page.width).coerceAtLeast(0.5f)
                    // Plafonner les deux dimensions en gardant le ratio (un plafond séparé déformait la page).
                    val fit = minOf(1f, minOf(2400f / (page.width * scale), 3400f / (page.height * scale)))
                    val width = (page.width * scale * fit).toInt().coerceAtLeast(1)
                    val height = (page.height * scale * fit).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    // transform = null : PdfRenderer met la page à l'échelle du bitmap (une Matrix identité la recadrait).
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }.getOrNull()
            runOnUiThread {
                if (isFinishing) { bitmap?.recycle(); return@runOnUiThread }
                if (bitmap == null) { Toast.makeText(this, "Erreur de rendu PDF", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                val image = ImageView(this).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    contentDescription = "Page PDF"
                    setColorFilter(pageFilter())
                }
                pdfImage = image
                showInScroll(image)
                updatePositionLabel(document.pageCount)
            }
        }
    }

    private fun openWeb(document: ReaderPayload.Web) {
        position = position.coerceIn(0, document.chapters.lastIndex)
        val file = document.chapters[position]
        currentText = document.plainTexts.getOrElse(position) { "" }
        val base = file.parentFile ?: cacheDir
        val web = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.textZoom = fontZoom
            webViewClient = WebViewClient()
            setBackgroundColor(Color.parseColor(theme.frame))
            // Le dossier du chapitre sert de base : les images et feuilles de style relatives restent accessibles.
            loadDataWithBaseURL(Uri.fromFile(base).toString() + "/", themedHtml(file), "text/html", "UTF-8", null)
        }
        webView = web
        contentFrame.removeAllViews()
        contentFrame.addView(web, FrameLayout.LayoutParams(-1, -1))
        updatePositionLabel(document.chapters.size)
    }

    /** Injecte le thème de lecture (couleurs, interligne, largeur de colonne) dans le chapitre. */
    private fun themedHtml(file: File): String {
        val html = file.readText(Charsets.UTF_8)
        val css = """<style>
html, body { background: ${theme.frame} !important; color: ${theme.text} !important; }
body { line-height: 1.6; max-width: 42em; margin: 0 auto; padding: 12px 16px; overflow-wrap: break-word; }
img, svg { max-width: 100%; height: auto; }
a { color: ${theme.link}; }
</style>"""
        val head = Regex("(?i)<head[^>]*>").find(html) ?: return css + html
        return html.substring(0, head.range.last + 1) + css + html.substring(head.range.last + 1)
    }

    private fun openComic(document: ReaderPayload.Comic) {
        position = position.coerceIn(0, document.images.lastIndex)
        currentText = document.title
        val imageFile = document.images[position]
        // Décodage hors du thread principal, réduit à environ deux fois la largeur de l'écran.
        val budget = resources.displayMetrics.widthPixels * 2
        worker.execute {
            val bitmap = decodeScaled(imageFile, budget)
            runOnUiThread {
                if (isFinishing) { bitmap?.recycle(); return@runOnUiThread }
                if (bitmap == null) { showUnsupported("Image illisible."); return@runOnUiThread }
                val image = ImageView(this).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    contentDescription = imageFile.name
                }
                showInScroll(image)
                updatePositionLabel(document.images.size)
            }
        }
    }

    private fun decodeScaled(file: File, budgetPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= budgetPx) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun showInScroll(view: View) {
        contentFrame.removeAllViews()
        contentFrame.addView(
            ScrollView(this).apply { addView(view, FrameLayout.LayoutParams(-1, -2)) },
            FrameLayout.LayoutParams(-1, -1)
        )
    }

    private fun setFontZoom(value: Int) {
        fontZoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
        prefs.edit().putInt(KEY_ZOOM, fontZoom).apply()
        webView?.settings?.textZoom = fontZoom
        Toast.makeText(this, "Taille du texte : $fontZoom %", Toast.LENGTH_SHORT).show()
    }

    private fun pageCount(): Int? = when (val p = payload) {
        is ReaderPayload.Pdf -> p.pageCount
        is ReaderPayload.Web -> p.chapters.size
        is ReaderPayload.Comic -> p.images.size
        else -> null
    }

    private fun goTo(index: Int) {
        val count = pageCount() ?: return
        val target = index.coerceIn(0, count - 1)
        if (target == position) return
        position = target
        when (val p = payload) {
            is ReaderPayload.Pdf -> showPdfPage(p)
            is ReaderPayload.Web -> openWeb(p)
            is ReaderPayload.Comic -> openComic(p)
            else -> Unit
        }
        library.updatePosition(sourceUri, position)
    }

    private fun jumpToPage() {
        val count = pageCount() ?: return
        val field = EditText(this).apply {
            hint = "1 – $count"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Aller à la page")
            .setView(field)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Aller") { _, _ ->
                val target = field.text.toString().toIntOrNull()
                if (target != null && target in 1..count) goTo(target - 1)
                else Toast.makeText(this, "Numéro hors limites", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showToc() {
        val web = payload as? ReaderPayload.Web
        if (web == null || web.plainTexts.size < 2) {
            Toast.makeText(this, "Sommaire indisponible pour ce document", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = web.plainTexts.mapIndexed { index, text -> "${index + 1}. ${tocLabel(text)}" }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("Sommaire")
            .setItems(labels) { _, which -> goTo(which) }
            .show()
    }

    /** Pas de titres de chapitre dans le texte brut : on prend la première ligne non vide. */
    private fun tocLabel(text: String): String {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "Partie"
        return if (first.length > 48) first.take(48) + "…" else first
    }

    private fun updatePositionLabel(count: Int) {
        val percent = if (count > 0) (position + 1) * 100 / count else 0
        pageLabel.text = "${position + 1} / $count · $percent %"
    }

    private fun bookmark() {
        library.updatePosition(sourceUri, position)
        Toast.makeText(this, "Position sauvegardée", Toast.LENGTH_SHORT).show()
    }

    private fun searchInBook() {
        val field = EditText(this).apply { hint = "Texte à rechercher" }
        AlertDialog.Builder(this).setTitle("Rechercher dans le livre").setView(field)
            .setNegativeButton("Fermer", null).setPositiveButton("Rechercher") { _, _ ->
                val query = field.text.toString()
                if (webView != null) webView?.findAllAsync(query)
                else Toast.makeText(this, if (currentText.contains(query, true)) "Texte présent dans cette page" else "Texte non trouvé sur cette page", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun speak() {
        if (currentText.isBlank()) {
            Toast.makeText(this, "Aucun texte disponible sur cette page", Toast.LENGTH_SHORT).show()
            return
        }
        val text = currentText.take(4000)
        if (textToSpeech == null) textToSpeech = TextToSpeech(this, this)
        // Le moteur TTS est asynchrone : on attend onInit avant de parler.
        if (ttsReady) textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "foldbook-reading")
        else pendingSpeech = text
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Toast.makeText(this, "Synthèse vocale indisponible sur cet appareil", Toast.LENGTH_SHORT).show()
            return
        }
        textToSpeech?.language = Locale.getDefault()
        ttsReady = true
        pendingSpeech?.let { textToSpeech?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "foldbook-reading") }
        pendingSpeech = null
    }

    private fun showUnsupported(reason: String) {
        contentFrame.removeAllViews()
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        panel.addView(TextView(this).apply {
            text = "Ce format ne peut pas être ouvert ici\n\n$reason"
            textSize = 16f
            setTextColor(Color.parseColor(theme.text))
            gravity = Gravity.CENTER
        })
        panel.addView(button("Ouvrir avec une autre application") {
            runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(sourceUri), "*/*")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            }.onFailure { Toast.makeText(this, "Aucune application compatible trouvée", Toast.LENGTH_LONG).show() }
        })
        contentFrame.addView(panel, FrameLayout.LayoutParams(-1, -1))
        pageLabel.text = "Format externe"
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        webView?.destroy()
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        worker.shutdown()
        super.onDestroy()
    }
}
