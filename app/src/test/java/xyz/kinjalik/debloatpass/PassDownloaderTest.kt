package xyz.kinjalik.debloatpass

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.kinjalik.debloatpass.TinyHttpServer.Companion.ok
import xyz.kinjalik.debloatpass.TinyHttpServer.Companion.redirect
import java.io.File
import java.io.IOException

/** End-to-end PassDownloader against a local HTTP server. */
class PassDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: TinyHttpServer
    private lateinit var dir: File

    @Before
    fun setUp() {
        server = TinyHttpServer()
        dir = File(tmp.root, "passes")
    }

    @After
    fun tearDown() = server.close()

    private fun download(path: String) = PassDownloader(dir).download(server.url(path))

    private fun failure(path: String): String =
        assertThrows(IOException::class.java) { download(path) }.message!!

    private fun servePass(path: String, bytes: ByteArray = TestPasses.storeCard, vararg headers: Pair<String, String>) =
        server.route(path) { ok(bytes, "application/vnd.apple.pkpass", *headers) }

    @Test
    fun downloadsAndDescribesPass() {
        servePass("/card/x.pkpass")
        val pass = download("/card/x.pkpass")

        assertEquals("Coffee Club", pass.title)
        assertEquals("Loyalty card · карта лояльности", pass.details)
        assertEquals(File(dir, "Coffee_Club.pkpass"), pass.file)
        assertArrayEquals(TestPasses.storeCard, pass.file.readBytes())
        assertEquals(listOf("Coffee_Club.pkpass"), dir.list()!!.toList()) // no .part leftovers
        val req = server.requests.single()
        assertEquals("GET", req.method)
        assertTrue(req.header("User-Agent")!!.contains("DebloatPass"))
        assertTrue(req.header("Accept")!!.contains("application/vnd.apple.pkpass"))
    }

    @Test
    fun titleFallsBackToLogoTextThenPass() {
        servePass("/logo", TestPasses.pkpass("""{"logoText":"Logo","eventTicket":{}}"""))
        servePass("/none", TestPasses.pkpass("""{}"""))

        val logo = download("/logo")
        assertEquals("Logo", logo.title)
        assertEquals("билет", logo.details)

        val none = download("/none")
        assertEquals("Pass", none.title)
        assertEquals("", none.details)
    }

    @Test
    fun followsRelativeAndAbsoluteRedirects() {
        server.route("/start") { redirect(302, "/middle") }
        server.route("/middle") { redirect(301, server.url("/final/real.pkpass")) }
        servePass("/final/real.pkpass", TestPasses.pkpass("""{"generic":{}}"""))

        val pass = download("/start")

        assertEquals(listOf("/start", "/middle", "/final/real.pkpass"), server.requests.map { it.target })
        // The name comes from the final URL, not the first one.
        assertEquals("real.pkpass", pass.file.name)
        assertEquals("generic", pass.details)
    }

    @Test
    fun allowsMaxRedirects() {
        val n = PassDownloader.MAX_REDIRECTS
        for (i in 0 until n) server.route("/r$i") { redirect(if (i % 2 == 0) 307 else 308, "/r${i + 1}") }
        servePass("/r$n")
        download("/r0")
        assertEquals(n + 1, server.requests.size)
    }

    @Test
    fun rejectsTooManyRedirects() {
        server.route("/loop") { redirect(302, "/loop") }
        assertTrue(failure("/loop").contains("редирект"))
        assertEquals(PassDownloader.MAX_REDIRECTS + 1, server.requests.size)
    }

    @Test
    fun redirectWithoutLocationFails() {
        server.route("/bad") { TinyHttpServer.Response(302) }
        assertTrue(failure("/bad").contains("302"))
    }

    @Test
    fun httpErrorFails() {
        val msg = failure("/missing") // default 404
        assertTrue(msg, msg.contains("404"))
        assertTrue(msg, msg.contains("127.0.0.1"))
    }

    @Test
    fun nonZipResponseIsRejectedAndNothingIsKept() {
        server.route("/landing") { ok("<html>Get our app</html>".toByteArray(), "text/html") }
        val msg = failure("/landing")
        assertTrue(msg, msg.contains("text/html"))
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun zipWithoutPassJsonIsRejected() {
        server.route("/zip") { ok(TestPasses.zip("a.txt" to "a".toByteArray()), "application/zip") }
        assertTrue(failure("/zip").contains("pass.json"))
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun sizeLimitIsEnforcedWithoutContentLength() {
        server.route("/huge") {
            TinyHttpServer.Response(200, listOf("Content-Type" to "application/vnd.apple.pkpass")) { out ->
                out.write(TestPasses.storeCard)
                val chunk = ByteArray(64 * 1024)
                var sent = 0L
                while (sent <= PassDownloader.MAX_SIZE) {
                    out.write(chunk)
                    sent += chunk.size
                }
            }
        }
        val msg = failure("/huge")
        assertTrue(msg, msg.contains("20 МБ"))
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun exactlyMaxSizePassesTheSizeCheck() {
        // MAX_SIZE bytes starting with the zip magic: it must get past the size check and fail
        // later, on the (missing) pass.json.
        val body = ByteArray(PassDownloader.MAX_SIZE.toInt()).also {
            it[0] = 'P'.code.toByte(); it[1] = 'K'.code.toByte(); it[2] = 3; it[3] = 4
        }
        server.route("/max") { ok(body, "application/octet-stream") }
        assertTrue(failure("/max").contains("pass.json"))
    }

    @Test
    fun fileNameFromContentDisposition() {
        servePass(
            "/get", TestPasses.pkpass("""{"coupon":{}}"""),
            "Content-Disposition" to "attachment; filename=\"Promo 2024.pkpass\"",
        )
        val pass = download("/get?id=1")
        assertEquals("Promo_2024.pkpass", pass.file.name)
        assertEquals("купон", pass.details)
    }

    @Test
    fun fileNameFromUrlIgnoresQuery() {
        servePass("/cards/abc123.pkpass", TestPasses.pkpass("""{"boardingPass":{}}"""))
        val pass = download("/cards/abc123.pkpass?sig=zzz")
        assertEquals("abc123.pkpass", pass.file.name)
        assertEquals("посадочный", pass.details)
    }

    @Test
    fun sameNameIsOverwritten() {
        val v1 = TestPasses.pkpass("""{"organizationName":"Shop","description":"v1"}""")
        val v2 = TestPasses.pkpass("""{"organizationName":"Shop","description":"v2"}""")
        servePass("/v1", v1)
        servePass("/v2", v2)
        download("/v1")
        val second = download("/v2")
        assertArrayEquals(v2, second.file.readBytes())
        assertEquals(listOf("Shop.pkpass"), dir.list()!!.toList())
    }

    @Test
    fun filesOlderThanAnHourAreCleanedUp() {
        dir.mkdirs()
        val old = File(dir, "old.pkpass").apply {
            writeText("x")
            setLastModified(System.currentTimeMillis() - 2 * 3600_000)
        }
        val fresh = File(dir, "fresh.pkpass").apply { writeText("x") }
        servePass("/p")
        download("/p")
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }
}
