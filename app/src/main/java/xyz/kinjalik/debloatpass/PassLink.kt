package xyz.kinjalik.debloatpass

import android.net.Uri
import java.net.URLDecoder

/**
 * Pulls the real https URL of a .pkpass out of the adware viewers' deeplinks:
 *
 *   walletunion://import/https://host/card/x.pkpass
 *   passeswallet://import/https://host/card/x.pkpass
 *   walletpasses://import/https://host/card/x.pkpass
 *   walletpasses://import?url=https%3A%2F%2Fhost%2Fx.pkpass
 *
 * Plain http(s) links are passed through, so "share link -> DebloatPass" works too.
 */
object PassLink {
    /** Generated from deeplinks.gradle.kts. */
    val SCHEMES: Set<String> = BuildConfig.DEEPLINK_SCHEMES.toSet()

    private val LINK = Regex(
        """(?i)\b(?:${SCHEMES.joinToString("|")}|https?)://\S+"""
    )

    fun extract(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val link = LINK.find(raw.trim())?.value?.trimEnd('.', ',', ')', '"', '\'', '>')
            ?: return null

        val scheme = link.substringBefore("://").lowercase()
        if (scheme == "http" || scheme == "https") return normalize(link)

        // walletpasses://import?url=...
        runCatching { Uri.parse(link).getQueryParameter("url") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return normalize(it) }

        // walletpasses://import/https://...  (also tolerate missing "import/")
        val rest = link.substringAfter("://")
        val idx = rest.indexOf("http", ignoreCase = true)
        val target = when {
            idx >= 0 -> rest.substring(idx)
            else -> "https://" + rest.removePrefix("import").trimStart('/')
        }
        return normalize(target)
    }

    private fun normalize(url: String): String? {
        var u = url.trim()
        // percent-encoded payload: https%3A%2F%2F...
        if (u.contains("%3A%2F%2F", ignoreCase = true)) {
            u = runCatching { URLDecoder.decode(u, "UTF-8") }.getOrDefault(u)
        }
        // some launchers collapse "https://" into "https:/"
        u = u.replaceFirst(Regex("""(?i)^(https?):/+"""), "$1://")
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) return null
        if (Uri.parse(u).host.isNullOrBlank()) return null
        return u
    }
}
