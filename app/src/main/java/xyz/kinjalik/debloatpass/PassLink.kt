package xyz.kinjalik.debloatpass

import android.net.Uri

/**
 * Pulls the real URL of a .pkpass out of an adware viewer's deeplink, matched strictly against
 * the templates in deeplinks.gradle.kts (see [DeeplinkTemplate]), e.g.
 *
 *   walletunion://import/https://host/card/x.pkpass
 *   walletpasses://import?url=https%3A%2F%2Fhost%2Fx.pkpass
 *
 * A deeplink of one of our schemes that fits none of the templates is rejected rather than
 * guessed at. Plain http(s) links are passed through as they are, so "share link → DebloatPass"
 * works too. Works on a bare link or on a link inside shared text.
 */
object PassLink {
    /** Generated from deeplinks.gradle.kts. */
    val TEMPLATES: List<DeeplinkTemplate> = BuildConfig.DEEPLINK_TEMPLATES.map(DeeplinkTemplate::parse)

    val SCHEMES: Set<String> = TEMPLATES.map { it.scheme }.toSet()

    // http(s) also with a broken slash count ("https:/host", "https:///host"): some apps mangle
    // links when sharing them as text; normalize() repairs them.
    private val LINK = Regex(
        """(?i)\b(?:(?:${SCHEMES.joinToString("|") { Regex.escape(it) }})://|https?:/+)\S+""",
    )

    fun extract(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val link = LINK.find(raw.trim())?.value?.trimEnd('.', ',', ')', '"', '\'', '>')
            ?: return null

        val scheme = link.substringBefore(":").lowercase()
        if (scheme == "http" || scheme == "https") return normalize(link)

        return TEMPLATES.firstNotNullOfOrNull { t -> t.extract(link)?.let(::normalize) }
    }

    /** Only repairs the scheme separator; a well-formed URL is never re-decoded or rewritten. */
    private fun normalize(url: String): String? {
        // Some apps collapse "https://" into "https:/"; the scheme is lowercased on the way
        // ("HTTPS:/host" → "https://host"). ^ means this happens once, at the start.
        val u = Regex("""(?i)^(https?):/+""").replace(url.trim()) { it.groupValues[1].lowercase() + "://" }
        if (!u.startsWith("http://") && !u.startsWith("https://")) return null
        if (Uri.parse(u).host.isNullOrBlank()) return null
        return u
    }
}
