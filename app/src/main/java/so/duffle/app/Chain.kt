package so.duffle.app

import org.sol4k.AccountMeta
import org.sol4k.PublicKey
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
 * **Mainnet. Every transfer here is real money.**
 *
 * It ran on devnet while the mechanism was being proved, and the split that went with it —
 * a `mainnet` flag threaded through every call, true only for the shop — is gone. Two clusters
 * reachable from one build is a bug waiting to be written: a payment built against one and
 * submitted to the other fails in a way that reads like a network problem, and a balance read
 * from the wrong one is simply a wrong number on screen. There is one cluster now and no flag
 * to get wrong.
 */
object Chain {

    private const val PUBLIC_RPC = "https://api.mainnet-beta.solana.com"

    /**
     * Which cluster this build talks to, for the one place that says so out loud.
     *
     * Read off the endpoint rather than declared beside it, so a build pointed somewhere else
     * cannot keep telling people it is on mainnet. The label is on the profile panel above a
     * line about real money, which is exactly the claim that must not go stale.
     */
    val NETWORK: String get() = when {
        "devnet" in PUBLIC_RPC -> "devnet"
        "testnet" in PUBLIC_RPC -> "testnet"
        else -> "mainnet"
    }

    const val LAMPORTS_PER_SOL = 1_000_000_000.0

    /** Solana's fee is ~5000 lamports, but leave headroom rather than land on the boundary. */
    internal const val FEE_HEADROOM_LAMPORTS = 50_000L
    /** What one signature costs. The fee of every transfer this app builds for one signer. */
    const val NETWORK_FEE_LAMPORTS = 5_000L

    /**
     * The least SOL a wallet may be left holding, other than none. The chain refuses any
     * transaction that leaves a wallet between zero and this ("insufficient funds for rent").
     */
    const val RENT_MIN_LAMPORTS = 890_880L
    /** Below what the app shows (0.0001 SOL): a remainder this small is part of "all of it". */
    private const val DUST_LAMPORTS = 100_000L

    sealed interface Built {
        data class Ok(val tx: ByteArray) : Built
        /** Amounts already formatted, in [symbol]: "0.004", "0.01", "SOL". */
        data class NotEnough(val have: String, val need: String, val symbol: String) : Built
        data class Offline(val detail: String) : Built
        data class Bad(val why: String) : Built
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
    private fun reserveSignatureSlot(serialized: ByteArray, slots: Int = 1): ByteArray {
        if (serialized.isEmpty()) return serialized
        // Already has slots (signed or pre-reserved) — leave it alone.
        if (serialized[0].toInt() != 0) return serialized
        return byteArrayOf(slots.toByte()) + ByteArray(64 * slots) + serialized.copyOfRange(1, serialized.size)
    }

    const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    private const val ATA_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    private const val SYSTEM_PROGRAM = "11111111111111111111111111111111"

    /**
     * Rent for the recipient's token account when this payment has to create it, plus fee
     * headroom. A plain token account is 2,039,280 lamports; Token-2022 accounts carry
     * extensions and cost a little more, so this rounds up rather than land on the boundary.
     */
    private const val NEW_TOKEN_ACCOUNT_LAMPORTS = 2_500_000L

    /** The associated token account: where a wallet holds a given mint, derived, never stored. */
    fun tokenAccount(owner: String, mint: String, program: String): String =
        PublicKey.findProgramAddress(
            listOf(PublicKey(owner), PublicKey(program), PublicKey(mint)), PublicKey(ATA_PROGRAM),
        ).publicKey.toBase58()

    /** What the chain says a mint is. Null when unreachable or not a mint at all. */
    data class Mint(val program: String, val decimals: Int)

    fun mintInfo(mint: String): Mint? {
        if (!Addresses.isValid(mint)) return null
        val res = post("""{"jsonrpc":"2.0","id":1,"method":"getAccountInfo","params":["$mint",{"encoding":"jsonParsed"}]}""")
            ?: return null
        if (rpcError(res) != null) return null
        return try {
            val value = org.json.JSONObject(res).getJSONObject("result").optJSONObject("value") ?: return null
            val program = value.getString("owner")
            val parsed = value.getJSONObject("data").getJSONObject("parsed")
            if (program !in setOf(TOKEN_PROGRAM, TOKEN_2022_PROGRAM) || parsed.optString("type") != "mint") null
            else Mint(program, parsed.getJSONObject("info").getInt("decimals"))
        } catch (e: Exception) { null }
    }

    /** Raw units held in one token account; 0 when it doesn't exist; null when unreachable. */
    fun tokenBalance(account: String): Long? {
        val res = post("""{"jsonrpc":"2.0","id":1,"method":"getTokenAccountBalance","params":["$account"]}""")
            ?: return null
        // An account that was never created is a balance of zero, not a failure.
        rpcError(res)?.let { return if ("could not find account" in it.lowercase() || "invalid param" in it.lowercase()) 0L else null }
        return Regex("\"amount\"\\s*:\\s*\"(\\d+)\"").find(res)?.groupValues?.get(1)?.toLongOrNull()
    }

    /** Whether an account exists yet. Null when unreachable. */
    fun exists(account: String): Boolean? {
        val res = post("""{"jsonrpc":"2.0","id":1,"method":"getAccountInfo","params":["$account",{"encoding":"base64","dataSlice":{"offset":0,"length":0}}]}""")
            ?: return null
        if (rpcError(res) != null) return null
        return try { !org.json.JSONObject(res).getJSONObject("result").isNull("value") } catch (e: Exception) { null }
    }

    /**
     * Builds an unsigned SPL token payment: create the recipient's token account if it doesn't
     * exist yet (idempotent, so a race with someone else creating it is harmless), then
     * `transferChecked` — which carries the mint and its decimals, so the chain refuses the
     * transfer outright if either is wrong. The `reference` rides on the transfer exactly as it
     * does for SOL.
     *
     * Works for both token programs: classic SPL Token and Token-2022. Which one a mint uses is
     * read from the chain, never assumed.
     */
    fun buildTokenTransfer(
        payer: String,
        recipient: String,
        mint: String,
        units: Long,
        decimals: Int,
        reference: String,
        symbol: String,
        /** Test seams: skip every network read. */
        blockhashOverride: String? = null,
        programOverride: String? = null,
        recipientAccountExists: Boolean? = null,
        /**
         * Pay INTO this token account as it is, instead of the account derived from [recipient].
         * For a seller that hands out a USDC account rather than a wallet (AirPay's server says
         * which, from the chain). Null is the ordinary case.
         */
        destinationAccount: String? = null,
        /** Mainnet throughout. See [post]. */
    ): Built {
        if (!Addresses.isValid(payer)) return Built.Bad("Your wallet address looks wrong.")
        if (!Addresses.isValid(recipient)) return Built.Bad("That destination address is not valid.")
        if (!Addresses.isValid(mint)) return Built.Bad("That token address is not valid.")
        if (!Addresses.isValid(reference)) return Built.Bad("That payment reference is not valid.")
        if (units <= 0) return Built.Bad("Enter an amount greater than zero.")

        val live = blockhashOverride == null
        val program = programOverride ?: run {
            val info = mintInfo(mint) ?: return Built.Offline("could not read $symbol from Solana")
            // The request's idea of the decimals must match the chain's, or "0.5" means something
            // different to each phone. transferChecked would refuse it anyway; saying so here is
            // kinder than a wallet error.
            if (info.decimals != decimals) return Built.Bad("That request's $symbol details don't match the token on Solana.")
            info.program
        }

        return try {
            val from = PublicKey(payer)
            val to = PublicKey(recipient)
            val mintKey = PublicKey(mint)
            val programKey = PublicKey(program)
            val source = PublicKey(tokenAccount(payer, mint, program))
            val destination = PublicKey(destinationAccount ?: tokenAccount(recipient, mint, program))

            val needsAccount = if (destinationAccount != null) false else recipientAccountExists?.not() ?: if (live) {
                exists(destination.toBase58())?.not() ?: return Built.Offline("could not reach Solana")
            } else false

            if (live) {
                val held = tokenBalance(source.toBase58()) ?: return Built.Offline("could not reach Solana")
                if (held < units) return Built.NotEnough(fmtUnits(held, decimals), fmtUnits(units, decimals), symbol)
                // Tokens move, but the fee — and the recipient's new account, if they have never
                // held this token — is paid in SOL.
                val solNeeded = FEE_HEADROOM_LAMPORTS + if (needsAccount) NEW_TOKEN_ACCOUNT_LAMPORTS else 0L
                when (val b = balance(payer)) {
                    is Balance.Ok -> if (b.sol * LAMPORTS_PER_SOL < solNeeded) return Built.NotEnough(
                        fmt(b.sol), fmt(solNeeded / LAMPORTS_PER_SOL), "SOL for fees",
                    )
                    is Balance.Offline -> return Built.Offline(b.detail)
                }
            }

            val blockhash = blockhashOverride ?: latestBlockhash() ?: return Built.Offline("could not reach Solana")

            val createAccount = BaseInstruction(
                data = byteArrayOf(1), // CreateIdempotent
                keys = listOf(
                    AccountMeta(from, signer = true, writable = true),
                    AccountMeta(destination, signer = false, writable = true),
                    AccountMeta(to, signer = false, writable = false),
                    AccountMeta(mintKey, signer = false, writable = false),
                    AccountMeta(PublicKey(SYSTEM_PROGRAM), signer = false, writable = false),
                    AccountMeta(programKey, signer = false, writable = false),
                ),
                programId = PublicKey(ATA_PROGRAM),
            )
            val transfer = BaseInstruction(
                data = transferCheckedData(units, decimals),
                keys = listOf(
                    AccountMeta(source, signer = false, writable = true),
                    AccountMeta(mintKey, signer = false, writable = false),
                    AccountMeta(destination, signer = false, writable = true),
                    AccountMeta(from, signer = true, writable = false),
                    AccountMeta(PublicKey(reference), signer = false, writable = false),
                ),
                programId = programKey,
            )
            val instructions = if (needsAccount) listOf(createAccount, transfer) else listOf(transfer)
            Built.Ok(reserveSignatureSlot(Transaction(blockhash, instructions, feePayer = from).serialize()))
        } catch (e: Exception) {
            Built.Bad(e.message ?: "could not build the transaction")
        }
    }

    /** TransferChecked: instruction 12, then the amount (u64, little-endian), then decimals (u8). */
    fun transferCheckedData(units: Long, decimals: Int): ByteArray {
        val out = ByteArray(10)
        out[0] = 12
        for (i in 0 until 8) out[1 + i] = (units ushr (8 * i)).toByte()
        out[9] = decimals.toByte()
        return out
    }

    /** Raw units to a readable amount. Cut, never rounded — see [Money]. */
    private fun fmt(sol: Double) =
        Money.tokenAmount(java.math.BigDecimal(sol.toString()), "SOL")

    /** Raw units to a readable amount. Cut, never rounded — see [Money]. */
    fun fmtUnits(units: Long, decimals: Int): String =
        java.math.BigDecimal.valueOf(units, decimals).stripTrailingZeros().toPlainString()

    /** The payer's SOL, which pays the network fee. */
    sealed interface Balance {
        data class Ok(val sol: Double) : Balance
        data class Offline(val detail: String) : Balance
    }

    fun balance(address: String): Balance {
        if (!Addresses.isValid(address)) return Balance.Offline("that address is not valid")
        val res = post("""{"jsonrpc":"2.0","id":1,"method":"getBalance","params":["$address"]}""")
            ?: return Balance.Offline("could not reach Solana")
        if (rpcError(res) != null) return Balance.Offline("could not reach Solana")
        val lamports = Regex("\"value\"\\s*:\\s*(\\d+)").find(res)?.groupValues?.get(1)?.toLongOrNull()
            ?: return Balance.Offline("could not read the balance")
        return Balance.Ok(lamports.toDouble() / LAMPORTS_PER_SOL)
    }

    /** The blockhash a transaction is built against, fetched when the payer presses Pay. */
    fun latestBlockhash(): String? {
        val res = post(
            """{"jsonrpc":"2.0","id":1,"method":"getLatestBlockhash","params":[{"commitment":"confirmed"}]}""",
        ) ?: return null
        if (rpcError(res) != null) return null
        return Regex("\"blockhash\"\\s*:\\s*\"([1-9A-HJ-NP-Za-km-z]{32,44})\"")
            .find(res)?.groupValues?.get(1)
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
                "params":["$b64",{"encoding":"base64","preflightCommitment":"confirmed"}]}""",
        ) ?: return Submitted.Offline

        Regex("\"result\"\\s*:\\s*\"([1-9A-HJ-NP-Za-km-z]{60,})\"").find(res)
            ?.let { return Submitted.Ok(it.groupValues[1]) }

        val why = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(res)?.groupValues?.get(1)
        // "Already processed" is not a refusal: the cluster has this exact transaction. It
        // happens when our server forwarded it and then timed out answering, so [post] sent it
        // a second time to the public node. Calling that Rejected told the payer "nothing left
        // your wallet" about money that had gone, and dropped a shop order that was paid for.
        // The signature is in the transaction itself, so it is handed on as submitted and
        // [confirm] decides what became of it.
        if (why != null && "already been processed" in why.lowercase()) {
            signatureOf(signedTx)?.let { return Submitted.Ok(it) }
        }
        return Submitted.Rejected(why ?: "the network refused the transaction")
    }

    /**
     * A signed transaction's own signature: the first 64 bytes after the one-byte count, which
     * is the fee payer's and is what the chain files the transaction under. Null when the slot
     * is missing or still zero-filled, which means it was never signed.
     */
    private fun signatureOf(signedTx: ByteArray): String? {
        if (signedTx.size < 65 || signedTx[0].toInt() !in 1..127) return null
        val first = signedTx.copyOfRange(1, 65)
        if (first.all { it.toInt() == 0 }) return null
        return org.sol4k.Base58.encode(first)
    }

    /** Turns a raw TransactionError into something a person can act on. */
    fun describeError(raw: String): String = when {
        "InsufficientFunds" in raw || "Custom\":1" in raw -> "there wasn't enough to cover the transfer and fee"
        "BlockhashNotFound" in raw -> "it took too long and expired"
        "AccountNotFound" in raw -> "the paying account doesn't exist on this network"
        else -> "the network rejected it"
    }

    /** Set when the node refused us for asking too often, so callers can say so honestly. */
    @Volatile var rateLimited = false
        private set

    /**
     * Every call to Solana, tried through the Tap server's provider first when one is
     * configured, then the public endpoint.
     *
     * The public devnet endpoint refuses after roughly eight calls in two seconds and answers
     * from whichever node a load balancer picks — which is how a payer ended up unable to
     * confirm money that had already landed. The server holds a provider key; the phone does
     * not, and should not ship one. The fallback keeps the app working with no server at all.
     */
    /** A raw call, for the few places that build their own request. */
    fun rpc(json: String): String? = post(json)

    private fun post(json: String): String? {
        // A server that just failed is skipped for a while. Otherwise every read waits out a
        // failed connection first — the token list took seven seconds with the server down.
        val serverUp = ServerClient.enabled && System.currentTimeMillis() > serverDownUntil
        val endpoints = if (serverUp) listOf(ServerClient.rpcUrl, PUBLIC_RPC) else listOf(PUBLIC_RPC)
        for (url in endpoints) {
            postTo(url, json)?.let { return it }
            if (url != PUBLIC_RPC) serverDownUntil = System.currentTimeMillis() + SERVER_RETRY_MS
        }
        return null
    }

    @Volatile private var serverDownUntil = 0L
    private const val SERVER_RETRY_MS = 30_000L

    /** Internal, but shared: [Names] and [Genesis] both need a mainnet call of their own. */
    internal fun postTo(url: String, json: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json")
        }
        // Our own RPC proxy needs the session; anyone else's endpoint must never see it.
        if (ServerClient.enabled && url.startsWith(ServerClient.rpcUrl.substringBefore("/v1/rpc"))) Session.apply(c)
        c.outputStream.use { it.write(json.toByteArray()) }
        val code = c.responseCode
        if (code == 401 && url.contains("/v1/rpc")) Session.stale()
        rateLimited = code == 429
        if (code == 429) android.util.Log.i("TapChain", "RPC rate limited (429)")
        if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) {
        // Android throws NetworkOnMainThreadException for a call made on the UI thread. Swallowing
        // it returned null, which every caller reads as "no answer from the node" — so a payment
        // that had already confirmed looked like one that would not confirm, for a whole minute.
        if (e is android.os.NetworkOnMainThreadException) {
            android.util.Log.e("TapChain", "RPC attempted on the main thread, this is a bug", e)
        } else {
            android.util.Log.i("TapChain", "RPC to ${url.substringAfter("//").substringBefore("/")} failed: ${e.message}")
        }
        null
    }
}
