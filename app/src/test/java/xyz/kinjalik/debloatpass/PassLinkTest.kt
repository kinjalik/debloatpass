package xyz.kinjalik.debloatpass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric only because PassLink uses android.net.Uri. */
@RunWith(RobolectricTestRunner::class)
class PassLinkTest {

    private val target = "https://host.example/card/x.pkpass"

    @Test
    fun schemesComeFromBuildConfig() {
        assertEquals(setOf("walletunion", "passeswallet", "walletpasses"), PassLink.SCHEMES)
    }

    @Test
    fun importPathForEveryScheme() {
        for (s in PassLink.SCHEMES) {
            assertEquals(s, target, PassLink.extract("$s://import/$target"))
        }
    }

    @Test
    fun schemeIsCaseInsensitive() {
        assertEquals(target, PassLink.extract("WalletUnion://import/$target"))
    }

    @Test
    fun anyHostInsteadOfImport() {
        for (s in PassLink.SCHEMES) {
            assertEquals(s, target, PassLink.extract("$s://anything/$target"))
            assertEquals(s, target, PassLink.extract("$s://open/card/$target"))
        }
    }

    @Test
    fun plainHttpInsideDeeplinkIsKept() {
        assertEquals(
            "http://host.example/x.pkpass",
            PassLink.extract("walletunion://import/http://host.example/x.pkpass"),
        )
    }

    @Test
    fun urlQueryParameterPercentEncoded() {
        for (s in PassLink.SCHEMES) {
            assertEquals(
                s,
                target,
                PassLink.extract("$s://import?url=https%3A%2F%2Fhost.example%2Fcard%2Fx.pkpass"),
            )
        }
    }

    @Test
    fun urlQueryParameterKeepsInnerQuery() {
        assertEquals(
            // getQueryParameter decodes once; the inner %20 stays a valid URL escape.
            "https://host.example/get?id=1&sig=a%20b",
            PassLink.extract("walletpasses://import?url=https%3A%2F%2Fhost.example%2Fget%3Fid%3D1%26sig%3Da%2520b"),
        )
    }

    @Test
    fun doubleEncodedPayloadInPath() {
        assertEquals(target, PassLink.extract("walletunion://import/https%3A%2F%2Fhost.example%2Fcard%2Fx.pkpass"))
    }

    @Test
    fun singleSlashAfterSchemeIsFixed() {
        assertEquals(target, PassLink.extract("walletunion://import/https:/host.example/card/x.pkpass"))
        assertEquals(target, PassLink.extract("passeswallet://anything/https:/host.example/card/x.pkpass"))
        assertEquals(target, PassLink.extract("walletunion://import/https:///host.example/card/x.pkpass"))
    }

    @Test
    fun bareBrokenSlashLinkIsRepaired() {
        assertEquals(target, PassLink.extract("https:/host.example/card/x.pkpass"))
        assertEquals(target, PassLink.extract("https:///host.example/card/x.pkpass"))
        assertEquals(target, PassLink.extract("Your card: https:/host.example/card/x.pkpass."))
        assertEquals("http://host.example/x.pkpass", PassLink.extract("HTTP:/host.example/x.pkpass"))
    }

    @Test
    fun brokenSlashWithoutHostIsStillRejected() {
        assertNull(PassLink.extract("https:/"))
        assertNull(PassLink.extract("see https:/ for details"))
    }

    @Test
    fun deeplinkWithoutHttpPrefixGetsHttps() {
        assertEquals(target, PassLink.extract("walletunion://import/host.example/card/x.pkpass"))
    }

    @Test
    fun linkEmbeddedInSharedText() {
        assertEquals(
            target,
            PassLink.extract("Ваша карта: walletunion://import/$target. Добавьте её в кошелёк!"),
        )
        assertEquals(target, PassLink.extract("open (walletpasses://import/$target)"))
        assertEquals(target, PassLink.extract("\"passeswallet://import/$target\","))
        assertEquals(target, PassLink.extract("<walletunion://import/$target>"))
        assertEquals(target, PassLink.extract("  \n\twalletunion://import/$target\n  "))
        assertEquals(target, PassLink.extract("card: $target'"))
    }

    @Test
    fun firstLinkInTextWins() {
        assertEquals(
            target,
            PassLink.extract("walletunion://import/$target and also https://other.example/y.pkpass"),
        )
    }

    @Test
    fun plainHttpsPassthrough() {
        assertEquals(target, PassLink.extract(target))
        assertEquals("http://host.example/x.pkpass", PassLink.extract("http://host.example/x.pkpass"))
        val withQuery = "https://host.example/get?id=42&token=abc"
        assertEquals(withQuery, PassLink.extract(withQuery))
    }

    @Test
    fun invalidInput() {
        assertNull(PassLink.extract(null))
        assertNull(PassLink.extract(""))
        assertNull(PassLink.extract("   "))
        assertNull(PassLink.extract("no links here"))
        assertNull(PassLink.extract("ftp://host.example/x.pkpass"))
        assertNull(PassLink.extract("otherwallet://import/$target".replace("https://", "")))
        assertNull(PassLink.extract("mailto:someone@host.example"))
    }

    @Test
    fun hostlessInputIsRejected() {
        assertNull(PassLink.extract("https://"))
        assertNull(PassLink.extract("https:///"))
        assertNull(PassLink.extract("walletunion://import"))
        assertNull(PassLink.extract("walletunion://import/"))
        assertNull(PassLink.extract("walletunion://import?url="))
        assertNull(PassLink.extract("walletunion://import?url=not-a-url"))
    }

    @Test
    fun unknownSchemeWithEmbeddedHttpsStillFindsTheHttpsLink() {
        // Not one of ours, but the https link inside is matched as a plain link.
        assertEquals(target, PassLink.extract("otherwallet://import/$target"))
    }

    @Test
    fun resultIsAlwaysHttp() {
        val inputs = listOf(
            "walletunion://import/$target",
            "walletunion://x?url=https%3A%2F%2Fh.example%2Fa",
            "walletunion://import/https:/h.example/a",
            "share: walletpasses://import/$target!",
        )
        for (i in inputs) {
            val out = PassLink.extract(i)
            assertTrue("$i -> $out", out != null && (out.startsWith("https://") || out.startsWith("http://")))
        }
    }
}
