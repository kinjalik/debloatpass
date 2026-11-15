package xyz.kinjalik.debloatpass

import android.content.Context
import android.os.Handler
import android.os.Looper

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

    /** Set when a deeplink/share download succeeded and the wallet app hasn't been opened yet. */
    var openWhenShown = false

    private var pending: Pair<String, Boolean>? = null
    private var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    /** The screen currently showing the state, or null; called on every state change. */
    fun observe(onChange: (() -> Unit)?) {
        listener = onChange
    }

    /**
     * Starts downloading the pass that [raw] (a deeplink, a URL, or text containing one) points
     * at. While another download runs, the link is queued instead (the latest one wins) and
     * started when that one finishes; returns false in that case.
     */
    fun start(ctx: Context, raw: String, autoOpen: Boolean): Boolean {
        if (state is State.Running) {
            pending = raw to autoOpen
            return false
        }
        val url = PassLink.extract(raw)
        if (url == null) {
            state = State.NoLink(raw)
            openWhenShown = false
            notifyChange()
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
                openWhenShown = autoOpen && result.isSuccess
                notifyChange()
                pending?.let { (nextRaw, nextAuto) ->
                    pending = null
                    start(app, nextRaw, nextAuto)
                }
            }
        }.start()
        return true
    }

    private fun notifyChange() {
        listener?.invoke()
    }

    /** Instrumented tests share one process; each starts from a clean state. */
    internal fun resetForTests() {
        state = State.Idle
        pending = null
        openWhenShown = false
    }
}
