package xyz.kinjalik.debloatpass

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.WindowInsets
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
    private lateinit var introStatus: TextView
    private lateinit var openBtn: Button

    private var pass: PassDownloader.Pass? = null
    private var busy = false
    private var hasWallet = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import)
        setActionBar(findViewById(R.id.toolbar))
        applyInsets()

        urlField = findViewById(R.id.url)
        fetchBtn = findViewById(R.id.fetch)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        actions = findViewById(R.id.actions)
        introStatus = findViewById(R.id.intro_status)
        introStatus.movementMethod = LinkMovementMethod.getInstance()
        openBtn = findViewById(R.id.open)

        fetchBtn.setOnClickListener { start(urlField.text.toString(), autoOpen = false) }
        urlField.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) {
                start(urlField.text.toString(), autoOpen = false)
                true
            } else {
                false
            }
        }
        openBtn.setOnClickListener { pass?.let { openInWallet(it.file) } }
        findViewById<Button>(R.id.share).setOnClickListener { pass?.let { share(it.file) } }
        findViewById<Button>(R.id.save).setOnClickListener { pass?.let { saveAs(it.file) } }

        if (savedInstanceState == null) handle(intent)
    }

    /** Re-checked on every resume: the user may have just installed (or removed) a wallet app. */
    override fun onResume() {
        super.onResume()
        hasWallet = WalletApps.handlers(this).isNotEmpty()
        introStatus.text = if (hasWallet) {
            getText(R.string.intro_ready)
        } else if (WalletApps.isGmsPkpassDisabled(this)) {
            TextUtils.concat(getText(R.string.intro_no_wallet), "\n\n", getText(R.string.note_gms_pkpass_disabled))
        } else {
            getText(R.string.intro_no_wallet)
        }
        openBtn.visibility = if (hasWallet) View.VISIBLE else View.GONE
    }

    /**
     * Android 15+ draws apps edge-to-edge (enforced for targetSdk 35+): content goes under the
     * status bar, navigation bar and keyboard unless we pad for them. The toolbar background
     * extends under the status bar; the scrolling content stops above the navigation bar or the
     * keyboard (adjustResize no longer resizes the window there). Older Android versions lay the
     * window out between the system bars themselves, so there is nothing to do.
     */
    private fun applyInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val toolbar = findViewById<View>(R.id.toolbar)
        val scroll = findViewById<View>(R.id.scroll)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { root, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            root.setPadding(bars.left, 0, bars.right, 0)
            toolbar.setPadding(toolbar.paddingLeft, bars.top, toolbar.paddingRight, toolbar.paddingBottom)
            scroll.setPadding(0, 0, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }
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
            showError(getString(R.string.status_no_link, raw))
            return
        }

        busy = true
        pass = null
        actions.visibility = View.GONE
        progress.visibility = View.VISIBLE
        fetchBtn.isEnabled = false
        status.text = getString(R.string.status_downloading, url)

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
                    .onFailure { showError(getString(R.string.status_failed, url, describe(it))) }
            }
        }.start()
    }

    private fun showPass(p: PassDownloader.Pass, url: String, autoOpen: Boolean) {
        pass = p
        val details = listOfNotNull(p.description, p.style?.let { getString(styleName(it)) })
        status.text = buildString {
            append(p.title)
            if (details.isNotEmpty()) append("\n").append(details.joinToString(" · "))
            append("\n\n").append(p.file.name).append(" · ")
            append(getString(R.string.size_kb, (p.file.length() / 1024).toInt()))
            append("\n").append(url)
        }
        actions.visibility = View.VISIBLE
        if (autoOpen) openInWallet(p.file, quiet = true)
    }

    private fun styleName(s: PassDownloader.Style): Int = when (s) {
        PassDownloader.Style.STORE_CARD -> R.string.style_store_card
        PassDownloader.Style.COUPON -> R.string.style_coupon
        PassDownloader.Style.EVENT_TICKET -> R.string.style_event_ticket
        PassDownloader.Style.BOARDING_PASS -> R.string.style_boarding_pass
        PassDownloader.Style.GENERIC -> R.string.style_generic
    }

    /** Known failures in the UI language; anything else (DNS, TLS, timeouts) as the system reports it. */
    private fun describe(t: Throwable): String {
        if (t !is PassDownloader.Failure) return t.message ?: t.toString()
        val detail = t.detail.orEmpty()
        return when (t.reason) {
            PassDownloader.Reason.NO_LOCATION -> getString(R.string.error_no_location, detail)
            PassDownloader.Reason.HTTP_ERROR -> getString(R.string.error_http, detail)
            PassDownloader.Reason.TOO_MANY_REDIRECTS -> getString(R.string.error_too_many_redirects)
            PassDownloader.Reason.TOO_LARGE -> getString(R.string.error_too_large)
            PassDownloader.Reason.NOT_PKPASS ->
                getString(R.string.error_not_pkpass, t.detail ?: getString(R.string.no_content_type))
            PassDownloader.Reason.NO_PASS_JSON -> getString(R.string.error_no_pass_json)
        }
    }

    private fun showError(msg: String) {
        pass = null
        actions.visibility = View.GONE
        status.text = msg
    }

    private fun uri(f: File): Uri = PassProvider.uriFor(this, f)

    private fun openInWallet(f: File, quiet: Boolean = false) {
        val u = uri(f)
        for (i in WalletApps.viewIntents(u)) {
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Don't bounce back to ourselves if someone made us the handler somehow.
            @Suppress("DEPRECATION")
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
            Toast.makeText(this, R.string.toast_no_wallet, Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, R.string.toast_no_file_manager, Toast.LENGTH_LONG).show()
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
        Toast.makeText(this, if (ok) R.string.toast_saved else R.string.toast_save_failed, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_SAVE = 1
    }
}
