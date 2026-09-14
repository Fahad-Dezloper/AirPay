package com.tapsol.tap

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
 * element, gated by a fingerprint. Being required by the hackathon rules is incidental; it is
 * the right architecture regardless, because a payments app that custodies keys is a liability.
 */
class Wallet(activity: ComponentActivity) {

    private val sender = ActivityResultSender(activity)

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
    }

    /** Base58 address of the connected account, or null when not connected. */
    var address: String? = null
        private set

    sealed interface Outcome {
        data class Ok(val address: String) : Outcome
        data class Failed(val why: String) : Outcome
        data object Declined : Outcome
    }

    suspend fun connect(): Outcome = try { connectInner() } catch (e: Exception) {
        Outcome.Failed(e.message ?: "the wallet could not be reached")
    }

    private suspend fun connectInner(): Outcome = when (val r = adapter.connect(sender)) {
        is TransactionResult.Success -> {
            val acct = r.authResult.accounts.firstOrNull()
            if (acct == null) Outcome.Failed("wallet returned no account")
            else {
                val a = Base58.encode(acct.publicKey)
                address = a
                Outcome.Ok(a)
            }
        }
        is TransactionResult.NoWalletFound ->
            Outcome.Failed("No MWA wallet installed. Install Seeker Wallet, Phantom or Solflare.")
        is TransactionResult.Failure -> Outcome.Declined
    }

    suspend fun disconnect() {
        runCatching { adapter.disconnect(sender) }
        address = null
    }

    /**
     * Hands an unsigned transaction to the wallet to sign and broadcast.
     *
     * `signAndSendTransactions` is used rather than sign-then-send-ourselves because the wallet
     * is better placed to handle the blockhash race: if it expires between signing and
     * submission, the wallet can re-sign, and we cannot.
     */
    suspend fun signAndSend(unsignedTx: ByteArray): SendOutcome = try {
        signAndSendInner(unsignedTx)
    } catch (e: Exception) {
        SendOutcome.Failed(e.message ?: "the wallet could not be reached")
    }

    private suspend fun signAndSendInner(unsignedTx: ByteArray): SendOutcome =
        when (val r = adapter.transact(sender) { auth ->
            signAndSendTransactions(arrayOf(unsignedTx))
        }) {
            is TransactionResult.Success -> {
                val sig: ByteArray? = r.payload.signatures.firstOrNull()
                if (sig == null) SendOutcome.Failed("wallet returned no signature")
                else SendOutcome.Sent(Base58.encode(sig))
            }
            is TransactionResult.NoWalletFound -> SendOutcome.Failed("no wallet found")
            is TransactionResult.Failure -> SendOutcome.Failed(r.e.message ?: "declined")
        }

    sealed interface SendOutcome {
        data class Sent(val signature: String) : SendOutcome
        data class Failed(val why: String) : SendOutcome
    }

    companion object {
        /**
         * Identity shown in the wallet's approval sheet. Must be a real https URI — wallets
         * display it so the user knows who is asking, and a junk value looks like a scam.
         */
        const val IDENTITY_URI = "https://tapsol.app"
    }
}
