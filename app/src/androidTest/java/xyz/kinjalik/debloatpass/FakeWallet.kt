package xyz.kinjalik.debloatpass

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/**
 * Switches [FakeWalletActivity] (a pkpass handler in the test APK, disabled in its manifest)
 * on and off with `pm enable` / `pm disable`, which the shell allows because the test APK is
 * `testOnly`.
 */
object FakeWallet {
    val pkg: String get() = InstrumentationRegistry.getInstrumentation().context.packageName
    private val component get() = "$pkg/${FakeWalletActivity::class.java.name}"
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * Older Android (seen on 7.0) doesn't let the shell enable a component even in a test-only
     * package. Then the test that needs it is skipped (an assumption failure), not failed.
     */
    fun set(enabled: Boolean) {
        // On success pm prints "Component {…} new state: …" to stdout; a refusal goes to stderr,
        // which executeShellCommand doesn't capture, so stdout is simply empty.
        val out = shell("pm ${if (enabled) "enable" else "disable"} $component")
        // Wait until the app's PackageManager view reflects it.
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (WalletApps.handlers(app).any { it.activityInfo.packageName == pkg } == enabled) return
            SystemClock.sleep(100)
        }
        assumeTrue(
            "pm refused to change the fake wallet's state on API ${Build.VERSION.SDK_INT}",
            "new state" in out,
        )
        throw AssertionError("fake wallet did not become ${if (enabled) "enabled" else "disabled"}: $out")
    }

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }
}
