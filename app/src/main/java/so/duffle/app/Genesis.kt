package so.duffle.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Detects whether a wallet holds a Seeker Genesis Token.
 *
 * The Genesis Token is the soulbound NFT proving a phone is a genuine Seeker. Each device gets
 * its **own** mint — the well-known address `GT22s89n…` has `supply: 0`, so it is not a token
 * anybody holds. What every Genesis Token shares is the authority that minted it.
 *
 * Two properties worth knowing, both read straight off the chain:
 *  - it is a **Token-2022** mint (`TokenzQdBNb…`), not classic SPL
 *  - `decimals: 0`, and the freeze authority is live — freeze is the mechanism that makes it
 *    soulbound, which is why a naive rug checker scores it as dangerous. It is not.
 *
 * Always queried against **mainnet**, even while payments run on devnet: the Genesis Token only
 * exists on mainnet, so a devnet lookup would tell every Seeker owner they do not own one.
 */
object Genesis {

    /** Mints every Seeker Genesis Token. The anchor we match on. */
    const val MINT_AUTHORITY = "GT2zuHVaZQYZSyQMgJPLzvkmyztfyXg2NJunqFp4p3A4"

    const val TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"

    /** Genesis lives on mainnet regardless of which cluster payments use. */
    private const val MAINNET = "https://api.mainnet-beta.solana.com"

    /**
     * Returns the Genesis mint this wallet holds, or null.
     *
     * Deliberately fails closed: any network trouble returns null, so the badge is absent
     * rather than wrongly claimed. Never say "Verified Seeker" on a guess.
     */
    /** What the chain says about a wallet, keeping "couldn't ask" apart from "no". */
    sealed interface Check {
        data class Held(val mint: String) : Check
        data object None : Check
        data object Unreachable : Check
    }

    /**
     * Does this wallet hold a Genesis Token? Unlike [mintFor], a network failure is reported as
     * [Check.Unreachable] rather than folded into "no", so a screen can say "couldn't check".
     */
    fun check(owner: String): Check {
        val accounts = tokenAccounts(owner) ?: return Check.Unreachable
        for (mint in accounts) {
            when (genesisMint(mint)) {
                true -> return Check.Held(mint)
                null -> return Check.Unreachable
                false -> Unit
            }
        }
        return Check.None
    }

    fun mintFor(owner: String): String? {
        val accounts = tokenAccounts(owner) ?: return null
        for (mint in accounts) {
            if (isGenesisMint(mint)) return mint
        }
        return null
    }

    /** Token-2022 accounts holding exactly one indivisible unit — i.e. NFT-shaped. */
    private fun tokenAccounts(owner: String): List<String>? {
        val res = rpc(
            """
            {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByOwner",
             "params":["$owner",{"programId":"$TOKEN_2022"},{"encoding":"jsonParsed"}]}
            """.trimIndent()
        ) ?: return null
        return try {
            val arr = JSONObject(res).getJSONObject("result").getJSONArray("value")
            buildList {
                for (i in 0 until arr.length()) {
                    val info = arr.getJSONObject(i)
                        .getJSONObject("account").getJSONObject("data")
                        .getJSONObject("parsed").getJSONObject("info")
                    val amt = info.getJSONObject("tokenAmount")
                    // decimals 0 and exactly 1 held: the shape of a per-device credential.
                    if (amt.optInt("decimals") == 0 && amt.optString("amount") == "1") {
                        add(info.getString("mint"))
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** True, false, or null when the chain couldn't be asked. */
    private fun genesisMint(mint: String): Boolean? {
        val res = rpc("""{"jsonrpc":"2.0","id":1,"method":"getAccountInfo","params":["$mint",{"encoding":"jsonParsed"}]}""")
            ?: return null
        // A refusal arrives as an HTTP 200 carrying an error object. Reading that as "not a
        // Genesis mint" turned one rate-limited call into a definite no, and a definite no is
        // what takes a contact's Seeker badge away.
        if (Chain.rpcError(res) != null) return null
        return try {
            val info = JSONObject(res).getJSONObject("result").getJSONObject("value")
                .getJSONObject("data").getJSONObject("parsed").getJSONObject("info")
            info.optString("mintAuthority") == MINT_AUTHORITY || info.optString("freezeAuthority") == MINT_AUTHORITY
        } catch (e: Exception) {
            false
        }
    }

    /** A Genesis mint is one minted by the Seeker authority. */
    private fun isGenesisMint(mint: String): Boolean {
        val res = rpc(
            """
            {"jsonrpc":"2.0","id":1,"method":"getAccountInfo",
             "params":["$mint",{"encoding":"jsonParsed"}]}
            """.trimIndent()
        ) ?: return false
        return try {
            val info = JSONObject(res).getJSONObject("result").getJSONObject("value")
                .getJSONObject("data").getJSONObject("parsed").getJSONObject("info")
            info.optString("mintAuthority") == MINT_AUTHORITY ||
                info.optString("freezeAuthority") == MINT_AUTHORITY
        } catch (e: Exception) {
            false
        }
    }

    private fun rpc(json: String): String? = try {
        val c = (URL(MAINNET).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(json.toByteArray()) }
        if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) {
        null
    }
}
