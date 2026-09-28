package so.duffle.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * The SIMs in this phone, so a recharge can start from "which of these" instead of a blank box.
 *
 * Recharging your own number is the overwhelmingly common case, and the phone already knows the
 * answer — it is holding the SIM. Typing ten digits and then choosing an operator from a list
 * is work the device can do for you, and getting the operator wrong is how a recharge goes to
 * the wrong network and cannot be undone.
 *
 * **Two tiers, because the second one costs a permission.**
 *
 *  * Without any permission, [TelephonyManager.getSimOperatorName] names the carrier of the
 *    default data SIM. That alone picks the right operator, which is the part that can be
 *    wrong in a way that costs money.
 *  * With `READ_PHONE_STATE`, [SubscriptionManager] lists **every** active SIM, with the slot
 *    and sometimes the number itself. That is what makes a dual-SIM phone answer "which one".
 *
 * The permission is never demanded. Without it the screen still works, just with one carrier
 * guessed and the number typed by hand — so it is asked for at the moment somebody taps "use my
 * SIM", where the trade is obvious, and not on a launch screen where it is a toll.
 *
 * **Numbers are read, never sent anywhere but the invoice the user is paying.** A carrier name
 * and a phone number are exactly the sort of thing an app should not be collecting, and nothing
 * here is stored or logged.
 */
object Sims {

    /**
     * One SIM.
     *
     * [number] is very often blank. Indian carriers are not required to write MSISDN to the SIM
     * and most do not, so a screen built on the assumption that it is there will be empty on
     * most phones. It is a convenience when present and never a requirement.
     */
    data class Sim(val slot: Int, val carrier: String, val number: String)

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    const val PERMISSION = Manifest.permission.READ_PHONE_STATE

    /**
     * Every SIM the phone will tell us about.
     *
     * Falls back to the default SIM's carrier when the permission is absent, so the list is
     * rarely empty on a phone that has a SIM at all. Never throws: a SecurityException here
     * would take out the whole recharge screen over a convenience.
     */
    fun all(context: Context): List<Sim> {
        if (granted(context)) {
            runCatching {
                val sm = context.getSystemService(SubscriptionManager::class.java)
                val active = sm?.activeSubscriptionInfoList
                if (!active.isNullOrEmpty()) {
                    return active.mapIndexed { i, info ->
                        Sim(
                            slot = info.simSlotIndex.takeIf { it >= 0 } ?: i,
                            carrier = (info.carrierName ?: info.displayName ?: "").toString().trim(),
                            number = clean(runCatching { info.number }.getOrNull()),
                        )
                    }.filter { it.carrier.isNotBlank() }
                }
            }
        }
        // No permission, or the list came back empty: the default SIM's carrier is free to read
        // and is enough to choose an operator.
        val tm = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
        val carrier = runCatching { tm?.simOperatorName }.getOrNull()?.trim().orEmpty()
        return if (carrier.isBlank()) emptyList() else listOf(Sim(0, carrier, ""))
    }

    /** Digits only, and only when there are enough of them to be a real number. */
    private fun clean(raw: String?): String {
        val digits = raw.orEmpty().filter { it.isDigit() }
        return if (digits.length < 10) "" else digits.takeLast(10)
    }

    /**
     * Which Bitrefill product recharges this carrier.
     *
     * Matched on the carrier **name** rather than the number, and that is not a shortcut. India
     * has had full mobile number portability since 2011, so a number's prefix says who issued
     * it and not who runs it now — a prefix table would be confidently wrong for millions of
     * people. The SIM knows its own network; nothing else here does.
     *
     * Matching is loose on purpose. The same network appears as "Jio", "JIO 4G", "RJIO" and
     * "Reliance Jio" depending on the phone and the SIM, and a table of exact strings is a
     * table that stops working on somebody else's handset.
     */
    fun productFor(carrier: String, products: List<Shop.Product>): Shop.Product? {
        val c = carrier.lowercase()
        val wanted = when {
            "jio" in c -> listOf("reliance-jio", "jio")
            "airtel" in c -> listOf("airtel")
            "bsnl" in c || "mtnl" in c -> listOf("bsnl-bundles", "bsnl")
            // Vi is the merged Vodafone-Idea, and SIMs still say all three.
            "vi " == c.take(3) || c == "vi" || "vodafone" in c || "idea" in c -> listOf("vi-", "vodafone")
            else -> return null
        }
        // Prefer a product that sells plans over one that only sells credit: given the choice,
        // "1.5GB/day for 28 days" is what somebody came to buy.
        return wanted.firstNotNullOfOrNull { key ->
            products.filter { it.id.startsWith(key) }
                .sortedByDescending { it.sellsPlans }
                .firstOrNull()
        }
    }

    /**
     * What Bitrefill wants: full international form.
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
