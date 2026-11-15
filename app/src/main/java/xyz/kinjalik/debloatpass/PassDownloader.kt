package xyz.kinjalik.debloatpass

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.zip.ZipFile

class PassDownloader(private val dir: File) {

    data class Pass(val file: File, val title: String, val description: String?, val style: Style?)

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
        NO_PASS_JSON("No pass.json in the archive, not a pkpass"),
    }

    fun download(url: String): Pass {
        dir.mkdirs()
        cleanup()

        val conn = connect(URL(url))
        try {
            val tmp = File.createTempFile("download", ".part", dir) // prefix must be >= 3 chars
            try {
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out -> copyLimited(input, out) }
                }
                val json = checkPass(tmp, conn.contentType)

                val title = json.optString("organizationName")
                    .ifBlank { json.optString("logoText") }
                    .ifBlank { "Pass" }
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

    private fun connect(start: URL): HttpURLConnection {
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false // handle http<->https hops ourselves
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/vnd.apple.pkpass, application/octet-stream, */*")
            val code = c.responseCode
            when (code) {
                in 300..399 -> {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    url = URL(url, loc ?: throw Failure(Reason.NO_LOCATION, "HTTP $code"))
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

    private fun copyLimited(input: InputStream, out: OutputStream) {
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_SIZE) throw Failure(Reason.TOO_LARGE)
            out.write(buf, 0, n)
        }
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
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) DebloatPass/1.0"

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
                val text = zip.getInputStream(entry).use { it.readBytes() }
                    .toString(Charsets.UTF_8)
                    .removePrefix(Char(0xFEFF).toString()) // UTF-8 BOM some generators put before the JSON
                JSONObject(text)
            }
        }.getOrNull()

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
