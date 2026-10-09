package so.duffle.app

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf

/**
 * Whether the wallet in use pays no network fee, because it holds enough SKR.
 *
 * The server decides, from the chain, and decides again when it signs: this is only what it last
 * said, kept so that every sheet can say "free" without asking, and so that a payment does not
 * wait on a request before it can be built. An answer older than [FRESH_MS] is not used to build
 * a payment, and a payment the server then refuses on these grounds forgets the answer at once.
 */
object FreeGas {
    private const val FRESH_MS = 10 * 60_000L

    private data class Answer(val wallet: String, val sponsor: Chain.Sponsor?, val at: Long)

    @Volatile private var last: Answer? = null
    @Volatile private var asked: Pair<String, Long>? = null
    private const val ASK_GAP_MS = 60_000L

    /**
     * For the screen: true while the wallet in use holds enough SKR. This is what every "free"
     * mark is drawn from, whether or not the fee wallet can pay today (user call, 2026-10-06:
     * the fee wallet will be changed, and the mark is not to depend on it).
     */
    var free by mutableStateOf(false)
        private set

    /** True only while a fee really will be paid for this wallet. What the sums are done with. */
    var paid by mutableStateOf(false)
        private set

    /**
     * Preview builds only: shows every "free" mark without the wallet holding the SKR, so the
     * screens can be looked at. It changes what is drawn and nothing about what is paid.
     */
    var preview by mutableStateOf(false)

    /** What the screen should say. */
    val shown: Boolean get() = free || (BuildConfig.PREVIEWS && preview)

    /** Asks the server about [wallet]. Safe to call often; a failure leaves the last answer. */
    suspend fun refresh(wallet: String?) {
        if (wallet == null) { last = null; free = false; paid = false; asked = null; return }
        if (last?.wallet != wallet) { last = null; free = false; paid = false }
        // The wallet is re-read every few seconds; this is asked at most once a minute for the
        // same wallet, since what it answers changes only when SKR is bought or sold.
        val now = System.currentTimeMillis()
        asked?.let { (who, at) -> if (who == wallet && now - at < ASK_GAP_MS) return }
        asked = wallet to now
        val (holds, s) = ServerClient.freeGas() ?: return
        last = Answer(wallet, s?.takeIf { it.free }, System.currentTimeMillis())
        free = holds
        paid = holds && s?.free == true
    }

    /** Who pays the fee for [wallet]'s next payment, or null when [wallet] pays its own. */
    fun sponsorFor(wallet: String, now: Long = System.currentTimeMillis()): Chain.Sponsor? =
        last?.takeIf { it.wallet == wallet && now - it.at < FRESH_MS }?.sponsor

    /** The server no longer agrees. Nothing is free until it is asked again. */
    fun forget() { last = null; paid = false; asked = null }
}
