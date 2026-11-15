package xyz.kinjalik.debloatpass

import java.io.File
import java.io.IOException
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

    // The local test server only speaks plain http; the real app decides via NetworkSecurityPolicy.
    private fun download(path: String) = PassDownloader(dir, cleartextPermitted = { true }).download(server.url(path))

    private fun failure(path: String): PassDownloader.Failure =
        assertThrows(PassDownloader.Failure::class.java) { download(path) }

    private fun servePass(path: String, bytes: ByteArray = TestPasses.storeCard, vararg headers: Pair<String, String>) =
        server.route(path) { ok(bytes, "application/vnd.apple.pkpass", *headers) }

    @Test
    fun downloadsAndDescribesPass() {
        servePass("/card/x.pkpass")
        val pass = download("/card/x.pkpass")

        assertEquals("Coffee Club", pass.title)
        assertEquals("Loyalty card", pass.description)
        assertEquals(PassDownloader.Style.STORE_CARD, pass.style)
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
        assertEquals(PassDownloader.Style.EVENT_TICKET, logo.style)
        assertEquals(null, logo.description)

        val none = download("/none")
        assertEquals(null, none.title) // the UI shows a translated "Pass"
        assertEquals(null, none.style)
    }

    @Test
    fun httpIsTriedOverHttpsWhenCleartextIsNotAllowed() {
        servePass("/x.pkpass")
        // The test server has no TLS, so the https attempt fails and the user gets a clear reason
        // instead of Android's raw "CLEARTEXT communication … not permitted".
        val f = assertThrows(PassDownloader.Failure::class.java) {
            PassDownloader(dir, cleartextPermitted = { false }).download(server.url("/x.pkpass"))
        }
        assertEquals(PassDownloader.Reason.INSECURE_HTTP, f.reason)
        assertTrue(server.requests.none { it.target == "/x.pkpass" }) // never fetched over plain http
    }

    @Test
    fun networkErrorsDuringTheHttpsUpgradeAreNotBlamedOnTheServer() {
        // No such host (".invalid" never resolves): the user should hear "can't find the server",
        // not "the server only offers http".
        val e = assertThrows(IOException::class.java) {
            PassDownloader(dir, cleartextPermitted = { false }).download("http://no-such-host.invalid/x.pkpass")
        }
        assertFalse(e.toString(), e is PassDownloader.Failure && e.reason == PassDownloader.Reason.INSECURE_HTTP)
    }

    @Test
    fun httpsUpgradeDropsTheHttpDefaultPort() {
        fun up(s: String) = PassDownloader.httpsOf(java.net.URL(s)).toString()
        assertEquals("https://h.example/a?b", up("http://h.example/a?b"))
        assertEquals("https://h.example/a", up("http://h.example:80/a")) // not https://…:80
        assertEquals("https://h.example:8080/a", up("http://h.example:8080/a"))
    }

    @Test
    fun redirectToANonHttpSchemeIsRejected() {
        server.route("/ftp") { redirect(302, "ftp://host.example/x.pkpass") }
        server.route("/file") { redirect(302, "file:///etc/hosts") }
        assertEquals(PassDownloader.Reason.BAD_REDIRECT, failure("/ftp").reason)
        assertEquals(PassDownloader.Reason.BAD_REDIRECT, failure("/file").reason)
    }

    @Test
    fun slowServerHitsTheOverallDeadline() {
        server.route("/slow") {
            TinyHttpServer.Response(200, listOf("Content-Type" to "application/vnd.apple.pkpass")) { out ->
                repeat(50) {
                    out.write(ByteArray(16))
                    out.flush()
                    Thread.sleep(100) // each read is quick; only the total is too long
                }
            }
        }
        val f = assertThrows(PassDownloader.Failure::class.java) {
            PassDownloader(dir, cleartextPermitted = { true }, totalTimeoutMs = 1_000).download(server.url("/slow"))
        }
        assertEquals(PassDownloader.Reason.TIMEOUT, f.reason)
        assertTrue(dir.list()!!.isEmpty())
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
        assertEquals(PassDownloader.Style.GENERIC, pass.style)
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
        assertEquals(PassDownloader.Reason.TOO_MANY_REDIRECTS, failure("/loop").reason)
        assertEquals(PassDownloader.MAX_REDIRECTS + 1, server.requests.size)
    }

    @Test
    fun redirectWithoutLocationFails() {
        server.route("/bad") { TinyHttpServer.Response(302) }
        val f = failure("/bad")
        assertEquals(PassDownloader.Reason.NO_LOCATION, f.reason)
        assertTrue(f.message!!, f.detail!!.contains("302"))
    }

    @Test
    fun httpErrorFails() {
        val f = failure("/missing") // default 404
        assertEquals(PassDownloader.Reason.HTTP_ERROR, f.reason)
        assertTrue(f.message!!, f.detail!!.contains("404"))
        assertTrue(f.message!!, f.detail!!.contains("127.0.0.1"))
    }

    @Test
    fun nonZipResponseIsRejectedAndNothingIsKept() {
        server.route("/landing") { ok("<html>Get our app</html>".toByteArray(), "text/html") }
        val f = failure("/landing")
        assertEquals(PassDownloader.Reason.NOT_PKPASS, f.reason)
        assertTrue(f.message!!, f.detail!!.contains("text/html"))
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun zipWithoutPassJsonIsRejected() {
        server.route("/zip") { ok(TestPasses.zip("a.txt" to "a".toByteArray()), "application/zip") }
        assertEquals(PassDownloader.Reason.NO_PASS_JSON, failure("/zip").reason)
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
        assertEquals(PassDownloader.Reason.TOO_LARGE, failure("/huge").reason)
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun exactlyMaxSizePassesTheSizeCheck() {
        // MAX_SIZE bytes starting with the zip magic: it must get past the size check and fail
        // later, on the (missing) pass.json.
        val body = ByteArray(PassDownloader.MAX_SIZE.toInt()).also {
            it[0] = 'P'.code.toByte()
            it[1] = 'K'.code.toByte()
            it[2] = 3
            it[3] = 4
        }
        server.route("/max") { ok(body, "application/octet-stream") }
        assertEquals(PassDownloader.Reason.NO_PASS_JSON, failure("/max").reason)
    }

    @Test
    fun fileNameFromContentDisposition() {
        servePass(
            "/get",
            TestPasses.pkpass("""{"coupon":{}}"""),
            "Content-Disposition" to "attachment; filename=\"Promo 2024.pkpass\"",
        )
        val pass = download("/get?id=1")
        assertEquals("Promo_2024.pkpass", pass.file.name)
        assertEquals(PassDownloader.Style.COUPON, pass.style)
    }

    @Test
    fun fileNameFromUrlIgnoresQuery() {
        servePass("/cards/abc123.pkpass", TestPasses.pkpass("""{"boardingPass":{}}"""))
        val pass = download("/cards/abc123.pkpass?sig=zzz")
        assertEquals("abc123.pkpass", pass.file.name)
        assertEquals(PassDownloader.Style.BOARDING_PASS, pass.style)
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
