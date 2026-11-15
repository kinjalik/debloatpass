package xyz.kinjalik.debloatpass

import org.junit.Assert.assertEquals
import org.junit.Test

class IntroTextTest {

    @Test
    fun walletInstalledWins() {
        assertEquals(R.string.intro_ready, WalletApps.introText(hasWallet = true, gmsPkpassDisabled = false))
        assertEquals(R.string.intro_ready, WalletApps.introText(hasWallet = true, gmsPkpassDisabled = true))
    }

    @Test
    fun noWalletAndGoogleImportDisabled() {
        assertEquals(
            R.string.intro_no_wallet_gms_disabled,
            WalletApps.introText(hasWallet = false, gmsPkpassDisabled = true),
        )
    }

    @Test
    fun noWalletAtAll() {
        assertEquals(R.string.intro_no_wallet, WalletApps.introText(hasWallet = false, gmsPkpassDisabled = false))
    }
}
