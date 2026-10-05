package so.duffle.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/**
 * The label this app writes itself when a scanned QR carried no name (`ScanActivity`).
 *
 * It is not a name anybody gave: shown as-is it reads as a person called "Scanned code", and
 * their avatar becomes an "S". Kept as one literal here so the writer and every reader agree.
 */
internal const val SCANNED_PLACEHOLDER = "Scanned code"

/**
 * The label "Pay anyone" passes on for a pasted wallet address, which has no name either.
 *
 * It is right on the payment sheet, where it says what was typed. Stored as a contact's name it
 * made every pasted address a person called "Wallet address" with a "W" for a face, all of them
 * alike, so it is read back as a placeholder exactly as [SCANNED_PLACEHOLDER] is.
 */
internal const val ADDRESS_PLACEHOLDER = "Wallet address"

/** How many leading characters of an address stand in for a name nobody gave. */
private const val ADDRESS_START = 6

/** True when [label] is the app's own placeholder rather than a name — see [SCANNED_PLACEHOLDER]. */
internal fun isPlaceholderName(label: String): Boolean =
    label.trim().let { it == SCANNED_PLACEHOLDER || it == ADDRESS_PLACEHOLDER }

/** The start of an address, with no ellipsis: short enough to recognise, too short to pretend to be a name. */
internal fun addressStart(address: String): String = address.trim().take(ADDRESS_START)

/**
 * The text to show for somebody, for string contexts (thread titles, notifications, history).
 *
 * A real name always wins and is returned unchanged; only the placeholder is swapped for the
 * address start. Row UI additionally draws a scan mark after it (see `NameLine`), which plain
 * text cannot, so the address start alone is what a string gets.
 */
internal fun displayName(label: String, address: String): String =
    if (isPlaceholderName(label)) addressStart(address) else label

/**
 * The name an address owns on chain — `dezloper.skr`, `toly.sol` — or nothing.
 *
 * An address is the one thing on screen nobody can read, and this is the only tier of identity
 * in the app that earns a tick. The reason is where it comes from: **we derive it ourselves from
 * the address, using nothing the other phone sent us.** A name arriving over NFC is a claim; a
 * name derived from an address is a fact. Everything else the app shows about a person is the
 * former, and is drawn differently on purpose.
 *
 * Two registries, tried in this order:
 *  - **`.skr`** — AllDomains (ANS). Every Seeker gets one with the device, so for the phones
 *    this app is built for the name is free and already there.
 *  - **`.sol`** — Solana Name Service. Bought, roughly $20 one-time, for everyone else.
 *
 * Both live on **mainnet** while payments run on devnet, the same split [Genesis] already lives
 * with and for the same reason: a devnet lookup would tell every owner they own nothing.
 *
 * Fails closed throughout. A network failure is [Name.Unreachable] and never [Name.None] —
 * "we could not ask" and "they have no name" put different words on screen, and a wallet queried
 * over a bad connection must not lose the name it has.
 *
 * No SDK. AllDomains ships a TypeScript library and this is Kotlin, but every step is a derived
 * address and a plain account read, so it is arithmetic rather than a dependency. Every constant
 * below was read off mainnet before being written down, and [NamesTest] re-derives the ones that
 * can be derived — a wrong seed does not throw, it just quietly names nobody.
 */
object Names {

    /** What the chain says an address is called. */
    sealed interface Name {
        /** A name nobody can forge, e.g. "dezloper.skr". */
        data class Domain(val text: String) : Name
        /** Asked, and they own none. */
        data object None : Name
        /** Could not ask. Not the same as [None], and must never be shown as one. */
        data object Unreachable : Name
    }

    private const val MAINNET = "https://api.mainnet-beta.solana.com"

    // ---- AllDomains / ANS, which serves .skr -------------------------------------------------
    private const val ANS = "ALTNSZ46uaAUU7XUV6awvdorLGqAsPwa9shm7h4uP2FK"
    private const val TLD_HOUSE = "TLDHkysf5pCnKsVA4gXpNvmy7psXLPEu4LAdDJthT9S"
    /** The `.skr` TLD's name account — the parent of every real `.skr` record. */
    private const val SKR_TLD = "F3A8kuikEiu6k2399oSJ1PWfcJYDHqpwoQ2e8psSDNuF"
    /** The `.skr` TLD house, the class every `.skr` reverse record is filed under. */
    private const val SKR_HOUSE = "4RKP4BEMu5sXBfXSH7xN2owtQrnAJvhhwtBBmj9JEYkA"
    private const val ANS_PREFIX = "ALT Name Service"
    /** disc 8 + parent 32 + owner 32 + class 32 + expires 8 + created 8 + flag 1 + pad 79 */
    private const val ANS_RECORD = 200
    private const val ANS_OWNER_AT = 40
    private const val ANS_PARENT_AT = 8
    private const val ANS_EXPIRES_AT = 104

    // ---- Solana Name Service, which serves .sol ----------------------------------------------
    private const val SNS = "namesLPneVptA9Z5rqUDD9tMTWEJwofgaYwp8cawRkX"
    private const val SOL_TLD = "58PwtjSDuFHuUkYjH9BYnnQKHfwo9reZhC2zMJv9JPkx"
    /**
     * SNS files every reverse record under one class rather than under a per-TLD house.
     *
     * This app previously hashed the *wallet address* under the `.sol` root instead, which
     * derives an account that has never existed — so the lookup always returned null and every
     * contact looked nameless. It failed by showing nothing, which is why it went unnoticed.
     */
    private const val SNS_REVERSE_CLASS = "33m47vH6Eav6jr5Ry86XjhRft2jRBLDnDgPSHoquXi2Z"
    private const val SNS_PREFIX = "SPL Name Service"
    /** parent 32 + owner 32 + class 32, then the payload. No discriminator: SNS predates Anchor. */
    private const val SNS_RECORD = 96
    private const val SNS_OWNER_AT = 32

    private val cache = mutableMapOf<String, Name>()

    /**
     * The name this address owns, or why there isn't one.
     *
     * Only definite answers are cached. An [Name.Unreachable] is never stored, so a lookup that
     * failed on a flaky connection is asked again rather than remembered as the truth.
     */
    suspend fun of(address: String): Name {
        synchronized(cache) { cache[address] }?.let { return it }
        val answer = withContext(Dispatchers.IO) { resolve(address) }
        if (answer !is Name.Unreachable) synchronized(cache) { cache[address] = answer }
        // Worth a line: a name that fails to resolve looks exactly like an address that has
        // none, and the difference is invisible from the screen. Logged once per address,
        // because only a cache miss reaches here.
        android.util.Log.i("Names", "${address.take(6)}… → $answer")
        return answer
    }

    /** What typing a name into "Pay anyone" came to. */
    sealed interface Resolved {
        data class Found(val address: String, val name: String) : Resolved
        /** Asked, and nobody owns it (or it has expired). */
        data object None : Resolved
        data object Unreachable : Resolved
        /** Resolved to something that is not an ordinary wallet, so money must not be sent to it. */
        data object Unsafe : Resolved
    }

    /**
     * The wallet that owns `name.skr` or `name.sol` — the other direction from [of].
     *
     * The name account is derived from the name alone, so nothing here trusts anyone's word for
     * who owns what; the owner is read off the chain. The owner must also be an ordinary wallet:
     * a **wrapped** domain is owned by a program's escrow, and sending money there would lose it,
     * so it is refused rather than paid.
     */
    suspend fun addressOf(raw: String): Resolved = withContext(Dispatchers.IO) {
        val text = raw.trim().lowercase()
        val tld = Tld.entries.firstOrNull { text.endsWith(it.suffix) } ?: return@withContext Resolved.None
        val label = text.removeSuffix(tld.suffix)
        if (label.isEmpty() || label.length > 63 || !label.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            return@withContext Resolved.None
        }
        val parent = if (tld == Tld.SKR) SKR_TLD else SOL_TLD
        val pda = Pda.find(listOf(hash(tld.prefix, label), ByteArray(32), Base58.decode(parent)), tld.program)
        val data = accountData(pda) ?: return@withContext Resolved.Unreachable
        if (data.size < tld.headerSize) return@withContext Resolved.None
        if (tld == Tld.SKR && expired(data)) return@withContext Resolved.None
        val owner = Base58.encode(data.copyOfRange(tld.ownerAt, tld.ownerAt + 32))
        // An ordinary wallet is owned by the System Program or does not exist yet.
        val info = post("""{"jsonrpc":"2.0","id":1,"method":"getAccountInfo","params":["$owner",{"encoding":"base64"}]}""")
            ?: return@withContext Resolved.Unreachable
        val value = try { JSONObject(info).getJSONObject("result").opt("value") } catch (e: Exception) { return@withContext Resolved.Unreachable }
        if (value is JSONObject && value.optString("owner") != "11111111111111111111111111111111") return@withContext Resolved.Unsafe
        Resolved.Found(owner, text)
    }

    /** A person a typed name could mean: the full name, the wallet behind it, and which registry said so. */
    data class Candidate(val name: String, val address: String)

    /**
     * Everyone `text` could be, across `.skr`, `.sol` and `.air`, for the "Pay anyone" box.
     *
     * Typing `fahad` asks all three registries for `fahad`; typing `fahad.s` asks only the two that
     * start with s; `fahad.skr` asks one. `.skr` and `.sol` are read off the chain by their exact
     * name, so only a full name resolves. `.air` is AirPay's own directory and also answers a
     * half-typed name. A pasted wallet address is its own single candidate.
     */
    suspend fun candidates(raw: String): List<Candidate> = lookup(raw).people

    /**
     * What a "Pay anyone" lookup came to: who it could be, and when that is nobody, why.
     *
     * The two reasons are kept because an empty list alone put "No one found. Check the
     * spelling." under a name that was spelled perfectly: one that could not be asked about on
     * a bad connection ([unreachable]), or one that resolved to something that must not be paid
     * ([unsafe]).
     */
    data class Lookup(
        val people: List<Candidate>,
        val unreachable: Boolean = false,
        val unsafe: Boolean = false,
    )

    /** [candidates], with the reason kept when the answer is nobody. */
    suspend fun lookup(raw: String): Lookup = coroutineScope {
        val typed = raw.trim()
        val text = typed.lowercase()
        if (text.length < 2) return@coroutineScope Lookup(emptyList())
        if (PayRequest.isBase58Address(typed)) {
            // A pasted address is held to the rule a name's owner is held to. It used to be
            // offered as it came, and the address people most often paste by mistake is a
            // token account or a mint: money sent there is not coming back.
            return@coroutineScope when (withContext(Dispatchers.IO) { ordinaryWallet(typed) }) {
                true -> Lookup(listOf(Candidate(typed, typed)))
                false -> Lookup(emptyList(), unsafe = true)
                null -> Lookup(emptyList(), unreachable = true)
            }
        }
        val base = text.substringBefore('.')
        val suffix = if ('.' in text) text.substringAfter('.') else ""
        if (base.length < 2) return@coroutineScope Lookup(emptyList())
        val chain = listOf("skr", "sol").filter { it.startsWith(suffix) }.map { tld ->
            async { addressOf("$base.$tld") }
        }
        val air = if ("air".startsWith(suffix)) async {
            ServerClient.airNames(base).map { Candidate(it.first, it.second) }
        } else null
        val answers = chain.awaitAll()
        val people = answers.filterIsInstance<Resolved.Found>().map { Candidate(it.name, it.address) } +
            (air?.await() ?: emptyList())
        Lookup(
            people = people.distinctBy { it.name },
            unreachable = answers.any { it is Resolved.Unreachable },
            unsafe = answers.any { it is Resolved.Unsafe },
        )
    }

    /**
     * Whether [address] is somewhere money can safely go: an account the System Program owns,
     * or one that does not exist yet and that a person could hold the key to. Null when the
     * chain could not be asked, which is never a yes.
     *
     * Asked through [Chain.rpc], so it has the server's provider behind it and is not at the
     * mercy of the public endpoint's rate limit the way the name lookups are.
     */
    private fun ordinaryWallet(address: String): Boolean? {
        val info = Chain.rpc(
            """{"jsonrpc":"2.0","id":1,"method":"getAccountInfo","params":["$address",{"encoding":"base64","dataSlice":{"offset":0,"length":0}}]}"""
        ) ?: return null
        val value = try { JSONObject(info).getJSONObject("result").opt("value") } catch (e: Exception) { return null }
        if (value is JSONObject) return value.optString("owner") == SYSTEM_PROGRAM
        // Nothing there yet, which is what a new wallet looks like. An address off the curve
        // has no private key, so it is not a wallet waiting for its first payment: it is a
        // program's address, most often a token account nobody has opened.
        return try {
            val bytes = Base58.decode(address)
            bytes.size == 32 && Pda.onCurve(bytes)
        } catch (e: Exception) {
            false
        }
    }

    private const val SYSTEM_PROGRAM = "11111111111111111111111111111111"

    /** "dezloper.skr", or null when there is no name or no answer. For places that show one line. */
    suspend fun label(address: String): String? = (of(address) as? Name.Domain)?.text

    /**
     * The name behind an address, following a verified link when the address has none itself.
     *
     * A name lives on one wallet. Pay somebody's second wallet and resolving it finds nothing,
     * even though you know exactly who they are — so a payload can carry a signature from the
     * wallet that *does* hold the name, vouching for this one ([Vouch]).
     *
     * The link is checked before we get here, and the name still comes off the chain: the
     * signature only says *which address to look up*. Nothing in the payload names anybody.
     */
    suspend fun of(address: String, link: Vouch.Link?): Found {
        when (val direct = of(address)) {
            is Name.Domain -> return Found(direct.text, borrowed = false)
            Name.Unreachable -> return Found(null, borrowed = false)
            Name.None -> Unit
        }
        // Only a link that already verified against THIS address is worth following.
        if (link == null || link.wallet != address) return Found(null, borrowed = false)
        val owner = of(link.owner) as? Name.Domain ?: return Found(null, borrowed = false)
        return Found(owner.text, borrowed = true)
    }

    /**
     * A resolved name, and whether it came from the address itself or from one vouching for it.
     *
     * [borrowed] is kept because the two are not the same claim and a screen should not pretend
     * they are: "this address owns dezloper.skr" and "this address belongs to whoever owns
     * dezloper.skr" differ, and the second is the one worth spelling out.
     */
    data class Found(val text: String?, val borrowed: Boolean)

    /** Forget an address, so the next look asks the chain again. */
    fun forget(address: String) {
        synchronized(cache) { cache.remove(address) }
    }

    private fun resolve(address: String): Name {
        var answered = false

        // 1. The main domain: one derived address, one read, and the name comes back as plain
        //    text. It is also the only path that answers "which one" when somebody owns several,
        //    because it is the one they nominated.
        when (val main = mainDomain(address)) {
            is Name.Domain -> return main
            Name.None -> answered = true
            Name.Unreachable -> Unit
        }

        // 2. Nobody nominated one — the common case, because owning a .skr and setting it as your
        //    main domain are two separate actions and setting up a Seeker only does the first.
        when (val skr = owned(address, Tld.SKR)) {
            is Name.Domain -> return skr
            Name.None -> answered = true
            Name.Unreachable -> Unit
        }

        when (val sol = owned(address, Tld.SOL)) {
            is Name.Domain -> return sol
            Name.None -> answered = true
            Name.Unreachable -> Unit
        }

        // Only say "no name" if at least one registry actually answered.
        return if (answered) Name.None else Name.Unreachable
    }

    /** The two registries, which differ in more places than they agree. */
    private enum class Tld(
        val suffix: String,
        val program: String,
        val prefix: String,
        val ownerAt: Int,
        val headerSize: Int,
        val reverseClass: String,
    ) {
        SKR(".skr", ANS, ANS_PREFIX, ANS_OWNER_AT, ANS_RECORD, SKR_HOUSE),
        SOL(".sol", SNS, SNS_PREFIX, SNS_OWNER_AT, SNS_RECORD, SNS_REVERSE_CLASS),
    }

    // -- the nominated name ---------------------------------------------------------------------

    /**
     * The domain this address nominated as its main one.
     *
     * Layout: discriminator 8, name account 32, then two Borsh strings — the TLD (".skr") and the
     * name ("dezloper"). Those plain strings are why this is tried first; every other route ends
     * at a hashed account and needs a second read to get the name back.
     *
     * It is re-checked against the chain before being believed. A main domain is a pointer the
     * owner set once, and it keeps pointing after the domain is sold, transferred or expires.
     */
    private fun mainDomain(address: String): Name {
        val pda = Pda.find(listOf("main_domain".toByteArray(), Base58.decode(address)), TLD_HOUSE)
        val data = accountData(pda) ?: return Name.Unreachable
        if (data.isEmpty() || data.size < 48) return Name.None      // never nominated one
        return try {
            val nameAccount = Base58.encode(data.copyOfRange(8, 40))
            var at = 40
            val tld = data.borshString(at).also { at += 4 + it.toByteArray().size }
            val domain = data.borshString(at)
            val full = clean(domain, tld) ?: return Name.None
            when (stillOwns(address, nameAccount, if (tld == Tld.SOL.suffix) Tld.SOL else Tld.SKR)) {
                true -> Name.Domain(full)
                false -> Name.None                                   // sold or expired since
                null -> Name.Unreachable
            }
        } catch (e: Exception) {
            Name.None
        }
    }

    // -- names the address holds ----------------------------------------------------------------

    /** A name this address owns under [tld], read back to plain text through its reverse record. */
    private fun owned(address: String, tld: Tld): Name {
        // ANS can filter on the parent, so it returns .skr records only. SNS cannot — its owner
        // field sits where a filter can reach but its parent does not — so those are checked one
        // at a time below and anything that is not a second-level .sol is skipped.
        val parent = if (tld == Tld.SKR) ANS_PARENT_AT to SKR_TLD else null
        val records = ownedRecords(tld.program, tld.ownerAt, address, parent) ?: return Name.Unreachable
        for (record in records) {
            val data = accountData(record) ?: return Name.Unreachable
            if (data.size < tld.headerSize) continue
            if (tld == Tld.SOL && Base58.encode(data.copyOfRange(0, 32)) != SOL_TLD) continue
            if (tld == Tld.SKR && expired(data)) continue
            val name = reverse(record, tld) ?: return Name.Unreachable
            clean(name, tld.suffix)?.let { return Name.Domain(it) }
        }
        return Name.None
    }

    /**
     * Name accounts a wallet owns, by asking the registry to filter on its owner field.
     *
     * A **wrapped** domain is invisible here, deliberately. Wrapping moves the record's ownership
     * to an escrow account and leaves the wallet holding an NFT instead, so the wallet is no
     * longer the owner the registry knows about. Following that trail means walking the NFT and
     * its mint, and getting it wrong means printing somebody else's name next to a payment.
     * Until that is built and tested it reports no name, which is the safe half of the mistake.
     */
    private fun ownedRecords(
        program: String,
        ownerAt: Int,
        owner: String,
        parent: Pair<Int, String>?,
    ): List<String>? {
        val filters = buildString {
            append("""{"memcmp":{"offset":$ownerAt,"bytes":"$owner"}}""")
            parent?.let { (at, account) -> append(""",{"memcmp":{"offset":$at,"bytes":"$account"}}""") }
        }
        val res = post(
            """
            {"jsonrpc":"2.0","id":1,"method":"getProgramAccounts","params":["$program",
             {"encoding":"base64","dataSlice":{"offset":0,"length":0},"filters":[$filters]}]}
            """.trimIndent()
        ) ?: return null
        return try {
            val arr = JSONObject(res).optJSONArray("result") ?: return null
            buildList { for (i in 0 until arr.length()) add(arr.getJSONObject(i).getString("pubkey")) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * A name account read back to text.
     *
     * The account is a hash of the name, so it cannot be reversed by arithmetic. Both registries
     * write a second account keyed by the *name account's own address* whose payload is the plain
     * name — ANS puts it after the record, SNS length-prefixes it.
     */
    private fun reverse(nameAccount: String, tld: Tld): String? {
        val pda = Pda.find(
            listOf(hash(tld.prefix, nameAccount), Base58.decode(tld.reverseClass), ByteArray(32)),
            tld.program,
        )
        val data = accountData(pda) ?: return null
        if (data.size <= tld.headerSize) return ""
        return try {
            if (tld == Tld.SKR) String(data, ANS_RECORD, data.size - ANS_RECORD).trimEnd('\u0000')
            else data.borshString(SNS_RECORD)
        } catch (e: Exception) {
            ""
        }
    }

    // -- checks ---------------------------------------------------------------------------------

    /** True, false, or null when the chain could not be asked. */
    private fun stillOwns(address: String, nameAccount: String, tld: Tld): Boolean? {
        val data = accountData(nameAccount) ?: return null
        if (data.size < tld.headerSize) return false
        if (tld == Tld.SKR && expired(data)) return false
        return Base58.encode(data.copyOfRange(tld.ownerAt, tld.ownerAt + 32)) == address
    }

    /** `expires_at` of 0 means never, which is what a Seeker's free `.skr` carries. */
    private fun expired(record: ByteArray): Boolean {
        if (record.size < ANS_EXPIRES_AT + 8) return false
        var seconds = 0L
        for (i in 7 downTo 0) seconds = (seconds shl 8) or (record[ANS_EXPIRES_AT + i].toLong() and 0xFF)
        return seconds != 0L && seconds * 1000L < System.currentTimeMillis()
    }

    /**
     * A name is about to be printed beside an amount of money, so it is checked before it is
     * believed: a plausible length, no whitespace, no control characters, and no second dot that
     * would let a record render as a different domain than it is.
     */
    private fun clean(name: String, suffix: String): String? {
        val n = name.trim().trimEnd('\u0000')
        if (n.isEmpty() || n.length > 64) return null
        if (n.any { it.isWhitespace() || it.code < 0x20 || it == '.' }) return null
        return n + suffix
    }

    // -- plumbing -------------------------------------------------------------------------------

    /** An account's bytes, empty when the account does not exist, null when unreachable. */
    private fun accountData(pubkey: String): ByteArray? {
        val res = post(
            """{"jsonrpc":"2.0","id":1,"method":"getAccountInfo","params":["$pubkey",{"encoding":"base64"}]}"""
        ) ?: return null
        return try {
            val result = JSONObject(res).optJSONObject("result") ?: return null
            if (result.isNull("value")) return ByteArray(0)
            Base64.getDecoder().decode(result.getJSONObject("value").getJSONArray("data").getString(0))
        } catch (e: Exception) {
            null
        }
    }

    private fun post(json: String): String? = Chain.postTo(MAINNET, json)

    private fun hash(prefix: String, name: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest((prefix + name).toByteArray(Charsets.UTF_8))

    /** Borsh string: a little-endian u32 length, then that many UTF-8 bytes. */
    private fun ByteArray.borshString(at: Int): String {
        if (at + 4 > size) return ""
        var length = 0
        for (i in 3 downTo 0) length = (length shl 8) or (this[at + i].toInt() and 0xFF)
        if (length !in 1..64 || at + 4 + length > size) return ""
        return String(this, at + 4, length, Charsets.UTF_8)
    }

    /**
     * Program-derived addresses.
     *
     * A PDA is the first hash, counting the bump down from 255, that is **not** a valid ed25519
     * public key — an address nobody can hold a key for, and therefore one only the program can
     * sign for. Deriving one needs the curve check, which is the only real arithmetic here.
     *
     * Hand-rolled because sol4k's `findProgramAddress` takes its seeds as `PublicKey`s, so every
     * seed has to be 32 bytes; half the seeds below are short strings like "main_domain".
     */
    internal object Pda {
        private val P = BigInteger.TWO.pow(255) - BigInteger.valueOf(19)
        private val D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
        private val SQRT_M1 = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)

        fun find(seeds: List<ByteArray>, program: String): String {
            val programBytes = Base58.decode(program)
            for (bump in 255 downTo 0) {
                val digest = MessageDigest.getInstance("SHA-256")
                seeds.forEach { digest.update(it) }
                digest.update(byteArrayOf(bump.toByte()))
                digest.update(programBytes)
                digest.update("ProgramDerivedAddress".toByteArray())
                val candidate = digest.digest()
                if (!onCurve(candidate)) return Base58.encode(candidate)
            }
            error("no off-curve bump")
        }

        /** Does this decompress to a point on ed25519? If it does, it is a real key, not a PDA. */
        fun onCurve(bytes: ByteArray): Boolean {
            var y = BigInteger(1, bytes.reversedArray())
            val negative = y.testBit(255)
            y = y.clearBit(255)
            if (y >= P) return false

            val yy = y.multiply(y).mod(P)
            val u = yy.subtract(BigInteger.ONE).mod(P)
            val v = D.multiply(yy).add(BigInteger.ONE).mod(P)
            if (v.signum() == 0) return false

            // The standard candidate root: x = (u·v³)·(u·v⁷)^((p−5)/8)
            val v3 = v.modPow(BigInteger.valueOf(3), P)
            val v7 = v.modPow(BigInteger.valueOf(7), P)
            var x = u.multiply(v3).mod(P)
                .multiply(
                    u.multiply(v7).mod(P)
                        .modPow(P.subtract(BigInteger.valueOf(5)).divide(BigInteger.valueOf(8)), P)
                )
                .mod(P)

            val vxx = v.multiply(x).multiply(x).mod(P)
            when (vxx) {
                u -> Unit
                u.negate().mod(P) -> x = x.multiply(SQRT_M1).mod(P)   // the other root
                else -> return false
            }
            return !(x.signum() == 0 && negative)
        }
    }

    /** Base58 for 32-byte keys, because nothing in the platform speaks it. */
    internal object Base58 {
        private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        fun decode(s: String): ByteArray {
            var n = BigInteger.ZERO
            for (c in s) {
                val digit = ALPHABET.indexOf(c)
                require(digit >= 0) { "not base58" }
                n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
            }
            val body = n.toByteArray().let {
                if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
            }
            // Left-pad to 32, so a key whose first bytes are zero keeps its width instead of
            // silently becoming a shorter, different key.
            return if (body.size >= 32) body else ByteArray(32 - body.size) + body
        }

        fun encode(bytes: ByteArray): String {
            var n = BigInteger(1, bytes)
            val sb = StringBuilder()
            val base = BigInteger.valueOf(58)
            while (n.signum() > 0) {
                val (q, r) = n.divideAndRemainder(base)
                sb.append(ALPHABET[r.toInt()])
                n = q
            }
            for (b in bytes) { if (b.toInt() != 0) break; sb.append('1') }
            return sb.reverse().toString()
        }
    }
}
