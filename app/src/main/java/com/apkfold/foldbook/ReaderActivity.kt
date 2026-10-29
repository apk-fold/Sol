package com.apkfold.foldbook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Lecteur en plein écran immersif, sans bouton dédié :
 * - par défaut, aucune barre ni menu : seulement le livre ;
 * - toucher le tiers gauche / droit de l'écran tourne la page précédente / suivante ;
 * - glisser horizontalement fait de même ;
 * - toucher le centre affiche le menu (titre, réglages, sommaire…) ; un nouveau toucher sur le texte le referme.
 */
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
        /** Zone de page tournée sur chaque bord (30 % de la largeur). */
        private const val EDGE_ZONE = 0.3f
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
    private lateinit var controls: LinearLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var contentFrame: FrameLayout
    private lateinit var pageLabel: TextView
    private lateinit var themeButton: Button
    private lateinit var library: BookLibrary
    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val width = contentFrame.width.takeIf { it > 0 } ?: return false
                when {
                    overlayVisible -> setOverlayVisible(false)
                    e.x / width < EDGE_ZONE -> goTo(position - 1)
                    e.x / width > 1f - EDGE_ZONE -> goTo(position + 1)
                    else -> setOverlayVisible(true)
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                // Balayage horizontal franc uniquement, pour ne pas gêner le défilement vertical.
                if (overlayVisible || abs(dx) < dp(80) || abs(dx) < abs(dy) || abs(velocityX) < dp(200)) return false
                if (dx < 0) goTo(position + 1) else goTo(position - 1)
                return true
            }
        })
    }
    private var theme = ReaderTheme.LIGHT
    private var overlayVisible = false
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

        rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Android 15+ dessine sous les barres système : on réserve leur place (et celle de l'encoche).
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + dp(12), bars.top + dp(8), bars.right + dp(12), bars.bottom + dp(8))
            insets
        }

        // Menu du haut (masqué par défaut).
        topBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        topBar.addView(button("‹ Retour") { finish() })
        titleView = TextView(this).apply { text = bookName; textSize = 16f; maxLines = 2 }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        topBar.addView(button("A−") { setFontZoom(fontZoom - 10) })
        topBar.addView(button("A+") { setFontZoom(fontZoom + 10) })
        rootView.addView(topBar)

        // Pagination (masquée par défaut).
        controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        controls.addView(button("‹ Précédent") { goTo(position - 1) }, LinearLayout.LayoutParams(0, -2, 1f))
        pageLabel = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setOnClickListener { jumpToPage() }
        }
        controls.addView(pageLabel, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(button("Suivant ›") { goTo(position + 1) }, LinearLayout.LayoutParams(0, -2, 1f))
        rootView.addView(controls)

        // Zone de lecture : occupe tout l'espace restant quand le menu est fermé.
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
        rootView.addView(contentFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(4) })

        // Menu du bas (masqué par défaut).
        themeButton = button("") { cycleTheme() }
        bottomPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        bottomPanel.addView(buttonRow(
            button("Sommaire") { showToc() },
            button("Aller à") { jumpToPage() },
            themeButton
        ))
        bottomPanel.addView(buttonRow(
            button("Rechercher") { searchInBook() },
            button("Marque-page") { bookmark() },
            button("Écouter") { speak() }
        ))
        rootView.addView(bottomPanel)

        setContentView(rootView)
        applyTheme()
        hideSystemBars()
        openBook()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Les barres reviennent parfois après une notification ou un changement d'écran : on les recache.
        if (hasFocus && !overlayVisible) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Affiche ou masque le menu, avec les barres système (elles suivent le menu). */
    private fun setOverlayVisible(visible: Boolean) {
        overlayVisible = visible
        val state = if (visible) View.VISIBLE else View.GONE
        topBar.visibility = state
        controls.visibility = state
        bottomPanel.visibility = state
        if (visible) {
            WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        } else {
            hideSystemBars()
        }
    }

    private fun buttonRow(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        buttons.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }

    private fun applyTheme() {
        val background = Color.parseColor(theme.background)
        rootView.setBackgroundColor(background)
        contentFrame.setBackgroundColor(Color.parseColor(theme.frame))
        topBar.setBackgroundColor(background)
        controls.setBackgroundColor(background)
        bottomPanel.setBackgroundColor(background)
        titleView.setTextColor(Color.parseColor(theme.text))
        pageLabel.setTextColor(Color.parseColor(theme.text))
        window.statusBarColor = background
        window.navigationBarColor = background
        // Icônes des barres système : sombres sur fond clair, claires en mode nuit.
        val barIcons = WindowCompat.getInsetsController(window, rootView)
        barIcons.isAppearanceLightStatusBars = theme != ReaderTheme.NIGHT
        barIcons.isAppearanceLightNavigationBars = theme != ReaderTheme.NIGHT
        themeButton.text = "Thème : ${theme.label}"
        webView?.setBackgroundColor(Color.parseColor(theme.frame))
        pdfImage?.let { applyPageFilter(it) }
    }

    private fun cycleTheme() {
        theme = theme.next()
        prefs.edit().putInt(KEY_THEME, theme.ordinal).apply()
        applyTheme()
        // Les chapitres HTML embarquent leurs couleurs : on les recharge avec le nouveau thème.
        (payload as? ReaderPayload.Web)?.let { openWeb(it) }
    }

    /** Les pages PDF sont blanches : en mode nuit on les inverse pour réduire l'éblouissement. */
    private fun applyPageFilter(image: ImageView) {
        if (theme == ReaderTheme.NIGHT) {
            val invert = ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            image.setColorFilter(ColorMatrixColorFilter(invert))
        } else {
            image.clearColorFilter()
        }
    }

    /** Retour : d'abord fermer le menu, puis quitter la lecture. */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (overlayVisible) setOverlayVisible(false) else super.onBackPressed()
    }

    /** Relie les gestes de lecture à une vue de contenu, sans la empêcher de défiler. */
    private fun attachGestures(view: View) {
        view.setOnTouchListener { _, event ->
            gestures.onTouchEvent(event)
            false
        }
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
                    // Matrice d'échelle explicite : points de la page -> pixels du bitmap (sans elle, la page est recadrée).
                    val matrix = Matrix()
                    matrix.setScale(width.toFloat() / page.width, height.toFloat() / page.height)
                    page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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
                }
                applyPageFilter(image)
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
            loadDataWithBaseURL(Uri.fromFile(base).toString() + "/", themedHtml(file), "text/html", "UTF-8", "")
        }
        attachGestures(web)
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
        val scroll = ScrollView(this).apply { addView(view, FrameLayout.LayoutParams(-1, -2)) }
        attachGestures(scroll)
        contentFrame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
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
        if (index < 0) { Toast.makeText(this, "Début du livre", Toast.LENGTH_SHORT).show(); return }
        if (index >= count) { Toast.makeText(this, "Fin du livre", Toast.LENGTH_SHORT).show(); return }
        if (index == position) return
        position = index
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
