package xyz.kinjalik.debloatpass

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Plain JVM: zip/pass.json checks and file naming, no network. */
class PassValidationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(bytes: ByteArray): File = tmp.newFile().apply { writeBytes(bytes) }

    @Test
    fun validPassReturnsPassJson() {
        val json = PassDownloader.checkPass(file(TestPasses.storeCard), "application/vnd.apple.pkpass")
        assertEquals("Coffee Club", json.getString("organizationName"))
        assertTrue(json.has("storeCard"))
    }

    @Test
    fun passJsonWithBomIsAccepted() {
        val f = file(TestPasses.pkpass(Char(0xFEFF) + "{\"organizationName\":\"Bom\"}"))
        assertEquals("Bom", PassDownloader.checkPass(f, null).getString("organizationName"))
    }

    @Test
    fun htmlIsRejectedWithContentTypeInMessage() {
        val f = file("<html><body>Install our wallet app!</body></html>".toByteArray())
        val e =
            assertThrows(PassDownloader.Failure::class.java) { PassDownloader.checkPass(f, "text/html; charset=utf-8") }
        assertEquals(PassDownloader.Reason.NOT_PKPASS, e.reason)
        assertTrue(e.message!!, e.message!!.contains("text/html"))
    }

    @Test
    fun missingContentTypeIsReported() {
        val e = assertThrows(PassDownloader.Failure::class.java) { PassDownloader.checkPass(file(ByteArray(0)), null) }
        assertEquals(PassDownloader.Reason.NOT_PKPASS, e.reason)
        assertEquals(null, e.detail) // the UI shows a localized "no Content-Type"
    }

    @Test
    fun tinyFilesAreNotZips() {
        assertFalse(PassDownloader.isZip(file(ByteArray(0))))
        assertFalse(PassDownloader.isZip(file("PK".toByteArray())))
        val emptyZipMarker = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 5, 6)
        assertFalse(PassDownloader.isZip(file(emptyZipMarker)))
        assertTrue(PassDownloader.isZip(file(TestPasses.storeCard)))
    }

    @Test
    fun oversizedPassJsonIsRejected() {
        // Highly compressible: a few KB in the zip, over the limit once inflated (zip-bomb shape).
        val big = "{\"a\":\"" + "x".repeat(PassDownloader.MAX_PASS_JSON.toInt()) + "\"}"
        val f = file(TestPasses.zip("pass.json" to big.toByteArray()))
        assertTrue(f.length() < PassDownloader.MAX_PASS_JSON / 10)
        assertNull(PassDownloader.readPassJson(f))
        val e = assertThrows(PassDownloader.Failure::class.java) { PassDownloader.checkPass(f, "application/zip") }
        assertEquals(PassDownloader.Reason.NO_PASS_JSON, e.reason)
    }

    @Test
    fun zipWithoutPassJsonIsRejected() {
        val f = file(TestPasses.zip("manifest.json" to "{}".toByteArray()))
        val e = assertThrows(PassDownloader.Failure::class.java) { PassDownloader.checkPass(f, "application/zip") }
        assertEquals(PassDownloader.Reason.NO_PASS_JSON, e.reason)
    }

    @Test
    fun passJsonInSubfolderDoesNotCount() {
        val f = file(TestPasses.zip("sub/pass.json" to "{}".toByteArray()))
        assertNull(PassDownloader.readPassJson(f))
    }

    @Test
    fun brokenPassJsonIsRejected() {
        assertNull(PassDownloader.readPassJson(file(TestPasses.pkpass("not json"))))
        assertNull(PassDownloader.readPassJson(file(TestPasses.pkpass("[1,2]"))))
    }

    @Test
    fun truncatedZipIsRejected() {
        val f = file(TestPasses.storeCard.copyOf(20))
        assertTrue(PassDownloader.isZip(f))
        assertNull(PassDownloader.readPassJson(f))
    }

    // ---- file names ----

    @Test
    fun organizationNameWins() {
        assertEquals(
            "Coffee_Club.pkpass",
            PassDownloader.fileName("Coffee Club", "attachment; filename=\"other.pkpass\"", "/card/x.pkpass"),
        )
    }

    @Test
    fun contentDispositionIsSecond() {
        assertEquals("other.pkpass", PassDownloader.fileName("", "attachment; filename=\"other.pkpass\"", "/x.pkpass"))
        assertEquals("plain.pkpass", PassDownloader.fileName(null, "attachment; filename=plain.pkpass", "/x"))
        assertEquals(
            "Кофе_1.pkpass",
            PassDownloader.fileName(null, "attachment; filename*=UTF-8''%D0%9A%D0%BE%D1%84%D0%B5%201.pkpass", "/x"),
        )
    }

    @Test
    fun urlPathIsThird() {
        assertEquals("x.pkpass", PassDownloader.fileName(" ", null, "/card/x.pkpass"))
        assertEquals("abc123.pkpass", PassDownloader.fileName(null, "inline", "/get/abc123"))
    }

    @Test
    fun fallbackIsPass() {
        assertEquals("pass.pkpass", PassDownloader.fileName(null, null, "/"))
        assertEquals("pass.pkpass", PassDownloader.fileName(null, null, null))
        assertEquals("pass.pkpass", PassDownloader.fileName("///", null, null))
        assertEquals("pass.pkpass", PassDownloader.fileName("..", null, null))
    }

    @Test
    fun namesAreSanitized() {
        assertEquals("a_b_c.pkpass", PassDownloader.fileName("a/b\\c", null, null))
        assertEquals("evil.pkpass", PassDownloader.fileName(null, "attachment; filename=\"../../evil.pkpass\"", null))
        assertEquals("x.pkpass", PassDownloader.fileName("x.pkpass", null, null))
        val long = PassDownloader.fileName("a".repeat(200), null, null)
        assertEquals("a".repeat(60) + ".pkpass", long)
    }
}
