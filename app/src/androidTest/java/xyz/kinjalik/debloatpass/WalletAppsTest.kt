package xyz.kinjalik.debloatpass

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletAppsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun probeLooksLikeARealPassUri() {
        val uri = WalletApps.probeUri(ctx)
        assertEquals("content", uri.scheme)
        assertEquals("${ctx.packageName}.passes", uri.authority)
        assertTrue(uri.path!!.endsWith(".pkpass"))
    }

    @Test
    fun handlersNeverIncludeUsAndAreOnePerApp() {
        val pkgs = WalletApps.handlers(ctx).map { it.activityInfo.packageName }
        assertFalse(ctx.packageName in pkgs)
        assertEquals(pkgs.distinct(), pkgs)
    }

    @Test
    fun gmsCheckIsConsistentWithHandlers() {
        // Disabled GMS import and a working GMS handler are mutually exclusive.
        val gmsHandles = WalletApps.handlers(ctx).any { it.activityInfo.packageName == WalletApps.GMS }
        if (gmsHandles) assertFalse(WalletApps.isGmsPkpassDisabled(ctx))
    }
}
