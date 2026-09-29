package so.duffle.app

import org.json.JSONObject

/**
 * Optional text out of a payload we do not control, honouring JSON `null`.
 *
 * `optString` does not return null for one. Android resolves the key to `JSONObject.NULL` and
 * stringifies it, handing back the four characters **"null"** — which is not blank, so every
 * `takeIf { it.isNotBlank() }` downstream waves it through.
 *
 * It has now reached the screen twice. A claimed offer rendered its refusal as the word "null";
 * a product with no description rendered `null` where the description goes. The second one was
 * fixed in [Shop] without noticing that [Rewards] had already met the same trap and grown its
 * own private copy of the fix — which is why this lives in one place now. The next read that
 * needs it is a gift card's PIN, which Bitrefill sends as `null` for products that do not have
 * one, and printing `null` as the PIN of something somebody just paid for is the version of
 * this bug that costs money.
 *
 * **It cannot be caught by a unit test here.** The desktop `org.json` on the test classpath
 * returns `""` for the same input, so the broken code passes on the JVM and only the phone
 * shows it. See `JsonNullTest`.
 */
internal fun JSONObject.text(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
