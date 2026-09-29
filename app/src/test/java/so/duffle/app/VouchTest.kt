package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sol4k.Keypair
import org.sol4k.PublicKey
import java.time.LocalDate

/**
 * The link signature, which is the only thing that lets a tick follow you to a second wallet.
 *
 * This is the highest-value known-answer test in the app, because both ways of getting it wrong
 * are silent. Verify too strictly and every linked wallet quietly loses its name, which looks
 * like a network problem. Verify too loosely and anybody can paint somebody else's verified
 * name onto their own address — and nothing on screen would say so.
 *
 * So the negatives matter more than the positives here, and there are more of them: a rule that
 * refuses everything passes any test made only of things it should accept.
 *
 * Real ed25519 throughout — sol4k keypairs, real signatures — because a mocked signature would
 * prove only that the mock agrees with itself.
 */
class VouchTest {

    private val owner = Keypair.generate()
    private val ownerAddress = owner.publicKey.toBase58()
    private val wallet = Keypair.generate().publicKey.toBase58()
    private val day = LocalDate.of(2027, 3, 28).toEpochDay()

    private fun signedBy(k: Keypair, o: String, w: String, d: Long): String =
        Vouch.encode(k.sign(Vouch.message(o, w, d)))

    // -- the library's contract ----------------------------------------------------------------

    @Test fun `sol4k verify takes the signature first and the message second`() {
        // Pinned empirically rather than assumed. PublicKey.verify(ByteArray, ByteArray) gives
        // no hint which is which, and Vouch.check is built on this order being right.
        val message = "hello".toByteArray()
        val signature = owner.sign(message)
        assertTrue(owner.publicKey.verify(signature, message))
        // And the other way round must NOT quietly pass, or the argument order would be a
        // coin flip that happens to work.
        assertFalse(PublicKey(ownerAddress).verify(message, signature))
    }

    // -- the happy path ------------------------------------------------------------------------

    @Test fun `a real signature from the owner verifies`() {
        val link = Vouch.check(ownerAddress, wallet, day, signedBy(owner, ownerAddress, wallet, day))
        assertNotNull(link)
        assertEquals(ownerAddress, link!!.owner)
        assertEquals(wallet, link.wallet)
        assertEquals(LocalDate.of(2027, 3, 28), link.expiresOn)
    }

    @Test fun `the message is byte-for-byte what both phones build`() {
        // The signer and the verifier are different devices and different builds. If this
        // string ever drifts, links sign cleanly and verify nowhere.
        assertEquals(
            "AirPay wallet link v1\nowner=$ownerAddress\nwallet=$wallet\nexpires=2027-03-28",
            String(Vouch.message(ownerAddress, wallet, day)),
        )
    }

    // -- the attacks ---------------------------------------------------------------------------

    @Test fun `a signature for one wallet cannot be replayed for another`() {
        // THE attack this design exists to stop. Someone taps you, keeps the payload, and
        // presents that signature beside their own address to wear your name.
        val real = signedBy(owner, ownerAddress, wallet, day)
        val attacker = Keypair.generate().publicKey.toBase58()
        assertNull(Vouch.check(ownerAddress, attacker, day, real))
    }

    @Test fun `somebody else's signature does not verify`() {
        val impostor = Keypair.generate()
        // Correctly signed — by the wrong key, over a statement naming the real owner.
        assertNull(Vouch.check(ownerAddress, wallet, day, signedBy(impostor, ownerAddress, wallet, day)))
    }

    @Test fun `the expiry cannot be stretched after signing`() {
        val real = signedBy(owner, ownerAddress, wallet, day)
        assertNull(Vouch.check(ownerAddress, wallet, day + 1, real))
        assertNull(Vouch.check(ownerAddress, wallet, day + 3650, real))
    }

    @Test fun `an expired link is refused`() {
        // UTC, because `Link.expired()` is UTC. With the local zone this passed for most of the
        // day and failed between 00:00 and 05:30 IST, where the local date is already tomorrow
        // and "yesterday" is still today in UTC — so the link it called expired was live.
        val past = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toEpochDay()
        val signed = signedBy(owner, ownerAddress, wallet, past)
        // Signed perfectly well — and still no.
        assertNotNull(PublicKey(ownerAddress))
        assertNull(Vouch.check(ownerAddress, wallet, past, signed))
    }

    @Test fun `a link valid until today still counts`() {
        val today = LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay()
        assertNotNull(Vouch.check(ownerAddress, wallet, today, signedBy(owner, ownerAddress, wallet, today)))
    }

    @Test fun `a wallet cannot vouch for itself`() {
        // It would verify — it is a real signature — and prove nothing at all.
        assertNull(Vouch.check(ownerAddress, ownerAddress, day, signedBy(owner, ownerAddress, ownerAddress, day)))
    }

    // -- hostile input -------------------------------------------------------------------------
    //
    // All of this arrives over NFC from a phone we do not control. None of it may throw.

    @Test fun `malformed input is refused rather than thrown`() {
        val real = signedBy(owner, ownerAddress, wallet, day)
        assertNull(Vouch.check(ownerAddress, wallet, day, "not base64 at all !!"))
        assertNull(Vouch.check(ownerAddress, wallet, day, ""))
        assertNull(Vouch.check(ownerAddress, wallet, day, Vouch.encode(ByteArray(63))))
        assertNull(Vouch.check(ownerAddress, wallet, day, Vouch.encode(ByteArray(64))))
        assertNull(Vouch.check("not-an-address", wallet, day, real))
        assertNull(Vouch.check(ownerAddress, "not-an-address", day, real))
        assertNull(Vouch.check("", "", 0, ""))
    }

    @Test fun `a flipped bit invalidates the signature`() {
        val raw = owner.sign(Vouch.message(ownerAddress, wallet, day))
        raw[0] = (raw[0].toInt() xor 1).toByte()
        assertNull(Vouch.check(ownerAddress, wallet, day, Vouch.encode(raw)))
    }

    @Test fun `base58 signatures convert, and rubbish does not`() {
        val raw = owner.sign(Vouch.message(ownerAddress, wallet, day))
        assertEquals(Vouch.encode(raw), Vouch.fromBase58(org.sol4k.Base58.encode(raw)))
        assertNull(Vouch.fromBase58("nonsense"))
        assertNull(Vouch.fromBase58(""))
    }

    // -- size ----------------------------------------------------------------------------------

    @Test fun `a linked card still fits in one tap`() {
        // A tap is a single APDU exchange with no chunking, so the payload has a ceiling. The
        // link adds an address, a date and a signature; this pins the cost so it cannot creep
        // past what a phone will hand over in one go.
        val card = TapCard(
            address = wallet,
            label = "Fahad",
            genesis = ownerAddress,
            brand = "oneplus",
            owner = ownerAddress,
            linkExpiry = day,
            linkSignature = signedBy(owner, ownerAddress, wallet, day),
        )
        val size = card.toBytes().size
        assertTrue("a linked card is ${size}B, which is too big for one tap", size < 512)
        assertNull(card.validate())
        // And it survives the round trip with the link intact.
        val back = TapCard.fromBytes(card.toBytes())
        assertNotNull(back)
        assertEquals(ownerAddress, back!!.owner)
        assertEquals(card.linkSignature, back.linkSignature)
    }
}
