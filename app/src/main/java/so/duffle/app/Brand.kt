package so.duffle.app

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color

/**
 * Which phone someone is holding, so the tap sheet can show its maker's mark instead of a letter.
 *
 * Travels as a short key ("oneplus", "seeker"…) rather than the raw manufacturer string, so a
 * phone can't put arbitrary text on someone else's screen. Anything unrecognised is null, and the
 * sheet falls back to the initial it always showed.
 */
object Brand {

    /** How a brand is drawn: its mark in white on its colour, or — for the Seeker — a picture. */
    data class Look(@DrawableRes val drawable: Int, val colour: Color, val picture: Boolean = false)

    private val LOOKS = mapOf(
        // The Seeker's own art ships as a picture; every other mark is from Simple Icons (CC0).
        SEEKER to Look(R.drawable.brand_seeker, Color.Black, picture = true),
        "oneplus" to Look(R.drawable.brand_oneplus, Color(0xFFF5010C)),
        "apple" to Look(R.drawable.brand_apple, Color(0xFF000000)),
        "samsung" to Look(R.drawable.brand_samsung, Color(0xFF1428A0)),
        "google" to Look(R.drawable.brand_google, Color(0xFF4285F4)),
        "xiaomi" to Look(R.drawable.brand_xiaomi, Color(0xFFFF6900)),
        "motorola" to Look(R.drawable.brand_motorola, Color(0xFFE1140A)),
        "oppo" to Look(R.drawable.brand_oppo, Color(0xFF2D683D)),
        "vivo" to Look(R.drawable.brand_vivo, Color(0xFF415FFF)),
        "huawei" to Look(R.drawable.brand_huawei, Color(0xFFFF0000)),
        "honor" to Look(R.drawable.brand_honor, Color(0xFF000000)),
        // Sony's brand colour is white; on a white sheet that would vanish.
        "sony" to Look(R.drawable.brand_sony, Color(0xFF000000)),
        "nokia" to Look(R.drawable.brand_nokia, Color(0xFF005AFF)),
        "asus" to Look(R.drawable.brand_asus, Color(0xFF000000)),
        "lg" to Look(R.drawable.brand_lg, Color(0xFFA50034)),
    )

    const val SEEKER = "seeker"

    fun look(key: String?): Look? = key?.let { LOOKS[it] }

    /** What to call the phone in a sentence. */
    fun name(key: String?): String? = when (key) {
        SEEKER -> "Solana Seeker"
        "oneplus" -> "OnePlus"
        "apple" -> "iPhone"
        "samsung" -> "Samsung"
        "google" -> "Google Pixel"
        "xiaomi" -> "Xiaomi"
        "motorola" -> "Motorola"
        "oppo" -> "OPPO"
        "vivo" -> "vivo"
        "huawei" -> "Huawei"
        "honor" -> "Honor"
        "sony" -> "Sony"
        "nokia" -> "Nokia"
        "asus" -> "ASUS"
        "lg" -> "LG"
        else -> null
    }

    /** Known keys only — the wire accepts nothing else. */
    fun isKnown(key: String?) = key != null && key in LOOKS

    /** The key if we can draw it, else null — so an unknown phone falls back to an initial. */
    fun keyOrNull(key: String?) = key?.takeIf { it in LOOKS }

    /** This phone. */
    fun mine(): String? = keyFor(Build.MANUFACTURER, Build.BRAND, Build.MODEL)

    /**
     * Manufacturer, brand and model as Android reports them, to a key. Several makers sell under
     * more than one name, so this matches on any of the three.
     */
    fun keyFor(manufacturer: String?, brand: String?, model: String?): String? {
        val all = listOf(manufacturer, brand, model).joinToString(" ") { it.orEmpty().lowercase() }
        return when {
            "solana" in all || model.equals("seeker", true) || "saga" == model?.lowercase() -> SEEKER
            "oneplus" in all -> "oneplus"
            "apple" in all || "iphone" in all -> "apple"
            "samsung" in all -> "samsung"
            "google" in all || "pixel" in all -> "google"
            "xiaomi" in all || "redmi" in all || "poco" in all -> "xiaomi"
            "motorola" in all || "moto" in all -> "motorola"
            "oppo" in all -> "oppo"
            "vivo" in all -> "vivo"
            "huawei" in all -> "huawei"
            "honor" in all -> "honor"
            "sony" in all -> "sony"
            "nokia" in all || "hmd" in all -> "nokia"
            "asus" in all -> "asus"
            "lge" in all || all.split(' ').contains("lg") -> "lg"
            else -> null
        }
    }

    /**
     * Their phone's mark — but the Seeker's only once the chain has confirmed a Genesis token in
     * their wallet ([verified]). A phone can say it is anything; the Seeker mark, like the badge,
     * is reserved for what was checked.
     */
    fun of(payload: TapPayload, verified: Boolean): String? =
        payload.brand?.takeIf { it != SEEKER || verified } ?: if (verified) SEEKER else null
}
