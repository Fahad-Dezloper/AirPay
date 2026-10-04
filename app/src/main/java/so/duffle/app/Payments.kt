package so.duffle.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One way to pay a request, used by the main screen and the tap popup alike.
 *
 * The three outcomes are kept apart because they mean different things to a person:
 *  * [PayResult.Sent] — confirmed on-chain. The money moved.
 *  * [PayResult.AlreadyPaid] — found paid on-chain before we sent anything.
 *  * [PayResult.Error] — nothing was sent: declined, offline, not enough SOL, already paid.
 *  * [PayResult.Failed] — a transaction was sent and the chain refused it, or it expired.
 *  * [PayResult.SignedOffline] — signed with no connection and not sent. Nothing has moved; the
 *    bytes still have to reach a phone that can send them.
 */
sealed interface PayResult {
    data class Sent(val signature: String) : PayResult
    /** Paid before — by this phone after a relaunch, or by someone else. Not an error, not new. */
    data class AlreadyPaid(val signature: String) : PayResult
    data class Error(val title: String, val detail: String) : PayResult
    data class Failed(val title: String, val detail: String, val signature: String? = null) : PayResult
    /** The signed transaction, for the caller to hand to the phone that asked. */
    class SignedOffline(val tx: ByteArray) : PayResult
}

object Payments {

    suspend fun pay(
        wallet: Wallet,
        payer: String,
        req: PayRequest,
        stage: (String) -> Unit,
        log: (String) -> Unit = {},
        /**
         * True when the caller can carry a signed payment back to the asking phone over NFC, and
         * the request was read moments ago. Then being offline is not the end: the payment is
         * built on the blockhash that came with the request and returned as
         * [PayResult.SignedOffline]. Anyone else gets "You're offline", as before.
         */
        handOver: Boolean = false,
    ): PayResult {
        if (req.recipient == payer)
            return PayResult.Error("That's your own wallet", "Pick a different phone to pay.")
        req.validate()?.let { return PayResult.Error("Can't pay this", it) }
        // In the token's own smallest unit: lamports for SOL, 10^-6 for USDC, and so on.
        val units = req.units()
            ?: return PayResult.Error("Enter an amount", "${req.symbol} amounts can have up to ${req.decimals} decimal places.")

        val offlineHash = req.blockhash.takeIf { handOver }

        // Requests never expire, so the same one can be paid twice — after a relaunch, or by a
        // second phone that tapped the same person. The chain is the only place both show.
        stage("Checking it hasn't been paid…")
        when (val already = withContext(Dispatchers.IO) { Chain.findPayment(req.reference, req.recipient, units, req.mint) }) {
            is Chain.Found.Yes -> return PayResult.AlreadyPaid(already.signature)
            Chain.Found.Offline -> return if (offlineHash != null) signOffline(wallet, payer, req, units, offlineHash, stage, log)
                else PayResult.Error("You're offline", "Nothing was sent. Try again when you're connected.")
            else -> Unit
        }

        stage("Checking your balance…")
        log("pay: building on a fresh blockhash")
        val tx = when (val built = withContext(Dispatchers.IO) {
            val mint = req.mint
            if (mint == null) Chain.buildTransfer(payer, req.recipient, units, req.reference)
            else Chain.buildTokenTransfer(payer, req.recipient, mint, units, req.decimals, req.reference, req.symbol)
        }) {
            is Chain.Built.Ok -> built.tx
            is Chain.Built.NotEnough -> return PayResult.Error(
                "Not enough ${built.symbol.substringBefore(' ')}",
                "You have ${built.have} ${built.symbol} and this needs ${built.need}" +
                    if (built.symbol == "SOL") " plus fees." else ".",
            )
            is Chain.Built.Offline -> return if (offlineHash != null) signOffline(wallet, payer, req, units, offlineHash, stage, log)
                else PayResult.Error("You're offline", "Nothing was sent. Try again when you're connected.")
            is Chain.Built.Bad -> return PayResult.Error("Can't pay this", built.why)
        }

        stage("Approve in your wallet…")
        val openedAt = System.currentTimeMillis()
        val signed = wallet.signOnly(tx)
        log("pay: wallet returned after ${System.currentTimeMillis() - openedAt}ms")
        val bytes = when (signed) {
            is Wallet.SendOutcome.Signed -> signed.tx
            is Wallet.SendOutcome.Failed -> return PayResult.Error("Payment cancelled", signed.why)
            Wallet.SendOutcome.NeedsReconnect -> return PayResult.Error("Reconnect your wallet", "Your wallet session ended. Connect again and retry.")
        }

        // We submit, not the wallet — so the cluster is ours to choose.
        stage("Sending…")
        val signature = when (val sent = withContext(Dispatchers.IO) { Chain.submit(bytes) }) {
            is Chain.Submitted.Ok -> sent.signature
            // An expired blockhash is NOT a failure: the transaction never reached the chain,
            // so nothing was sent and nothing can land later. It was returning `Failed` — the
            // red "the chain refused it" shape — while its own text said "Nothing was sent",
            // which is the one confusion that makes somebody pay twice.
            is Chain.Submitted.Rejected ->
                return if (sent.why.contains("blockhash", true)) {
                    PayResult.Error(
                        "Nothing was sent",
                        "It took too long in your wallet and expired. Tap again.",
                    )
                } else {
                    PayResult.Failed("The chain refused it", "It went out and didn't land. Nothing left your wallet.")
                }
            Chain.Submitted.Offline -> return PayResult.Error("You're offline", "It was signed but never sent. Try again.")
        }
        log("✓ SENT, $signature")

        stage("Confirming on Solana…")
        return when (val c = Chain.confirm(signature, req.reference, req.recipient, units, req.mint)) {
            Chain.Confirmation.Confirmed -> PayResult.Sent(signature)
            is Chain.Confirmation.Failed ->
                PayResult.Failed("The chain refused it", "It went out and didn't land. Nothing left your wallet.", signature)
            // Submitted, and we could not get an answer — usually the public RPC refusing us,
            // not a failed payment. Say that, rather than implying it went wrong.
            Chain.Confirmation.Unknown -> PayResult.Failed(
                "Sent, still confirming",
                if (Chain.rateLimited) "Sent, we just can't get an answer right now. It is very likely fine; check the explorer."
                else "Sent, we couldn't confirm it yet. Check the explorer before paying again.",
                signature,
            )
        }
    }

    /**
     * The payer's half with no connection: build on the asking phone's blockhash, sign, and stop.
     *
     * Nothing is read from Solana, so nothing is checked either: not whether it was already paid,
     * not the balance. The asking phone's node does that when it sends it, and refuses a payment
     * that cannot be made. That is also why only SOL and USDC are paid this way: what a token
     * transfer needs (its program, its decimals) is known for USDC and would have to be read
     * for any other.
     */
    private suspend fun signOffline(
        wallet: Wallet, payer: String, req: PayRequest, units: Long, blockhash: String,
        stage: (String) -> Unit, log: (String) -> Unit,
    ): PayResult {
        val mint = req.mint
        if (mint != null && mint != Shop.USDC_MAINNET)
            return PayResult.Error("You're offline", "Paying this token needs a connection.")
        if (mint != null && req.decimals != USDC_DECIMALS)
            return PayResult.Error("Can't pay this", "That request's ${req.symbol} details don't match the token on Solana.")

        log("pay: offline, building on their blockhash ${blockhash.take(8)}…")
        val tx = when (val built = withContext(Dispatchers.Default) {
            if (mint == null) Chain.buildTransfer(payer, req.recipient, units, req.reference, blockhashOverride = blockhash)
            else Chain.buildTokenTransfer(
                payer, req.recipient, mint, units, USDC_DECIMALS, req.reference, req.symbol,
                blockhashOverride = blockhash, programOverride = Chain.TOKEN_PROGRAM, recipientAccountExists = true,
            )
        }) {
            is Chain.Built.Ok -> built.tx
            is Chain.Built.Bad -> return PayResult.Error("Can't pay this", built.why)
            else -> return PayResult.Error("You're offline", "Nothing was sent. Try again when you're connected.")
        }

        stage("Approve in your wallet…")
        return when (val signed = wallet.signOnly(tx)) {
            is Wallet.SendOutcome.Signed ->
                // A wallet that hands back the empty slot has not signed, whatever it says.
                if (OfflineTx.accountKeys(signed.tx) == null) PayResult.Error("Payment cancelled", "Your wallet did not hand back a signed payment. Nothing was sent.")
                else PayResult.SignedOffline(signed.tx)
            is Wallet.SendOutcome.Failed -> PayResult.Error("Payment cancelled", signed.why)
            Wallet.SendOutcome.NeedsReconnect -> PayResult.Error("Reconnect your wallet", "Your wallet session ended. Connect again and retry.")
        }
    }

    private const val USDC_DECIMALS = 6
}
