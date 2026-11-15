package xyz.kinjalik.debloatpass

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * The current download and its result, kept outside ImportActivity so that it survives the
 * activity being recreated (dark mode or language switch, rotation, …) and so that only one
 * download runs at a time. All methods are called on the main thread.
 */
object ImportJob {
    sealed interface State {
        data object Idle : State

        data class NoLink(val raw: String) : State

        data class Running(val raw: String, val url: String) : State

        data class Done(val raw: String, val url: String, val pass: PassDownloader.Pass) : State

        data class Failed(val raw: String, val url: String, val error: Throwable) : State
    }

    var state: State = State.Idle
        private set

    /** Links that arrived while a download was running, started in order. */
    private val queue = ArrayDeque<Pair<String, Boolean>>()

    /** For tests and the UI: how many links wait behind the running download. */
    val queued: Int get() = queue.size

    /** A deeplink/share download succeeded and the wallet app should be opened (once, soon). */
    private var openWhenShown = false
    private var doneAt = 0L

    /** The screen was closed by the user (Back), so a finishing download must not pop up later. */
    private var screenClosed = false

    private var observer: Any? = null
    private var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    /** [owner] shows the state from now on; [onChange] is called on every state change. */
    fun observe(owner: Any, onChange: () -> Unit) {
        observer = owner
        listener = onChange
    }

    /** Stops notifying [owner], unless another screen has registered since. */
    fun stopObserving(owner: Any) {
        if (observer !== owner) return
        observer = null
        listener = null
    }

    /** The user closed the screen; don't open a wallet app for a download finishing later. */
    fun screenClosed() {
        screenClosed = true
        openWhenShown = false
    }

    /**
     * True (once) if the wallet app should be opened for the result being shown now: only for
     * a deeplink/share download, and only shortly after it finished, never hours later.
     */
    fun takeAutoOpen(): Boolean {
        val fresh = SystemClock.elapsedRealtime() - doneAt < AUTO_OPEN_WINDOW_MS
        val open = openWhenShown && fresh
        openWhenShown = false
        return open
    }

    /**
     * Starts downloading the pass that [raw] (a deeplink, a URL, or text containing one) points
     * at. While another download runs, the link is queued instead and started after it (and any
     * links queued before it); returns false in that case.
     */
    fun start(ctx: Context, raw: String, autoOpen: Boolean): Boolean {
        screenClosed = false
        if (state is State.Running) {
            queue.addLast(raw to autoOpen)
            return false
        }
        val url = PassLink.extract(raw)
        if (url == null) {
            state = State.NoLink(raw)
            openWhenShown = false
            notifyChange()
            startNext(ctx)
            return true
        }
        state = State.Running(raw, url)
        openWhenShown = false
        notifyChange()

        val app = ctx.applicationContext // no Activity reference held by the download thread
        val dir = PassProvider.dir(app)
        Thread {
            val result = runCatching { PassDownloader(dir).download(url) }
            main.post {
                state = result.fold({ State.Done(raw, url, it) }, { State.Failed(raw, url, it) })
                openWhenShown = autoOpen && result.isSuccess && !screenClosed
                doneAt = SystemClock.elapsedRealtime()
                notifyChange()
                startNext(app)
            }
        }.start()
        return true
    }

    private fun startNext(ctx: Context) {
        val (raw, autoOpen) = queue.removeFirstOrNull() ?: return
        start(ctx, raw, autoOpen)
    }

    private fun notifyChange() {
        listener?.invoke()
    }

    /** Instrumented tests share one process; each starts from a clean state. */
    internal fun resetForTests() {
        state = State.Idle
        queue.clear()
        openWhenShown = false
        screenClosed = false
        observer = null
        listener = null
    }

    private const val AUTO_OPEN_WINDOW_MS = 60_000L
}
