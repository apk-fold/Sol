package com.apkfold.foldbook

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class ReaderActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
        const val EXTRA_EXTENSION = "extension"
        const val EXTRA_POSITION = "position"
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var sourceUri: String
    private lateinit var bookName: String
    private lateinit var extension: String
    private lateinit var contentFrame: FrameLayout
    private lateinit var pageLabel: TextView
    private lateinit var library: BookLibrary
    private var position = 0
    private var payload: ReaderPayload? = null
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var imageView: ImageView? = null
    private var webView: WebView? = null
    private var fontZoom = 100
    private var textToSpeech: TextToSpeech? = null
    private var currentText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceUri = intent.getStringExtra(EXTRA_URI).orEmpty()
        bookName = intent.getStringExtra(EXTRA_NAME) ?: "Livre"
        extension = intent.getStringExtra(EXTRA_EXTENSION).orEmpty()
        position = intent.getIntExtra(EXTRA_POSITION, 0).coerceAtLeast(0)
        library = BookLibrary(this)
        window.statusBarColor = 0xFFF8F6F0.toInt()
        window.navigationBarColor = 0xFFF8F6F0.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFFF8F6F0.toInt())
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(button("‹ Retour") { finish() })
        top.addView(TextView(this).apply { text = bookName; textSize = 16f; setTextColor(0xFF253027.toInt()); maxLines = 2 },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        top.addView(button("A−") { setFontZoom(fontZoom - 10) })
        top.addView(button("A+") { setFontZoom(fontZoom + 10) })
        root.addView(top)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(button("‹ Précédent") { navigate(-1) }, LinearLayout.LayoutParams(0, -2, 1f))
        pageLabel = TextView(this).apply { gravity = Gravity.CENTER; textSize = 12f; setTextColor(0xFF475B4C.toInt()) }
        controls.addView(pageLabel, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(button("Suivant ›") { navigate(1) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)
        contentFrame = FrameLayout(this).apply { setBackgroundColor(0xFFF1EEE5.toInt()) }
        root.addView(contentFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) })
        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(button("Rechercher") { searchInBook() }, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(button("Marque-page") { bookmark() }, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(button("Écouter") { speak() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bottom)
        setContentView(root)
        openBook()
    }

    private fun openBook() {
        contentFrame.removeAllViews()
        contentFrame.addView(TextView(this).apply {
            text = "Ouverture de votre document…"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(0xFF475B4C.toInt())
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
                    val scale = minOf(2f, (resources.displayMetrics.widthPixels * 0.95f) / page.width).coerceAtLeast(1f)
                    val bmp = android.graphics.Bitmap.createBitmap((page.width * scale).toInt().coerceAtMost(2400), (page.height * scale).toInt().coerceAtMost(3400), android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, Matrix(), PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }.getOrNull()
            runOnUiThread {
                if (isFinishing) { bitmap?.recycle(); return@runOnUiThread }
                if (bitmap == null) { Toast.makeText(this, "Erreur de rendu PDF", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                val image = ImageView(this).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true; contentDescription = "Page PDF" }
                imageView = image
                contentFrame.removeAllViews()
                contentFrame.addView(ScrollView(this).apply { addView(image, FrameLayout.LayoutParams(-1, -2)) }, FrameLayout.LayoutParams(-1, -1))
                updatePositionLabel(document.pageCount)
            }
        }
    }

    private fun openWeb(document: ReaderPayload.Web) {
        position = position.coerceIn(0, document.chapters.lastIndex)
        val file = document.chapters[position]
        currentText = document.plainTexts.getOrElse(position) { "" }
        val web = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.textZoom = fontZoom
            webViewClient = WebViewClient()
            setBackgroundColor(if (night) Color.rgb(30, 32, 30) else Color.rgb(241, 238, 229))
            loadUrl("file://" + file.absolutePath)
        }
        webView = web
        contentFrame.removeAllViews()
        contentFrame.addView(web, FrameLayout.LayoutParams(-1, -1))
        updatePositionLabel(document.chapters.size)
    }

    private fun openComic(document: ReaderPayload.Comic) {
        position = position.coerceIn(0, document.images.lastIndex)
        currentText = document.title
        val imageFile = document.images[position]
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
        if (bitmap == null) { showUnsupported("Image illisible."); return }
        val image = ImageView(this).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = imageFile.name }
        imageView = image
        contentFrame.removeAllViews()
        contentFrame.addView(ScrollView(this).apply { addView(image, FrameLayout.LayoutParams(-1, -2)) }, FrameLayout.LayoutParams(-1, -1))
        updatePositionLabel(document.images.size)
    }

    private var night = false
    private fun setFontZoom(value: Int) {
        fontZoom = value.coerceIn(70, 180)
        webView?.settings?.textZoom = fontZoom
        Toast.makeText(this, "Taille du texte : $fontZoom %", Toast.LENGTH_SHORT).show()
    }

    private fun navigate(delta: Int) {
        val count = when (val p = payload) {
            is ReaderPayload.Pdf -> p.pageCount
            is ReaderPayload.Web -> p.chapters.size
            is ReaderPayload.Comic -> p.images.size
            else -> return
        }
        position = (position + delta).coerceIn(0, count - 1)
        when (val p = payload) {
            is ReaderPayload.Pdf -> showPdfPage(p)
            is ReaderPayload.Web -> openWeb(p)
            is ReaderPayload.Comic -> openComic(p)
            else -> Unit
        }
        library.updatePosition(sourceUri, position)
    }

    private fun updatePositionLabel(count: Int) {
        pageLabel.text = "Page/partie ${position + 1} / $count"
    }

    private fun bookmark() {
        library.updatePosition(sourceUri, position)
        Toast.makeText(this, "Position sauvegardée", Toast.LENGTH_SHORT).show()
    }

    private fun searchInBook() {
        val field = android.widget.EditText(this).apply { hint = "Texte à rechercher" }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Rechercher dans le livre").setView(field)
            .setNegativeButton("Fermer", null).setPositiveButton("Rechercher") { _, _ ->
                val query = field.text.toString()
                if (webView != null) webView?.findAllAsync(query)
                else Toast.makeText(this, if (currentText.contains(query, true)) "Texte présent dans cette page" else "Texte non trouvé sur cette page", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun speak() {
        if (textToSpeech == null) textToSpeech = TextToSpeech(this, this)
        if (currentText.isBlank()) {
            Toast.makeText(this, "Aucun texte disponible sur cette page", Toast.LENGTH_SHORT).show()
            return
        }
        textToSpeech?.speak(currentText.take(4000), TextToSpeech.QUEUE_FLUSH, null, "foldbook-reading")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) textToSpeech?.language = Locale.getDefault()
    }

    private fun showUnsupported(reason: String) {
        contentFrame.removeAllViews()
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        panel.addView(TextView(this).apply {
            text = "Ce format ne peut pas être ouvert ici\n\n$reason"
            textSize = 16f
            setTextColor(0xFF253027.toInt())
            gravity = Gravity.CENTER
        })
        panel.addView(button("Ouvrir avec une autre application") {
            runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(android.net.Uri.parse(sourceUri), "*/*")
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
