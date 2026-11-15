package xyz.kinjalik.debloatpass

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.io.FileNotFoundException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs inside the debug app: a fixture .pkpass is served from 127.0.0.1 (cleartext is allowed
 * only by the debug network security config), a real VIEW deeplink is fired, and the downloaded
 * file is read back through PassProvider.
 */
@RunWith(AndroidJUnit4::class)
class DeeplinkImportTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: TinyHttpServer
    private val fixture = TestPasses.pkpass(
        """{"formatVersion":1,"organizationName":"Instrumented Test","description":"fixture","storeCard":{}}""",
    )
    private val expectedName = "Instrumented_Test.pkpass"

    @Before
    fun setUp() {
        // In case WalletAppsTest left the test APK's fake wallet on: it would take over the
        // screen when ImportActivity auto-opens the downloaded pass.
        FakeWallet.set(enabled = false)
        PassProvider.dir(ctx).deleteRecursively()
        server = TinyHttpServer()
        server.route("/test.pkpass") { TinyHttpServer.ok(fixture, PassProvider.MIME) }
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            for (stage in listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED, Stage.STARTED)) {
                monitor.getActivitiesInStage(stage).forEach { it.finish() }
            }
        }
        server.close()
        PassProvider.dir(ctx).deleteRecursively()
    }

    private fun deeplink(scheme: String, payload: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://import/$payload"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(ctx.packageName)

    @Test
    fun everyDeeplinkSchemeResolvesToImportActivity() {
        assertTrue(BuildConfig.DEEPLINK_SCHEMES.isNotEmpty())
        for (scheme in BuildConfig.DEEPLINK_SCHEMES) {
            val intent = deeplink(scheme, "https://host.example/x.pkpass")

            @Suppress("DEPRECATION")
            val handlers = ctx.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertEquals(scheme, listOf(ImportActivity::class.java.name), handlers.map { it.activityInfo.name })
        }
    }

    @Test
    fun deeplinkDownloadsPassAndProviderServesIt() {
        // Implicit VIEW intent (restricted to our package so no chooser can pop up).
        val intent = deeplink("walletunion", server.url("/test.pkpass"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)

        val file = File(PassProvider.dir(ctx), expectedName)
        waitFor("$file to be downloaded") { file.isFile && file.length() == fixture.size.toLong() }

        assertEquals(listOf("/test.pkpass"), server.requests.map { it.target })
        assertArrayEquals(fixture, file.readBytes())

        val uri = PassProvider.uriFor(ctx, file)
        assertEquals("content://${ctx.packageName}.passes/$expectedName", uri.toString())

        val resolver = ctx.contentResolver
        assertEquals("application/vnd.apple.pkpass", resolver.getType(uri))
        resolver.query(uri, null, null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(expectedName, c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(fixture.size.toLong(), c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        val served = resolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals(fixture, served)
    }

    @Test
    fun providerRefusesPathsOutsideItsDirectory() {
        val secret = File(ctx.cacheDir, "secret.txt").apply { writeText("nope") }
        val evil = Uri.parse("content://${ctx.packageName}.passes/..%2Fsecret.txt")
        try {
            ctx.contentResolver.openInputStream(evil)!!.close()
            fail("provider served a file outside cache/passes")
        } catch (_: FileNotFoundException) {
        } finally {
            secret.delete()
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (cond()) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for $what")
    }
}
