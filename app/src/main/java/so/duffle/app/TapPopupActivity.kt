package so.duffle.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The popup that appears when two phones touch — over whatever is on screen, with Tap closed.
 *
 * It opens three ways: Android launches it after reading the other phone's NDEF tag; our HCE
 * service opens it (or notifies, if Android refuses) after the other phone said HELLO; or the
 * user taps a notification after unlocking. In every case it shows who the other person is and
 * offers the two things you can do with someone you just touched phones with: pay them, or ask
 * them to pay you.
 *
 * States are kept deliberately distinct, because they mean different things to the person:
 * working, waiting for the other person, success (confirmed on-chain), error (nothing was sent),
 * and failed (a transaction was sent and the chain refused it).
 */
class TapPopupActivity : ComponentActivity() {

    sealed interface Phase {
        data object Ready : Phase
        data class Working(val text: String) : Phase
        data class Waiting(val amount: String, val reference: String, val symbol: String = "SOL") : Phase
        data class Success(val title: String, val detail: String, val signature: String?) : Phase
        data class Error(val title: String, val detail: String) : Phase
        data class Failed(val title: String, val detail: String, val signature: String?) : Phase
    }

    private lateinit var wallet: Wallet
    private var peer by mutableStateOf<TapPayload?>(null)

    /**
     * Opened to wait for a tap rather than because one happened.
     *
     * The sheet then shows "touch both the mobiles" and fills itself in when the other phone
     * arrives — which is why it needs a reader of its own: MainActivity stops its reader the
     * moment it pauses, and it pauses to show this.
     */
    private var armed = false
    private var reader: TapReader? = null
    private var phase by mutableStateOf<Phase>(Phase.Ready)
    private var address by mutableStateOf<String?>(null)
    private var shownAt = 0L
    /** What the connected wallet holds — the token list. Null until the first read lands. */
    private var holdings by mutableStateOf<List<Tokens.Holding>?>(null)
    /** How this sheet came to be open — in person, or relayed afterwards. Shown on the device page. */
    private var cameFrom by mutableStateOf<String?>(null)
    /** Debug preview only: the amount to show in the "working" state. */
    private var previewAmount by mutableStateOf<String?>(null)
    /**
     * "They will pay later", while we keep waiting.
     *
     * A deferral is not an ending, so it does not change the phase — the money can still
     * arrive and this sheet must still be here to catch it. It only replaces the line under
     * the amount, so the person holding the phone stops wondering.
     */
    private var waitingNote by mutableStateOf<String?>(null)
    /** SKR this payment earned, once the server has seen it settle. Null until then, and if
     *  nothing was earned — an empty space is better than a nil. */
    private var earned by mutableStateOf<Rewards.Earned?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wallet = Wallet(this)
        address = wallet.address
        if (!handle(intent)) {
            finish()
            return
        }
        // The sheet is white, so the navigation bar's handle has to be dark to show on it.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = D.Blue, background = D.White, surface = D.White, onSurface = D.Black)) {
                // No early return on a null peer any more: null IS a state now — the sheet
                // open and waiting for the phones to touch.
                val p = peer
                PopupScreen(
                    peer = p,
                    phase = phase,
                    connected = address != null,
                    me = Identity.label(this).take(1).uppercase().ifBlank { "Y" },
                    myAddress = address.orEmpty(),
                    history = p?.let { who -> Contacts(this).all().firstOrNull { it.address == who.address } },
                    onDismiss = { if (phase !is Phase.Working) finish() },
                    onConnect = ::connect,
                    onPay = ::pay,
                    onRequest = ::request,
                    onDecline = ::decline,
                    onLater = ::later,
                    onVerified = { held -> p?.let { Contacts(this).verify(it.address, held) } },
                    onRetry = { phase = Phase.Ready },
                    onExplorer = { sig -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Chain.explorerUrl(sig)))) } },
                    previewAmount = previewAmount,
                    waitingNote = waitingNote,
                    holdings = holdings,
                    earned = earned,
                    cameFrom = cameFrom,
                )
                // Balances are read every time the sheet opens: they change with every payment.
                LaunchedEffect(address) {
                    val me = address ?: return@LaunchedEffect
                    holdings = withContext(Dispatchers.IO) { Tokens.holdings(this@TapPopupActivity, me) }
                    Log.i(TAG, "holdings: ${holdings?.let { Tokens.describe(it) } ?: "unreachable"}")
                }
                // A request ends three ways, and the popup has to notice all of them: they pay,
                // they decline, or — when we are the one being asked — they take it back.
                LaunchedEffect(phase) {
                    when (val ph = phase) {
                        is Phase.Waiting -> watchForPayment(ph)
                        Phase.Ready -> watchForWithdrawal()
                        else -> Unit
                    }
                }
            }
        }
    }

    /**
     * Listens for the other phone while the sheet is armed.
     *
     * Whatever it finds lands in this activity rather than starting another one: a request
     * they had ready, or just their card. Either way the sheet already open becomes the sheet
     * for that person, which is the whole reason this is here and not a second screen.
     */
    private fun arm() {
        if (!armed || peer != null || reader != null) return
        reader = TapReader(
            activity = this,
            onLog = { Log.i(TAG, it) },
            onRequest = { req ->
                runOnUiThread {
                    if (peer != null) return@runOnUiThread
                    // Saved first, exactly as MainActivity does: from here it survives a closed
                    // sheet and can still be paid from Activity.
                    Inbox(this).add(req)
                    Contacts(this).record(req.recipient, req.label, false, brand = req.brand)
                    Haptics.contact(this)
                    disarm()
                    peer = TapPayload.Request(req)
                    phase = Phase.Ready
                    shownAt = System.currentTimeMillis()
                }
            },
            onCard = { card ->
                runOnUiThread {
                    if (peer != null) return@runOnUiThread
                    Contacts(this).record(card.address, card.label, false, brand = card.brand)
                    Haptics.contact(this)
                    disarm()
                    peer = TapPayload.Card(card)
                    phase = Phase.Ready
                    shownAt = System.currentTimeMillis()
                }
            },
        ).also { it.start() }
    }

    private fun disarm() {
        reader?.stop()
        reader = null
    }

    override fun onResume() {
        super.onResume()
        // Reader mode only exists while a screen is in front, so it is armed here rather than
        // once in onCreate — the same reason MainActivity re-arms on every resume.
        arm()
    }

    override fun onPause() {
        super.onPause()
        disarm()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Never swap the person under a payment in progress.
        if (phase is Phase.Working || phase is Phase.Waiting) return
        handle(intent)
    }

    /** Reads whatever launched us. False if there is nothing valid to show. */
    private fun handle(intent: Intent): Boolean {
        val payload = when (intent.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED -> fromNdef(intent)
            else -> intent.getByteArrayExtra(EXTRA_PAYLOAD)?.let { TapPayload.parse(it) }
                // Debug builds only: open the popup from the Mac with a JSON payload, so the
                // screen can be designed without two phones and a tap every time. `adb am start`
                // can pass strings but not the byte array a real tap delivers. Parsed and
                // validated exactly like radio input.
                ?: intent.getStringExtra(EXTRA_PAYLOAD_JSON)?.takeIf { BuildConfig.PREVIEWS }
                    ?.let { TapPayload.parse(it.toByteArray()) }
        }
        if (payload == null) {
            // Armed: no payload yet, and the sheet is the thing that waits for one.
            if (!intent.getBooleanExtra(EXTRA_ARMED, false)) return false
            armed = true
            phase = Phase.Ready
            cameFrom = intent.getStringExtra(EXTRA_FROM)
            return true
        }

        // The same person can arrive twice in a moment — our NDEF read of them and their HELLO
        // to us — which should be one popup, not two.
        if (peer?.address == payload.address && System.currentTimeMillis() - shownAt < 4_000) return true
        if (payload.address == address) return false // our own phone, somehow

        // Whoever it came from — our own reader, an NDEF intent, a push — the waiting is over,
        // so the radio stops listening for a second person to walk into the same sheet.
        disarm()
        peer = payload
        phase = Phase.Ready
        shownAt = System.currentTimeMillis()
        cameFrom = if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED) "nfc" else intent.getStringExtra(EXTRA_FROM)

        // Debug builds only: jump straight to a state, so every screen can be looked at on a real
        // phone without paying anyone. No side effects — nothing is recorded or registered.
        intent.getStringExtra(EXTRA_PREVIEW)?.takeIf { BuildConfig.PREVIEWS }?.let { state ->
            val amount = (payload as? TapPayload.Request)?.request?.amount ?: "0.5"
            phase = when (state) {
                "working" -> Phase.Working("Approve in your wallet…")
                "waiting" -> Phase.Waiting(amount, "preview")
                "success" -> Phase.Success("Sent", "$amount SOL to ${payload.label}", "67WKe2RHq4Z8vY1pXbT5mN3cJdedqWM5")
                "received" -> Phase.Success("Received", "$amount SOL from ${payload.label}", "67WKe2RHq4Z8vY1pXbT5mN3cJdedqWM5")
                // The ✓ with the reward that really arrives a few seconds later, so the earn
                // moment can be looked at without waiting for a payment to settle.
                "earned" -> {
                    lifecycleScope.launch { delay(1_400); earned = Rewards.Earned(300, listOf("first_payment", "per_payment")) }
                    Phase.Success("Sent", "$amount SOL to ${payload.label}", "67WKe2RHq4Z8vY1pXbT5mN3cJdedqWM5")
                }
                "error" -> Phase.Error("Nothing was sent", "The approval window closed. Tap again.")
                // The §7 wording, so the preview shows what ships rather than its own phrasing.
                "failed" -> Phase.Failed("The chain refused it", "It went out and didn't land. Nothing left your wallet.", "67WKe2RHq4Z8vY1pXbT5mN3cJdedqWM5")
                "connect" -> { address = null; Phase.Ready }
                else -> Phase.Ready
            }
            if (state == "working") previewAmount = amount
            return true
        }
        Log.i(TAG, "popup for ${payload.label} via ${intent.getStringExtra(EXTRA_FROM) ?: intent.action}")
        Push.cancel(this, payload.address)
        Haptics.contact(this)
        Contacts(this).record(payload.address, payload.label, false, brand = payload.brand)

        if (payload is TapPayload.Request) {
            Push.cancel(this, payload.request.reference)
            // Saved before anything else, so "Later" — or closing the popup — never loses it.
            Inbox(this).add(payload.request)
            lifecycleScope.launch {
                // As payer: a request received by tapping was registered by the phone asking,
                // which cannot know who will pay — so a withdrawal would reach nobody.
                ServerClient.register(payload.request.reference, payload.request.recipient,
                    payload.request.amount, payload.request.label, payer = address, origin = "tap",
                    deliver = false)
            }
        }

        // Launched by Android's NDEF read: the other phone still has no idea who we are. Say
        // HELLO while the phones may still be touching; failing that, relay through the server.
        if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED) introduce(intent, payload)
        return true
    }

    private fun fromNdef(intent: Intent): TapPayload? {
        val messages = IntentCompat.getParcelableArrayExtra(intent, NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
            ?: return null
        for (m in messages) {
            for (r in (m as NdefMessage).records) {
                if (r.toMimeType() == Ndef.MIME) return TapPayload.parse(r.payload)
            }
        }
        return null
    }

    private fun introduce(intent: Intent, payload: TapPayload) {
        val me = Identity.card(this) ?: return
        val tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java)
        lifecycleScope.launch(Dispatchers.IO) {
            val introduced = tag != null &&
                (TapExchange.run(tag, me) as? TapExchange.Outcome.Got)?.introduced == true
            Log.i(TAG, "introduced over NFC: $introduced")
            if (!introduced) ServerClient.relayTap(me, payload.address)
        }
    }

    private fun connect() {
        lifecycleScope.launch {
            phase = Phase.Working("Opening your wallet…")
            when (val r = wallet.connect()) {
                is Wallet.Outcome.Ok -> {
                    address = r.address
                    phase = Phase.Ready
                    launch(Dispatchers.IO) {
                        Identity.rememberGenesis(this@TapPopupActivity, r.address,
                            runCatching { Genesis.mintFor(r.address) }.getOrNull())
                    }
                }
                is Wallet.Outcome.Failed -> phase = Phase.Error("Couldn't connect", r.why)
                Wallet.Outcome.Declined -> phase = Phase.Ready
            }
        }
    }

    private fun pay(amount: String, token: Tokens.Holding?) {
        val p = peer ?: return
        val payer = address ?: return connect()
        val t = token ?: SOL
        val req = when (p) {
            is TapPayload.Request -> p.request
            is TapPayload.Card -> PayRequest(p.card.address, amount.trim(), t.token, p.card.label, PayRequest.newReference(),
                p.card.genesis, decimals = t.decimals, symbol = t.symbol)
        }
        lifecycleScope.launch {
            phase = Phase.Working("Getting ready…")
            // Registered before paying so the server is already watching and can tell the other
            // phone the instant it lands.
            launch {
                // req.label is the person being PAID. It used to send our own name, so the
                // request was filed — and announced — as "Seeker requests 1 USDC" on the
                // Seeker that was doing the paying.
                ServerClient.register(req.reference, req.recipient, req.amount, req.label,
                    payer = payer, token = req.token, decimals = req.decimals,
                    symbol = req.symbol, origin = "tap", deliver = false)
            }
            val result = try {
                Payments.pay(wallet, payer, req, stage = { phase = Phase.Working(it) }, log = { Log.i(TAG, it) })
            } catch (e: Exception) {
                // The exception goes to the log, not onto the screen: `e.message` is the
                // machinery talking, and ui/PORT.md §7 asks for what happened to the money.
                Log.e(TAG, "pay threw", e)
                PayResult.Error("Nothing was sent", "That payment stopped before it went out. Tap again.")
            }
            phase = when (result) {
                is PayResult.Sent -> {
                    // Every payment that leaves this phone is filed, not only the ones that began
                    // as a request. A wallet address scanned from a QR has no request behind it,
                    // so a payment to it left no row in Transactions, no person on Home and no
                    // thread to open — the money had gone and the app had no memory of it.
                    Inbox(this@TapPopupActivity).apply {
                        if (get(req.reference) == null) add(req)
                        markPaid(req.reference, result.signature)
                    }
                    // The name the address owns on chain, if any, in place of "Scanned code".
                    launch {
                        val name = withContext(Dispatchers.IO) { runCatching { Names.label(req.recipient) }.getOrNull() }
                        if (name != null) Contacts(this@TapPopupActivity).record(req.recipient, name, req.genesis != null)
                    }
                    // The contact's running total is in SOL; token payments don't add to it.
                    Contacts(this@TapPopupActivity).record(req.recipient, req.label, req.genesis != null,
                        paid = if (req.isSol) req.amount.toDoubleOrNull() ?: 0.0 else 0.0)
                    Haptics.success(this@TapPopupActivity)
                    refreshHoldings()
                    // The reward can only be decided once the server has seen the payment on
                    // the chain, which is after this screen appears. It slides in when it
                    // lands, rather than holding up the ✓.
                    launch { earned = Rewards.awaitEarned(payer, req.reference) }
                    Phase.Success("Sent", "${req.amount} ${req.symbol} to ${req.label}", result.signature)
                }
                is PayResult.AlreadyPaid -> {
                    if (p is TapPayload.Request) Inbox(this@TapPopupActivity).markPaid(req.reference, result.signature)
                    Phase.Success("Already paid", "This request was paid before, nothing new was sent.", result.signature)
                }
                is PayResult.Error -> { Haptics.failure(this@TapPopupActivity); Phase.Error(result.title, result.detail) }
                is PayResult.Failed -> { Haptics.failure(this@TapPopupActivity); Phase.Failed(result.title, result.detail, result.signature) }
            }
        }
    }

    /**
     * "No thanks" — an answer, unlike "Later", which is only a delay. The person who asked is
     * standing there waiting, so they are told; without this their phone would show the request
     * as still expected until they gave up on it themselves.
     */
    /**
     * "Later" — not an answer, but not nothing either. The request stays in Requests and stays
     * payable; the asker is told so their phone shows it as pending rather than as silence.
     * Before this, "Later" and "never opened it" looked identical from the other side.
     */
    private fun later() {
        val p = peer as? TapPayload.Request ?: return finish()
        lifecycleScope.launch { ServerClient.defer(p.request.reference, Identity.label(this@TapPopupActivity)) }
        Log.i(TAG, "later ${p.request.reference.take(8)}…")
        finish()
    }

    private fun decline() {
        val p = peer as? TapPayload.Request ?: return finish()
        val req = p.request
        Inbox(this).remove(req.reference)
        lifecycleScope.launch { ServerClient.reject(req.reference, Identity.label(this@TapPopupActivity)) }
        Log.i(TAG, "declined ${req.reference.take(8)}…")
        finish()
    }

    private fun request(amount: String, token: Tokens.Holding?) {
        val p = peer ?: return
        val me = address ?: return connect()
        val t = token ?: SOL
        val value = amount.trim()
        if (PayRequest.baseUnits(value, t.decimals) == null) {
            phase = Phase.Error("Enter an amount", "${t.symbol} amounts can have up to ${t.decimals} decimal places.")
            return
        }
        if (!ServerClient.enabled) {
            // The phones have parted by now, so the request has no radio to travel over.
            phase = Phase.Error("Requests need the AirPay server", "Paying works without it. To request after a tap. Run the server, see server/README.md.")
            return
        }
        lifecycleScope.launch {
            phase = Phase.Working("Sending request…")
            val reference = PayRequest.newReference()
            val sent = ServerClient.register(
                reference, me, value, Identity.label(this@TapPopupActivity),
                payer = p.address, genesis = Identity.card(this@TapPopupActivity)?.genesis,
                token = t.token, decimals = t.decimals, symbol = t.symbol,
                // The radio could not carry this one, but the phones did touch — that is how we
                // know their address at all — so it is still an in-person payment.
                origin = "tap",
            )
            if (!sent) {
                Haptics.failure(this@TapPopupActivity)
                phase = Phase.Error("Couldn't send the request", "Check your connection and try again.")
                return@launch
            }
            Watch(this@TapPopupActivity).add(Watch.Entry(
                reference = reference, recipient = me, peer = p.address, amount = value,
                createdAt = System.currentTimeMillis(),
                token = t.token, decimals = t.decimals, symbol = t.symbol))
            phase = Phase.Waiting(value, reference, t.symbol)
        }
    }

    private suspend fun watchForPayment(w: Phase.Waiting) {
        val me = address ?: return
        val entry0 = Watch(this).all().firstOrNull { it.reference == w.reference } ?: return
        val units = entry0.units() ?: return
        while (phase == w) {
            // They may answer instead of paying. A refusal arrives over the live stream or as a
            // push, and both write it to the watch list — so reading that covers either route,
            // including when this popup is the only part of the app on screen.
            val entry = Watch(this).all().firstOrNull { it.reference == w.reference }
            // Written by the live stream or by the push handler, whichever reached us — this
            // sheet is often the only part of the app on screen, and then it is the push.
            waitingNote = entry?.deferredBy?.takeIf { !entry.paid && !entry.rejected }?.let { "$it will pay later" }
            if (entry?.rejected == true) {
                Haptics.failure(this)
                phase = Phase.Error("${entry.rejectedBy} declined", "Nothing was paid. You can ask again.")
                return
            }
            val found = withContext(Dispatchers.IO) { Chain.findPayment(w.reference, me, units, entry0.mint) }
            if (found is Chain.Found.Yes) {
                if (Watch(this).markPaid(w.reference, found.signature)) {
                    Haptics.success(this)
                    refreshHoldings()
                    phase = Phase.Success("Received", "${w.amount} ${w.symbol} from ${peer?.label ?: "them"}", found.signature)
                }
                return
            }
            delay(2_000)
        }
    }

    /** The other side of the same coin: a request we were asked to pay, taken back. */
    private suspend fun watchForWithdrawal() {
        val req = (peer as? TapPayload.Request)?.request ?: return
        while (phase == Phase.Ready) {
            if (Inbox(this).get(req.reference)?.withdrawn == true) {
                Haptics.failure(this)
                phase = Phase.Error("Request withdrawn", "${req.label} took this request back.")
                return
            }
            delay(2_000)
        }
    }

    private fun refreshHoldings() {
        val me = address ?: return
        lifecycleScope.launch { holdings = withContext(Dispatchers.IO) { Tokens.holdings(this@TapPopupActivity, me) } ?: holdings }
    }

    companion object {
        private val SOL = Tokens.Holding(null, "SOL", "Solana", 9, 0, null)
        private const val TAG = "TapPopup"
        private const val EXTRA_PAYLOAD = "payload"
        private const val EXTRA_PAYLOAD_JSON = "payload_json"
        private const val EXTRA_FROM = "from"
        private const val EXTRA_PREVIEW = "preview"
        private const val EXTRA_ARMED = "armed"

        /** The sheet, open and waiting for a tap, with nobody on the other side yet. */
        fun armed(context: Context): Intent =
            Intent(context, TapPopupActivity::class.java)
                .putExtra(EXTRA_ARMED, true)
                .putExtra(EXTRA_FROM, "tap-button")

        fun intent(context: Context, payload: TapPayload, cameFrom: String): Intent =
            Intent(context, TapPopupActivity::class.java)
                .putExtra(EXTRA_PAYLOAD, payload.toBytes())
                .putExtra(EXTRA_FROM, cameFrom)
    }
}

