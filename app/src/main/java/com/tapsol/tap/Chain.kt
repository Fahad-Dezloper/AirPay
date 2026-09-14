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
        /** Test seam — production always fetches a live blockhash. */
        blockhashOverride: String? = null,
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

        val blockhash = blockhashOverride ?: run {
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
    fun findPayment(reference: String): String? {
        if (!PayRequest.isBase58Address(reference)) return null
        val res = post(
            """{"jsonrpc":"2.0","id":1,"method":"getSignaturesForAddress",
                "params":["$reference",{"limit":10}]}"""
        ) ?: return null
        return Regex("\"signature\"\\s*:\\s*\"([1-9A-HJ-NP-Za-km-z]{60,})\"")
            .find(res)?.groupValues?.get(1)
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
