package xyz.kinjalik.debloatpass

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Stands in for a wallet app in WalletAppsTest / ImportActivityTest; see
 * src/androidTest/AndroidManifest.xml. It stays up for a moment so a test can see (via
 * `dumpsys activity`) that it was launched, then goes away.
 */
class FakeWalletActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 2_000)
    }
}
