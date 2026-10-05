package so.duffle.app

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.RpcCluster
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
        // Mainnet. The wallet has to be told, because it decides the cluster it signs for —
        // and a wallet sitting on the other one fails a payment in a way that reads like a
        // network fault rather than a mismatch.
        blockchain = Solana.Mainnet
        // The same thing said the older way, for a wallet that speaks the first version of the
        // protocol and never reads `blockchain`. Left unset, that one defaults to devnet.
        @Suppress("DEPRECATION")
        rpcCluster = RpcCluster.MainnetBeta
        token?.let { authToken = it }
    }

    // Restores the selected wallet's session so a relaunch does not force a reconnect.
    private var adapter = newAdapter(linked.selected()?.token ?: prefs.getString(KEY_TOKEN, null))

    init {
        // A wallet session belongs to the cluster it was issued for, and the wallet goes on
        // treating it that way: a session from when this app ran on devnet made Seeker Wallet
        // refuse a mainnet payment with "this transaction is for devnet". Every session from
        // before the move is dropped once. The accounts stay; the next payment asks the wallet
        // again, on mainnet, and is given a session for mainnet.
        if (prefs.getString(KEY_SESSION_CLUSTER, null) != SESSION_CLUSTER) {
            linked.all().forEach { linked.clearToken(it) }
            prefs.edit().remove(KEY_TOKEN).putString(KEY_SESSION_CLUSTER, SESSION_CLUSTER).apply()
            adapter = newAdapter(null)
        }
        // A wallet connected before AirPay could hold more than one. Without this it keeps
        // working and shows as "No wallet", because the list it is meant to appear in is empty.
        if (linked.all().isEmpty()) {
            prefs.getString(KEY_ADDRESS, null)?.let { old ->
                linked.add(old, prefs.getString(KEY_TOKEN, null), "Wallet", null)
                linked.select(old)
            }
        }
    }

    /**
     * Base58 address of the wallet in use, or null when none is linked.
     *
     * Checked against the list on every read, not trusted. The Wallets screen removes a wallet
     * through [linked] directly, and the tap sheet is another activity with a Wallet of its own,
     * so the copy held here went on naming a wallet that had been removed or switched away
     * from. Removing the last wallet never reached the first screen, the removed address was
     * still the one a tap answered with, and the next launch linked it again from what was left
     * on disk.
     */
    val address: String?
        get() {
            val now = linked.selected()
            if (now?.address != current) {
                if (now != null) use(now) else drop()
            }
            return current
        }

    private var current: String? = addressOf(activity)

    /**
     * Switches which linked wallet AirPay uses.
     *
     * Only the token changes hands. Android still decides which wallet app opens when signing —
     * there is no way to say "open Phantom" — so this is "which session do we present", not
     * "which app launches". A token offered to the wrong wallet fails and is retried, which is
     * the same path a stale token already takes.
     */
    fun use(w: Linked) {
        // Read again from the list: a caller passes the copy its screen was drawn from, and a
        // session refreshed since then is only in the list. The old copy's token is a dead one.
        val now = linked.holding(w.address) ?: return
        linked.select(now)
        current = now.address
        // A fresh adapter, so the one we carry is not still pointed at the previous wallet's app.
        adapter = newAdapter(now.token)
        // The token is removed when this wallet has none. Left behind, the previous wallet's
        // session was offered for this one at the next launch.
        val e = prefs.edit().putString(KEY_ADDRESS, now.address)
        if (now.token != null) e.putString(KEY_TOKEN, now.token) else e.remove(KEY_TOKEN)
        e.apply()
    }

    /** No wallet is left: nothing is held here, on disk or in the adapter. */
    private fun drop() {
        current = null
        adapter = newAdapter(null)
        prefs.edit().remove(KEY_TOKEN).remove(KEY_ADDRESS).apply()
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
    suspend fun connect(fresh: Boolean = false): Outcome {
        val out = try {
            if (fresh) adapter = newAdapter(null)
            connectInner()
        } catch (e: Exception) {
            // The exception's own text is for the log. On screen it was a line of Java.
            android.util.Log.i("Wallet", "connect failed", e)
            Outcome.Failed("Your wallet could not be reached. Try again.")
        }
        // An add that was declined must not cost the wallet already in use its session: the
        // empty adapter made for the add was kept, so the next payment asked to connect afresh.
        if (fresh && out !is Outcome.Ok) adapter = newAdapter(linked.selected()?.token)
        return out
    }

    private suspend fun connectInner(): Outcome = when (val r = adapter.connect(sender)) {
        is TransactionResult.Success -> {
            val acct = r.authResult.accounts.firstOrNull()
            if (acct == null) Outcome.Failed(
                "Your wallet did not share an account. Open it, choose an account, then try again."
            )
            else {
                val a = Base58.encode(acct.publicKey)
                // The phone's default wallet answered with an address that is already linked
                // through another app. That card is kept as it is and simply put in use: it is
                // the same account, and the app the person linked it through stays the one asked.
                linked.holding(a)?.takeIf { it.pkg != null && it.pkg != SEEKER_WALLET_PKG }?.let { held ->
                    use(held)
                    return Outcome.Ok(a)
                }
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
                // One card per address. A second app that signs for an address already linked
                // is the same wallet again (on a Seeker, Solflare and Seeker Wallet both use the
                // phone's own account). It used to replace the first card without a word, which
                // looked like a wallet being disconnected. Now it is refused, and says why.
                val held = linked.holding(r.address)
                // An entry with no app recorded is asked which app it resolves to, as signing
                // does. Without that, a wallet first linked through this very app (before apps
                // were recorded) was refused as "already linked" by the app that holds it.
                val sameApp = held != null &&
                    (linked.sameApp(held, app.pkg) || WalletLink.resolve(activity, held) == app.pkg)
                if (held != null && !sameApp) {
                    val through = held.name.takeIf { !it.equals("Wallet", true) } ?: "another wallet app"
                    return Outcome.Failed(
                        "That account (${held.short}) is already linked through $through. " +
                            "Pick a different account in ${app.label}, or link a different wallet."
                    )
                }
                val w = linked.add(
                    address = r.address, token = r.token,
                    // The app's own name on this phone, which is what the person just tapped.
                    name = app.label, uri = r.uri, pkg = app.pkg,
                )
                linked.rename(w, app.label)
                use(w)
                Outcome.Ok(r.address)
            }
        }
    } catch (e: Exception) {
        // A decline arrives here as an exception, and its text is a Java class name: it was
        // shown as it came, in a dialog. Declining is not a fault, so it is no longer reported
        // as one; anything else gets a sentence, and the raw text goes to the log.
        android.util.Log.i("Wallet", "link ${app.pkg} failed", e)
        val m = "${e.message.orEmpty()} ${e.cause?.message.orEmpty()}"
        when {
            m.contains("declin", true) || m.contains("cancel", true) -> Outcome.Declined
            // A refused authorization is usually a decline too, but the wallet does not say so.
            m.contains("authoriz", true) ->
                Outcome.Failed("${app.label} did not approve the link. Nothing changed.")
            else -> Outcome.Failed("${app.label} did not answer. Open ${app.label} once, then try again.")
        }
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
        linked.selected()?.takeIf { it.token != null }?.let { w ->
            val pkg = WalletLink.resolve(activity, w) ?: return@let
            return try {
                when (val r = WalletLink.sign(
                    pkg, w.token!!, unsignedTx,
                    onToken = { t -> linked.add(w.address, t, w.name, w.uri, w.pkg) },
                ) { activity.startActivity(it) }) {
                    is WalletLink.Signing.Signed -> SendOutcome.Signed(r.tx)
                    is WalletLink.Signing.Failed -> SendOutcome.Failed(r.why)
                    // The token is kept. A decline and a dead session look the same here, and
                    // the next try already asks the wallet for a new session by itself. Clearing
                    // it sent that next try down the untargeted route instead, which on a Seeker
                    // opens Seeker Wallet for another wallet's account.
                    WalletLink.Signing.NeedsReconnect -> SendOutcome.Failed(
                        "Your wallet did not approve that. Nothing was sent. Try again."
                    )
                }
            } catch (e: Exception) {
                // A decline in the wallet, a wallet that never opens and one that times out all
                // arrive as an exception. Nothing caught it on this route, so declining a payment
                // in a wallet linked from the list ended the app instead of the payment.
                android.util.Log.i("Wallet", "sign through $pkg failed", e)
                val m = "${e.message.orEmpty()} ${e.cause?.message.orEmpty()}"
                SendOutcome.Failed(
                    if (m.contains("declin", true) || m.contains("cancel", true) || m.contains("not signed", true)) {
                        "You declined the payment in your wallet."
                    } else "Your wallet did not complete the payment. Nothing was sent."
                )
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
                r.authResult.authToken?.let { fresh ->
                    adapter.authToken = fresh
                    prefs.edit().putString(KEY_TOKEN, fresh).apply()
                    // And the list, which is what the next launch and the next switch read. It
                    // kept the token from when the wallet was linked, so a wallet that rolls its
                    // tokens was handed a dead one after every restart. Only when the wallet that
                    // answered holds the account in use.
                    current?.takeIf { a -> r.authResult.accounts.any { Base58.encode(it.publicKey) == a } }
                        ?.let { keep(it, fresh) }
                }
                val signed: ByteArray? = r.payload.signedPayloads.firstOrNull()
                if (signed == null) SendOutcome.Failed("The wallet returned nothing to send.")
                else SendOutcome.Signed(signed)
            }
            is TransactionResult.NoWalletFound -> SendOutcome.Failed("No wallet app found on this phone.")
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

    /** A refreshed session, written to the list entry of the wallet it belongs to. */
    private fun keep(address: String, token: String) {
        linked.holding(address)?.let { w -> linked.add(w.address, token, w.name, w.uri, w.pkg) }
    }

    private fun remember(addr: String, token: String?) {
        current = addr
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

    /**
     * Signs out of every wallet at once. Each wallet is forgotten with its session, so the app is
     * back where it was before any wallet was connected. Nothing on chain changes, and nothing
     * is asked of the wallet apps: this only forgets what this phone was holding.
     */
    fun logoutAll() {
        linked.all().forEach { linked.remove(it) }
        drop()
    }

    /** A real disconnect: the user asked to sign out. */
    private fun forget() {
        linked.selected()?.let { linked.remove(it) }
        current = linked.selected()?.address
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
        // A wallet linked through the picker is reached by name; an untargeted intent would be
        // routed to Seeker Wallet by the Seeker's own default and refuse another wallet's account.
        val picked = linked.selected()?.takeIf { it.address == address && it.token != null }
        val pkg = picked?.let { WalletLink.resolve(activity, it) }
        if (picked != null && pkg != null) {
            WalletLink.signMessage(
                pkg, picked.token!!, addressBytes, message,
                onToken = { t -> linked.add(picked.address, t, picked.name, picked.uri, picked.pkg) },
            ) { activity.startActivity(it) }
        } else when (val r = adapter.transact(sender) {
            signMessagesDetached(arrayOf(message), arrayOf(addressBytes))
        }) {
            is TransactionResult.Success -> {
                r.authResult.authToken?.let {
                    adapter.authToken = it
                    remember(address, it)
                    if (r.authResult.accounts.any { a -> a.publicKey.contentEquals(addressBytes) }) keep(address, it)
                }
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
        private const val SEEKER_WALLET_PKG = "com.solanamobile.wallet"
        private const val KEY_SESSION_CLUSTER = "session_cluster"
        private const val SESSION_CLUSTER = "mainnet-beta"
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
