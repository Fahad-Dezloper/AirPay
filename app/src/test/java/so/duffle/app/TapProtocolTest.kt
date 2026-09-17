package so.duffle.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tap with nobody pressing anything: an introduction that works with Tap closed.
 *
 * Covers the three things that have to be byte-exact for that to work on real phones — the
 * card payload, the HELLO command, and the NFC Forum Type 4 tag Android reads to launch us.
 */
class TapProtocolTest {

    private val me = TapCard("4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D", "Seeker", "8eYAaFEyuwxKwpa9qSbCTWj8VStg5MeAE9GPMyyMTfSn")
    private val request = PayRequest("2qxPntVgWyxU2jQz2MLcUuCp71D9D1ZSTcbFvCcXWrUc", "0.01", "SOL", "CPH2401", "GwQqYKapoH4mS1M4G8NFvLBWcWNQTpoJBGBDyKi92KrU")

    // -------------------------------------------------------------------------- payloads

    @Test fun `a card round-trips and is told apart from a request`() {
        val card = TapPayload.parse(me.toBytes())
        assertTrue(card is TapPayload.Card)
        assertEquals(me, (card as TapPayload.Card).card)
        val req = TapPayload.parse(request.toBytes())
        assertTrue(req is TapPayload.Request)
        assertEquals(request.reference, (req as TapPayload.Request).request.reference)
    }

    @Test fun `a card is never mistaken for a payable request`() {
        assertNull(PayRequest.fromBytes(me.toBytes()))
    }

    @Test fun `hostile cards are refused`() {
        assertNull(TapPayload.parse(TapCard("not-an-address", "x").toBytes()))
        assertNull(TapPayload.parse(TapCard(me.address, "x".repeat(200)).toBytes()))
        assertNull(TapPayload.parse(TapCard(me.address, "x", "bad-mint").toBytes()))
        assertNull(TapPayload.parse("""{"v":3,"k":"card","r":"${me.address}","l":"old"}""".toByteArray()))
    }

    // ----------------------------------------------------------------------------- HELLO

    @Test fun `hello carries the card and is recognised`() {
        val cmd = Apdu.hello(me)
        assertTrue(Apdu.isHello(cmd))
        assertFalse(Apdu.isSelect(cmd))
        assertEquals(me, TapCard.fromBytes(Apdu.data(cmd)!!))
    }

    @Test fun `a long card uses an extended length and still parses`() {
        val long = me.copy(label = "L".repeat(64), genesis = me.genesis)
        val big = TapCard(long.address, long.label, long.genesis)
        val cmd = Apdu.hello(big)
        if (big.toBytes().size > 255) assertEquals(0, cmd[4].toInt())
        assertEquals(big, TapCard.fromBytes(Apdu.data(cmd)!!))
    }

    @Test fun `truncated apdus are rejected rather than read past their end`() {
        val cmd = Apdu.hello(me)
        assertNull(Apdu.data(cmd.copyOf(cmd.size - 5)))
        assertNull(Apdu.data(byteArrayOf(0x80.toByte(), 0x10, 0, 0)))
    }

    // ------------------------------------------------------------------- the NDEF tag

    @Test fun `the ndef message is a mime record then an android application record`() {
        val payload = me.toBytes()
        val recs = parseRecords(Ndef.message(payload))
        assertEquals(2, recs.size)
        assertEquals(0x02, recs[0].tnf)
        assertEquals(Ndef.MIME, String(recs[0].type))
        assertArrayEquals(payload, recs[0].payload)
        assertTrue("first record carries MB", recs[0].mb)
        assertEquals(0x04, recs[1].tnf)
        assertEquals("android.com:pkg", String(recs[1].type))
        assertEquals("so.duffle.app", String(recs[1].payload))
        assertTrue("last record carries ME", recs[1].me)
    }

    /**
     * Replays the exact command sequence Android's NFC stack uses to read a Type 4 tag, in the
     * chunk size our own capability container advertises, and checks it gets the payload back.
     */
    @Test fun `a type 4 reader gets the payload back byte for byte`() {
        val payload = request.toBytes()
        val s = Ndef.Session(Ndef.file(payload))

        assertOk(s.handle(hex("00A4000C02E103")))                  // SELECT CC
        val cc = body(s.handle(hex("00B000000F")))                 // READ CC (15)
        assertEquals(0x20, cc[2].toInt())                         // mapping version 2.0
        val mle = ((cc[3].toInt() and 0xFF) shl 8) or (cc[4].toInt() and 0xFF)
        assertArrayEquals(hex("E104"), cc.copyOfRange(9, 11))       // NDEF file id
        assertEquals(0xFF, cc[14].toInt() and 0xFF)                // never writable

        assertOk(s.handle(hex("00A4000C02E104")))                  // SELECT NDEF
        val nlenBytes = body(s.handle(hex("00B0000002")))
        val nlen = ((nlenBytes[0].toInt() and 0xFF) shl 8) or (nlenBytes[1].toInt() and 0xFF)
        assertFalse("reading only the length is not a serve", s.payloadRead)

        val out = java.io.ByteArrayOutputStream()
        var offset = 2
        while (out.size() < nlen) {
            val n = minOf(mle, nlen - out.size())
            out.write(body(s.handle(byteArrayOf(0x00, 0xB0.toByte(), (offset shr 8).toByte(), offset.toByte(), n.toByte()))))
            offset += n
        }
        assertTrue(s.payloadRead)
        assertArrayEquals(payload, parseRecords(out.toByteArray())[0].payload)
    }

    @Test fun `the tag refuses what a real tag would refuse`() {
        val s = Ndef.Session(Ndef.file(me.toBytes()))
        assertArrayEquals(Apdu.SW_FILE_NOT_FOUND, s.handle(hex("00B0000002")))   // read before select
        assertArrayEquals(Apdu.SW_FILE_NOT_FOUND, s.handle(hex("00A4000C02E105"))) // unknown file
        assertOk(s.handle(hex("00A4000C02E104")))
        assertArrayEquals(Apdu.SW_WRONG_OFFSET, s.handle(hex("00B00FFF01")))       // past the end
        assertNull("not a tag command — left for the service", s.handle(hex("80100000")))
    }

    @Test fun `the ndef aid is the one Android reads`() {
        assertEquals("D2760000850101", Ndef.AID)
        assertArrayEquals(hex("00A4040007D276000085010100"), Apdu.select(Ndef.AID))
    }

    // ------------------------------------------------------------------------- helpers

    private data class Rec(val tnf: Int, val mb: Boolean, val me: Boolean, val type: ByteArray, val payload: ByteArray)

    /** An independent NDEF parser, written from the spec rather than from Ndef.kt. */
    private fun parseRecords(b: ByteArray): List<Rec> {
        val out = mutableListOf<Rec>()
        var i = 0
        while (i < b.size) {
            val h = b[i].toInt() and 0xFF
            val sr = h and 0x10 != 0
            val il = h and 0x08 != 0
            val typeLen = b[i + 1].toInt() and 0xFF
            var j = i + 2
            val payloadLen = if (sr) (b[j].toInt() and 0xFF).also { j += 1 }
            else ((b[j].toInt() and 0xFF shl 24) or (b[j + 1].toInt() and 0xFF shl 16) or
                (b[j + 2].toInt() and 0xFF shl 8) or (b[j + 3].toInt() and 0xFF)).also { j += 4 }
            val idLen = if (il) (b[j].toInt() and 0xFF).also { j += 1 } else 0
            val type = b.copyOfRange(j, j + typeLen); j += typeLen + idLen
            val payload = b.copyOfRange(j, j + payloadLen); j += payloadLen
            out += Rec(h and 0x07, h and 0x80 != 0, h and 0x40 != 0, type, payload)
            i = j
        }
        return out
    }

    private fun hex(s: String) = s.hexToBytes()
    private fun assertOk(r: ByteArray?) { assertNotNull(r); assertTrue("expected 9000, got ${r!!.toHex()}", Apdu.isOk(r)) }
    private fun body(r: ByteArray?): ByteArray { assertOk(r); return Apdu.body(r!!) }
}
