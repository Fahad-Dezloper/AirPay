package so.duffle.app

/**
 * Phone numbers, in the shape the shop wants them.
 *
 * This file used to read the SIMs out of the phone, to work out which network to recharge. That
 * is gone, and the reason is worth keeping: Bitrefill will answer the same question from the
 * number itself (see [Shop.operatorFor]), authoritatively, for **any** number rather than only
 * the ones in this handset — and it needs no permission at all.
 *
 * So `READ_PHONE_STATE` came back out of the manifest. A permission that buys nothing is worse
 * than no feature: it is a prompt asking somebody to hand over their carrier and number for a
 * convenience the app can get another way.
 */
object Sims {

    /**
     * A number in full international form, or null when it is not one.
     *
     * India only, which is what the catalogue is. Ten digits get the country code; anything
     * already carrying one is left alone.
     */
    fun e164(number: String): String? {
        val digits = number.filter { it.isDigit() }
        return when {
            digits.length == 10 -> "+91$digits"
            digits.length == 12 && digits.startsWith("91") -> "+$digits"
            else -> null
        }
    }
}
