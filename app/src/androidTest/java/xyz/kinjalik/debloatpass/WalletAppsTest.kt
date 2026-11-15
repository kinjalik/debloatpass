package xyz.kinjalik.debloatpass

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reproduces every "is there a wallet app?" state with a fake wallet activity that lives in the
 * test APK (src/androidTest/AndroidManifest.xml) and is switched on and off with `pm`:
 *
 * - enabled → a wallet is installed;
 * - disabled, and WalletApps treats the test APK as "Google Play services" → Google Wallet's
 *   pkpass import is present but disabled (what Google does in some regions);
 * - disabled, and the "Google" package is something that doesn't exist → no wallet at all.
 */
@RunWith(AndroidJUnit4::class)
class WalletAppsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val testPkg = FakeWallet.pkg

    @Before
    fun noRealWallets() {
        FakeWallet.set(enabled = false)
        val others = WalletApps.handlers(ctx).map { it.activityInfo.packageName } - testPkg
        assumeTrue("A real wallet app is installed: $others", others.isEmpty())
    }

    @After
    fun restore() {
        FakeWallet.set(enabled = false)
        WalletApps.gmsPackage = WalletApps.GMS
    }

    @Test
    fun probeLooksLikeARealPassUri() {
        val uri = WalletApps.probeUri(ctx)
        assertEquals("content", uri.scheme)
        assertEquals("${ctx.packageName}.passes", uri.authority)
        assertTrue(uri.path!!.endsWith(".pkpass"))
    }

    @Test
    fun enabledWalletIsAHandlerAndScreenSaysAllSet() {
        FakeWallet.set(enabled = true)

        val pkgs = WalletApps.handlers(ctx).map { it.activityInfo.packageName }
        assertEquals(listOf(testPkg), pkgs) // one entry per app, never ourselves
        assertFalse(WalletApps.isPkpassDisabledIn(ctx, testPkg))

        assertScreen(R.string.intro_ready, openButtonVisible = true)
    }

    @Test
    fun disabledImportIsDetectedAndExplained() {
        WalletApps.gmsPackage = testPkg

        assertTrue(WalletApps.handlers(ctx).isEmpty())
        assertTrue(WalletApps.isGmsPkpassDisabled(ctx))

        assertScreen(R.string.intro_no_wallet_gms_disabled, openButtonVisible = false)
    }

    @Test
    fun noWalletAtAllOffersFossWalletOrGoogleWallet() {
        WalletApps.gmsPackage = "com.example.not.installed"

        assertFalse(WalletApps.isGmsPkpassDisabled(ctx))

        assertScreen(R.string.intro_no_wallet, openButtonVisible = false)
    }

    @Test
    fun screenUpdatesWhenAWalletIsInstalledWhileAway() {
        WalletApps.gmsPackage = testPkg
        ActivityScenario.launch<ImportActivity>(Intent(ctx, ImportActivity::class.java)).use { scenario ->
            scenario.onActivity { assertIntro(it, R.string.intro_no_wallet_gms_disabled, false) }

            scenario.moveToState(Lifecycle.State.CREATED) // the user goes off to install a wallet
            FakeWallet.set(enabled = true)
            scenario.moveToState(Lifecycle.State.RESUMED)

            scenario.onActivity { assertIntro(it, R.string.intro_ready, true) }
        }
    }

    private fun assertScreen(expected: Int, openButtonVisible: Boolean) {
        ActivityScenario.launch<ImportActivity>(Intent(ctx, ImportActivity::class.java)).use { scenario ->
            scenario.onActivity { assertIntro(it, expected, openButtonVisible) }
        }
    }

    private fun assertIntro(a: ImportActivity, expected: Int, openButtonVisible: Boolean) {
        assertEquals(a.getText(expected).toString(), a.findViewById<TextView>(R.id.intro_status).text.toString())
        assertEquals(openButtonVisible, a.findViewById<View>(R.id.open).visibility == View.VISIBLE)
    }
}
