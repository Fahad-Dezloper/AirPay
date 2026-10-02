package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which wallet app to open for a wallet. Getting this wrong is the "Open with…" chooser on a phone
 * with no default wallet — and a signature that never reaches the wallet that holds the key.
 */
class WalletAppChoiceTest {
    private val phantom = "app.phantom" to "Phantom"
    private val solflare = "com.solflare.mobile" to "Solflare"
    private val backpack = "app.backpack.mobile.standalone" to "Backpack"
    private val seeker = "com.solanamobile.wallet" to "Seeker Wallet"

    private fun linked(name: String, uri: String? = null, pkg: String? = null) =
        Linked(address = "A", token = "t", name = name, uri = uri, addedAt = 0, pkg = pkg)

    @Test fun aRecordedPackageIsAlwaysUsed() {
        assertEquals("x.y", WalletLink.chooseApp("x.y", listOf("phantom"), listOf(phantom, solflare)))
    }

    @Test fun otherwiseTheAppIsFoundByWhatTheWalletCalledItself() {
        val apps = listOf(backpack, phantom, solflare)
        assertEquals("app.phantom", WalletLink.chooseApp(null, listOf("phantom"), apps))
        assertEquals("com.solflare.mobile", WalletLink.chooseApp(null, listOf("solflare"), apps))
        assertEquals("app.backpack.mobile.standalone", WalletLink.chooseApp(null, listOf("backpack"), apps))
    }

    @Test fun theOnlyWalletOnThePhoneIsTheAnswerWhenNothingElseIs() {
        assertEquals("app.phantom", WalletLink.chooseApp(null, null, listOf(phantom)))
        assertEquals("app.phantom", WalletLink.chooseApp(null, listOf("backpack"), listOf(phantom)))
    }

    @Test fun whenItCannotBeToldItSaysSoInsteadOfGuessing() {
        assertNull(WalletLink.chooseApp(null, null, listOf(phantom, solflare)))
        assertNull(WalletLink.chooseApp(null, listOf("backpack"), listOf(phantom, solflare)))
        assertNull(WalletLink.chooseApp(null, listOf("phantom"), emptyList()))
    }

    @Test fun theHintsComeFromWhatTheWalletSaidWhenItConnected() {
        assertEquals(listOf("phantom"), linked("Phantom").appHints)
        assertEquals(listOf("solflare"), linked("Wallet", uri = "https://solflare.com/ul/v1").appHints)
        assertEquals(listOf("backpack"), linked("Backpack").appHints)
    }

    @Test fun seekerWalletIsLeftToTheSystemDefaultSoItsBehaviourIsUnchanged() {
        assertNull(linked("dezloper.skr").appHints)
        // With several wallets installed and no hint, it falls back to the untargeted route.
        assertNull(WalletLink.chooseApp(null, linked("dezloper.skr").appHints, listOf(seeker, phantom, solflare)))
    }
}
