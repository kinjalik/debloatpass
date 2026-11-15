package xyz.kinjalik.debloatpass

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Annotation
import android.text.SpannableString
import android.text.Spanned
import android.text.format.Formatter
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * The only screen. It shows [ImportJob]'s state; the download itself lives in ImportJob so it
 * survives this activity being recreated.
 */
class ImportActivity : Activity() {

    private lateinit var urlField: EditText
    private lateinit var fetchBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var actions: View
    private lateinit var introStatus: TextView
    private lateinit var openBtn: Button

    private var hasWallet = false
    private var resumed = false

    private val pass: PassDownloader.Pass?
        get() = (ImportJob.state as? ImportJob.State.Done)?.pass

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

    override fun onStart() {
        super.onStart()
        ImportJob.observe { render() }
    }

    override fun onStop() {
        ImportJob.observe(null)
        super.onStop()
    }

    /** Re-checked on every resume: the user may have just installed (or removed) a wallet app. */
    override fun onResume() {
        super.onResume()
        resumed = true
        hasWallet = WalletApps.handlers(this).isNotEmpty()
        val gmsDisabled = !hasWallet && WalletApps.isGmsPkpassDisabled(this)
        introStatus.text = styled(WalletApps.introText(hasWallet, gmsDisabled))
        render()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    /**
     * Android 15+ draws apps edge-to-edge (enforced for targetSdk 35+): content goes under the
     * status bar, navigation bar and keyboard unless we pad for them. A toolbar-coloured strip
     * fills the status bar area; the scrolling content stops above the navigation bar or the
     * keyboard (adjustResize no longer resizes the window there). Older Android versions lay the
     * window out between the system bars themselves, so there is nothing to do.
     */
    private fun applyInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val statusBarBg = findViewById<View>(R.id.status_bar_bg)
        val scroll = findViewById<View>(R.id.scroll)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { root, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            root.setPadding(bars.left, 0, bars.right, 0)
            if (statusBarBg.layoutParams.height != bars.top) {
                statusBarBg.layoutParams = statusBarBg.layoutParams.apply { height = bars.top }
            }
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
            // CharSequence: some apps share styled text, which getStringExtra would drop.
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            else -> null
        } ?: return
        // Came from a deeplink/share: go straight to the wallet app once downloaded.
        start(text, autoOpen = true)
    }

    private fun start(raw: String, autoOpen: Boolean) {
        if (!ImportJob.start(this, raw, autoOpen)) {
            Toast.makeText(this, R.string.toast_queued, Toast.LENGTH_SHORT).show()
        }
    }

    /** Shows ImportJob's current state; also opens the wallet app once for a deeplink download. */
    private fun render() {
        val s = ImportJob.state
        val running = s is ImportJob.State.Running
        progress.visibility = if (running) View.VISIBLE else View.GONE
        fetchBtn.isEnabled = !running
        actions.visibility = if (s is ImportJob.State.Done) View.VISIBLE else View.GONE
        openBtn.visibility = if (hasWallet) View.VISIBLE else View.GONE
        when (s) {
            ImportJob.State.Idle -> status.text = ""

            is ImportJob.State.NoLink -> status.text = getString(R.string.status_no_link, s.raw)

            is ImportJob.State.Running -> {
                if (!urlField.hasFocus()) urlField.setText(s.raw)
                status.text = getString(R.string.status_downloading, s.url)
            }

            is ImportJob.State.Failed -> status.text = getString(R.string.status_failed, s.url, describe(s.error))

            is ImportJob.State.Done -> {
                status.text = summary(s.pass, s.url)
                if (resumed && ImportJob.openWhenShown) {
                    ImportJob.openWhenShown = false
                    openInWallet(s.pass.file, quiet = true)
                }
            }
        }
    }

    private fun summary(p: PassDownloader.Pass, url: String): String {
        val title = p.title ?: getString(R.string.pass_untitled)
        val details = listOfNotNull(p.description, p.style?.let { getString(styleName(it)) })
        val head = if (details.isEmpty()) {
            title
        } else {
            getString(R.string.pass_title_details, title, details.joinToString(getString(R.string.separator)))
        }
        val size = Formatter.formatShortFileSize(this, p.file.length())
        return getString(R.string.pass_summary, head, p.file.name, size, url)
    }

    private fun styleName(s: PassDownloader.Style): Int = when (s) {
        PassDownloader.Style.STORE_CARD -> R.string.style_store_card
        PassDownloader.Style.COUPON -> R.string.style_coupon
        PassDownloader.Style.EVENT_TICKET -> R.string.style_event_ticket
        PassDownloader.Style.BOARDING_PASS -> R.string.style_boarding_pass
        PassDownloader.Style.GENERIC -> R.string.style_generic
    }

    /** Every failure in the UI language; only truly unexpected errors show their raw message. */
    private fun describe(t: Throwable): String {
        if (t !is PassDownloader.Failure) {
            return when (t) {
                is UnknownHostException -> getString(R.string.error_unknown_host)
                is SocketTimeoutException -> getString(R.string.error_timeout)
                is ConnectException -> getString(R.string.error_connect)
                is SSLException -> getString(R.string.error_tls)
                is IOException -> getString(R.string.error_network, t.javaClass.simpleName)
                else -> getString(R.string.error_unexpected, t.toString())
            }
        }
        val detail = t.detail.orEmpty()
        return when (t.reason) {
            PassDownloader.Reason.NO_LOCATION -> getString(R.string.error_no_location, detail)
            PassDownloader.Reason.HTTP_ERROR -> getString(R.string.error_http, detail)
            PassDownloader.Reason.TOO_MANY_REDIRECTS -> getString(R.string.error_too_many_redirects)
            PassDownloader.Reason.TOO_LARGE -> getString(R.string.error_too_large)
            PassDownloader.Reason.NOT_PKPASS ->
                getString(R.string.error_not_pkpass, t.detail ?: getString(R.string.no_content_type))
            PassDownloader.Reason.NO_PASS_JSON -> getString(R.string.error_no_pass_json)
            PassDownloader.Reason.INSECURE_HTTP -> getString(R.string.error_insecure_http, detail)
            PassDownloader.Reason.BAD_REDIRECT -> getString(R.string.error_bad_redirect)
            PassDownloader.Reason.TIMEOUT -> getString(R.string.error_timeout)
        }
    }

    /**
     * A string resource with `<annotation color="ok|error">` spans coloured from resources, so
     * the colours can differ between light and dark theme (`<font color>` can't do that).
     */
    private fun styled(id: Int): CharSequence {
        val text = getText(id)
        if (text !is Spanned) return text
        val s = SpannableString(text)
        for (a in s.getSpans(0, s.length, Annotation::class.java)) {
            if (a.key != "color") continue
            val color = getColor(if (a.value == "ok") R.color.intro_ok else R.color.intro_error)
            s.setSpan(ForegroundColorSpan(color), s.getSpanStart(a), s.getSpanEnd(a), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }

    private fun uri(f: File): Uri = PassProvider.uriFor(this, f)

    private fun openInWallet(f: File, quiet: Boolean = false) {
        val u = uri(f)
        for (i in WalletApps.viewIntents(u)) {
            if (WalletApps.query(this, i).isEmpty()) continue
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
