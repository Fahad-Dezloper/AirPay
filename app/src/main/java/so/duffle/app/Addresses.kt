package so.duffle.app

import org.sol4k.Base58
import java.security.SecureRandom

/** Solana address helpers the payment needs. */
object Addresses {
    private val B58 = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

    /** Shape only: 32 to 44 base58 characters. */
    fun isValid(s: String?): Boolean = s != null && B58.matches(s)

    /**
     * A fresh Solana Pay reference for one payment: 32 random bytes as a public key. It is
     * attached as a read-only account so the payment can be found on-chain; no key exists for it.
     */
    fun newReference(): String {
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        return Base58.encode(b)
    }

    const val USDC_MAINNET = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    const val USDC_DECIMALS = 6
}
