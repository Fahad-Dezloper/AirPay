package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Known-answer tests for the arithmetic behind [Names].
 *
 * The lookups themselves talk to mainnet and cannot run here, but everything that decides
 * *which account to read* is pure: base58, SHA-256 and the ed25519 curve check. Getting any of
 * it subtly wrong does not throw — it derives a different address, the read comes back empty,
 * and the app quietly reports "no name" for somebody who has one. That failure is invisible in
 * a demo and impossible to debug from the UI, which is exactly why it is pinned here.
 *
 * Every expected value below is an account that exists on Solana mainnet today. They were read
 * off the chain before being written down, so this file is a real cross-check and not a
 * restatement of the implementation.
 */
class NamesTest {

    private val ans = "ALTNSZ46uaAUU7XUV6awvdorLGqAsPwa9shm7h4uP2FK"
    private val tldHouse = "TLDHkysf5pCnKsVA4gXpNvmy7psXLPEu4LAdDJthT9S"
    private val sns = "namesLPneVptA9Z5rqUDD9tMTWEJwofgaYwp8cawRkX"
    private val solTld = "58PwtjSDuFHuUkYjH9BYnnQKHfwo9reZhC2zMJv9JPkx"

    private fun hash(prefix: String, name: String): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest((prefix + name).toByteArray())

    @Test fun `base58 round trips a real address`() {
        val address = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
        val bytes = Names.Base58.decode(address)
        assertEquals(32, bytes.size)
        assertEquals(address, Names.Base58.encode(bytes))
    }

    @Test fun `base58 keeps leading zero bytes`() {
        // The system program is 32 zero bytes and encodes as 32 ones. Drop the padding and it
        // silently becomes a different, shorter key.
        val systemProgram = "1".repeat(32)
        assertEquals(32, Names.Base58.decode(systemProgram).size)
        assertEquals(systemProgram, Names.Base58.encode(ByteArray(32)))
    }

    @Test fun `a real wallet address is on the curve`() {
        // A wallet someone holds a key for must decompress to a point; if this said false, every
        // PDA derivation below would stop one bump early and land on the wrong account.
        assertTrue(Names.Pda.onCurve(Names.Base58.decode("4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D")))
        assertTrue(Names.Pda.onCurve(Names.Base58.decode("GT2zuHVaZQYZSyQMgJPLzvkmyztfyXg2NJunqFp4p3A4")))
    }

    @Test fun `a derived address is off the curve`() {
        assertFalse(Names.Pda.onCurve(Names.Base58.decode("3mX9b4AZaQehNoQGfckVcmgmA6bkBoFcbLj9RMmMyNcU")))
    }

    @Test fun `derives the AllDomains origin account`() {
        // AllDomains publishes this one as a constant, so it is the cleanest check that the
        // hash prefix, the seed order and the bump search all agree with the real program.
        val origin = Names.Pda.find(
            listOf(hash("ALT Name Service", "ANS"), ByteArray(32), ByteArray(32)), ans,
        )
        assertEquals("3mX9b4AZaQehNoQGfckVcmgmA6bkBoFcbLj9RMmMyNcU", origin)
    }

    @Test fun `derives the skr TLD account`() {
        val origin = Names.Pda.find(
            listOf(hash("ALT Name Service", "ANS"), ByteArray(32), ByteArray(32)), ans,
        )
        val skr = Names.Pda.find(
            listOf(hash("ALT Name Service", ".skr"), ByteArray(32), Names.Base58.decode(origin)), ans,
        )
        // The parent of every real .skr record on mainnet, including dezloper.skr.
        assertEquals("F3A8kuikEiu6k2399oSJ1PWfcJYDHqpwoQ2e8psSDNuF", skr)
    }

    @Test fun `derives the skr TLD house`() {
        // The leading dot matters — "skr" without it derives a different, empty house, and then
        // every reverse lookup returns nothing and every Seeker looks nameless.
        val house = Names.Pda.find(listOf("tld_house".toByteArray(), ".skr".toByteArray()), tldHouse)
        assertEquals("4RKP4BEMu5sXBfXSH7xN2owtQrnAJvhhwtBBmj9JEYkA", house)
    }

    @Test fun `derives a known sol domain account`() {
        // bonfida.sol, which has resolved to the same account since SNS launched.
        val account = Names.Pda.find(
            listOf(hash("SPL Name Service", "bonfida"), ByteArray(32), Names.Base58.decode(solTld)),
            sns,
        )
        assertEquals("Crf8hzfthWGbGbLTVCiqRqV5MVnbpHB1L9KQMd6gsinb", account)
    }

    @Test fun `derives the reverse record that names a sol domain`() {
        val account = "Crf8hzfthWGbGbLTVCiqRqV5MVnbpHB1L9KQMd6gsinb"
        val reverse = Names.Pda.find(
            listOf(
                hash("SPL Name Service", account),
                Names.Base58.decode("33m47vH6Eav6jr5Ry86XjhRft2jRBLDnDgPSHoquXi2Z"),
                ByteArray(32),
            ),
            sns,
        )
        // Reading this account on mainnet yields the string "bonfida".
        assertEquals("DqgmWxe2PPrfy45Ja3UPyFGwcbRzkRuwXt3NyxjX8krg", reverse)
    }

    @Test fun `derives the reverse record that names a skr domain`() {
        val account = "4zgrRPw9fEPCxDP88M5vURzBowzGPkwDmt2XwL9c7jN7"   // dezloper.skr
        val reverse = Names.Pda.find(
            listOf(
                hash("ALT Name Service", account),
                Names.Base58.decode("4RKP4BEMu5sXBfXSH7xN2owtQrnAJvhhwtBBmj9JEYkA"),
                ByteArray(32),
            ),
            ans,
        )
        // Reading this account on mainnet yields the string "dezloper".
        assertEquals("EL9nHmogHcR2BcDCfWhVsDpCMUtQ1GFzcQrFJxCZLW5i", reverse)
    }

    // -- usernames ---------------------------------------------------------------------------
    //
    // A username is the weakest tier of identity here and these rules are what stop it
    // impersonating the strongest one. Positives and negatives both, because a rule that
    // refuses everything passes any test made only of things it should refuse.

    @Test fun `a username may not wear a domain's clothes`() {
        // The attack: call yourself dezloper.skr and inherit a tick you never earned.
        assertNull(Username.clean("dezloper.skr"))
        assertNull(Username.clean("dominos.sol"))
        assertNull(Username.clean("a.b"))
        assertTrue(Username.looksLikeDomain("x.sol"))
        assertNotNull(Username.problem("dezloper.skr"))
    }

    @Test fun `ordinary names are accepted`() {
        assertEquals("Fahad", Username.clean("Fahad"))
        assertEquals("Fahad", Username.clean("  Fahad  "))
        assertEquals("chai guy", Username.clean("chai guy"))
        assertEquals("Ravi K", Username.clean("Ravi K"))
        // Nothing wrong with these, so nothing to complain about under the box.
        assertNull(Username.problem("Fahad"))
        assertNull(Username.problem("chai guy"))
    }

    @Test fun `unusable names are refused`() {
        assertNull(Username.clean(""))
        assertNull(Username.clean("   "))
        assertNull(Username.clean("x".repeat(Username.MAX + 1)))
        assertNull(Username.clean("two\nlines"))
        // An empty box is unfinished, not wrong — it must not show an error.
        assertNull(Username.problem(""))
        assertNotNull(Username.problem("x".repeat(Username.MAX + 1)))
    }

    @Test fun `the wire refuses a name shaped like a domain`() {
        val address = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
        // A modified client can put anything on the radio, so the receiving phone checks too.
        assertNotNull(TapCard(address, "dezloper.skr").validate())
        assertNull(TapCard(address, "Fahad").validate())
        assertNull(TapCard(address, "").validate())
    }

    @Test fun `derives the main domain pointer for a wallet`() {
        val pda = Names.Pda.find(
            listOf(
                "main_domain".toByteArray(),
                Names.Base58.decode("4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"),
            ),
            tldHouse,
        )
        assertEquals("45ntU6gttcg1h3CEn9UtUqe8haTNxz4j5Vu6ohyktL2Y", pda)
    }
}
