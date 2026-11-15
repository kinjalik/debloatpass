package xyz.kinjalik.debloatpass

import android.net.Uri
import android.util.Base64

/**
 * One deeplink shape from deeplinks.gradle.kts, with {url} marking where the .pkpass address is:
 *
 *   scheme://host/path/{url}         the rest of the link is the URL (raw, or percent-encoded
 *                                    as a whole: https%3A%2F%2F…)
 *   scheme://host[/path]?name={url}  the URL is the value of query parameter `name`
 *   …{url:base64}                    the URL is Base64 (standard or URL-safe, padding optional)
 *
 * The build validates the same grammar and generates the manifest intent-filters from it.
 */
class DeeplinkTemplate private constructor(
    val template: String,
    val scheme: String,
    val host: String,
    /** Path form: the prefix before {url}. Query form: the exact path ("" if none). */
    private val path: String,
    private val queryParam: String?,
    private val base64: Boolean,
) {
    /**
     * The address [link] carries in this template's {url} slot, decoded, or null if [link] isn't
     * this shape. Not validated as http(s) yet: that's PassLink's job.
     */
    fun extract(link: String): String? {
        val uri = Uri.parse(link)
        if (!scheme.equals(uri.scheme, ignoreCase = true) || !host.equals(uri.host, ignoreCase = true)) return null
        val raw = if (queryParam == null) {
            // Taken from the raw string, not uri.path: a "?…" or "#…" after the embedded URL
            // belongs to that URL, not to the deeplink.
            val afterHost = link.substring(link.indexOf("://") + 3 + (uri.encodedAuthority?.length ?: return null))
            if (!afterHost.startsWith(path)) return null
            afterHost.substring(path.length)
        } else {
            // "scheme://host?x=" and "scheme://host/?x=" are the same link (the intent-filter
            // can't tell them apart either), as are "/add" and "/add/".
            if ((uri.path ?: "").trimEnd('/') != path.trimEnd('/')) return null
            uri.getQueryParameter(queryParam) ?: return null // already percent-decoded once
        }
        if (raw.isEmpty()) return null
        return when {
            // A "+" of standard Base64 in a query comes out of getQueryParameter as " ".
            base64 -> decodeBase64(Uri.decode(raw).replace(' ', '+'))
            queryParam == null && ENCODED_URL.containsMatchIn(raw) -> Uri.decode(raw)
            else -> raw
        }
    }

    /** The deeplink this template makes for [url]; the inverse of [extract] (used by tests). */
    fun build(url: String): String {
        val value = if (base64) {
            Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } else {
            url
        }
        return if (queryParam == null) {
            "$scheme://$host$path$value"
        } else {
            "$scheme://$host$path?$queryParam=${Uri.encode(value)}"
        }
    }

    override fun toString() = template

    companion object {
        /**
         * Keep in sync with `templateGrammar` in app/build.gradle.kts. The path allows only URL
         * characters (no quotes, backslashes, `&`, `<`, spaces): templates also end up in the
         * generated manifest XML and in a BuildConfig string.
         */
        private val GRAMMAR = Regex(
            """^([a-z][a-z0-9+.-]*)://([a-z0-9.-]+)(/[A-Za-z0-9._~!$'()*+,;=:@%/-]*)?""" +
                """(?:\{url(:base64)?\}|\?([A-Za-z0-9_.~-]+)=\{url(:base64)?\})$""",
        )

        /** A whole URL that was percent-encoded: https%3A%2F%2F… */
        private val ENCODED_URL = Regex("""(?i)^https?%3A""")

        fun parse(template: String): DeeplinkTemplate {
            val m = requireNotNull(GRAMMAR.matchEntire(template)) { "Not a deeplink template: $template" }
            val (scheme, host, path, pathB64, query, queryB64) = m.destructured
            return if (query.isEmpty()) {
                require(path.endsWith("/")) { "{url} must follow a / in $template" }
                DeeplinkTemplate(template, scheme, host, path, null, pathB64.isNotEmpty())
            } else {
                DeeplinkTemplate(template, scheme, host, path, query, queryB64.isNotEmpty())
            }
        }

        /** Standard or URL-safe alphabet, padding optional. Strict: android.util.Base64 skips junk. */
        private val BASE64 = Regex("""^[A-Za-z0-9+/_-]+={0,2}$""")

        private fun decodeBase64(s: String): String? {
            val trimmed = s.trim()
            if (!BASE64.matches(trimmed)) return null
            val std = trimmed.trimEnd('=').replace('-', '+').replace('_', '/')
            if (std.length % 4 == 1) return null // no valid encoding has this length
            val padded = std + "=".repeat((4 - std.length % 4) % 4)
            return runCatching { String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
        }
    }
}
