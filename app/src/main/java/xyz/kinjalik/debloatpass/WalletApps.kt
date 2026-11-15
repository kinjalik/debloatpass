package xyz.kinjalik.debloatpass

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri

/** Which installed apps can take a .pkpass from us. */
object WalletApps {
    const val GMS = "com.google.android.gms"

    val MIMES = listOf(PassProvider.MIME, "application/vnd-com.apple.pkpass")

    /** A URI shaped like the ones we really send (content://<pkg>.passes/<name>.pkpass). */
    fun probeUri(ctx: Context): Uri = Uri.parse("content://${ctx.packageName}.passes/probe.pkpass")

    fun viewIntents(uri: Uri): List<Intent> = MIMES.map { Intent(Intent.ACTION_VIEW).setDataAndType(uri, it) }

    /** Apps that would open the pass, one entry per app, never ourselves. */
    fun handlers(ctx: Context, uri: Uri = probeUri(ctx)): List<ResolveInfo> = query(ctx, uri, 0)

    /**
     * Google Wallet's pkpass import is an activity inside Google Play services
     * (`.pay.deeplink.AliasSavePkPassActivity`). Where Google turns the feature off (some
     * regions, devices without Wallet set up) the activity is disabled: it is still in the
     * manifest, so it shows up when disabled components are included, but not otherwise.
     */
    fun isGmsPkpassDisabled(ctx: Context, uri: Uri = probeUri(ctx)): Boolean = isPkpassDisabledIn(ctx, gmsPackage, uri)

    /** [pkg] declares a pkpass handler, but it is disabled right now. */
    fun isPkpassDisabledIn(ctx: Context, pkg: String, uri: Uri = probeUri(ctx)): Boolean {
        if (handlers(ctx, uri).any { it.activityInfo.packageName == pkg }) return false
        return query(ctx, uri, PackageManager.MATCH_DISABLED_COMPONENTS)
            .any { it.activityInfo.packageName == pkg }
    }

    /**
     * The package treated as "Google Wallet's importer". Instrumented tests point it at a fake
     * wallet in the test APK so every state can be reproduced on any emulator.
     */
    @Volatile
    internal var gmsPackage: String = GMS

    /** First paragraph of the main screen for the current state. */
    fun introText(hasWallet: Boolean, gmsPkpassDisabled: Boolean): Int = when {
        hasWallet -> R.string.intro_ready
        gmsPkpassDisabled -> R.string.intro_no_wallet_gms_disabled
        else -> R.string.intro_no_wallet
    }

    @Suppress("DEPRECATION") // the ResolveInfoFlags overload is API 33+
    private fun query(ctx: Context, uri: Uri, flags: Int): List<ResolveInfo> = viewIntents(uri)
        .flatMap { ctx.packageManager.queryIntentActivities(it, flags) }
        .filter { it.activityInfo.packageName != ctx.packageName }
        .distinctBy { it.activityInfo.packageName }
}
