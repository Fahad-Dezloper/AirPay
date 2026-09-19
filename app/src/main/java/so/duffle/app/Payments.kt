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
 */
sealed interface PayResult {
    data class Sent(val signature: String) : PayResult
    /** Paid before — by this phone after a relaunch, or by someone else. Not an error, not new. */
    data class AlreadyPaid(val signature: String) : PayResult
    data class Error(val title: String, val detail: String) : PayResult
    data class Failed(val title: String, val detail: String, val signature: String? = null) : PayResult
}

object Payments {

    suspend fun pay(
        wallet: Wallet,
        payer: String,
        req: PayRequest,
        stage: (String) -> Unit,
        log: (String) -> Unit = {},
    ): PayResult {
        if (req.recipient == payer)
            return PayResult.Error("That's your own wallet", "Pick a different phone to pay.")
        req.validate()?.let { return PayResult.Error("Can't pay this", it) }
        // In the token's own smallest unit: lamports for SOL, 10^-6 for USDC, and so on.
        val units = req.units()
            ?: return PayResult.Error("Enter an amount", "${req.symbol} amounts can have up to ${req.decimals} decimal places.")

        // Requests never expire, so the same one can be paid twice — after a relaunch, or by a
        // second phone that tapped the same person. The chain is the only place both show.
        stage("Checking it hasn't been paid…")
        when (val already = withContext(Dispatchers.IO) { Chain.findPayment(req.reference, req.recipient, units, req.mint) }) {
            is Chain.Found.Yes -> return PayResult.AlreadyPaid(already.signature)
            Chain.Found.Offline -> return PayResult.Error("You're offline", "Nothing was sent. Try again when you're connected.")
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
            is Chain.Built.Offline -> return PayResult.Error("You're offline", "Nothing was sent. Try again when you're connected.")
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
            is Chain.Submitted.Rejected -> return PayResult.Failed(
                "Transaction failed",
                if (sent.why.contains("blockhash", true)) "It took too long in your wallet and expired. Nothing was sent — try again."
                else "The network refused it: ${sent.why}",
            )
            Chain.Submitted.Offline -> return PayResult.Error("You're offline", "It was signed but never sent. Try again.")
        }
        log("✓ SENT — $signature")

        stage("Confirming on Solana…")
        return when (val c = Chain.confirm(signature, req.reference, req.recipient, units, req.mint)) {
            Chain.Confirmation.Confirmed -> PayResult.Sent(signature)
            is Chain.Confirmation.Failed -> PayResult.Failed("Transaction failed", "It reached Solana but ${c.why}.", signature)
            // Submitted, and we could not get an answer — usually the public RPC refusing us,
            // not a failed payment. Say that, rather than implying it went wrong.
            Chain.Confirmation.Unknown -> PayResult.Failed(
                "Sent, still confirming",
                if (Chain.rateLimited) "Solana's public node is rate-limiting us. The payment is very likely fine — check the explorer."
                else "It was sent but we couldn't confirm it yet. Check the explorer before paying again.",
                signature,
            )
        }
    }
}
