package xyz.kinjalik.debloatpass

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.zip.ZipFile
import org.json.JSONObject

/**
 * @param cleartextPermitted whether plain http may be used for a host. The release app's network
 *   security config forbids it everywhere, so http links are upgraded to https; debug builds
 *   allow it for localhost (instrumented tests). JVM tests pass their own answer.
 * @param totalTimeoutMs a deadline for the whole download, so a server trickling bytes can't
 *   keep it running forever (connect/read timeouts only bound a single wait).
 */
class PassDownloader(
    private val dir: File,
    private val cleartextPermitted: (host: String) -> Boolean = { host ->
        android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
    },
    private val totalTimeoutMs: Long = TOTAL_TIMEOUT_MS,
) {

    /** [title] is null when the pass names neither an organization nor a logo text. */
    data class Pass(val file: File, val title: String?, val description: String?, val style: Style?)

    enum class Style { STORE_CARD, COUPON, EVENT_TICKET, BOARDING_PASS, GENERIC }

    /** A download that failed for a reason the UI can explain in the user's language. */
    class Failure(val reason: Reason, val detail: String? = null) :
        IOException(reason.english + (detail?.let { " ($it)" } ?: ""))

    /** [english] is only for logs/tests; the UI uses string resources. */
    enum class Reason(val english: String) {
        NO_LOCATION("Redirect without a Location header"),
        HTTP_ERROR("HTTP error"),
        TOO_MANY_REDIRECTS("Too many redirects"),
        TOO_LARGE("File is larger than 20 MB"),
        NOT_PKPASS("Server returned something that is not a pkpass"),
        NO_PASS_JSON("No valid pass.json in the archive, not a pkpass"),
        INSECURE_HTTP("The server only offers insecure http"),
        BAD_REDIRECT("Redirect to something that is not an http(s) link"),
        TIMEOUT("The download took too long"),
    }

    fun download(url: String): Pass {
        dir.mkdirs()
        cleanup()
        val watchdog = Watchdog(totalTimeoutMs)
        try {
            return fetch(URL(url), watchdog)
        } catch (e: IOException) {
            // The watchdog disconnecting a stuck connection surfaces as some IOException.
            if (watchdog.fired && e !is Failure) throw Failure(Reason.TIMEOUT)
            throw e
        } finally {
            watchdog.cancel()
        }
    }

    private fun fetch(start: URL, watchdog: Watchdog): Pass {
        val conn = connect(start, watchdog)
        try {
            val tmp = File.createTempFile("download", ".part", dir) // prefix must be >= 3 chars
            try {
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out -> copyLimited(input, out, watchdog) }
                }
                val json = checkPass(tmp, conn.contentType)

                val title = json.optString("organizationName")
                    .ifBlank { json.optString("logoText") }
                    .takeIf { it.isNotBlank() }
                val description = json.optString("description").takeIf { it.isNotBlank() }

                val out = File(
                    dir,
                    fileName(
                        organizationName = json.optString("organizationName"),
                        contentDisposition = conn.getHeaderField("Content-Disposition"),
                        urlPath = conn.url.path,
                    ),
                )
                out.delete()
                if (!tmp.renameTo(out)) tmp.copyTo(out, overwrite = true)
                return Pass(out, title, description, passStyle(json))
            } finally {
                tmp.delete()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun connect(start: URL, watchdog: Watchdog): HttpURLConnection {
        var url = start
        val visited = HashSet<String>()
        repeat(MAX_REDIRECTS + 1) {
            watchdog.check()
            val upgrade = url.protocol == "http" && !cleartextPermitted(url.host)
            val target = if (upgrade) httpsOf(url) else url
            // https → (redirect to) http → upgraded to the same https again: the server insists
            // on plain http; say so instead of running into "too many redirects".
            if (!visited.add(target.toString()) && upgrade) throw Failure(Reason.INSECURE_HTTP, url.host)
            val c = open(target, upgrade, url.host, watchdog)
            val code = c.responseCode
            when (code) {
                in 300..399 -> {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    val next = runCatching { URL(target, loc ?: throw Failure(Reason.NO_LOCATION, "HTTP $code")) }
                        .getOrElse { if (it is Failure) throw it else throw Failure(Reason.BAD_REDIRECT, loc) }
                    if (next.protocol != "http" && next.protocol != "https") throw Failure(Reason.BAD_REDIRECT, loc)
                    url = next
                }
                in 200..299 -> return c
                else -> {
                    c.disconnect()
                    throw Failure(Reason.HTTP_ERROR, "HTTP $code, ${url.host}")
                }
            }
        }
        throw Failure(Reason.TOO_MANY_REDIRECTS)
    }

    /**
     * Opens one hop and sends the request. Plain http to a host where cleartext isn't allowed
     * (everywhere in release) is tried over https instead: most pass servers serve both. If that
     * fails too, the user gets a clear "only insecure http" error rather than Android's
     * "CLEARTEXT communication … not permitted".
     */
    private fun open(target: URL, upgraded: Boolean, host: String, watchdog: Watchdog): HttpURLConnection {
        val c = target.openConnection() as HttpURLConnection
        watchdog.connection = c
        c.instanceFollowRedirects = false // handle http<->https hops ourselves
        // Never wait past the overall deadline in a single connect/read.
        c.connectTimeout = minOf(15_000L, watchdog.remainingMs()).coerceAtLeast(1).toInt()
        c.readTimeout = minOf(30_000L, watchdog.remainingMs()).coerceAtLeast(1).toInt()
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Accept", "application/vnd.apple.pkpass, application/octet-stream, */*")
        if (upgraded) {
            try {
                c.responseCode // connect + TLS handshake now
            } catch (e: IOException) {
                c.disconnect()
                // No TLS on the server (handshake fails, or nothing listens on 443) means it only
                // speaks plain http. Offline, DNS, timeouts: the usual errors, not this one.
                if (e is javax.net.ssl.SSLException || e is java.net.ConnectException) {
                    throw Failure(Reason.INSECURE_HTTP, host)
                }
                throw e
            }
        }
        return c
    }

    private fun copyLimited(input: InputStream, out: OutputStream, watchdog: Watchdog) {
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            watchdog.check()
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_SIZE) throw Failure(Reason.TOO_LARGE)
            out.write(buf, 0, n)
        }
    }

    /**
     * Enforces the overall deadline: checked between steps, and when it passes it disconnects
     * the current connection, so a handshake or a server trickling headers can't run past it.
     * Monotonic clock (nanoTime), unaffected by wall-clock changes.
     */
    private class Watchdog(timeoutMs: Long) {
        private val deadline = System.nanoTime() + timeoutMs * 1_000_000

        @Volatile var connection: HttpURLConnection? = null

        @Volatile var fired = false
            private set

        private val timer = java.util.Timer("pass-download-deadline", true).apply {
            schedule(
                object : java.util.TimerTask() {
                    override fun run() {
                        fired = true
                        connection?.disconnect()
                    }
                },
                timeoutMs,
            )
        }

        fun remainingMs() = (deadline - System.nanoTime()) / 1_000_000

        fun check() {
            if (fired || remainingMs() <= 0) throw Failure(Reason.TIMEOUT)
        }

        fun cancel() = timer.cancel()
    }

    private fun passStyle(json: JSONObject): Style? = when {
        json.has("storeCard") -> Style.STORE_CARD
        json.has("coupon") -> Style.COUPON
        json.has("eventTicket") -> Style.EVENT_TICKET
        json.has("boardingPass") -> Style.BOARDING_PASS
        json.has("generic") -> Style.GENERIC
        else -> null
    }

    /** Wallet apps copy the pass on import, so old files are just garbage. */
    private fun cleanup() {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    companion object {
        internal const val MAX_REDIRECTS = 5
        internal const val MAX_SIZE = 20L * 1024 * 1024

        /** pass.json is a few KB; anything bigger is a broken or malicious (zip bomb) pass. */
        internal const val MAX_PASS_JSON = 1L * 1024 * 1024
        internal const val TOTAL_TIMEOUT_MS = 90_000L

        /** http://host[:80]/x → https://host/x (an explicit :80 would send TLS to the http port). */
        internal fun httpsOf(url: URL) = URL("https", url.host, if (url.port == 80) -1 else url.port, url.file)
        private val USER_AGENT = "Mozilla/5.0 (Linux; Android) DebloatPass/${BuildConfig.VERSION_NAME}"

        /** Throws unless [f] is a zip with a parseable pass.json; returns that pass.json. */
        internal fun checkPass(f: File, contentType: String?): JSONObject {
            if (!isZip(f)) {
                throw Failure(Reason.NOT_PKPASS, contentType)
            }
            return readPassJson(f) ?: throw Failure(Reason.NO_PASS_JSON)
        }

        internal fun isZip(f: File): Boolean = f.inputStream().use { s ->
            val b = ByteArray(4)
            s.read(b) == 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() &&
                b[2] == 3.toByte() && b[3] == 4.toByte()
        }

        internal fun readPassJson(f: File): JSONObject? = runCatching {
            ZipFile(f).use { zip ->
                val entry = zip.getEntry("pass.json") ?: return@use null
                if (entry.size > MAX_PASS_JSON) return@use null
                // The declared size can lie: read at most MAX_PASS_JSON + 1 bytes and check.
                val bytes = zip.getInputStream(entry).use { it.readNBytesCompat(MAX_PASS_JSON + 1) }
                if (bytes.size > MAX_PASS_JSON) return@use null
                val text = bytes
                    .toString(Charsets.UTF_8)
                    .removePrefix(Char(0xFEFF).toString()) // UTF-8 BOM some generators put before the JSON
                JSONObject(text)
            }
        }.getOrNull()

        /** InputStream.readNBytes is API 33+. */
        private fun InputStream.readNBytesCompat(limit: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var left = limit
            while (left > 0) {
                val n = read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
            return out.toByteArray()
        }

        /** organizationName, else the Content-Disposition filename, else the last URL segment. */
        internal fun fileName(organizationName: String?, contentDisposition: String?, urlPath: String?): String {
            val fromOrg = organizationName?.takeIf { it.isNotBlank() }
            val fromHeader = contentDisposition
                ?.let { Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(it) }
                ?.groupValues?.get(1)
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            val fromUrl = urlPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

            val base = (fromOrg ?: fromHeader ?: fromUrl ?: "pass")
                .removeSuffix(".pkpass")
                .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
                .joinToString("")
                .trim('_', '.')
                .take(60)
                .ifBlank { "pass" }
            return "$base.pkpass"
        }
    }
}
