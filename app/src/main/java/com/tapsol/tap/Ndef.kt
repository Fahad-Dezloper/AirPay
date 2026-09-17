package com.tapsol.tap

/**
 * An NFC Forum Type 4 tag, emulated in software — so a phone with Tap CLOSED can still be read.
 *
 * Our own AID only answers an app that already knows to ask for it, and the only app that asks
 * is Tap in the foreground. Android itself, though, reads every NDEF tag it sees on an unlocked
 * phone and opens the app that claims the record. So each phone also presents a tiny tag holding
 * its payload as a MIME record, plus an Android Application Record naming this package: touch an
 * idle phone to another and the system opens Tap's popup on the reading side, with no app open.
 *
 * Pure bytes, no android.nfc, so the whole file layout is unit-tested.
 */
object Ndef {

    /** The NDEF Tag Application, from the NFC Forum Type 4 Tag specification. */
    const val AID = "D2760000850101"
    const val MIME = "application/vnd.com.tapsol.tap"
    const val PACKAGE = "com.tapsol.tap"

    private val CC_FILE = byteArrayOf(0xE1.toByte(), 0x03)
    private val NDEF_FILE = byteArrayOf(0xE1.toByte(), 0x04)
    private const val MAX_NDEF_FILE = 0x0400

    /**
     * Capability Container: version 2.0, readers may take up to 0x3B bytes per READ BINARY, one
     * NDEF file E104 of up to 1KB that is readable (00) and never writable (FF). Read-only
     * matters — nobody should be able to rewrite the payment address a phone hands out.
     */
    val capabilityContainer: ByteArray = bytes(
        0x00, 0x0F,             // CCLEN
        0x20,                   // mapping version 2.0
        0x00, 0x3B,             // MLe: max bytes per READ BINARY response
        0x00, 0x34,             // MLc
        0x04, 0x06,             // NDEF File Control TLV
        0xE1, 0x04,             //   file id
        MAX_NDEF_FILE shr 8, MAX_NDEF_FILE and 0xFF,
        0x00,                   //   read: open
        0xFF,                   //   write: never
    )

    /** One MIME record carrying the payload, then an AAR so Android opens Tap and nothing else. */
    fun message(payload: ByteArray): ByteArray =
        record(tnf = 0x02, type = MIME.toByteArray(), payload = payload, first = true, last = false) +
            record(tnf = 0x04, type = "android.com:pkg".toByteArray(), payload = PACKAGE.toByteArray(), first = false, last = true)

    /** The NDEF file as a reader sees it: two-byte length, then the message. */
    fun file(payload: ByteArray): ByteArray {
        val msg = message(payload)
        return byteArrayOf((msg.size shr 8).toByte(), msg.size.toByte()) + msg
    }

    private fun record(tnf: Int, type: ByteArray, payload: ByteArray, first: Boolean, last: Boolean): ByteArray {
        val short = payload.size < 256
        var header = tnf
        if (first) header = header or 0x80
        if (last) header = header or 0x40
        if (short) header = header or 0x10
        val length = if (short) byteArrayOf(payload.size.toByte())
        else byteArrayOf((payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte())
        return byteArrayOf(header.toByte(), type.size.toByte()) + length + type + payload
    }

    /** Which file a reader has selected within one tap. */
    enum class Selected { NONE, CC, NDEF }

    /**
     * Answers the Type 4 command set for one session: SELECT the file, READ BINARY from it.
     * Returns null for any command that is not part of the tag, so the caller can try its own.
     */
    class Session(private val ndefFile: ByteArray) {
        var selected = Selected.NONE
            private set

        /** True once a reader has read past the length prefix — the payload actually left. */
        var payloadRead = false
            private set

        fun handle(cmd: ByteArray): ByteArray? {
            if (cmd.size < 4 || cmd[0] != 0x00.toByte()) return null
            return when (cmd[1]) {
                0xA4.toByte() -> selectFile(cmd)
                0xB0.toByte() -> readBinary(cmd)
                else -> null
            }
        }

        private fun selectFile(cmd: ByteArray): ByteArray? {
            if (cmd[2] != 0x00.toByte()) return null // selection by name is handled by the caller
            val id = Apdu.data(cmd) ?: return Apdu.SW_WRONG_DATA
            selected = when {
                id.contentEquals(CC_FILE) -> Selected.CC
                id.contentEquals(NDEF_FILE) -> Selected.NDEF
                else -> return Apdu.SW_FILE_NOT_FOUND
            }
            return Apdu.SW_OK
        }

        private fun readBinary(cmd: ByteArray): ByteArray {
            val file = when (selected) {
                Selected.CC -> capabilityContainer
                Selected.NDEF -> ndefFile
                Selected.NONE -> return Apdu.SW_FILE_NOT_FOUND
            }
            val offset = ((cmd[2].toInt() and 0xFF) shl 8) or (cmd[3].toInt() and 0xFF)
            val want = if (cmd.size >= 5) (cmd[4].toInt() and 0xFF).let { if (it == 0) 256 else it } else 256
            if (offset > file.size) return Apdu.SW_WRONG_OFFSET
            val end = minOf(file.size, offset + want)
            if (selected == Selected.NDEF && end > 2) payloadRead = true
            return file.copyOfRange(offset, end) + Apdu.SW_OK
        }
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
