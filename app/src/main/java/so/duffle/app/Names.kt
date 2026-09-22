package so.duffle.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.sol4k.PublicKey
import java.security.MessageDigest

/**
 * The name behind an address, when there is one.
 *
 * An address is the one thing on this screen nobody can read. Solana Name Service holds a
 * **reverse record** for wallets that registered a `.sol` domain, so an address can name itself
 * without anyone typing anything.
 *
 * **Mainnet only**, like [Genesis]: SNS lives there, and a devnet lookup would tell every owner
 * they have no name. Cached in memory for the life of the process — a domain does not change
 * while someone is looking at a list of faces, and this runs once per contact.
 *
 * Phantom account labels are deliberately not attempted: Phantom does not publish them for an
 * arbitrary address, so there is nothing to look up. The only name that exists off-device is
 * the one on-chain.
 */
object Names {

    private const val MAINNET = "https://api.mainnet-beta.solana.com"

    /** SPL Name Service, which holds every `.sol` record. */
    private const val NAME_PROGRAM = "namesLPneVptA9Z5rqUDD9tMTWEJwofgaYwp8cawRkX"

    /**
     * The `.sol` root, and also the class every reverse record is filed under — SNS uses the
     * same account for both, which looks like a mistake and is not.
     */
    private const val ROOT = "58PwtjSDuFHuUkYjH9BYnnQKHfwo9reZhC2zMJv9JPkx"

    private const val HASH_PREFIX = "SPL Name Service"

    /** Every name record starts with parent, owner and class — 32 bytes each. */
    private const val HEADER = 96

    private val cache = mutableMapOf<String, String?>()

    /** "fahad.sol", or null when the address has no reverse record or the chain is unreachable. */
    suspend fun sol(address: String): String? {
        cache[address]?.let { return it }
        if (cache.containsKey(address)) return null
        val name = withContext(Dispatchers.IO) { lookup(address) }
        // A failure is not cached: a name that could not be read today is worth asking for
        // again, unlike an address that genuinely has none.
        if (name != null) cache[address] = name
        return name
    }

    private fun lookup(address: String): String? = runCatching {
        val pda = PublicKey.findProgramAddress(
            listOf(PublicKey(hashed(address)), PublicKey(ROOT), PublicKey(ByteArray(32))),
            PublicKey(NAME_PROGRAM),
        ).publicKey.toBase58()
        val raw = accountData(pda) ?: return null
        if (raw.size <= HEADER + 4) return null
        // A borsh string: four bytes of length, then the name. Anything longer than a domain
        // can be is a record we have misread, and is dropped rather than shown.
        val len = (raw[HEADER].toInt() and 0xFF) or
            ((raw[HEADER + 1].toInt() and 0xFF) shl 8) or
            ((raw[HEADER + 2].toInt() and 0xFF) shl 16) or
            ((raw[HEADER + 3].toInt() and 0xFF) shl 24)
        if (len !in 1..64 || HEADER + 4 + len > raw.size) return null
        val name = String(raw, HEADER + 4, len, Charsets.UTF_8)
        if (name.any { it.isWhitespace() || it.code < 0x20 }) null else "$name.sol"
    }.getOrNull()

    /** sha256("SPL Name Service" + name), which is how SNS addresses every record. */
    private fun hashed(name: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest((HASH_PREFIX + name).toByteArray(Charsets.UTF_8))

    private fun accountData(pubkey: String): ByteArray? {
        val body = """{"jsonrpc":"2.0","id":1,"method":"getAccountInfo",
            "params":["$pubkey",{"encoding":"base64"}]}"""
        val res = Chain.postTo(MAINNET, body) ?: return null
        return runCatching {
            val value = JSONObject(res).getJSONObject("result").optJSONObject("value") ?: return null
            Base64.decode(value.getJSONArray("data").getString(0), Base64.DEFAULT)
        }.getOrNull()
    }
}
