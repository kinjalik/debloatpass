package xyz.kinjalik.debloatpass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric for android.net.Uri and android.util.Base64. */
@RunWith(RobolectricTestRunner::class)
class DeeplinkTemplateTest {

    private val url = "https://host.example/card/x.pkpass?sig=a%2Bb"

    @Test
    fun pathTemplateWithLongerPrefix() {
        val t = DeeplinkTemplate.parse("newwallet://add/pass/{url}")
        assertEquals(url, t.extract("newwallet://add/pass/$url"))
        assertNull(t.extract("newwallet://add/$url"))
        assertNull(t.extract("newwallet://other/pass/$url"))
        assertNull(t.extract("otherwallet://add/pass/$url"))
        assertEquals(url, t.extract(t.build(url)))
    }

    @Test
    fun wholeUrlPercentEncodedInPathIsDecodedOnce() {
        val t = DeeplinkTemplate.parse("w://import/{url}")
        assertEquals(
            "https://h.example/a?x=%2B",
            t.extract("w://import/https%3A%2F%2Fh.example%2Fa%3Fx%3D%252B"),
        )
    }

    @Test
    fun queryTemplate() {
        val t = DeeplinkTemplate.parse("w://import?u={url}")
        assertEquals(url, t.extract(t.build(url)))
        assertEquals("https://h.example/a", t.extract("w://import?x=1&u=https%3A%2F%2Fh.example%2Fa"))
        assertNull(t.extract("w://import?url=https%3A%2F%2Fh.example%2Fa")) // other parameter name
        assertNull(t.extract("w://import/path?u=https%3A%2F%2Fh.example%2Fa")) // other path
        assertNull(t.extract("w://import?u="))
    }

    @Test
    fun queryTemplateWithPath() {
        val t = DeeplinkTemplate.parse("w://h/add?url={url}")
        assertEquals("https://h.example/a", t.extract("w://h/add?url=https%3A%2F%2Fh.example%2Fa"))
        assertNull(t.extract("w://h?url=https%3A%2F%2Fh.example%2Fa"))
    }

    @Test
    fun base64InPathAndQuery() {
        val p = DeeplinkTemplate.parse("w://add/{url:base64}")
        val q = DeeplinkTemplate.parse("w://add?d={url:base64}")
        assertEquals(url, p.extract(p.build(url)))
        assertEquals(url, q.extract(q.build(url)))
        // standard alphabet with padding, and percent-encoded "=" padding
        val std = java.util.Base64.getEncoder().encodeToString("https://h.example/a?b=c".toByteArray())
        assertEquals("https://h.example/a?b=c", p.extract("w://add/$std"))
        assertEquals("https://h.example/a?b=c", p.extract("w://add/" + std.replace("=", "%3D")))
        assertNull(p.extract("w://add/!!!not-base64!!!"))
    }

    @Test
    fun hostAndSchemeAreCaseInsensitive() {
        val t = DeeplinkTemplate.parse("walletunion://import/{url}")
        assertEquals(url, t.extract("WalletUnion://IMPORT/$url"))
    }

    @Test
    fun malformedTemplatesAreRejected() {
        for (bad in listOf(
            "walletunion://import", // no {url}
            "walletunion://import{url}", // {url} not after a /
            "walletunion://import/{url}/more", // {url} not at the end
            "walletunion://{url}", // no host
            "WalletUnion://import/{url}", // upper case scheme
            "walletunion://import/{link}", // unknown placeholder
            "walletunion://import/{url:hex}", // unknown encoding
            "walletunion://import?a={url}&b=1", // query placeholder not last
        )) {
            assertThrows(bad, IllegalArgumentException::class.java) { DeeplinkTemplate.parse(bad) }
        }
    }
}
