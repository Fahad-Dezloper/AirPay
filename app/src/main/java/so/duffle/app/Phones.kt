package so.duffle.app

import android.content.Context
import android.telephony.TelephonyManager
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/**
 * Phone numbers for every country, through Google's libphonenumber: the country code, whether a
 * number can be real, its E.164 form (what Cryptorefills is sent), and how to write it back.
 *
 * Replaces the app's India-only handling ("+91" and ten digits), which CellUp cannot use: it
 * recharges in every country Cryptorefills sells in.
 */
object Phones {
    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    data class Number(val e164: String, val region: String, val national: String, val international: String)

    /** The country this phone is most likely in: its SIM, then the network, then the language setting. */
    fun defaultRegion(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val candidates = listOf(tm?.simCountryIso, tm?.networkCountryIso, Locale.getDefault().country)
        return candidates.firstNotNullOfOrNull { it?.uppercase()?.takeIf { c -> c.length == 2 } } ?: "US"
    }

    /** "+91" for India. Blank for a code libphonenumber does not know. */
    fun dialCode(region: String): String = util.getCountryCodeForRegion(region.uppercase()).let { if (it > 0) "+$it" else "" }

    /**
     * [typed] as a number in [region], or as a full international number when it starts with "+".
     * Null until it is a number that can exist; a typo or a short number is null, not a guess.
     */
    fun parse(typed: String, region: String): Number? {
        val raw = typed.filter { it.isDigit() || it == '+' }
        if (raw.count { it.isDigit() } < 5) return null
        return try {
            val n = util.parse(raw, region.uppercase())
            if (!util.isValidNumber(n)) return null
            val r = util.getRegionCodeForNumber(n) ?: region.uppercase()
            Number(
                e164 = util.format(n, PhoneNumberUtil.PhoneNumberFormat.E164),
                region = r,
                national = util.format(n, PhoneNumberUtil.PhoneNumberFormat.NATIONAL),
                international = util.format(n, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL),
            )
        } catch (e: NumberParseException) {
            null
        }
    }

    /**
     * The network that was first given this number's prefix ("Jio", "Airtel", "MTN"), from
     * libphonenumber's offline tables; null when it does not say. A number moved to another
     * network keeps its prefix, so this is a guess to start from, never the final word.
     */
    fun issuer(e164: String): String? = try {
        val n = util.parse(e164, null)
        PhoneNumberToCarrierMapper.getInstance().getNameForValidNumber(n, Locale.ENGLISH).takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private val NOISE = setOf("india", "usa", "us", "uk", "prepaid", "mobile", "topup", "top", "up", "credits",
        "credit", "recharge", "pin", "pinless", "bundles", "bundle", "data", "airtime", "telecom", "the", "wireless",
        "telecommunications", "communications", "cellular", "ltd", "limited", "inc", "plc", "sa", "group")

    /** Rebrands and brand names, from the server's operator_aliases.json (copied in at build time). */
    @Volatile private var aliases: List<Pair<String, String>> = emptyList()

    fun loadAliases(context: Context) {
        if (aliases.isNotEmpty()) return
        aliases = runCatching {
            val o = org.json.JSONObject(context.assets.open("operator_aliases.json").bufferedReader().use { it.readText() })
            o.keys().asSequence().filterNot { it.startsWith("_") }.map { it to o.getString(it) }
                .sortedByDescending { it.first.length }.toList()
        }.getOrDefault(emptyList())
    }

    internal fun setAliasesForTest(list: List<Pair<String, String>>) { aliases = list.sortedByDescending { it.first.length } }

    private fun words(name: String?): Set<String> =
        name.orEmpty().lowercase().split(Regex("[^a-z0-9!&]+")).filter { it.isNotBlank() && it !in NOISE }.toSet()

    private fun joined(name: String?) = name.orEmpty().lowercase().replace(Regex("[^a-z0-9]"), "")

    /**
     * Which of [ids] (a country's networks, by name) [name] most likely means, or null when it is not
     * clearly one of them. The same rules as the server's match_operator (topups.py).
     */
    fun matchOperator(name: String?, ids: List<String>): String? {
        val low = name.orEmpty().lowercase().trim()
        val alias = aliases.firstOrNull { (k, _) -> k in low }?.second
        val want = words(name) + listOfNotNull(alias)
        if (want.isEmpty()) return null
        val j = joined(name)
        ids.firstOrNull { id -> joined(id).isNotEmpty() && (joined(id) == j || (alias != null && joined(id) == joined(alias))) }
            ?.let { return it }
        val scored = ids.map { it to (want intersect words(it)).size }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return null
        return scored.filter { it.second == best }.singleOrNull()?.first
    }

    /** "+91 98765 43210" for "+919876543210"; the input itself when it cannot be read. */
    fun pretty(e164: String): String = try {
        util.format(util.parse(e164, null), PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
    } catch (e: NumberParseException) {
        e164
    }

    /** The flag for a two-letter country code, as the two regional-indicator letters. */
    fun flag(region: String): String {
        val r = region.uppercase()
        if (r.length != 2 || !r.all { it in 'A'..'Z' }) return "🏳️"
        return String(Character.toChars(0x1F1E6 + (r[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (r[1] - 'A')))
    }
}
