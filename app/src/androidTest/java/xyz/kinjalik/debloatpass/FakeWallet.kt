package xyz.kinjalik.debloatpass

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Switches [FakeWalletActivity] (a pkpass handler in the test APK, disabled in its manifest)
 * on and off with `pm enable` / `pm disable`, which the shell allows because the test APK is
 * `testOnly`.
 */
object FakeWallet {
    val pkg: String get() = InstrumentationRegistry.getInstrumentation().context.packageName
    private val component get() = "$pkg/${FakeWalletActivity::class.java.name}"
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    fun set(enabled: Boolean) {
        shell("pm ${if (enabled) "enable" else "disable"} $component")
        // Wait until the app's PackageManager view reflects it.
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (WalletApps.handlers(app).any { it.activityInfo.packageName == pkg } == enabled) return
            SystemClock.sleep(100)
        }
        throw AssertionError("fake wallet did not become ${if (enabled) "enabled" else "disabled"}")
    }

    private fun shell(cmd: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }
}
