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

    /**
     * How much to actually send when somebody asks to send [asked] lamports out of [held].
     *
     * Sending everything is not refused for want of a fee: the fee comes out of the amount and
     * the rest goes. "Everything" includes a typed amount that matches the balance as the app
     * shows it, which is cut to four places, so the wallet really holds a few lamports more;
     * those go too, because the chain will not let them stay behind.
     *
     * Null when it cannot be sent: more than is held, nothing left after the fee, or a remainder
     * too big to call dust and too small for the chain to allow ([RENT_MIN_LAMPORTS]).
     */
    fun sendable(asked: Long, held: Long, fee: Long = NETWORK_FEE_LAMPORTS): Long? {
        if (asked <= 0 || asked > held || held <= fee) return null
        val rest = held - asked - fee
        return when {
            rest < DUST_LAMPORTS -> held - fee
            rest < RENT_MIN_LAMPORTS -> null
            else -> asked
        }
    }

    sealed interface Built {
        data class Ok(val tx: ByteArray) : Built
        /** Amounts already formatted, in [symbol]: "0.004", "0.01", "SOL". */
        data class NotEnough(val have: String, val need: String, val symbol: String) : Built
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
                    val held = Math.round(b.sol * LAMPORTS_PER_SOL)
                    // Emptying the wallet is allowed: the amount plus the one-signature fee is
                    // exactly what it holds, which leaves zero, and zero is a balance the chain
                    // accepts. Short of that, the usual headroom is asked for.
                    val emptying = lamports + NETWORK_FEE_LAMPORTS == held
                    if (!emptying && held < lamports + FEE_HEADROOM_LAMPORTS)
                        return Built.NotEnough(fmt(b.sol), fmt(lamports / LAMPORTS_PER_SOL), "SOL")
                }
                is Balance.Offline -> return Built.Offline(b.detail)
            }
        }

        // Fetched now, at the moment Pay is pressed — never carried in the request. A request is
        // good forever; only the transaction built from it has a clock, and that clock should
        // start when the person decides to pay, not when the phones touched.
        val blockhash = blockhashOverride ?: latestBlockhash()
            ?: return Built.Offline("could not reach Solana")

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
    private fun reserveSignatureSlot(serialized: ByteArray, slots: Int = 1): ByteArray {
        if (serialized.isEmpty()) return serialized
        // Already has slots (signed or pre-reserved) — leave it alone.
        if (serialized[0].toInt() != 0) return serialized
        return byteArrayOf(slots.toByte()) + ByteArray(64 * slots) + serialized.copyOfRange(1, serialized.size)
    }

    /**
     * Somebody else paying this transaction's network fee, and what they take for it.
     *
     * For a buyer who holds USDC and no SOL. [feePayer] is AirPay's fee wallet: it is the
     * transaction's fee payer and nothing else in it. [feeUnits] of USDC go to [feeAccount], its
     * token account, in the same transaction, so the fee is paid or nothing is.
     */
    data class Sponsor(val feePayer: String, val feeAccount: String, val feeUnits: Long)

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
        if (!PayRequest.isBase58Address(mint)) return null
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
        /** Set when the buyer has no SOL: see [Sponsor]. Null is the ordinary, self-paid transfer. */
        sponsor: Sponsor? = null,
        /** Mainnet throughout. See [post]. */
    ): Built {
        if (!PayRequest.isBase58Address(payer)) return Built.Bad("Your wallet address looks wrong.")
        if (!PayRequest.isBase58Address(recipient)) return Built.Bad("That destination address is not valid.")
        if (!PayRequest.isBase58Address(mint)) return Built.Bad("That token address is not valid.")
        if (!PayRequest.isBase58Address(reference)) return Built.Bad("That payment reference is not valid.")
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
            val destination = PublicKey(tokenAccount(recipient, mint, program))

            val needsAccount = recipientAccountExists?.not() ?: if (live) {
                exists(destination.toBase58())?.not() ?: return Built.Offline("could not reach Solana")
            } else false

            // A new token account costs SOL rent, which is more than a fee and not something the
            // fee wallet pays for.
            if (sponsor != null && needsAccount) return Built.Bad("This payment needs SOL in your wallet.")
            val owed = units + (sponsor?.feeUnits ?: 0L)

            if (live && sponsor != null) {
                val held = tokenBalance(source.toBase58()) ?: return Built.Offline("could not reach Solana")
                if (held < owed) return Built.NotEnough(fmtUnits(held, decimals), fmtUnits(owed, decimals), symbol)
            } else if (live) {
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
            if (sponsor != null) {
                // The fee, in the same token, to the fee wallet's own account. The fee wallet is
                // the fee payer and appears in no instruction: that is the rule it signs under.
                val fee = BaseInstruction(
                    data = transferCheckedData(sponsor.feeUnits, decimals),
                    keys = listOf(
                        AccountMeta(source, signer = false, writable = true),
                        AccountMeta(mintKey, signer = false, writable = false),
                        AccountMeta(PublicKey(sponsor.feeAccount), signer = false, writable = true),
                        AccountMeta(from, signer = true, writable = false),
                    ),
                    programId = programKey,
                )
                // Two signers, so two slots: the fee wallet's first, then the buyer's.
                return Built.Ok(reserveSignatureSlot(
                    Transaction(blockhash, listOf(transfer, fee), feePayer = PublicKey(sponsor.feePayer)).serialize(), slots = 2,
                ))
            }
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

    /**
     * A SOL figure for the screen.
     *
     * Was `"%.4f".format(sol)`, which is HALF_UP — it rounded a balance **up**, so 0.00009 SOL
     * showed as 0.0001 and claimed money the wallet does not have. [Money] cuts instead, and
     * says `<0.0001` when a real amount cuts away to nothing rather than showing zero.
     *
     * The Double comes from a lamport count, so it is exact at this scale; it is converted
     * through a string so the BigDecimal is the decimal value and not the binary one.
     */
    private fun fmt(sol: Double) =
        Money.tokenAmount(java.math.BigDecimal(sol.toString()), "SOL")

    /** Raw units back to a readable amount, trimmed: 1500000 at 6 decimals is "1.5". */
    /** Raw units to a readable amount. Cut, never rounded — see [Money]. */
    fun fmtUnits(units: Long, decimals: Int): String =
        java.math.BigDecimal.valueOf(units, decimals).stripTrailingZeros().toPlainString()

    sealed interface Balance {
        data class Ok(val sol: Double) : Balance
        data class Offline(val detail: String) : Balance
    }

    fun balance(address: String): Balance {
        if (!PayRequest.isBase58Address(address)) return Balance.Offline("that address is not valid")
        val res = post("""{"jsonrpc":"2.0","id":1,"method":"getBalance","params":["$address"]}""")
            ?: return Balance.Offline("could not reach Solana")
        if (rpcError(res) != null) return Balance.Offline("could not reach Solana")
        val lamports = Regex("\"value\"\\s*:\\s*(\\d+)").find(res)?.groupValues?.get(1)?.toLongOrNull()
            ?: return Balance.Offline("could not read the balance")
        return Balance.Ok(lamports.toDouble() / LAMPORTS_PER_SOL)
    }

    /** The blockhash a transaction is built against, fetched when the payer presses Pay. */
    /** The blockhash a transaction is built against, fetched when the payer presses Pay. */
    fun latestBlockhash(): String? {
        val res = post(
            """{"jsonrpc":"2.0","id":1,"method":"getLatestBlockhash","params":[{"commitment":"confirmed"}]}""",
        ) ?: return null
        if (rpcError(res) != null) return null
        return Regex("\"blockhash\"\\s*:\\s*\"([1-9A-HJ-NP-Za-km-z]{32,44})\"")
            .find(res)?.groupValues?.get(1)
    }

    sealed interface Found {
        data class Yes(val signature: String) : Found
        data object NotYet : Found
        data object Offline : Found
        /** The node answered and the answer was an error. Never silently a "not yet". */
        data class Broken(val why: String) : Found
        /**
         * A transaction carried this request's reference but did not pay it. Never counted as
         * paid — attaching somebody's reference to a 1-lamport transfer costs a fraction of a
         * cent, and a payee who trusted the reference alone would hand over the goods.
         */
        data class Short(val signature: String, val why: String) : Found
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

    fun transactionBody(signature: String) =
        """{"jsonrpc":"2.0","id":1,"method":"getTransaction",
            "params":["$signature",{"encoding":"json","commitment":"confirmed","maxSupportedTransactionVersion":0}]}"""

    /**
     * Has this request been paid — in full, to the right address?
     *
     * The reference only FINDS candidate transactions; it proves nothing about them. Each one
     * is fetched and checked for the thing that actually matters: the recipient's balance went
     * up by at least the amount asked. That is also the check that survives wallets rewriting
     * what they sign — Seeker Wallet was seen adding two ComputeBudget instructions to a
     * transaction we built, so "is this our exact transaction" would have been the wrong test.
     */
    fun findPayment(reference: String, recipient: String, lamports: Long, mint: String? = null): Found {
        if (!PayRequest.isBase58Address(reference)) return Found.Broken("that reference is not valid")
        if (!PayRequest.isBase58Address(recipient)) return Found.Broken("that address is not valid")
        val res = post(signaturesBody(reference)) ?: return Found.Offline
        // Check for an error BEFORE hunting for a signature. A JSON-RPC error arrives with a
        // 200, so reading "no signature in the body" as "not paid yet" hides a broken query
        // behind a spinner — which is precisely how this went wrong once.
        rpcError(res)?.let { return Found.Broken(it) }

        val candidates = successfulSignatures(res)
        var short: Found.Short? = null
        for (sig in candidates) {
            val tx = post(transactionBody(sig)) ?: return Found.Offline
            rpcError(tx)?.let { return Found.Broken(it) }
            when (val v = verifyTransfer(tx, reference, recipient, lamports, mint)) {
                Verdict.Paid -> return Found.Yes(sig)
                is Verdict.Rejected -> short = Found.Short(sig, v.why)
                // Listed by signature but not yet served by getTransaction — look again later.
                Verdict.Pending -> Unit
            }
        }
        return short ?: Found.NotYet
    }

    sealed interface Verdict {
        data object Paid : Verdict
        data object Pending : Verdict
        data class Rejected(val why: String) : Verdict
    }

    /**
     * The pure half of [findPayment], so it can be tested against a real devnet transaction
     * without a network. `txJson` is a raw `getTransaction` response.
     */
    fun verifyTransfer(txJson: String, reference: String, recipient: String, lamports: Long, mint: String? = null): Verdict = try {
        val result = org.json.JSONObject(txJson).optJSONObject("result")
        if (result == null) {
            Verdict.Pending
        } else {
            val meta = result.getJSONObject("meta")
            val keys = accountKeys(result)
            val at = keys.indexOf(recipient)
            when {
                !meta.isNull("err") -> Verdict.Rejected("that transaction failed on-chain")
                reference !in keys -> Verdict.Rejected("that transaction is not for this request")
                // A token payment lands in the recipient's token account, not their wallet, so
                // their own key need not appear at all. What counts is the token balance they own.
                mint != null -> {
                    val got = tokenHeld(meta.optJSONArray("postTokenBalances"), recipient, mint) -
                        tokenHeld(meta.optJSONArray("preTokenBalances"), recipient, mint)
                    if (got >= lamports) Verdict.Paid
                    else Verdict.Rejected("paid $got of $lamports token units")
                }
                at < 0 -> Verdict.Rejected("that transaction did not pay this address")
                else -> {
                    val got = meta.getJSONArray("postBalances").getLong(at) -
                        meta.getJSONArray("preBalances").getLong(at)
                    if (got >= lamports) Verdict.Paid
                    else Verdict.Rejected("paid $got of $lamports lamports")
                }
            }
        }
    } catch (e: Exception) {
        Verdict.Rejected("could not read that transaction")
    }

    /**
     * Everything [owner] holds of [mint] across the accounts a transaction touched. A token
     * account created by this very transaction has no "pre" entry, which correctly reads as 0.
     */
    private fun tokenHeld(balances: org.json.JSONArray?, owner: String, mint: String): Long {
        if (balances == null) return 0
        var sum = 0L
        for (i in 0 until balances.length()) {
            val b = balances.optJSONObject(i) ?: continue
            if (b.optString("owner") == owner && b.optString("mint") == mint) {
                sum += b.getJSONObject("uiTokenAmount").getString("amount").toLong()
            }
        }
        return sum
    }

    /**
     * Account keys in the order balances are reported: the message's static keys, then any
     * loaded from lookup tables (writable, then readonly). A legacy transfer has only the first.
     */
    private fun accountKeys(result: org.json.JSONObject): List<String> {
        val out = mutableListOf<String>()
        val static = result.getJSONObject("transaction").getJSONObject("message").getJSONArray("accountKeys")
        for (i in 0 until static.length()) {
            val k = static.get(i)
            out += if (k is org.json.JSONObject) k.getString("pubkey") else k.toString()
        }
        result.getJSONObject("meta").optJSONObject("loadedAddresses")?.let { loaded ->
            for (group in listOf("writable", "readonly")) {
                val arr = loaded.optJSONArray(group) ?: continue
                for (i in 0 until arr.length()) out += arr.getString(i)
            }
        }
        return out
    }

    /** Signatures from a getSignaturesForAddress response whose transactions succeeded. */
    fun successfulSignatures(res: String): List<String> = try {
        val arr = org.json.JSONObject(res).getJSONArray("result")
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.isNull("err") }
            .map { it.getString("signature") }
    } catch (e: Exception) {
        emptyList()
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
        return Submitted.Rejected(why ?: "the network refused the transaction")
    }

    sealed interface Confirmation {
        data object Confirmed : Confirmation
        /** Landed, and the chain rejected it. Money did not move; the fee may have. */
        data class Failed(val why: String) : Confirmation
        /** Still not seen when we stopped waiting. Neither success nor failure. */
        data object Unknown : Confirmation
    }

    /**
     * Waits until a submitted transaction is confirmed or has failed.
     *
     * "Submitted" is not "sent": sendTransaction only means a node accepted it for forwarding,
     * and it can still fail on-chain or never land. The popup's success state waits for this, so
     * a green tick means the money actually moved.
     */
    suspend fun confirm(
        signature: String,
        /** The request being paid. Its reference is the second, sturdier way to confirm. */
        reference: String? = null,
        recipient: String? = null,
        lamports: Long = 0,
        mint: String? = null,
        timeoutMs: Long = 60_000,
    ): Confirmation {
        val until = System.currentTimeMillis() + timeoutMs
        var round = 0
        while (System.currentTimeMillis() < until) {
            round++
            // searchTransactionHistory, because the default only looks in a short-lived status
            // cache: api.devnet.solana.com answered `null` for a transaction one minute old that
            // Helius reported as finalized, and the payer was told "not confirmed yet" for money
            // that had already arrived.
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                post(
                    """{"jsonrpc":"2.0","id":1,"method":"getSignatureStatuses",
                        "params":[["$signature"],{"searchTransactionHistory":true}]}"""
                )
            }
            if (res != null && rpcError(res) == null) {
                val v = try {
                    org.json.JSONObject(res).getJSONObject("result").getJSONArray("value").opt(0)
                } catch (e: Exception) { null }
                if (v is org.json.JSONObject) {
                    if (!v.isNull("err")) return Confirmation.Failed(describeError(v.get("err").toString()))
                    val level = v.optString("confirmationStatus")
                    if (level == "confirmed" || level == "finalized") return Confirmation.Confirmed
                }
            }
            // The same evidence the payee accepts: the money is on-chain, under this request's
            // reference, in full. Independent of whether the status cache has heard of us.
            if (reference != null && recipient != null && lamports > 0) {
                val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    findPayment(reference, recipient, lamports, mint)
                }
                if (found is Found.Yes) return Confirmation.Confirmed
            }
            // Public RPC starts refusing after roughly eight calls in two seconds, and a refusal
            // looks exactly like "not yet". Ask less often the longer this goes on.
            kotlinx.coroutines.delay(if (round < 10) 2_000 else 4_000)
        }
        return Confirmation.Unknown
    }

    /** Turns a raw TransactionError into something a person can act on. */
    fun describeError(raw: String): String = when {
        "InsufficientFunds" in raw || "Custom\":1" in raw -> "there wasn't enough to cover the transfer and fee"
        "BlockhashNotFound" in raw -> "it took too long and expired"
        "AccountNotFound" in raw -> "the paying account doesn't exist on this network"
        else -> "the network rejected it"
    }

    /** Cheap liveness probe, so "waiting" can be told apart from "offline". */
    fun reachable(): Boolean =
        post("""{"jsonrpc":"2.0","id":1,"method":"getHealth"}""") != null

    /** Where to look a transaction up. One cluster, so no cluster parameter. */
    fun explorerUrl(signature: String) = "https://explorer.solana.com/tx/$signature"

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
