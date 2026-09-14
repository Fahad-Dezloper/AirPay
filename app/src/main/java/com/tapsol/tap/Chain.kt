package com.tapsol.tap

import org.sol4k.AccountMeta
import org.sol4k.Connection
import org.sol4k.PublicKey
import org.sol4k.RpcUrl
import org.sol4k.Transaction
import org.sol4k.instruction.BaseInstruction
import org.sol4k.instruction.TransferInstruction
import java.net.HttpURLConnection
import java.net.URL

/**
 * Everything that talks to Solana.
 *
 * Nothing here throws. A payments app that crashes mid-tap is worse than one that says "no
 * network", so every call returns a typed result and the UI decides what to say. sol4k's
 * `Connection` and `PublicKey` both throw freely, so they are fenced off inside this file.
 *
 * Devnet for now — the flow is proven with worthless money before it moves real money.
 */
object Chain {

    private const val RPC = "https://api.devnet.solana.com"
    private val conn by lazy { Connection(RpcUrl.DEVNET) }

    const val LAMPORTS_PER_SOL = 1_000_000_000.0

    /** Solana's fee is ~5000 lamports, but leave headroom rather than land on the boundary. */
    private const val FEE_HEADROOM_LAMPORTS = 50_000L

    sealed interface Built {
        data class Ok(val tx: ByteArray) : Built
        data class NotEnough(val have: Double, val need: Double) : Built
        data class Offline(val detail: String) : Built
        data class Bad(val why: String) : Built
    }

    /**
     * Builds the unsigned transfer the wallet will sign.
     *
     * The Solana Pay `reference` rides along as an extra account — **read-only and non-signer**,
     * so it changes nothing about the transfer. Validators index by account key, which is how
     * the payee finds this payment later without knowing its signature.
     */
    fun buildTransfer(
        payer: String,
        recipient: String,
        lamports: Long,
        reference: String,
        /** Test seam — also skips the balance check, so tests need no network. */
        blockhashOverride: String? = null,
        /**
         * The blockhash carried across the tap. Using the payee's hash rather than fetching our
         * own is what makes both phones expire on the same instant instead of two guesses.
         */
        sharedBlockhash: String? = null,
    ): Built {
        if (!PayRequest.isBase58Address(payer)) return Built.Bad("Your wallet address looks wrong.")
        if (!PayRequest.isBase58Address(recipient)) return Built.Bad("That destination address is not valid.")
        if (!PayRequest.isBase58Address(reference)) return Built.Bad("That payment reference is not valid.")
        if (lamports <= 0) return Built.Bad("Enter an amount greater than zero.")

        val from = try { PublicKey(payer) } catch (e: Exception) { return Built.Bad("Your wallet address looks wrong.") }
        val to = try { PublicKey(recipient) } catch (e: Exception) { return Built.Bad("That destination address is not valid.") }
        val ref = try { PublicKey(reference) } catch (e: Exception) { return Built.Bad("That payment reference is not valid.") }

        // Check funds before involving the wallet. A rejection inside the wallet is opaque;
        // "you have 0.004 and need 0.01" is not.
        if (blockhashOverride == null) {
            when (val b = balance(payer)) {
                is Balance.Ok -> {
                    val need = (lamports + FEE_HEADROOM_LAMPORTS) / LAMPORTS_PER_SOL
                    if (b.sol < need) return Built.NotEnough(b.sol, lamports / LAMPORTS_PER_SOL)
                }
                is Balance.Offline -> return Built.Offline(b.detail)
            }
        }

        val blockhash = blockhashOverride ?: sharedBlockhash ?: run {
            try { conn.getLatestBlockhash() }
            catch (e: Exception) { return Built.Offline(e.message ?: "could not reach Solana") }
        }

        return try {
            val transfer = TransferInstruction(from, to, lamports)
            // sol4k's TransferInstruction does not expose its account list for mutation, so the
            // instruction is rebuilt with the reference appended.
            val withRef = BaseInstruction(
                data = transfer.data,
                keys = transfer.keys + AccountMeta(publicKey = ref, signer = false, writable = false),
                programId = transfer.programId,
            )
            val tx = Transaction(blockhash, withRef, feePayer = from)
            Built.Ok(reserveSignatureSlot(tx.serialize()))
        } catch (e: Exception) {
            Built.Bad(e.message ?: "could not build the transaction")
        }
    }

    /**
     * sol4k serializes an unsigned transaction with a signature count of ZERO, and Seeker
     * Wallet rightly refuses it: Solana's wire format is
     *     compact-u16 signature count | 64 bytes per signature | message
     * so zero slots leaves the wallet nowhere to write its signature. The rejection reads
     * "not properly formed and can't be signed", which is exactly correct.
     *
     * A transaction needing one signer must arrive with one zero-filled slot reserved.
     * Verified by TxShapeTest, which asserts the leading byte.
     */
    private fun reserveSignatureSlot(serialized: ByteArray): ByteArray {
        if (serialized.isEmpty()) return serialized
        // Already has slots (signed or pre-reserved) — leave it alone.
        if (serialized[0].toInt() != 0) return serialized
        return byteArrayOf(1) + ByteArray(64) + serialized.copyOfRange(1, serialized.size)
    }

    sealed interface Balance {
        data class Ok(val sol: Double) : Balance
        data class Offline(val detail: String) : Balance
    }

    fun balance(address: String): Balance = try {
        Balance.Ok(conn.getBalance(PublicKey(address)).toDouble() / LAMPORTS_PER_SOL)
    } catch (e: Exception) {
        Balance.Offline(e.message ?: "could not reach Solana")
    }

    /**
     * The payee's half. Validators index by account key, so asking for signatures that touched
     * `reference` finds the payment — nothing comes back over NFC, and it still works if the
     * payer wandered off and paid five minutes later.
     *
     * Returns null both when unpaid and when unreachable; the caller distinguishes by whether
     * `reachable()` holds, because "not yet" and "cannot tell" must not look the same to a user.
     */
    sealed interface Found {
        data class Yes(val signature: String) : Found
        data object NotYet : Found
        data object Offline : Found
        /** The node answered and the answer was an error. Never silently a "not yet". */
        data class Broken(val why: String) : Found
    }

    /**
     * The exact body we ask the node for. Pulled out so a test can assert its shape.
     *
     * **`confirmed` is the floor.** Asking for `processed` to shave a second off the payee's
     * wait returns `-32602 Method does not support commitment below confirmed` — as an HTTP
     * 200 carrying an error object. A regex hunting for a signature finds none, and the payee
     * waits forever on a payment that already landed. Verified against devnet, not assumed.
     */
    fun signaturesBody(reference: String) =
        """{"jsonrpc":"2.0","id":1,"method":"getSignaturesForAddress",
            "params":["$reference",{"limit":10,"commitment":"confirmed"}]}"""

    fun findPayment(reference: String): Found {
        if (!PayRequest.isBase58Address(reference)) return Found.Broken("that reference is not valid")
        val res = post(signaturesBody(reference)) ?: return Found.Offline
        // Check for an error BEFORE hunting for a signature. A JSON-RPC error arrives with a
        // 200, so reading "no signature in the body" as "not paid yet" hides a broken query
        // behind a spinner — which is precisely how this went wrong.
        rpcError(res)?.let { return Found.Broken(it) }
        val sig = Regex("\"signature\"\\s*:\\s*\"([1-9A-HJ-NP-Za-km-z]{60,})\"")
            .find(res)?.groupValues?.get(1)
        return if (sig != null) Found.Yes(sig) else Found.NotYet
    }

    /** The `message` from a JSON-RPC error object, or null when the call actually worked. */
    fun rpcError(res: String): String? =
        Regex("\"error\"\\s*:\\s*\\{[^}]*\"message\"\\s*:\\s*\"([^\"]+)\"")
            .find(res)?.groupValues?.get(1)

    sealed interface Submitted {
        data class Ok(val signature: String) : Submitted
        data class Rejected(val why: String) : Submitted
        data object Offline : Submitted
    }

    /**
     * Submits a signed transaction to OUR cluster.
     *
     * The wallet signs; we send. `signAndSendTransactions` would have the wallet submit, which
     * means the wallet picks the cluster — and a wallet sitting on mainnet silently fails a
     * devnet transaction, returning nothing useful. Submitting here makes the wallet's own
     * network setting irrelevant, which is the difference between "works" and "works if the
     * user configured Phantom correctly".
     *
     * Cost of the change: the wallet can no longer re-sign an expired blockhash on our behalf.
     * Worth it — an expired blockhash is recoverable by tapping again; a silent failure is not.
     */
    fun submit(signedTx: ByteArray): Submitted {
        val b64 = android.util.Base64.encodeToString(signedTx, android.util.Base64.NO_WRAP)
        val res = post(
            """{"jsonrpc":"2.0","id":1,"method":"sendTransaction",
                "params":["$b64",{"encoding":"base64","preflightCommitment":"confirmed"}]}"""
        ) ?: return Submitted.Offline

        Regex("\"result\"\\s*:\\s*\"([1-9A-HJ-NP-Za-km-z]{60,})\"").find(res)
            ?.let { return Submitted.Ok(it.groupValues[1]) }

        val why = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(res)?.groupValues?.get(1)
        return Submitted.Rejected(why ?: "the network refused the transaction")
    }

    /**
     * The blockhash a payer building right now would use.
     *
     * Recorded when a request is served, so the payee has a lower bound on whatever blockhash
     * the payer's transaction will carry — theirs is fetched moments later, a handful of slots
     * newer than ours.
     */
    fun latestBlockhash(): String? {
        val res = post(
            """{"jsonrpc":"2.0","id":1,"method":"getLatestBlockhash",
                "params":[{"commitment":"confirmed"}]}"""
        ) ?: return null
        if (rpcError(res) != null) return null
        return Regex("\"blockhash\"\\s*:\\s*\"([1-9A-HJ-NP-Za-km-z]{32,44})\"")
            .find(res)?.groupValues?.get(1)
    }

    /**
     * Can a transaction built against this blockhash still be submitted at all?
     *
     * This is the only honest answer to "did the payer give up". Nothing about someone
     * declining or walking away reaches the chain, so the payee can never observe it — but a
     * blockhash dies after ~150 slots, and once it has, the payer's transaction is unable to
     * land no matter what they do. That turns an arbitrary timer into a fact.
     *
     * Null means we could not ask, which must never be read as "dead".
     */
    fun blockhashAlive(blockhash: String): Boolean? {
        if (!PayRequest.isBase58Address(blockhash)) return null
        val res = post(
            """{"jsonrpc":"2.0","id":1,"method":"isBlockhashValid",
                "params":["$blockhash",{"commitment":"confirmed"}]}"""
        ) ?: return null
        if (rpcError(res) != null) return null
        return Regex("\"value\"\\s*:\\s*(true|false)")
            .find(res)?.groupValues?.get(1)?.equals("true")
    }

    /** Cheap liveness probe, so "waiting" can be told apart from "offline". */
    fun reachable(): Boolean =
        post("""{"jsonrpc":"2.0","id":1,"method":"getHealth"}""") != null

    fun explorerUrl(signature: String) =
        "https://explorer.solana.com/tx/$signature?cluster=devnet"

    private fun post(json: String): String? = try {
        val c = (URL(RPC).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(json.toByteArray()) }
        if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) {
        null
    }
}
