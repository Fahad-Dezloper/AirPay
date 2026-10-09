package so.duffle.app

/**
 * A payment signed by a phone with no connection, carried back over NFC for the asking phone to
 * send.
 *
 * The payer reads the request on a first tap, builds on the blockhash that came with it, signs,
 * and on a second tap hands the signed bytes to the phone that asked. That phone is online, so
 * it submits them. Two things live here because both must be exact and neither needs a phone to
 * test: how the bytes are cut into APDUs and put back together, and what the asking phone checks
 * before it sends bytes that arrived over the air.
 */
object OfflineTx {

    /** Solana's own ceiling on a transaction. Nothing longer can be a payment. */
    const val MAX_SIZE = 1232

    /** The most data a short APDU carries. */
    const val MAX_CHUNK = 255

    // ------------------------------------------------------------------------- the radio

    /**
     * [tx] as the commands that carry it: `80 20 P1 P2 Lc data`, P1 the piece's index from zero,
     * P2 1 on the last piece and 0 before it. Short APDUs only, so it works on a reader that
     * cannot send extended ones. Empty when [tx] is not something that could be handed over.
     */
    fun apdus(tx: ByteArray, chunk: Int = MAX_CHUNK): List<ByteArray> {
        if (tx.isEmpty() || tx.size > MAX_SIZE || chunk !in 1..MAX_CHUNK) return emptyList()
        val pieces = (tx.size + chunk - 1) / chunk
        return (0 until pieces).map { i ->
            val data = tx.copyOfRange(i * chunk, minOf(tx.size, (i + 1) * chunk))
            byteArrayOf(0x80.toByte(), Apdu.INS_HAND, i.toByte(), if (i == pieces - 1) 1 else 0, data.size.toByte()) + data
        }
    }

    sealed interface Step {
        /** That piece is kept and more are expected. Answer 9000. */
        data object More : Step
        /** The last piece arrived and this is all of it. Answer 9000. */
        class Done(val tx: ByteArray) : Step
        /** Refused, and everything gathered so far is dropped. Answer [sw]. */
        class Bad(val sw: ByteArray) : Step
    }

    /**
     * The card's side: pieces in, whole payment out.
     *
     * Strict on purpose. A piece out of turn, a repeat or a gap means the two phones no longer
     * agree on what has been sent, and guessing would put the wrong bytes in front of the chain.
     * So anything unexpected is refused and the count starts again from zero; the reader simply
     * sends the whole thing again on the next tap.
     */
    class Assembler {
        private val got = java.io.ByteArrayOutputStream()
        private var next = 0

        fun reset() {
            got.reset()
            next = 0
        }

        fun accept(cmd: ByteArray): Step {
            fun bad(sw: ByteArray): Step { reset(); return Step.Bad(sw) }
            if (!Apdu.isHand(cmd)) return bad(Apdu.SW_UNKNOWN_CMD)
            val index = cmd[2].toInt() and 0xFF
            val last = cmd[3].toInt()
            val n = cmd[4].toInt() and 0xFF
            // Exactly the header and the data it declares: no extended lengths, no trailing bytes.
            if (n == 0 || cmd.size != 5 + n) return bad(Apdu.SW_WRONG_LENGTH)
            if (last != 0 && last != 1) return bad(Apdu.SW_WRONG_DATA)
            if (index != next) return bad(Apdu.SW_WRONG_DATA)
            if (got.size() + n > MAX_SIZE) return bad(Apdu.SW_WRONG_LENGTH)
            got.write(cmd, 5, n)
            next++
            if (last == 0) return Step.More
            val tx = got.toByteArray()
            reset()
            return Step.Done(tx)
        }
    }

    // ------------------------------------------------------------------------- the check

    sealed interface Verdict {
        data object Ok : Verdict
        /** [why] is for the log. The screen says one plain thing for all of them. */
        data class Refused(val why: String) : Verdict
    }

    /**
     * Is this worth sending for [request]? Never throws: the bytes came over the air.
     *
     * It reads only as far as the account keys. A legacy transaction, one signature that has
     * actually been filled in, and among its accounts both the place this request is paid to and
     * the request's own reference. That is enough to refuse rubbish and payments meant for
     * somebody else; it does not prove the amount. The node simulates the transaction before it
     * accepts it, and the screen only ever says Received after finding the money on the chain.
     */
    fun check(tx: ByteArray, request: PayRequest): Verdict = try {
        val keys = accountKeys(tx)
        val mint = request.mint
        val paidTo = when {
            mint == null -> request.recipient
            // The only token a phone with no connection may pay. See Payments.
            mint == Shop.USDC_MAINNET -> Chain.tokenAccount(request.recipient, mint, Chain.TOKEN_PROGRAM)
            else -> null
        }
        when {
            keys == null -> Verdict.Refused("not a signed legacy transaction")
            paidTo == null -> Verdict.Refused("this token cannot be paid this way")
            paidTo !in keys -> Verdict.Refused("it does not pay this request's address")
            request.reference !in keys -> Verdict.Refused("it does not carry this request's reference")
            else -> Verdict.Ok
        }
    } catch (e: Exception) {
        Verdict.Refused("unreadable")
    }

    /**
     * The account keys of a legacy transaction carrying exactly one, filled in, signature. Null
     * for anything else.
     *
     *     01 | 64-byte signature | 3-byte header | compact-u16 key count | 32 bytes per key | …
     */
    internal fun accountKeys(tx: ByteArray): List<String>? {
        if (tx.size > MAX_SIZE || tx.size < 1 + 64 + 3 + 1 + 32) return null
        if (tx[0].toInt() != 1) return null
        // A slot of zeros is what this app hands a wallet to sign into, so it is a payment
        // nobody approved.
        if ((1..64).all { tx[it].toInt() == 0 }) return null
        var at = 65
        // The top bit marks a versioned message, whose accounts can hide in lookup tables.
        if (tx[at].toInt() and 0x80 != 0) return null
        // One required signer, to match the one signature.
        if (tx[at].toInt() != 1) return null
        at += 3
        var count = tx[at].toInt() and 0x7F
        if (tx[at].toInt() and 0x80 != 0) {
            // Two-byte compact-u16. More than that is more keys than a transaction can hold.
            if (tx[at + 1].toInt() and 0x80 != 0) return null
            count = count or ((tx[at + 1].toInt() and 0x7F) shl 7)
            at++
        }
        at++
        // Every key, and the blockhash after them, has to really be there.
        if (count == 0 || at + 32 * count + 32 > tx.size) return null
        return (0 until count).map { Base58.encode(tx.copyOfRange(at + 32 * it, at + 32 * it + 32)) }
    }
}
