package so.duffle.app

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import org.sol4k.Base58

/**
 * Wallet access via Mobile Wallet Adapter.
 *
 * This app never sees a private key. MWA hands the request to whichever wallet the user has —
 * Seeker Wallet, Phantom, Solflare — and on a Seeker that wallet signs inside the secure
 * element, gated by a fingerprint. Required by the rules, and the right architecture anyway:
 * a payments app that custodies keys is a liability.
 *
 * **Sessions are the fiddly part.** `connect()` returns an auth token that every later
 * `transact` must present. Lose it and the wallet is asked to authorize afresh mid-payment,
 * which surfaces as `-1 / authorization request failed` — connect looked fine and the signature
 * inexplicably did not. The token is therefore persisted, reused, and cleared on exactly the
 * failure that means it went stale.
 */
class Wallet(private val activity: ComponentActivity) {

    private val sender = ActivityResultSender(activity)
    private val prefs = activity.getSharedPreferences("wallet", Context.MODE_PRIVATE)

    /** Every wallet this phone has linked. The selected one is the one a tap pays from. */
    val linked = Wallets(activity)

    /**
     * A new adapter, carrying only the session it is given.
     *
     * Rebuilt rather than reconfigured because the adapter privately remembers
     * `walletUriBase` from the last authorization and re-associates straight to **that** wallet.
     * There is no setter for it, so clearing `authToken` is not enough: "Add wallet" kept
     * opening the wallet already connected, which is the opposite of what was asked. A fresh
     * instance has no wallet to go back to, so Android asks which one to use.
     */
    private fun newAdapter(token: String?) = MobileWalletAdapter(
        // ConnectionIdentity takes (identityUri, iconUri, identityName) positionally in 2.1.1.
        ConnectionIdentity(
            Uri.parse(IDENTITY_URI),
            Uri.parse("favicon.ico"),
            "AirPay",
        )
    ).apply {
        // Devnet until the flow is proven end to end. Mainnet is a one-line change.
        blockchain = Solana.Devnet
        token?.let { authToken = it }
    }

    // Restores the selected wallet's session so a relaunch does not force a reconnect.
    private var adapter = newAdapter(linked.selected()?.token ?: prefs.getString(KEY_TOKEN, null))

    init {
        // A wallet connected before AirPay could hold more than one. Without this it keeps
        // working and shows as "No wallet", because the list it is meant to appear in is empty.
        if (linked.all().isEmpty()) {
            prefs.getString(KEY_ADDRESS, null)?.let { old ->
                linked.add(old, prefs.getString(KEY_TOKEN, null), "Wallet", null)
                linked.select(old)
            }
        }
    }

    /** Base58 address of the connected account, or null when not connected. */
    var address: String? = addressOf(activity)
        private set

    /**
     * Switches which linked wallet AirPay uses.
     *
     * Only the token changes hands. Android still decides which wallet app opens when signing —
     * there is no way to say "open Phantom" — so this is "which session do we present", not
     * "which app launches". A token offered to the wrong wallet fails and is retried, which is
     * the same path a stale token already takes.
     */
    fun use(w: Linked) {
        linked.select(w.address)
        address = w.address
        // A fresh adapter, so the one we carry is not still pointed at the previous wallet's app.
        adapter = newAdapter(w.token)
        prefs.edit().putString(KEY_ADDRESS, w.address).apply()
        w.token?.let { prefs.edit().putString(KEY_TOKEN, it).apply() }
    }

    sealed interface Outcome {
        data class Ok(val address: String) : Outcome
        data class Failed(val why: String) : Outcome
        data object Declined : Outcome
    }

    /**
     * @param fresh true when adding a wallet rather than resuming one. Presenting an existing
     *   token asks that same wallet to reauthorize, which is exactly wrong when the user has
     *   asked for a different one — they would be sent back to the wallet they already have.
     */
    suspend fun connect(fresh: Boolean = false): Outcome = try {
        if (fresh) adapter = newAdapter(null)
        connectInner()
    } catch (e: Exception) {
        Outcome.Failed(e.message ?: "the wallet could not be reached")
    }

    private suspend fun connectInner(): Outcome = when (val r = adapter.connect(sender)) {
        is TransactionResult.Success -> {
            val acct = r.authResult.accounts.firstOrNull()
            if (acct == null) Outcome.Failed("wallet returned no account")
            else {
                val a = Base58.encode(acct.publicKey)
                // The wallet names itself here, which is the only honest source for the label
                // on its card: anything else would be us guessing which app the user picked.
                val w = linked.add(
                    address = a,
                    token = r.authResult.authToken,
                    name = walletName(r.authResult.walletUriBase?.host, acct.accountLabel),
                    uri = r.authResult.walletUriBase?.toString(),
                )
                use(w)
                remember(a, r.authResult.authToken)
                Outcome.Ok(a)
            }
        }
        is TransactionResult.NoWalletFound ->
            Outcome.Failed("No wallet app found. Install Seeker Wallet, Phantom or Solflare.")
        is TransactionResult.Failure -> Outcome.Declined
    }

    /**
     * Links a named wallet app, bypassing the Seeker's ROM-level default for the scheme.
     *
     * The picker knows which app the person chose, which the adapter never can — so this is the
     * only route to a wallet that is not Seeker Wallet on this hardware.
     */
    suspend fun link(app: WalletLink.App): Outcome = try {
        when (val r = WalletLink.authorize(app.pkg) { activity.startActivity(it) }) {
            is WalletLink.Linking.Failed -> Outcome.Failed(r.why)
            is WalletLink.Linking.Ok -> {
                val w = linked.add(
                    address = r.address, token = r.token,
                    // The app's own name on this phone, which is what the person just tapped.
                    name = app.label, uri = r.uri, pkg = app.pkg,
                )
                linked.rename(r.address, app.label)
                use(w)
                Outcome.Ok(r.address)
            }
        }
    } catch (e: Exception) {
        Outcome.Failed(e.message ?: "that wallet did not answer")
    }

    suspend fun disconnect() {
        runCatching { adapter.disconnect(sender) }
        forget()
    }

    /**
     * Asks the wallet to SIGN only. We submit.
     *
     * `signAndSendTransactions` has the wallet broadcast, which means the wallet picks the
     * cluster — and Phantom sitting on mainnet fails a devnet transaction silently, returning
     * an empty error. Signing here and submitting ourselves makes the wallet's own network
     * setting irrelevant, which is the difference between "works" and "works if the user
     * configured their wallet the way we assumed".
     *
     * Retries once on an authorization failure. A stale token is the most common reason a
     * payment dies at the last step, and asking the user to reconnect by hand — when we know
     * exactly what is wrong — is a poor way to end a tap.
     */
    suspend fun signOnly(unsignedTx: ByteArray): SendOutcome {
        // A wallet linked through the picker is reached by name, because an untargeted intent
        // would be routed to Seeker Wallet by the Seeker's own default — which would then be
        // handed a session token issued by a different app, and refuse it.
        linked.selected()?.takeIf { it.pkg != null && it.token != null }?.let { w ->
            return when (val r = WalletLink.sign(w.pkg!!, w.token!!, unsignedTx) { activity.startActivity(it) }) {
                is WalletLink.Signing.Signed -> SendOutcome.Signed(r.tx)
                is WalletLink.Signing.Failed -> SendOutcome.Failed(r.why)
                WalletLink.Signing.NeedsReconnect -> {
                    linked.clearToken(w.address)
                    SendOutcome.Failed("${w.name} needs linking again — open Wallets and add it.")
                }
            }
        }
        val first = attemptSend(unsignedTx)
        if (first !is SendOutcome.NeedsReconnect) return first

        // Drop the token, keep the account. MWA reports a stale session and a plain user
        // decline identically (`-1 / authorization request failed`), so a declined payment
        // used to wipe the stored address and leave the app asking to connect on next launch.
        // Losing a session is not losing an account.
        forgetToken()
        return when (val again = attemptSend(unsignedTx)) {
            is SendOutcome.NeedsReconnect -> SendOutcome.Failed(
                "Your wallet did not approve that. Try the tap again."
            )
            else -> again
        }
    }

    private suspend fun attemptSend(unsignedTx: ByteArray): SendOutcome = try {
        when (val r = adapter.transact(sender) { signTransactions(arrayOf(unsignedTx)) }) {
            is TransactionResult.Success -> {
                // A successful transact hands back a refreshed token, and wallets roll them:
                // issuing the new one invalidates the old. Writing it to prefs but not to the
                // adapter left THIS session still presenting the dead token, so the next
                // payment failed authorization — which is why every payment was asking to
                // connect first. Both copies get updated.
                r.authResult.authToken?.let {
                    adapter.authToken = it
                    prefs.edit().putString(KEY_TOKEN, it).apply()
                }
                val signed: ByteArray? = r.payload.signedPayloads.firstOrNull()
                if (signed == null) SendOutcome.Failed("The wallet returned nothing to send.")
                else SendOutcome.Signed(signed)
            }
            is TransactionResult.NoWalletFound -> SendOutcome.Failed("no wallet app found")
            is TransactionResult.Failure -> classify(r.e.message)
        }
    } catch (e: Exception) {
        classify(e.message)
    }

    /**
     * MWA reports a stale session as `-1 / authorization request failed`, which is
     * indistinguishable from a user decline unless the text is read.
     */
    private fun classify(message: String?): SendOutcome {
        val m = message.orEmpty()
        return when {
            m.contains("authorization", true) || m.contains("auth_token", true) ->
                SendOutcome.NeedsReconnect
            m.contains("declin", true) || m.contains("cancel", true) ->
                SendOutcome.Failed("You declined the payment in your wallet.")
            m.isBlank() -> SendOutcome.Failed("The wallet did not complete the payment.")
            else -> SendOutcome.Failed(m)
        }
    }

    private fun remember(addr: String, token: String?) {
        address = addr
        prefs.edit().putString(KEY_ADDRESS, addr).apply()
        token?.let {
            adapter.authToken = it
            prefs.edit().putString(KEY_TOKEN, it).apply()
        }
    }

    /** Ends the session but keeps the account — the user is still connected to this wallet. */
    private fun forgetToken() {
        adapter.authToken = null
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    /** A real disconnect: the user asked to sign out. */
    private fun forget() {
        address?.let { linked.remove(it) }
        address = linked.selected()?.address
        forgetToken()
        // Another linked wallet takes over rather than leaving the app with none.
        linked.selected()?.let { use(it) } ?: prefs.edit().remove(KEY_ADDRESS).apply()
    }

    /**
     * A name for the card. The host of `walletUriBase` is what the wallet published about
     * itself; the account label is the user's own name for the account, which beats a domain.
     */
    private fun walletName(host: String?, accountLabel: String?): String {
        accountLabel?.takeIf { it.isNotBlank() }?.let { return it.take(24) }
        val h = host?.removePrefix("www.")?.substringBefore('.') ?: return "Wallet"
        return h.replaceFirstChar { it.uppercase() }
    }

    sealed interface SendOutcome {
        /** The wallet signed it. Submitting is our job. */
        data class Signed(val tx: ByteArray) : SendOutcome
        data class Failed(val why: String) : SendOutcome
        /** Internal: the session went stale and the send is worth one retry. */
        data object NeedsReconnect : SendOutcome
    }

    /**
     * Carries your name onto another wallet, by asking the named one to vouch for it.
     *
     * Switches the adapter to `owner`, gets one signature, and switches back — **always**, even
     * when the signature fails, because leaving the app pointed at a wallet the user did not
     * choose would make the next payment come from the wrong address.
     *
     * One approval, on the owner's wallet. The person sees a readable sentence naming the
     * wallet being vouched for, not a transaction.
     */
    suspend fun link(owner: Linked, wallet: String): Vouch.Link? {
        if (owner.address == wallet) return null
        val previous = linked.selected()
        return try {
            use(owner)
            val expiry = Vouch.expiryFor()
            val signature = signMessage(owner.address, Vouch.message(owner.address, wallet, expiry))
                ?: return null
            Vouch.remember(activity, owner.address, wallet, expiry, Vouch.encode(signature))
        } catch (e: Exception) {
            null
        } finally {
            previous?.let { use(it) }
        }
    }

    /**
     * Asks a wallet to sign a short statement that is **not** a transaction.
     *
     * Used once per wallet you link, to produce the signature in [Vouch] that carries your name
     * onto a second address. `signMessagesDetached` is the right call rather than
     * `signTransactions`: it returns a bare ed25519 signature over exactly the bytes handed in,
     * which is what verification on the other phone needs, and it shows the person a readable
     * sentence instead of a transaction they would have to take on faith.
     *
     * The wallet has to be the **currently connected** one, because MWA signs with the session
     * it has. Linking therefore means connecting the named wallet, signing, and connecting back
     * — which is why the flow asks for the Seeker wallet by name.
     *
     * @return the 64-byte signature, or null if the wallet declined or could not be reached.
     */
    suspend fun signMessage(address: String, message: ByteArray): ByteArray? = try {
        val addressBytes = Base58.decode(address)
        when (val r = adapter.transact(sender) {
            signMessagesDetached(arrayOf(message), arrayOf(addressBytes))
        }) {
            is TransactionResult.Success -> {
                r.authResult.authToken?.let { adapter.authToken = it; remember(address, it) }
                // One message in, so one signed message out — and the signature belonging to
                // the address we asked for, rather than whichever the wallet listed first.
                val signed = r.payload.messages.firstOrNull()
                val index = signed?.addresses?.indexOfFirst { it.contentEquals(addressBytes) } ?: -1
                signed?.signatures?.getOrNull(if (index >= 0) index else 0)
                    ?.takeIf { it.size == 64 }
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        /**
         * Identity shown in the wallet's approval sheet. Must be a real https URI — wallets
         * display it so the user knows who is asking, and a junk value looks like a scam.
         */
        const val IDENTITY_URI = "https://duffle.so"

        private const val KEY_TOKEN = "auth_token"
        private const val KEY_ADDRESS = "address"

        /**
         * Which address this phone pays from, without opening a wallet session.
         *
         * Constructing a [Wallet] registers an MWA `ActivityResultSender`, which is only legal
         * before an activity is STARTED — doing it to answer "what is my address?" from a
         * running screen crashes the app. This is the same expression [address] uses, kept in
         * one place so the two can never disagree about who "you" are.
         */
        fun addressOf(context: Context): String? =
            Wallets(context).selected()?.address
                ?: context.getSharedPreferences("wallet", Context.MODE_PRIVATE)
                    .getString(KEY_ADDRESS, null)
    }
}
