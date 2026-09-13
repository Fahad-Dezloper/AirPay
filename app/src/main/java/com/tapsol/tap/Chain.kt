package com.tapsol.tap

import org.sol4k.Connection
import org.sol4k.PublicKey
import org.sol4k.RpcUrl
import org.sol4k.Transaction
import org.sol4k.instruction.BaseInstruction
import org.sol4k.instruction.TransferInstruction
import org.sol4k.AccountMeta
import java.net.HttpURLConnection
import java.net.URL

/**
 * Everything that talks to Solana.
 *
 * Devnet for now — the flow has to be proven with worthless money before it moves real money.
 */
object Chain {

    private const val RPC = "https://api.devnet.solana.com"
    private val conn = Connection(RpcUrl.DEVNET)

    const val LAMPORTS_PER_SOL = 1_000_000_000.0

    /**
     * Builds the unsigned transfer the wallet will sign.
     *
     * The Solana Pay `reference` is attached as an extra account on the transfer instruction —
     * **read-only and non-signer**, so it changes nothing about the transfer itself. Its only
     * purpose is that validators index transactions by account key, which lets the payee find
     * this payment later without knowing its signature.
     */
    fun buildTransfer(
        payer: String,
        recipient: String,
        lamports: Long,
        reference: String,
    ): ByteArray {
        val from = PublicKey(payer)
        val to = PublicKey(recipient)
        val blockhash = conn.getLatestBlockhash()

        val transfer = TransferInstruction(from, to, lamports)

        // Re-emit the same instruction with the reference appended. sol4k's TransferInstruction
        // does not expose its account list for mutation, so the instruction is rebuilt by hand.
        val withRef = BaseInstruction(
            data = transfer.data,
            keys = transfer.keys + AccountMeta(
                publicKey = PublicKey(reference),
                signer = false,
                writable = false,
            ),
            programId = transfer.programId,
        )

        val tx = Transaction(blockhash, withRef, feePayer = from)
        // Unsigned: MWA hands this to the wallet, which signs and submits it.
        return tx.serialize()
    }

    /** SOL balance, in SOL. */
    fun balance(address: String): Double =
        conn.getBalance(PublicKey(address)).toDouble() / LAMPORTS_PER_SOL

    /**
     * The payee's half of the flow. Validators index by account key, so asking for signatures
     * that touched `reference` finds the payment — no NFC round trip, and it works even if the
     * payer walked away and paid five minutes later.
     */
    fun findPayment(reference: String): String? {
        val body = """
            {"jsonrpc":"2.0","id":1,"method":"getSignaturesForAddress",
             "params":["$reference",{"limit":10}]}
        """.trimIndent()
        val res = post(body) ?: return null
        // Cheap extraction — the full shape is not needed, only whether one exists.
        val m = Regex("\"signature\"\\s*:\\s*\"([1-9A-HJ-NP-Za-km-z]+)\"").find(res)
        return m?.groupValues?.get(1)
    }

    /** Has this transaction been confirmed yet? */
    fun isConfirmed(signature: String): Boolean {
        val body = """
            {"jsonrpc":"2.0","id":1,"method":"getSignatureStatuses",
             "params":[["$signature"],{"searchTransactionHistory":true}]}
        """.trimIndent()
        val res = post(body) ?: return false
        return res.contains("\"confirmationStatus\":\"confirmed\"") ||
            res.contains("\"confirmationStatus\":\"finalized\"")
    }

    fun explorerUrl(signature: String) =
        "https://explorer.solana.com/tx/$signature?cluster=devnet"

    private fun post(json: String): String? = try {
        val c = (URL(RPC).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(json.toByteArray()) }
        if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) {
        null
    }
}
