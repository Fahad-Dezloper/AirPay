package so.duffle.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationIntentCreator
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationScenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Talking to **one named wallet**, instead of whichever one Android picks.
 *
 * The Seeker ships a persistent preferred activity for the `solana-wallet` scheme, pointing at
 * Seeker Wallet — it sits in the ROM next to the system's own file-type defaults, so no chooser
 * ever appears and there is nothing in Settings to clear. Every `solana-wallet://` intent lands
 * in the same wallet no matter what the app does. Clearing the auth token does not help; nor
 * does a fresh adapter, because the routing happens before any of that is read.
 *
 * An **explicit** intent skips preferred-activity resolution entirely, which is the one lever
 * left. The ktx `MobileWalletAdapter` will not hand us its intent — `ActivityResultSender` is
 * final and its launcher is internal — so this drives the base client directly: make the local
 * association, build the intent, aim it at a package, and run the session by hand.
 *
 * Only used for wallets linked *through the picker*, where the package is known. The wallet that
 * was already connected keeps the original path, so the code that signs today cannot regress
 * because of a wallet somebody added later.
 */
object WalletLink {

    private const val TAG = "WalletLink"
    private const val TIMEOUT_MS = 90_000L
    private const val CLUSTER = "devnet"

    /** A wallet app on this phone that speaks Mobile Wallet Adapter. */
    data class App(val pkg: String, val label: String, val icon: Drawable?)

    sealed interface Linking {
        data class Ok(val address: String, val token: String, val accountLabel: String?, val uri: String?) : Linking
        data class Failed(val why: String) : Linking
    }

    sealed interface Signing {
        data class Signed(val tx: ByteArray) : Signing
        data class Failed(val why: String) : Signing
        /** The token is no longer good. Same meaning as the adapter's version: link again. */
        data object NeedsReconnect : Signing
    }

    /**
     * Every installed wallet, as a person would pick from them.
     *
     * Sorted so the same list comes back in the same order each time — a picker that reshuffles
     * is a picker people mis-tap.
     */
    fun installed(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("solana-wallet://v1/associate/local"))
        return runCatching {
            pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
                .map { pkg ->
                    val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                    App(
                        pkg = pkg,
                        label = info?.let { pm.getApplicationLabel(it).toString() } ?: pkg,
                        icon = info?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() },
                    )
                }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    /** Links [pkg]'s wallet. [launch] starts the intent — the caller owns the activity. */
    suspend fun authorize(pkg: String, launch: (Intent) -> Unit): Linking =
        session(pkg, launch) { client ->
            val r = client.authorize(Uri.parse(Wallet.IDENTITY_URI), Uri.parse("favicon.ico"), "Duffle", CLUSTER)
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val account = r.accounts.firstOrNull()
                ?: return@session Linking.Failed("that wallet returned no account")
            Linking.Ok(
                address = Base58.encode(account.publicKey),
                token = r.authToken,
                accountLabel = account.accountLabel,
                uri = r.walletUriBase?.toString(),
            )
        }

    /**
     * Signs with [pkg]'s wallet, reusing [token].
     *
     * Reauthorize then sign, in one association: the wallet is opened once, not twice. We submit
     * the signed transaction ourselves, as everywhere else — the wallet's own cluster setting
     * must not decide where a devnet payment goes.
     */
    suspend fun sign(pkg: String, token: String, tx: ByteArray, launch: (Intent) -> Unit): Signing =
        session(pkg, launch) { client ->
            val auth = runCatching {
                client.reauthorize(Uri.parse(Wallet.IDENTITY_URI), Uri.parse("favicon.ico"), "Duffle", token)
                    .get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }.getOrNull() ?: return@session Signing.NeedsReconnect
            if (auth.authToken == null) return@session Signing.NeedsReconnect
            val signed = client.signTransactions(arrayOf(tx)).get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val out = signed.signedPayloads.firstOrNull()
                ?: return@session Signing.Failed("the wallet returned no signature")
            Signing.Signed(out)
        }

    /**
     * One association: open the wallet, run [work], close.
     *
     * Blocking futures, so this belongs on IO. `close()` is in a finally because a session left
     * open holds a socket the next attempt cannot have — the failure then looks like the second
     * wallet being broken rather than the first never having hung up.
     */
    private suspend fun <T> session(pkg: String, launch: (Intent) -> Unit, work: (MobileWalletAdapterClient) -> T): T =
        withContext(Dispatchers.IO) {
            val scenario = LocalAssociationScenario(TIMEOUT_MS.toInt())
            val intent = LocalAssociationIntentCreator.createAssociationIntent(null, scenario.port, scenario.session)
                .setPackage(pkg)   // the whole point: explicit, so preferred activities are skipped
            Log.i(TAG, "associating with $pkg on port ${scenario.port}")
            withContext(Dispatchers.Main) { launch(intent) }
            try {
                val client = scenario.start().get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                work(client)
            } finally {
                runCatching { scenario.close().get(3, TimeUnit.SECONDS) }
            }
        }
}
