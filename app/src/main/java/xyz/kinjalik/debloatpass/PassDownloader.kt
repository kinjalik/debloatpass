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
        val deadline = System.currentTimeMillis() + totalTimeoutMs

        val conn = connect(URL(url), deadline)
        try {
            val tmp = File.createTempFile("download", ".part", dir) // prefix must be >= 3 chars
            try {
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out -> copyLimited(input, out, deadline) }
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

    private fun connect(start: URL, deadline: Long): HttpURLConnection {
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            checkDeadline(deadline)
            val c = open(url)
            val code = c.responseCode
            when (code) {
                in 300..399 -> {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    val next = runCatching { URL(url, loc ?: throw Failure(Reason.NO_LOCATION, "HTTP $code")) }
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
    private fun open(url: URL): HttpURLConnection {
        val upgrade = url.protocol == "http" && !cleartextPermitted(url.host)
        val target = if (upgrade) URL("https", url.host, url.port, url.file) else url
        val c = target.openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false // handle http<->https hops ourselves
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Accept", "application/vnd.apple.pkpass, application/octet-stream, */*")
        if (upgrade) {
            try {
                c.responseCode // connect + TLS handshake now
            } catch (e: IOException) {
                c.disconnect()
                throw Failure(Reason.INSECURE_HTTP, url.host)
            }
        }
        return c
    }

    private fun copyLimited(input: InputStream, out: OutputStream, deadline: Long) {
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            checkDeadline(deadline)
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_SIZE) throw Failure(Reason.TOO_LARGE)
            out.write(buf, 0, n)
        }
    }

    private fun checkDeadline(deadline: Long) {
        if (System.currentTimeMillis() > deadline) throw Failure(Reason.TIMEOUT)
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
