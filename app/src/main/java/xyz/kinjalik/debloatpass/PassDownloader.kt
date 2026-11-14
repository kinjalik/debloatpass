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

    data class Pass(val file: File, val title: String, val details: String)

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
                val details = listOfNotNull(
                    json.optString("description").takeIf { it.isNotBlank() },
                    passStyle(json),
                ).joinToString(" · ")

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
                return Pass(out, title, details)
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
                    url = URL(url, loc ?: throw IOException("HTTP $code без Location"))
                }
                in 200..299 -> return c
                else -> {
                    c.disconnect()
                    throw IOException("HTTP $code от ${url.host}")
                }
            }
        }
        throw IOException("Слишком много редиректов")
    }

    private fun copyLimited(input: InputStream, out: OutputStream) {
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_SIZE) throw IOException("Файл больше ${MAX_SIZE / 1024 / 1024} МБ — подозрительно")
            out.write(buf, 0, n)
        }
    }

    private fun passStyle(json: JSONObject): String? = when {
        json.has("storeCard") -> "карта лояльности"
        json.has("coupon") -> "купон"
        json.has("eventTicket") -> "билет"
        json.has("boardingPass") -> "посадочный"
        json.has("generic") -> "generic"
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
                throw IOException("Сервер вернул не pkpass (${contentType ?: "без Content-Type"})")
            }
            return readPassJson(f) ?: throw IOException("В архиве нет pass.json — это не pkpass")
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
                    .removePrefix("﻿")
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
