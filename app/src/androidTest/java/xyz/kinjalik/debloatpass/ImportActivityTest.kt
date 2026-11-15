package xyz.kinjalik.debloatpass

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** How ImportActivity takes links in and keeps its result (state lives in ImportJob). */
@RunWith(AndroidJUnit4::class)
class ImportActivityTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val instr = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: TinyHttpServer

    private val first = TestPasses.pkpass("""{"organizationName":"First","storeCard":{}}""")
    private val second = TestPasses.pkpass("""{"organizationName":"Second","coupon":{}}""")

    @Before
    fun setUp() {
        FakeWallet.set(enabled = false)
        instr.runOnMainSync { ImportJob.resetForTests() }
        PassProvider.dir(ctx).deleteRecursively()
        server = TinyHttpServer()
        server.route("/first.pkpass") { TinyHttpServer.ok(first, PassProvider.MIME) }
        server.route("/second.pkpass") { TinyHttpServer.ok(second, PassProvider.MIME) }
        // ~3 s for the first card, so a second link surely arrives while it downloads.
        server.route("/slow.pkpass") {
            TinyHttpServer.Response(200, listOf("Content-Type" to PassProvider.MIME)) { out ->
                first.toList().chunked(first.size / 10 + 1).forEach {
                    out.write(it.toByteArray())
                    out.flush()
                    Thread.sleep(300)
                }
            }
        }
    }

    @After
    fun tearDown() {
        importActivities().forEach { a -> instr.runOnMainSync { a.finish() } }
        FakeWallet.set(enabled = false)
        server.close()
        instr.runOnMainSync { ImportJob.resetForTests() }
        PassProvider.dir(ctx).deleteRecursively()
    }

    private fun link(path: String) = "walletunion://import/" + server.url(path)

    private fun view(link: String) = Intent(Intent.ACTION_VIEW, Uri.parse(link))
        .setComponent(ComponentName(ctx, ImportActivity::class.java))

    private fun state(): ImportJob.State {
        var s: ImportJob.State = ImportJob.State.Idle
        instr.runOnMainSync { s = ImportJob.state }
        return s
    }

    private fun waitFor(what: String, timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (cond()) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for $what (state: ${state()})")
    }

    private fun doneTitle(): String? = ((state() as? ImportJob.State.Done)?.pass?.title)

    @Test
    fun sharedStyledTextIsRead() {
        // Some apps share Spanned text; getStringExtra would return null for it.
        val text = SpannableString("Your card: ${link("/first.pkpass")}").apply {
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, 4, 0)
        }
        val share = Intent(Intent.ACTION_SEND)
            .setComponent(ComponentName(ctx, ImportActivity::class.java))
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text as CharSequence)
        ActivityScenario.launch<ImportActivity>(share).use {
            waitFor("the shared link to be downloaded") { doneTitle() == "First" }
        }
    }

    /**
     * Two deeplinks the way a browser sends them (startActivity, no ActivityScenario: it can't
     * close an activity whose intent onNewIntent replaced). singleTask must route the second one
     * to the running instance, and ImportJob must queue it behind the running download.
     */
    @Test
    fun linkArrivingDuringADownloadIsQueuedNotLost() {
        ctx.startActivity(view(link("/slow.pkpass")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("the slow download to start") { state() is ImportJob.State.Running }
        ctx.startActivity(view(link("/second.pkpass")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("the queued link to be downloaded after the first") { doneTitle() == "Second" }
        assertEquals(listOf("/slow.pkpass", "/second.pkpass"), server.requests.map { it.target })
        assertEquals("ImportActivity instances", 1, importActivities().size)
    }

    private fun importActivities(): List<android.app.Activity> {
        val found = mutableListOf<android.app.Activity>()
        instr.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            for (stage in listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)) {
                found += monitor.getActivitiesInStage(stage).filterIsInstance<ImportActivity>()
            }
        }
        return found
    }

    @Test
    fun resultSurvivesActivityRecreation() {
        ActivityScenario.launch<ImportActivity>(view(link("/first.pkpass"))).use { scenario ->
            waitFor("the download") { doneTitle() == "First" }
            scenario.recreate() // what a dark mode / language switch or rotation does
            scenario.onActivity { a ->
                val status = a.findViewById<TextView>(R.id.status).text.toString()
                assertTrue(status, "First.pkpass" in status)
                assertEquals(View.VISIBLE, a.findViewById<View>(R.id.actions).visibility)
            }
        }
    }

    @Test
    fun recreationDuringADownloadDoesNotStartASecondOne() {
        ActivityScenario.launch<ImportActivity>(view(link("/slow.pkpass"))).use { scenario ->
            waitFor("the slow download to start") { state() is ImportJob.State.Running }
            scenario.recreate()
            waitFor("the download to finish") { doneTitle() == "First" }
            scenario.onActivity { a ->
                assertTrue("First.pkpass" in a.findViewById<TextView>(R.id.status).text.toString())
            }
            assertEquals(listOf("/slow.pkpass"), server.requests.map { it.target })
        }
    }

    @Test
    fun deeplinkDownloadOpensTheWalletApp() {
        FakeWallet.set(enabled = true) // skipped where the shell can't enable it (Android 7)
        ActivityScenario.launch<ImportActivity>(view(link("/first.pkpass"))).use {
            waitFor("the download") { doneTitle() == "First" }
            assertTrue("the wallet app was not opened", FakeWallet.appearsWithin(10_000))
        }
    }
}
