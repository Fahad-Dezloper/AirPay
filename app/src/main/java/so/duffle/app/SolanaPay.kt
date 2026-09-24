package so.duffle.app

import java.net.URLDecoder

/**
 * Reading a Solana Pay code.
 *
 * The format is a URI, not JSON: `solana:<recipient>?amount=1.5&spl-token=<mint>&label=Shop`.
 * Everything after the recipient is optional, which is the whole difficulty — the same scheme
 * covers "pay this shop 1.5 USDC" and "here is my address", and those are two different
 * screens.
 *
 * **Transaction requests are refused.** When the path is an https URL instead of an address,
 * Solana Pay means "POST to this server and it will hand you a transaction to sign". That is a
 * stranger's URL building a transaction this app would then ask someone to approve, and
 * nothing in AirPay is equipped to show them what it does. Refused by name, so the screen can
 * say which kind of code it was rather than "invalid".
 */
object SolanaPay {

    sealed interface Scanned {
        /** Pay this much, to this address — everything needed is in the code. */
        data class Request(
            val recipient: String,
            val amount: String,
            /** "SOL" or an SPL mint. */
            val token: String,
            val label: String,
            /** The payee's handle on the payment, when the code carries one. */
            val reference: String?,
        ) : Scanned

        /** An address and nothing else: who to pay, but not how much. */
        data class Address(val recipient: String, val label: String) : Scanned

        /** Read, understood, and deliberately not acted on. */
        data class Refused(val why: String) : Scanned

        /** Not a Solana code at all. */
        data object Unknown : Scanned
    }

    fun parse(raw: String): Scanned {
        val text = raw.trim()
        if (text.isEmpty()) return Scanned.Unknown

        // A bare address, which is what most wallets put in a "receive" code.
        if (PayRequest.isBase58Address(text)) return Scanned.Address(text, "")

        if (!text.startsWith("solana:", ignoreCase = true)) return Scanned.Unknown

        // Cut by hand rather than with `android.net.Uri`: `solana:ADDRESS?...` is an opaque
        // URI, so the address is the scheme-specific part and not a path. Doing it here also
        // keeps this whole object free of Android, which is what lets it be tested — the first
        // version leaned on Uri and every case fell through to "unknown" on the JVM, including
        // the two refusals, which are the ones worth having a test for.
        val body = text.substring("solana:".length)
        val target = body.substringBefore('?')
        if (target.startsWith("http", ignoreCase = true)) {
            return Scanned.Refused("That's a transaction request — a link that builds a payment on a server. AirPay only pays codes it can read itself.")
        }
        if (!PayRequest.isBase58Address(target)) return Scanned.Unknown

        val params = body.substringAfter('?', "").split('&').mapNotNull { pair ->
            val k = pair.substringBefore('=', "")
            val v = pair.substringAfter('=', "")
            if (k.isBlank() || v.isBlank()) null
            else k.lowercase() to runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }.toMap()

        fun q(name: String) = params[name]?.takeIf { it.isNotBlank() }

        val label = (q("label") ?: q("message")).orEmpty().take(64)
        val mint = q("spl-token")?.takeIf { PayRequest.isBase58Address(it) }
        val token = mint ?: "SOL"
        val reference = q("reference")?.takeIf { PayRequest.isBase58Address(it) }
        val amount = q("amount")

        // An amount that is present but unreadable is refused rather than dropped: silently
        // turning "pay 12.5" into "pay how much?" invites somebody to type a different number.
        if (amount != null) {
            val value = amount.toDoubleOrNull()
            if (value == null || value <= 0 || value > PayRequest.MAX_AMOUNT) {
                return Scanned.Refused("That code asks for \"$amount\", which isn't an amount this app can pay.")
            }
            return Scanned.Request(target, amount.trim(), token, label, reference)
        }
        return Scanned.Address(target, label)
    }
}
