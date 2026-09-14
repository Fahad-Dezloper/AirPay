package com.tapsol.tap

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

    // ConnectionIdentity takes (identityUri, iconUri, identityName) positionally in 2.1.1.
    private val adapter = MobileWalletAdapter(
        ConnectionIdentity(
            Uri.parse(IDENTITY_URI),
            Uri.parse("favicon.ico"),
            "Tap",
        )
    ).apply {
        // Devnet until the flow is proven end to end. Mainnet is a one-line change.
        blockchain = Solana.Devnet
        // Restore a previous session so a relaunch does not force the user to reconnect.
        prefs.getString(KEY_TOKEN, null)?.let { authToken = it }
    }

    /** Base58 address of the connected account, or null when not connected. */
    var address: String? = prefs.getString(KEY_ADDRESS, null)
        private set

    sealed interface Outcome {
        data class Ok(val address: String) : Outcome
        data class Failed(val why: String) : Outcome
        data object Declined : Outcome
    }

    suspend fun connect(): Outcome = try {
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
                remember(a, r.authResult.authToken)
                Outcome.Ok(a)
            }
        }
        is TransactionResult.NoWalletFound ->
            Outcome.Failed("No wallet app found. Install Seeker Wallet, Phantom or Solflare.")
        is TransactionResult.Failure -> Outcome.Declined
    }

    suspend fun disconnect() {
        runCatching { adapter.disconnect(sender) }
        forget()
    }

    /**
     * Hands an unsigned transaction to the wallet to sign and broadcast.
     *
     * `signAndSendTransactions` rather than sign-then-send-ourselves, because the wallet can
     * re-sign if the blockhash expires between signing and submission, and we cannot.
     *
     * Retries once on an authorization failure. A stale token is the most common reason a
     * payment dies at the last step, and asking the user to reconnect by hand — when we know
     * exactly what is wrong — is a poor way to end a tap.
     */
    suspend fun signAndSend(unsignedTx: ByteArray): SendOutcome {
        val first = attemptSend(unsignedTx)
        if (first !is SendOutcome.NeedsReconnect) return first

        forget()
        return when (val again = attemptSend(unsignedTx)) {
            is SendOutcome.NeedsReconnect -> SendOutcome.Failed(
                "Your wallet session expired. Connect again, then retry the tap."
            )
            else -> again
        }
    }

    private suspend fun attemptSend(unsignedTx: ByteArray): SendOutcome = try {
        when (val r = adapter.transact(sender) { signAndSendTransactions(arrayOf(unsignedTx)) }) {
            is TransactionResult.Success -> {
                // A successful transact may hand back a refreshed token; keep it.
                r.authResult.authToken?.let { prefs.edit().putString(KEY_TOKEN, it).apply() }
                val sig: ByteArray? = r.payload.signatures.firstOrNull()
                if (sig == null) SendOutcome.Failed("wallet returned no signature")
                else SendOutcome.Sent(Base58.encode(sig))
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

    private fun forget() {
        address = null
        adapter.authToken = null
        prefs.edit().remove(KEY_TOKEN).remove(KEY_ADDRESS).apply()
    }

    sealed interface SendOutcome {
        data class Sent(val signature: String) : SendOutcome
        data class Failed(val why: String) : SendOutcome
        /** Internal: the session went stale and the send is worth one retry. */
        data object NeedsReconnect : SendOutcome
    }

    companion object {
        /**
         * Identity shown in the wallet's approval sheet. Must be a real https URI — wallets
         * display it so the user knows who is asking, and a junk value looks like a scam.
         */
        const val IDENTITY_URI = "https://tapsol.app"

        private const val KEY_TOKEN = "auth_token"
        private const val KEY_ADDRESS = "address"
    }
}
