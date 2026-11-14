package xyz.kinjalik.debloatpass

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

class ImportActivity : Activity() {

    private lateinit var urlField: EditText
    private lateinit var fetchBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var actions: View

    private var pass: PassDownloader.Pass? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import)

        urlField = findViewById(R.id.url)
        fetchBtn = findViewById(R.id.fetch)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        actions = findViewById(R.id.actions)

        fetchBtn.setOnClickListener { start(urlField.text.toString(), autoOpen = false) }
        urlField.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) {
                start(urlField.text.toString(), autoOpen = false); true
            } else false
        }
        findViewById<Button>(R.id.open).setOnClickListener { pass?.let { openInWallet(it.file) } }
        findViewById<Button>(R.id.share).setOnClickListener { pass?.let { share(it.file) } }
        findViewById<Button>(R.id.save).setOnClickListener { pass?.let { saveAs(it.file) } }

        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        urlField.setText(text)
        // Came from a deeplink/share: go straight to the wallet app once downloaded.
        start(text, autoOpen = true)
    }

    private fun start(raw: String, autoOpen: Boolean) {
        if (busy) return
        val url = PassLink.extract(raw)
        if (url == null) {
            showError("Не нашёл ссылку на pkpass в:\n$raw")
            return
        }

        busy = true
        pass = null
        actions.visibility = View.GONE
        progress.visibility = View.VISIBLE
        fetchBtn.isEnabled = false
        status.text = "Качаю $url"

        val dir = PassProvider.dir(this)
        Thread {
            val result = runCatching { PassDownloader(dir).download(url) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                progress.visibility = View.GONE
                fetchBtn.isEnabled = true
                result
                    .onSuccess { showPass(it, url, autoOpen) }
                    .onFailure { showError("Не получилось скачать $url\n\n${it.message ?: it}") }
            }
        }.start()
    }

    private fun showPass(p: PassDownloader.Pass, url: String, autoOpen: Boolean) {
        pass = p
        status.text = buildString {
            append(p.title)
            if (p.details.isNotBlank()) append("\n").append(p.details)
            append("\n\n").append(p.file.name).append(" · ").append(p.file.length() / 1024).append(" КБ")
            append("\n").append(url)
        }
        actions.visibility = View.VISIBLE
        if (autoOpen) openInWallet(p.file, quiet = true)
    }

    private fun showError(msg: String) {
        pass = null
        actions.visibility = View.GONE
        status.text = msg
    }

    private fun uri(f: File): Uri = PassProvider.uriFor(this, f)

    private fun openInWallet(f: File, quiet: Boolean = false) {
        val u = uri(f)
        for (mime in VIEW_MIMES) {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(u, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Don't bounce back to ourselves if someone made us the handler somehow.
            val handlers = packageManager.queryIntentActivities(i, 0)
                .filter { it.activityInfo.packageName != packageName }
            if (handlers.isEmpty()) continue
            try {
                startActivity(i)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        if (!quiet) {
            Toast.makeText(this, "Нет приложения для .pkpass — открываю «Поделиться»", Toast.LENGTH_LONG).show()
            share(f)
        }
    }

    private fun share(f: File) {
        val u = uri(f)
        val i = Intent(Intent.ACTION_SEND)
            .setType(PassProvider.MIME)
            .putExtra(Intent.EXTRA_STREAM, u)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(f.name, u)
        startActivity(Intent.createChooser(i, f.name))
    }

    private fun saveAs(f: File) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(PassProvider.MIME)
            .putExtra(Intent.EXTRA_TITLE, f.name)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_SAVE)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Нет файлового менеджера", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAVE || resultCode != RESULT_OK) return
        val target = data?.data ?: return
        val src = pass?.file ?: return
        val ok = runCatching {
            contentResolver.openOutputStream(target, "w")!!.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
        }.isSuccess
        Toast.makeText(this, if (ok) "Сохранено" else "Не удалось сохранить", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_SAVE = 1
        private val VIEW_MIMES = listOf(
            PassProvider.MIME,
            "application/vnd-com.apple.pkpass",
        )
    }
}
