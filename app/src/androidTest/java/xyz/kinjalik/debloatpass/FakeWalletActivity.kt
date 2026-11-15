package xyz.kinjalik.debloatpass

import android.app.Activity
import android.os.Bundle

/** Stands in for a wallet app in WalletAppsTest; see src/androidTest/AndroidManifest.xml. */
class FakeWalletActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
