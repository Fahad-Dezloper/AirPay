package so.duffle.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.sol4k.PublicKey
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the connected wallet actually holds, so the token list shows your tokens rather than a
 * catalogue.
 *
 * Built from standard RPC only — `getBalance`, `getTokenAccountsByOwner` and `getAccountInfo` —
 * so it works on the public endpoint with no provider key. Names, symbols and logos come from the
 * token's own on-chain metadata: the Token-2022 metadata extension, or the Metaplex metadata
 * account that classic SPL tokens use. Both point at a URI; the logo is the `image` in that JSON,
 * or the URI itself when it is already an image.
 *
 * Metadata is cached on disk per mint (it almost never changes); balances never are.
 */
object Tokens {

    data class Holding(
        /** Null for SOL. */
        val mint: String?,
        val symbol: String,
        val name: String,
        val decimals: Int,
        /** Raw units held — lamports for SOL. */
        val units: Long,
        /** A URL for the logo, or null to fall back to a letter. SOL's logo ships with the app. */
        val image: String?,
    ) {
        val isSol get() = mint == null
        /** What a request calls this token: "SOL" or the mint. */
        val token get() = mint ?: "SOL"
        val amount: String get() = Chain.fmtUnits(units, decimals)
        /**
         * For reading, not paying.
         *
         * Through [Money], so it is cut rather than rounded, grouped the way the phone's locale
         * groups, and says `<0.0001` when a real balance cuts away to nothing.
         *
         * It used to end in `stripTrailingZeros()`, which made the decimal count depend on the
         * value: 2.50 rendered as "2.5" and 2.00 as "2", so a column of balances had its points
         * in different places and tabular figures had nothing to align.
         */
        val shown: String get() =
            Money.tokenAmount(java.math.BigDecimal.valueOf(units, decimals), symbol)
    }

    private const val METAPLEX = "metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s"

    /** A logo that ships with the app rather than loading from the web. */
    const val BUNDLED_USDC = "bundled:usdc"

    /**
     * Mints we can name without asking the chain. Devnet USDC from Circle's faucet has no
     * metadata on devnet, so without this it would show as "4zMM".
     */
    private val KNOWN = mapOf(
        "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU" to Meta("USDC", "USD Coin", BUNDLED_USDC),
        "EPjFWdd5AufqSSqeM2qJxdxLTSnu4ZsdH6DsjCfCnDF6" to Meta("USDC", "USD Coin", BUNDLED_USDC),
    )

    data class Meta(val symbol: String, val name: String, val image: String?)

    /** SOL first, then tokens by name. Null when the chain can't be reached at all. */
    fun holdings(context: Context, owner: String): List<Holding>? {
        val sol = when (val b = Chain.balance(owner)) {
            is Chain.Balance.Ok -> Math.round(b.sol * Chain.LAMPORTS_PER_SOL)
            is Chain.Balance.Offline -> return null
        }
        val out = mutableListOf(Holding(null, "SOL", "Solana", 9, sol, null))

        // One mint can sit in several accounts; what matters is the total per mint.
        data class Acc(var units: Long, val decimals: Int)
        val byMint = linkedMapOf<String, Acc>()
        for (program in listOf(Chain.TOKEN_PROGRAM, Chain.TOKEN_2022_PROGRAM)) {
            val res = Chain.rpc(
                """{"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByOwner",
                    "params":["$owner",{"programId":"$program"},{"encoding":"jsonParsed"}]}"""
            ) ?: continue
            if (Chain.rpcError(res) != null) continue
            try {
                val arr = JSONObject(res).getJSONObject("result").getJSONArray("value")
                for (i in 0 until arr.length()) {
                    val info = arr.getJSONObject(i).getJSONObject("account").getJSONObject("data")
                        .getJSONObject("parsed").getJSONObject("info")
                    val amt = info.getJSONObject("tokenAmount")
                    val units = amt.getString("amount").toLongOrNull() ?: continue
                    val mint = info.getString("mint")
                    byMint.getOrPut(mint) { Acc(0, amt.getInt("decimals")) }.units += units
                }
            } catch (e: Exception) {
                continue
            }
        }

        android.util.Log.i("Tokens", "owner=${owner.take(6)}… sol=$sol mints=${byMint.keys.map { it.take(6) }}")
        val tokens = byMint.filter { it.value.units > 0 }.map { (mint, acc) ->
            val meta = meta(context, mint)
            Holding(mint, meta.symbol, meta.name, acc.decimals, acc.units, meta.image)
        }.sortedBy { it.name.lowercase() }
        return out + tokens
    }

    /** Name, symbol and logo for a mint — from the cache, a known list, or its metadata. */
    fun meta(context: Context, mint: String): Meta {
        val prefs = context.getSharedPreferences("token_meta", Context.MODE_PRIVATE)
        prefs.getString(mint, null)?.let { cached ->
            try {
                val o = JSONObject(cached)
                return Meta(o.getString("s"), o.getString("n"), o.optString("i").ifBlank { null })
            } catch (e: Exception) { /* re-resolve */ }
        }
        val found = KNOWN[mint] ?: token2022Meta(mint) ?: metaplexMeta(mint)
        val meta = found ?: Meta(mint.take(4), "${mint.take(4)}…${mint.takeLast(4)}", null)
        // Only cache what we actually learned; an unreachable node shouldn't stick as "unnamed".
        if (found != null) {
            prefs.edit().putString(mint, JSONObject().apply {
                put("s", meta.symbol); put("n", meta.name); meta.image?.let { put("i", it) }
            }.toString()).apply()
        }
        return meta
    }

    /** Token-2022 keeps metadata in the mint itself, as an extension. */
    private fun token2022Meta(mint: String): Meta? {
        val res = Chain.rpc("""{"jsonrpc":"2.0","id":1,"method":"getAccountInfo","params":["$mint",{"encoding":"jsonParsed"}]}""")
            ?: return null
        return try {
            val info = JSONObject(res).getJSONObject("result").getJSONObject("value")
                .getJSONObject("data").getJSONObject("parsed").getJSONObject("info")
            val exts = info.optJSONArray("extensions") ?: return null
            (0 until exts.length()).map { exts.getJSONObject(it) }
                .firstOrNull { it.optString("extension") == "tokenMetadata" }
                ?.getJSONObject("state")
                ?.let { st -> named(st.optString("symbol"), st.optString("name"), st.optString("uri")) }
        } catch (e: Exception) { null }
    }

    /** Classic SPL tokens: the Metaplex metadata account at a PDA of the mint. */
    private fun metaplexMeta(mint: String): Meta? {
        val pda = try {
            PublicKey.findProgramAddress(
                listOf(PublicKey("metadata".toByteArray()), PublicKey(METAPLEX), PublicKey(mint)), PublicKey(METAPLEX),
            ).publicKey.toBase58()
        } catch (e: Exception) {
            return null
        }
        val res = Chain.rpc("""{"jsonrpc":"2.0","id":1,"method":"getAccountInfo","params":["$pda",{"encoding":"base64"}]}""")
            ?: return null
        return try {
            val value = JSONObject(res).getJSONObject("result").optJSONObject("value") ?: return null
            val data = Base64.decode(value.getJSONArray("data").getString(0), Base64.DEFAULT)
            // key (1) · update authority (32) · mint (32) · name · symbol · uri, each a borsh
            // string (u32 little-endian length, then bytes), padded with NULs.
            var at = 1 + 32 + 32
            fun str(): String {
                val len = (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8) or
                    ((data[at + 2].toInt() and 0xff) shl 16) or ((data[at + 3].toInt() and 0xff) shl 24)
                at += 4
                val s = String(data, at, len, Charsets.UTF_8).trimEnd('\u0000').trim()
                at += len
                return s
            }
            val name = str(); val symbol = str(); val uri = str()
            named(symbol, name, uri)
        } catch (e: Exception) { null }
    }

    private fun named(symbol: String, name: String, uri: String): Meta? {
        if (symbol.isBlank() && name.isBlank()) return null
        val cleanSymbol = symbol.ifBlank { name }.take(10)
        return Meta(cleanSymbol, name.ifBlank { cleanSymbol }.take(32), image(uri))
    }

    /** The logo behind a metadata URI: the URI itself if it's an image, else its JSON `image`. */
    private fun image(uri: String): String? {
        val url = web(uri) ?: return null
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 5_000; readTimeout = 5_000 }
            val type = c.contentType.orEmpty()
            when {
                type.startsWith("image/") -> url.also { c.disconnect() }
                else -> {
                    val body = c.inputStream.bufferedReader().use { it.readText().take(64_000) }
                    web(JSONObject(body).optString("image"))
                }
            }
        } catch (e: Exception) { null }
    }

    /** https only; IPFS through a public gateway. Anything else is not fetched. */
    fun web(uri: String?): String? {
        val u = uri?.trim().orEmpty()
        return when {
            u.startsWith("https://") -> u
            u.startsWith("ipfs://") -> "https://ipfs.io/ipfs/" + u.removePrefix("ipfs://")
            else -> null
        }
    }

    /** For tests and logs: a readable one-liner. */
    fun describe(list: List<Holding>): String = JSONArray(list.map { "${it.amount} ${it.symbol}" }).toString()
}
