@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package so.duffle.app

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The product's name, in one place, so the rename is one line. */
internal object App {
    const val NAME = "CellUp"
}

/**
 * CellUp: recharge any phone, in any country Cryptorefills sells in, with USDC from your own
 * wallet. One flow: number, network, plan, pay, delivered. Everything else AirPay did is off.
 *
 * Built from AirPay's own pieces so it feels the same: the plan card (PLAN_WASH / PLAN_EDGE,
 * Labelled, BulletLine, PlanTab) and the payment sheet (Tray, SheetStat, PurchaseFact) are the
 * ones the AirPay recharge screen uses, shared rather than copied.
 */
class CellUpActivity : ComponentActivity() {

    private lateinit var wallet: Wallet

    private enum class Screen { HOME, PLANS, HISTORY, SUPPORT }

    private companion object { const val SUPPORT_EMAIL = "airpayhq@gmail.com" }

    /** What the payment sheet is showing. The same sheet from review to delivered, as in AirPay. */
    private sealed interface Sheet {
        data class Review(val plan: TopUp.Plan) : Sheet
        data class Paid(val plan: TopUp.Plan?, val order: TopUp.Order, val signature: String?) : Sheet
        data class Problem(val plan: TopUp.Plan, val title: String, val detail: String) : Sheet
        /** Writing to support about one order; [sentTo] once it went. */
        data class Help(val order: TopUp.Order, val sentTo: String? = null, val viaMailApp: Boolean = false,
                        val confirmed: Boolean = false, val query: String = "") : Sheet
    }

    // ---- state ---------------------------------------------------------------------------
    private var screen by mutableStateOf(Screen.HOME)
    private var connected by mutableStateOf<String?>(null)
    private var region by mutableStateOf("US")
    private var typed by mutableStateOf("")
    private var number by mutableStateOf<Phones.Number?>(null)
    private val operators = mutableStateListOf<TopUp.Operator>()
    private var operatorsLoading by mutableStateOf(false)
    private var operator by mutableStateOf<TopUp.Operator?>(null)
    private var detecting by mutableStateOf(false)
    private var detectedNote by mutableStateOf<String?>(null)
    private val plans = mutableStateListOf<TopUp.Plan>()
    private var plansLoading by mutableStateOf(false)
    private var plansProblem by mutableStateOf<String?>(null)
    private var shelf by mutableStateOf<TopUp.Shelf?>(null)
    private var sheet by mutableStateOf<Sheet?>(null)
    private var picking by mutableStateOf<String?>(null) // "country" | "operator"
    private var email by mutableStateOf("")
    private var saveEmail by mutableStateOf(true)
    private var busy by mutableStateOf<String?>(null)
    private var problem by mutableStateOf<String?>(null)
    private val history = mutableStateListOf<TopUp.Order>()
    private val countries = mutableStateListOf<TopUp.Country>()
    /** Orders this phone has already paid on-chain, so "waiting" from the supplier reads as confirming. */
    private val sent = mutableStateListOf<String>()
    /** Between finding the network and sliding to its plans: hides "See plans" so nothing flickers. */
    private var autoPending by mutableStateOf(false)

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Test builds only: the screen stays on while the app is open, so a phone on the desk
        // does not sleep in the middle of a test. Release builds sleep as normal.
        if (BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Session.init(this)
        Analytics.init(this)
        wallet = Wallet(this)
        connected = wallet.address
        region = Phones.defaultRegion(this)
        lifecycleScope.launch(Dispatchers.IO) { Phones.loadAliases(this@CellUpActivity) }
        email = Identity.email(this).orEmpty()
        sent.addAll(getSharedPreferences("cellup", MODE_PRIVATE).getStringSet("sent", emptySet()).orEmpty())
        loadOperators()
        lifecycleScope.launch { countries.addAll(TopUp.countries()) }
        refreshHistory()
        setContent { Root() }
    }

    override fun onResume() {
        super.onResume()
        refreshHistory()
        (sheet as? Sheet.Paid)?.order?.takeIf { !it.finished }?.let { follow(it.id) }
    }

    // ---- choosing ------------------------------------------------------------------------
    private fun loadOperators(after: (() -> Unit)? = null) {
        val r = region
        operatorsLoading = true
        lifecycleScope.launch {
            val got = TopUp.operators(r)
            if (r != region) return@launch
            operators.clear()
            got?.let { operators.addAll(it) }
            operatorsLoading = false
            if (operator?.let { o -> got?.none { it.id == o.id } } != false) operator = null
            after?.invoke()
        }
    }

    private fun pickRegion(code: String, after: (() -> Unit)? = null) {
        if (code == region && !operatorsLoading) { after?.invoke(); return }
        region = code
        operator = null
        detectedNote = null
        lastDetected = null
        number = Phones.parse(typed, code)
        loadOperators(after)
    }

    private fun onTyped(text: String) {
        typed = text.filter { it.isDigit() || it == '+' || it == ' ' }.take(20)
        var n = Phones.parse(typed, region)
        // Typed or pasted with its country code: the flag and code take that country, and the
        // field keeps only the number after it, the same as if the country had been picked.
        if (n != null && typed.trimStart().startsWith("+")) {
            typed = n.e164.removePrefix(Phones.dialCode(n.region))
            n = Phones.parse(typed, n.region)
        }
        number = n
        if (n == null) { detectedNote = null; return }
        val found = n
        if (found.region != region || operatorsLoading) pickRegion(found.region) { detect(found) } else detect(found)
    }

    private var lastDetected: String? = null

    /**
     * Picks the network from the number, without being asked: the live lookup first (it knows
     * about numbers that moved network), then the number's prefix from libphonenumber's offline
     * tables. Either way the buyer can still change it, and is told where the answer came from.
     */
    private fun detect(n: Phones.Number) {
        if (n.e164 == lastDetected) return
        lastDetected = n.e164
        detectedNote = null
        detecting = true
        lifecycleScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                val live = TopUp.detect(n.e164, n.region)
                if (number?.e164 != n.e164) return@launch
                val ids = operators.map { it.id }
                val prefix = withContext(Dispatchers.Default) { Phones.matchOperator(Phones.issuer(n.e164), ids) }
                val pick = live?.takeIf { it in ids } ?: prefix
                if (BuildConfig.DEBUG) android.util.Log.i("CellUp", "detect ${n.region} live=$live prefix=$prefix pick=$pick " +
                    "${System.currentTimeMillis() - t0}ms of ${ids.size}")
                val op = operators.firstOrNull { it.id == pick }
                if (op != null) {
                    operator = op
                    detectedNote = if (live == pick) "Found from your number" else "Guessed from the number. Tap to change"
                    Analytics.event("network_detected", "how" to if (live == pick) "lookup" else "prefix", "country" to n.region)
                    // The ring turns into the logo, then the number and logo slide up to the plans.
                    autoPending = true
                    detecting = false
                    loadPlans(op)
                    delay(650)
                    if (number?.e164 == n.e164 && operator?.id == op.id && screen == Screen.HOME && sheet == null && picking == null) openPlans()
                    // Kept on until home has left, so "See plans" never flashes on the way out.
                    delay(700)
                    autoPending = false
                } else {
                    Analytics.event("network_not_detected", "country" to n.region)
                }
            } finally {
                detecting = false
            }
        }
    }

    /** Which network's plans are in [plans] (region:operator), so a second open does not reload. */
    private var plansKey: String? = null

    private fun openPlans() {
        val op = operator ?: run { picking = "operator"; return }
        if (number == null) return
        screen = Screen.PLANS
        Analytics.event("topup_plans_opened", "country" to region)
        loadPlans(op)
    }

    /** Loads [op]'s plans unless they are already here or on their way. Called early, while the
     *  logo is still showing, so the plans screen arrives with its list instead of building it. */
    private fun loadPlans(op: TopUp.Operator) {
        val key = "$region:${op.id}"
        if (key == plansKey && (plansLoading || plans.isNotEmpty())) return
        plansKey = key
        plans.clear(); plansProblem = null; plansLoading = true; shelf = null
        val r = region
        lifecycleScope.launch {
            val got = TopUp.plans(r, op.id)
            if (plansKey != key) return@launch
            plansLoading = false
            when {
                got == null -> { plansProblem = "Could not load plans. Check your connection and try again."; plansKey = null }
                got.isEmpty() -> plansProblem = "No plans for ${op.name} right now."
                else -> plans.addAll(got)
            }
            showPendingPlan()
        }
    }

    // ---- wallet and sign-in --------------------------------------------------------------
    private fun connect(after: (() -> Unit)? = null) {
        lifecycleScope.launch {
            busy = "Opening your wallet…"
            try {
                when (val r = wallet.connect()) {
                    is Wallet.Outcome.Ok -> {
                        connected = r.address
                        Analytics.event("wallet_connected")
                        refreshHistory()
                        after?.invoke()
                    }
                    is Wallet.Outcome.Failed -> problem = r.why
                    else -> Unit
                }
            } finally {
                busy = null
            }
        }
    }

    /** Sign-in, once per wallet: it proves the wallet is yours to our server. */
    private suspend fun ensureSignedIn(addr: String): Boolean {
        if (Session.valid(addr)) return true
        busy = "Approve sign-in in your wallet…"
        val ok = Session.signIn(this, wallet, addr)
        if (ok) {
            lifecycleScope.launch { Push.token(this@CellUpActivity)?.let { ServerClient.registerDevice(addr, it) } }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        return ok
    }

    // ---- paying --------------------------------------------------------------------------
    private fun pay(plan: TopUp.Plan) {
        val n = number ?: return
        val op = operator ?: return
        val mail = email.trim()
        if (!Identity.looksLikeEmail(mail)) { problem = "Enter an email for your receipt."; return }
        val addr = connected ?: return connect { pay(plan) }
        if (busy != null) return
        problem = null
        Analytics.event("topup_started", "country" to region, "category" to plan.category)
        lifecycleScope.launch {
            var placed: TopUp.Order? = null
            var sent = false
            fun fail(title: String, detail: String) { sheet = Sheet.Problem(plan, title, detail) }
            try {
                if (!ensureSignedIn(addr)) return@launch fail("Sign-in not approved", "Nothing was charged. Try again and approve the sign-in in your wallet.")
                if (saveEmail) Identity.setEmail(this@CellUpActivity, mail)
                busy = "Starting your recharge…"
                val o = when (val r = TopUp.place(n.e164, region, op.id, plan.id, mail)) {
                    is TopUp.Placed.Ok -> r.order
                    is TopUp.Placed.Refused -> return@launch fail("Couldn't start this recharge", r.message)
                    TopUp.Placed.SignIn -> return@launch fail("Please sign in again", "Nothing was charged.")
                    TopUp.Placed.Offline -> return@launch fail("You're offline", "Nothing was charged. Check your connection.")
                }
                placed = o
                val dest = o.address
                val units = o.usdcUnits
                if (dest == null || units == null || units <= 0) return@launch fail("Couldn't start this recharge", "Nothing was charged.")
                busy = "Preparing payment…"
                val built = withContext(Dispatchers.IO) {
                    Chain.buildTokenTransfer(
                        payer = addr, recipient = dest, mint = Shop.USDC_MAINNET, units = units,
                        decimals = Shop.USDC_DECIMALS, reference = PayRequest.newReference(), symbol = "USDC",
                        destinationAccount = if (o.payTo == "token_account") dest else null,
                    )
                }
                val tx = when (built) {
                    is Chain.Built.Ok -> built.tx
                    is Chain.Built.NotEnough -> return@launch fail("Not enough ${built.symbol}",
                        "You have ${built.have} ${built.symbol}, this needs ${built.need}. Nothing was charged.")
                    is Chain.Built.Offline -> return@launch fail("You're offline", "Nothing was charged.")
                    is Chain.Built.Bad -> return@launch fail("Couldn't prepare the payment", built.why)
                }
                busy = "Approve in your wallet…"
                val signed = when (val s = wallet.signOnly(tx)) {
                    is Wallet.SendOutcome.Signed -> s.tx
                    is Wallet.SendOutcome.Failed -> return@launch fail("Not approved", "${s.why} Nothing was charged.")
                    else -> return@launch fail("Not approved", "Nothing was charged.")
                }
                busy = "Sending…"
                val sig = when (val r = withContext(Dispatchers.IO) { Chain.submit(signed) }) {
                    is Chain.Submitted.Ok -> r.signature
                    is Chain.Submitted.Rejected -> return@launch fail("The network refused it", "${r.why}. Nothing was charged.")
                    Chain.Submitted.Offline -> {
                        sent = true
                        return@launch fail("No answer from the network", "It may have been sent. Check your wallet before trying again.")
                    }
                }
                sent = true
                markSent(o.id)
                Analytics.event("topup_paid", "country" to region)
                sheet = Sheet.Paid(plan, o.copy(state = "paying"), sig)
                busy = null
                follow(o.id)
            } finally {
                busy = null
                // Started and never paid: tell Cryptorefills now, so an abandoned checkout does
                // not count against our key as an unpaid order.
                val o = placed
                if (o != null && !sent) lifecycleScope.launch { TopUp.cancel(o.id) }
            }
        }
    }

    private var following: String? = null

    private fun markSent(id: String) {
        if (id in this.sent) return
        this.sent.add(id)
        getSharedPreferences("cellup", MODE_PRIVATE).edit().putStringSet("sent", this.sent.takeLast(200).toSet()).apply()
    }

    /** Asks the server about [id] until it ends. The server pushes the ending too. */
    private fun follow(id: String) {
        if (following == id) return
        following = id
        lifecycleScope.launch {
            var gap = 3_000L
            val until = System.currentTimeMillis() + 10 * 60_000
            while (following == id && System.currentTimeMillis() < until) {
                TopUp.status(id)?.let { fresh ->
                    (sheet as? Sheet.Paid)?.takeIf { it.order.id == id }?.let { sheet = it.copy(order = fresh) }
                    if (fresh.finished) {
                        if (fresh.delivered) Analytics.event("topup_delivered", "country" to fresh.country)
                        refreshHistory()
                        following = null
                        return@launch
                    }
                }
                delay(gap)
                gap = (gap * 1.3).toLong().coerceAtMost(15_000)
            }
            if (following == id) following = null
        }
    }

    private fun refreshHistory() {
        if (!Session.valid(connected)) return
        lifecycleScope.launch { TopUp.history()?.let { history.clear(); history.addAll(it) } }
    }

    /** The plan "Recharge again" should open once its network's plans are here. */
    private var pendingPlan: String? = null
    private var againBusy by mutableStateOf(false)
    private var signingIn by mutableStateOf(false)

    /** Same number, same network, same plan: straight to that plan's sheet, ready to pay. */
    /** The order "Recharge again" came from, until its plans are here and the screen can change. */
    private var pendingAgain: TopUp.Order? = null
    /** True while the screen behind an open sheet changes: one plain cross-fade, nothing flying. */
    private var quietSwitch by mutableStateOf(false)

    /**
     * Same number, same network, same plan, in three calm steps rather than all at once: the sheet
     * holds while the plans load out of sight; then the screen behind cross-fades to them; then the
     * sheet eases into that plan.
     */
    private fun again(o: TopUp.Order) {
        val n = Phones.parse(o.phone, o.country) ?: return
        againBusy = true
        pendingPlan = o.planId
        pendingAgain = o
        pickRegion(n.region) {
            val op = operators.firstOrNull { it.id == o.operator } ?: run {
                pendingAgain = null; pendingPlan = null; againBusy = false
                showNumber(o); sheet = null; screen = Screen.HOME; picking = "operator"
                return@pickRegion
            }
            operator = op; detectedNote = "Used last time"
            loadPlans(op)
            showPendingPlan()
        }
    }

    /** The order's number in the field: the digits after the country code, no trunk "0". */
    private fun showNumber(o: TopUp.Order) {
        val n = Phones.parse(o.phone, o.country) ?: return
        typed = n.e164.removePrefix(Phones.dialCode(n.region)).filter { it.isDigit() }
        number = n
        lastDetected = n.e164
    }

    private fun showPendingPlan() {
        val id = pendingPlan ?: return
        if (plansLoading) return  // loadPlans calls back when the plans arrive
        pendingPlan = null
        val p = plans.firstOrNull { it.id == id }
        pendingAgain?.let { o ->
            pendingAgain = null
            showNumber(o)
            quietSwitch = true
            screen = Screen.PLANS
        }
        lifecycleScope.launch {
            // Let the screen behind settle first; then the sheet changes, alone.
            delay(180)
            againBusy = false
            problem = null
            // The open order sheet becomes this plan's sheet; with the plan gone, it steps aside.
            sheet = p?.let { Sheet.Review(it) }
            delay(600)
            quietSwitch = false
        }
    }

    // ---- UI ------------------------------------------------------------------------------
    @Composable
    private fun Root() {
        BackHandler(enabled = screen != Screen.HOME || sheet != null || picking != null) {
            when {
                picking != null -> picking = null
                sheet != null && busy == null -> sheet = null
                screen != Screen.HOME -> screen = Screen.HOME
            }
        }
        Box(Modifier.fillMaxSize().background(D.White)) {
            // Home and plans share the number and the logo: they travel between the two screens.
            SharedTransitionLayout(Modifier.fillMaxSize()) {
                val shared = this
                AnimatedContent(screen, transitionSpec = {
                    if (quietSwitch) fadeIn(tween(240)) togetherWith fadeOut(tween(240))
                    else fadeIn(tween(260, delayMillis = 80)) togetherWith fadeOut(tween(160))
                }, label = "screen") { s ->
                    // Behind an open sheet nothing flies: the shared pieces sit this one out.
                    val sh = if (quietSwitch) null else shared
                    when (s) {
                        Screen.HOME -> Home(sh, this)
                        Screen.PLANS -> PlansScreen(sh, this)
                        Screen.HISTORY -> HistoryScreen()
                        Screen.SUPPORT -> SupportScreen()
                    }
                }
            }
            sheet?.let { PaySheet(it) }
            when (picking) {
                "country" -> CountryPicker()
                "operator" -> OperatorPicker()
            }
        }
    }

    @Composable
    private fun Fact(text: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(PLAN_WASH), contentAlignment = Alignment.Center) {
                HiIcon(Hi.Tick, D.BlueInk, 12.dp)
            }
            Spacer(Modifier.width(6.dp))
            Text(text, style = Type.caption, color = D.Quiet, maxLines = 1)
        }
    }

    /**
     * A globe of dots turning slowly, with some of the countries CellUp recharges pulsing on it.
     * Drawn, not an image: it stays crisp at any size and costs nothing to ship.
     */
    @Composable
    private fun DotGlobe(modifier: Modifier = Modifier) {
        val spin = rememberInfiniteTransition(label = "globe")
        val beat by spin.animateFloat(0f, 1f, infiniteRepeatable(tween(2_200, easing = LinearEasing)), label = "beat")
        // A small physics loop: the globe idles at a slow spin; a drag turns it directly; a fling
        // carries on with the finger's speed and friction bleeds it back to the idle spin. Tilt
        // follows the finger up and down and springs back to rest when let go.
        val idle = 0.17f                       // radians a second, about one turn in 36 s
        var turn by remember { mutableStateOf(0f) }
        var tiltNow by remember { mutableStateOf(0.32f) }
        var fling by remember { mutableStateOf(0f) }
        var held by remember { mutableStateOf(false) }
        var radius by remember { mutableStateOf(1f) }
        var clock by remember { mutableStateOf(0f) }
        // Stars: fixed places, each with its own size and twinkle.
        val stars = remember {
            val rnd = java.util.Random(7)
            List(170) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 0.5f + rnd.nextFloat() * 1.4f,
                rnd.nextFloat() * 6.28f, 0.6f + rnd.nextFloat() * 1.8f) }
        }
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { now ->
                    val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                    last = now
                    clock += dt
                    if (!held) {
                        turn += (idle + fling) * dt
                        fling *= kotlin.math.exp(-1.6f * dt)           // friction
                        if (kotlin.math.abs(fling) < 0.002f) fling = 0f
                        tiltNow += (0.32f - tiltNow) * (1f - kotlin.math.exp(-3.5f * dt))  // spring back
                    }
                }
            }
        }
        val tracker = remember { androidx.compose.ui.input.pointer.util.VelocityTracker() }
        val dragModifier = Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { held = true; fling = 0f; tracker.resetTracking() },
                onDragEnd = {
                    val v = tracker.calculateVelocity()
                    fling = (v.x / radius).coerceIn(-14f, 14f)
                    held = false
                },
                onDragCancel = { held = false },
            ) { change, drag ->
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                turn += drag.x / radius
                tiltNow = (tiltNow + drag.y / radius).coerceIn(-1.1f, 1.1f)
            }
        }
        val dots = remember {
            val n = 900
            val golden = Math.PI * (3 - Math.sqrt(5.0))
            List(n) { i ->
                val y = 1 - 2.0 * (i + 0.5) / n
                val r = Math.sqrt(1 - y * y)
                floatArrayOf((Math.cos(golden * i) * r).toFloat(), y.toFloat(), (Math.sin(golden * i) * r).toFloat())
            }
        }
        // India, US, Brazil, Nigeria, Indonesia, UK, Mexico, Philippines, Kenya, Pakistan, UAE, Bangladesh.
        val pins = remember {
            listOf(20.0 to 78.0, 38.0 to -97.0, -10.0 to -55.0, 9.0 to 8.0, -2.0 to 118.0, 54.0 to -2.0,
                23.0 to -102.0, 13.0 to 122.0, 0.0 to 38.0, 30.0 to 70.0, 24.0 to 54.0, 24.0 to 90.0).map { (la, lo) ->
                val phi = Math.toRadians(la); val lam = Math.toRadians(lo)
                floatArrayOf((Math.cos(phi) * Math.sin(lam)).toFloat(), Math.sin(phi).toFloat(), (Math.cos(phi) * Math.cos(lam)).toFloat())
            }
        }
        val flags = remember { listOf("IN", "US", "BR", "NG", "ID", "GB", "MX", "PH", "KE", "PK", "AE", "BD").map { Phones.flag(it) } }
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val flagStyle = Type.heading
        val dot = D.Blue
        Canvas(modifier.then(dragModifier)) {
            val r = size.minDimension * 0.42f
            radius = r
            val c = center
            val tilt = tiltNow
            // Stars on the page itself: soft blue specks that twinkle and glide with the globe.
            // Bigger (nearer) ones move further than small (far) ones, which gives the depth.
            val unitStar = 1.dp.toPx()
            val tiltShift = (tiltNow - 0.32f)
            for (st in stars) {
                val depth = st[2] / 1.9f
                val gx = ((st[0] + turn * 0.045f * depth) % 1f + 1f) % 1f
                val gy = ((st[1] + tiltShift * 0.08f * depth) % 1f + 1f) % 1f
                val sx = gx * size.width
                val sy = gy * size.height
                // Keep the page clear just behind the globe's face.
                if (kotlin.math.hypot(sx - c.x, sy - c.y) < r * 1.02f) continue
                val twinkle = 0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(clock * st[4] + st[3]))
                val ink = Color(0xFF6FA8E8)
                drawCircle(ink.copy(alpha = 0.55f * twinkle), radius = unitStar * st[2] * 0.75f, center = Offset(sx, sy))
                if (st[2] > 1.6f) {
                    // The brightest few get a small four-point sparkle.
                    val arm = unitStar * 4.5f * twinkle
                    drawLine(ink.copy(alpha = 0.45f * twinkle), Offset(sx - arm, sy), Offset(sx + arm, sy), strokeWidth = unitStar * 0.8f)
                    drawLine(ink.copy(alpha = 0.45f * twinkle), Offset(sx, sy - arm), Offset(sx, sy + arm), strokeWidth = unitStar * 0.8f)
                }
            }
            // Atmosphere, then the sphere itself lit from the top left.
            drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(Color(0xFF8CC8FF).copy(alpha = 0.28f), Color(0xFF8CC8FF).copy(alpha = 0.08f), Color.Transparent),
                center = c, radius = r * 1.16f), radius = r * 1.16f, center = c)
            drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(Color(0xFFF7FBFF), Color(0xFFE6F2FF), Color(0xFFCFE6FF)),
                center = Offset(c.x - r * 0.35f, c.y - r * 0.4f), radius = r * 1.5f), radius = r, center = c)
            val ct = kotlin.math.cos(tilt); val st = kotlin.math.sin(tilt)
            val cy = kotlin.math.cos(turn); val sy = kotlin.math.sin(turn)
            fun place(p: FloatArray): Triple<Float, Float, Float> {
                val x = p[0] * cy + p[2] * sy
                val z = -p[0] * sy + p[2] * cy
                val y = p[1] * ct - z * st
                val z2 = p[1] * st + z * ct
                return Triple(c.x + x * r, c.y - y * r, z2)
            }
            val unit = 1.dp.toPx()
            for (p in dots) {
                val (x, y, z) = place(p)
                if (z < -0.05f) continue
                drawCircle(dot.copy(alpha = 0.10f + 0.32f * z), radius = unit * (0.9f + 0.7f * z), center = Offset(x, y))
            }
            pins.forEachIndexed { i, p ->
                val (x, y, z) = place(p)
                if (z < 0.15f) return@forEachIndexed
                val phase = (beat + i * 0.17f) % 1f
                drawCircle(dot.copy(alpha = 0.35f * (1 - phase) * z), radius = unit * (4f + 14f * phase), center = Offset(x, y))
                drawCircle(dot.copy(alpha = 0.95f * z), radius = unit * 3.6f, center = Offset(x, y))
                drawCircle(Color.White.copy(alpha = z), radius = unit * 1.4f, center = Offset(x, y))
                // The country itself, beside its point: the globe says "these places", not "dots".
                if (z > 0.35f) {
                    val label = measurer.measure(flags[i], flagStyle)
                    val k = 0.75f + 0.25f * z
                    scale(k, Offset(x, y)) {
                        drawText(label, topLeft = Offset(x + unit * 7f, y - label.size.height / 2f), alpha = ((z - 0.35f) / 0.4f).coerceIn(0f, 1f))
                    }
                }
            }
        }
    }

    /** Marks this piece as the same thing on home and plans, so it moves instead of fading. */
    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable
    private fun Modifier.shared(key: String, s: SharedTransitionScope?, a: AnimatedVisibilityScope?, bounds: Boolean = false): Modifier {
        if (s == null || a == null) return this
        val move = { _: androidx.compose.ui.geometry.Rect, _: androidx.compose.ui.geometry.Rect -> tween<androidx.compose.ui.geometry.Rect>(480, easing = FastOutSlowInEasing) }
        return with(s) {
            if (bounds) this@shared.sharedBounds(rememberSharedContentState(key), a, boundsTransform = move,
                resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds())
            else this@shared.sharedElement(rememberSharedContentState(key), a, boundsTransform = move)
        }
    }

    /** A ring of dots going round while the network is looked up. */
    @Composable
    private fun DottedSpinner(modifier: Modifier = Modifier, color: Color = D.Blue) {
        val turn by rememberInfiniteTransition(label = "dots").animateFloat(
            0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "turn")
        Canvas(modifier.graphicsLayer { rotationZ = turn }) {
            val count = 10
            val r = size.minDimension / 2
            val dot = r * 0.16f
            for (i in 0 until count) {
                val a = Math.toRadians(i * 360.0 / count - 90)
                val c = Offset(center.x + ((r - dot) * Math.cos(a)).toFloat(), center.y + ((r - dot) * Math.sin(a)).toFloat())
                drawCircle(color.copy(alpha = 0.15f + 0.85f * (i + 1) / count), radius = dot * (0.6f + 0.4f * (i + 1) / count), center = c)
            }
        }
    }

    @Composable
    private fun Header(title: String? = null, onBack: (() -> Unit)? = null) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Space.gutter, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                BackArrow(onBack)
                Spacer(Modifier.width(8.dp))
                Text(title.orEmpty(), style = Type.heading, color = D.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
            } else {
                BrandMark(30.dp)
                Spacer(Modifier.width(10.dp))
                Text(App.NAME, style = Type.title, color = D.Black)
                Spacer(Modifier.weight(1f))
            }
            if (screen != Screen.HISTORY) WalletChip()
        }
    }

    @Composable
    private fun WalletChip() {
        val addr = connected
        val label = if (addr == null) "Connect" else "${addr.take(4)}…${addr.takeLast(4)}"
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(if (addr == null) D.Blue else D.Fill)
                .clickable { if (addr == null) connect() else screen = Screen.HISTORY }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(label, style = Type.caption.copy(fontWeight = Type.body.fontWeight), color = if (addr == null) D.White else D.Black)
        }
    }

    @Composable
    private fun Home(s: SharedTransitionScope? = null, a: AnimatedVisibilityScope? = null) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            Header()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter)) {
                Spacer(Modifier.height(8.dp))
                Text("Recharge your phone", style = Type.title, color = D.Black)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Fact("140+ countries supported")
                    Fact("Paid in USDC")
                }
                Spacer(Modifier.height(24.dp))
                Text("Enter your phone number", style = Type.caption.copy(fontWeight = Type.body.fontWeight), color = D.Black)
                Spacer(Modifier.height(8.dp))
                NumberField(s, a)
                problem?.let { Spacer(Modifier.height(12.dp)); Text(it, style = Type.caption, color = D.DangerInk) }
                // Only when the number did not tell us its network: pick it by hand, then plans open.
                AnimatedVisibility(number != null && !detecting && operator == null && !operatorsLoading,
                    enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column { Spacer(Modifier.height(12.dp)); OperatorRow() }
                }
                // Back from the plans with nothing changed: one tap returns to them.
                AnimatedVisibility(number != null && !detecting && operator != null && !autoPending && !quietSwitch && !againBusy,
                    enter = fadeIn(), exit = fadeOut()) {
                    Column { Spacer(Modifier.height(20.dp)); PrimaryButton("See plans", { openPlans() }) }
                }
                if (history.isNotEmpty()) {
                    Spacer(Modifier.height(Space.section))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent", style = Type.heading, color = D.Black, modifier = Modifier.weight(1f))
                        Text("See all", style = Type.body, color = D.BlueInk, modifier = Modifier.clickable { screen = Screen.HISTORY })
                    }
                    Spacer(Modifier.height(Space.lead))
                    history.take(3).forEach { o -> OrderRow(o) { openOrder(o) } }
                }
                Spacer(Modifier.height(24.dp))
                // Where CellUp reaches: a slow globe with the countries pulsing. No location needed.
                DotGlobe(Modifier.fillMaxWidth().aspectRatio(0.95f))
                Spacer(Modifier.height(8.dp))
            }
            // Support, always in the same place: bottom centre, plain words, a thumb-sized target.
            if (connected != null) {
                val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val down by press.collectIsPressedAsState()
                Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                    Text("Support", style = Type.body.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                        color = if (down) D.BlueInk else D.Black,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (down) PLAN_WASH else Color.Transparent)
                            .clickable(press, indication = null) { refreshTickets(); screen = Screen.SUPPORT }
                            .padding(horizontal = 28.dp, vertical = 14.dp))
                }
            }
        }
    }

    @Composable
    private fun NumberField(s: SharedTransitionScope? = null, a: AnimatedVisibilityScope? = null) {
        Row(
            Modifier.fillMaxWidth().shared("numberbox", s, a, bounds = true).heightIn(min = 64.dp)
                .clip(RoundedCornerShape(18.dp)).background(D.Fill),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.clickable { picking = "country" }.padding(start = 12.dp, end = 10.dp, top = 13.dp, bottom = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Each piece has a partner on the plans bar and turns into it where it stands:
                // flag -> network logo, code -> code, digits -> digits, end mark -> "Change network".
                Box(Modifier.size(38.dp).shared("lead", s, a, bounds = true), contentAlignment = Alignment.Center) {
                    Text(Phones.flag(region), style = Type.heading)
                }
                Spacer(Modifier.width(4.dp))
                Text(Phones.dialCode(region), style = Type.heading.copy(color = D.Black).figure(), modifier = Modifier.shared("dial", s, a))
                Spacer(Modifier.width(4.dp))
                Chevron(12.dp, D.Quiet, turn = 90f)
            }
            Box(Modifier.width(1.dp).height(28.dp).background(D.Line))
            Box(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                if (typed.isEmpty()) Text("Phone number", style = Type.heading.copy(fontWeight = Type.body.fontWeight), color = D.Quiet)
                BasicTextField(
                    value = typed, onValueChange = ::onTyped, singleLine = true,
                    textStyle = Type.heading.copy(color = D.Black).figure(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    cursorBrush = SolidColor(D.Blue), modifier = Modifier.fillMaxWidth().shared("digits", s, a),
                )
            }
            // Dots go round while the network is found; then they become its logo.
            val stage = when { detecting -> 1; number != null && operator != null -> 2; else -> 0 }
            AnimatedContent(stage, transitionSpec = {
                (fadeIn(tween(240)) + scaleIn(tween(360, easing = FastOutSlowInEasing), initialScale = 0.3f)) togetherWith
                    (fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 1.6f))
            }, label = "mark") { st ->
                when (st) {
                    1 -> DottedSpinner(Modifier.padding(end = 16.dp).size(28.dp))
                    2 -> OperatorLogo(operator, 38.dp, Modifier.padding(end = 12.dp).shared("right", s, a, bounds = true))
                    else -> Spacer(Modifier.size(1.dp))
                }
            }
        }
        if (typed.count { it.isDigit() } >= 6 && number == null) {
            Spacer(Modifier.height(6.dp))
            Text("That doesn't look like a ${countryName(region)} number yet.", style = Type.caption, color = D.Quiet)
        }
    }

    @Composable
    private fun OperatorRow() {
        val op = operator
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.5.dp, D.Line, RoundedCornerShape(18.dp))
                .clickable(enabled = operators.isNotEmpty()) { picking = "operator" }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OperatorLogo(op, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        detecting -> "Finding the network…"
                        op != null -> op.name
                        operatorsLoading -> "Loading networks…"
                        operators.isEmpty() -> "No networks in ${countryName(region)}"
                        else -> "Choose network"
                    },
                    style = Type.body, color = D.Black,
                )
                val note = if (op != null) detectedNote ?: "Tap to change" else "${operators.size} networks in ${countryName(region)}"
                if (!operatorsLoading && !detecting && operators.isNotEmpty()) Text(note, style = Type.caption, color = D.Quiet)
            }
            if (operatorsLoading || detecting) CircularProgressIndicator(Modifier.size(18.dp), color = D.Blue, strokeWidth = 2.dp)
            else if (operators.isNotEmpty()) Chevron()
        }
    }

    /** A network's logo, whole: fitted inside a white tile with a little air, never cropped. */
    @Composable
    private fun OperatorLogo(op: TopUp.Operator?, side: Dp, modifier: Modifier = Modifier) {
        val shape = RoundedCornerShape(10.dp)
        Box(modifier.size(side).clip(shape).background(if (op?.logo != null) D.White else D.Fill).border(1.dp, PLAN_EDGE, shape),
            contentAlignment = Alignment.Center) {
            if (op?.logo != null) {
                AsyncImage(op.logo, contentDescription = op.name, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(side * 0.06f))
            } else {
                Text(op?.name?.take(1) ?: "?", style = Type.heading, color = D.Black)
            }
        }
    }

    @Composable
    private fun PlansScreen(s: SharedTransitionScope? = null, a: AnimatedVisibilityScope? = null) {
        val n = number
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            Header(operator?.name ?: "Plans") { screen = Screen.HOME }
            if (n != null) {
                // The number field from home, landed: logo and number, and the way to change the network.
                Row(Modifier.padding(horizontal = Space.gutter).fillMaxWidth().shared("numberbox", s, a, bounds = true)
                    .heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).background(D.Fill).clickable { screen = Screen.HOME }
                    .padding(start = 12.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OperatorLogo(operator, 38.dp, Modifier.shared("lead", s, a, bounds = true))
                    Spacer(Modifier.width(10.dp))
                    // The digits exactly as typed, so the two lines are the same text and only move.
                    val own = typed.trim()
                    if (!own.startsWith("+")) {
                        Text(Phones.dialCode(region), style = Type.heading.copy(color = D.Black).figure(), maxLines = 1,
                            modifier = Modifier.shared("dial", s, a))
                        Spacer(Modifier.width(8.dp))
                    }
                    FitText(androidx.compose.ui.text.AnnotatedString(if (own.startsWith("+")) own else own.ifEmpty { n.national }),
                        Type.heading.copy(color = D.Black).figure(), Modifier.weight(1f).shared("digits", s, a))
                    Text("Change network", style = Type.caption.copy(fontWeight = Type.body.fontWeight), color = D.BlueInk, maxLines = 1,
                        modifier = Modifier.shared("right", s, a, bounds = true).clip(RoundedCornerShape(50))
                            .clickable { picking = "operator" }.padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
            // The bar moves first; the list joins once it has landed, so the move never stutters.
            val landed = a == null || !a.transition.isRunning
            if (!landed) return@Column
            val fade = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(Unit) { fade.animateTo(1f, tween(260)) }
            Column(Modifier.weight(1f).graphicsLayer { alpha = fade.value; translationY = (1f - fade.value) * 24f }) {
            val shelves = TopUp.Shelf.entries.filter { s -> plans.any { TopUp.Shelf.of(it.category) == s } }
            // Page 0 is "All"; one page per kind after it. Tabs and a sideways swipe move the same pager.
            val pages: List<TopUp.Shelf?> = if (shelves.size > 1) listOf(null) + shelves else listOf(null)
            val pager = rememberPagerState { pages.size }
            val scope = rememberCoroutineScope()
            LaunchedEffect(pager.currentPage, pages.size) { shelf = pages.getOrNull(pager.currentPage) }
            if (pages.size > 1 && !plansLoading && plansProblem == null) {
                // AirPay's underlined tabs.
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter - 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pages.forEachIndexed { i, s ->
                        PlanTab(s?.title ?: "All", i == pager.currentPage) { scope.launch { pager.animateScrollToPage(i) } }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            when {
                plansLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = D.Blue, strokeWidth = 2.dp)
                }
                plansProblem != null -> Column(Modifier.padding(Space.gutter)) {
                    Text(plansProblem.orEmpty(), style = Type.body, color = D.Quiet)
                    Spacer(Modifier.height(16.dp))
                    SecondaryButton("Try again", { plansKey = null; openPlans() }, Modifier.fillMaxWidth())
                }
                else -> HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { pages.getOrNull(it)?.key ?: "all" }) { page ->
                    val only = pages.getOrNull(page)
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Space.gutter, vertical = 4.dp)) {
                        // "All" is one list by price; a kind's own page keeps the server's order.
                        val shown = if (only == null) plans.sortedBy { it.usdcValue }
                            else plans.filter { TopUp.Shelf.of(it.category) == only }
                        items(shown, key = { it.id }) { p ->
                            PlanCard(p) { problem = null; sheet = Sheet.Review(p) }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
            }
        }
    }

    /** AirPay's plan card: blue wash, price in its own column, validity and data side by side. */
    @Composable
    private fun PlanCard(p: TopUp.Plan, onPick: () -> Unit) {
        val shape = RoundedCornerShape(20.dp)
        val tag = tagOf(p)
        Column(
            Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(shape)
                .background(PLAN_WASH).border(1.5.dp, PLAN_EDGE, shape).clickable(onClick = onPick),
        ) {
            if (tag != null) {
                Text(tag, color = D.Black, style = Type.micro, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(bottomEnd = 12.dp)).background(D.Highlight)
                        .padding(horizontal = 14.dp, vertical = 6.dp))
            }
            Column(Modifier.padding(start = 16.dp, end = 12.dp, top = if (tag != null) 12.dp else 16.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(98.dp)) {
                        FitText(currencyStyled(face(p) ?: "${p.usdc} USDC"), Type.title.figure().copy(color = D.Black))
                        Text("${p.usdc} USDC", color = D.Quiet, style = Type.caption.figure(), maxLines = 1)
                    }
                    Box(Modifier.width(1.5.dp).height(40.dp).background(PLAN_EDGE))
                    Spacer(Modifier.width(14.dp))
                    // No validity: keep its column empty so every card's second figure lines up.
                    validityOf(p)?.let { Labelled("Validity", it, Modifier.width(92.dp)) } ?: Spacer(Modifier.width(92.dp))
                    val (label, value) = mainOf(p)
                    Labelled(label, value, Modifier.weight(1f))
                    Box(Modifier.size(32.dp).clip(CircleShape).background(D.White), contentAlignment = Alignment.Center) {
                        HiIcon(Hi.ArrowRight, D.BlueInk, 18.dp)
                    }
                }
                val lines = summaryOf(p)
                if (lines.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(1.5.dp).background(PLAN_EDGE))
                    Spacer(Modifier.height(10.dp))
                    lines.take(2).forEach { BulletLine(it, maxLines = 1) }
                    if (lines.size > 2) {
                        Text("+${lines.size - 2} more", color = D.Quiet, style = Type.caption, modifier = Modifier.padding(start = 14.dp, top = 2.dp))
                    }
                }
            }
        }
    }

    /** AirPay's payment sheet: the same layout from review to paid, only the button and status move. */
    @Composable
    private fun PaySheet(s: Sheet) {
        val n = number
        val working = busy != null && s is Sheet.Review
        Tray(onClose = { if (busy == null) sheet = null }, dismissible = busy == null, liftWithKeyboard = s is Sheet.Help || s is Sheet.Review) {
          // One tray for the whole journey: when what it shows changes (an order, then the plan to
          // buy again, then help), the old content fades, the tray eases to its new height and the
          // new content fades in. Nothing jumps.
          AnimatedContent(s, contentKey = { it::class }, transitionSpec = {
              (fadeIn(tween(260, delayMillis = 50)) togetherWith fadeOut(tween(110))) using
                  androidx.compose.animation.SizeTransform(clip = false) { _, _ -> tween(380, easing = FastOutSlowInEasing) }
          }, label = "sheet") { s ->
          Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(Space.lead))
            when (s) {
                is Sheet.Review -> {
                    val plan = s.plan
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${face(plan) ?: plan.title} plan details", color = D.Black, style = Type.title,
                            modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!working) Box(Modifier.size(44.dp).clip(CircleShape).clickable { sheet = null }, contentAlignment = Alignment.Center) {
                            HiIcon(Hi.Close, D.Black, 22.dp, contentDescription = "Close")
                        } else Spacer(Modifier.size(44.dp))
                    }
                    Spacer(Modifier.height(Space.tight))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OperatorLogo(operator, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(n?.international.orEmpty(), color = D.Black, style = Type.body.figure())
                    }
                    Spacer(Modifier.height(Space.lead))
                    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                        Row(Modifier.fillMaxWidth()) {
                            SheetStat("Validity", validityOf(plan) ?: "-", Modifier.weight(1f))
                            val (label, value) = mainOf(plan)
                            SheetStat(label, value, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(Space.lead))
                        RowLine()
                        val facts = summaryOf(plan)
                        val words = plan.description?.let { Plans.points(tidy(it)) }.orEmpty()
                        if (facts.isNotEmpty() || words.isNotEmpty()) {
                            Spacer(Modifier.height(Space.lead))
                            Text("Plan details", color = D.Black, style = Type.heading)
                            Spacer(Modifier.height(Space.tight))
                            facts.forEach { BulletLine(it, color = D.Black, style = Type.body) }
                            if (words.isNotEmpty()) {
                                Spacer(Modifier.height(Space.inline))
                                words.take(8).forEach { BulletLine(it) }
                            }
                        }
                        Spacer(Modifier.height(Space.lead))
                    }
                    RowLine()
                    Spacer(Modifier.height(Space.inline))
                    // The receipt email: asked once, kept if they say so, used every time after.
                    Text("Email for your receipt", color = D.Black, style = Type.body)
                    Spacer(Modifier.height(Space.tight))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.5.dp, D.Line, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 14.dp)) {
                        if (email.isEmpty()) Text("you@example.com", style = Type.body, color = D.Quiet)
                        BasicTextField(email, { email = it.trim().take(120) }, singleLine = true, enabled = !working,
                            textStyle = Type.body.copy(color = D.Black), cursorBrush = SolidColor(D.Blue),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                    }
                    Row(Modifier.padding(top = 10.dp).clickable(enabled = !working) { saveEmail = !saveEmail }, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (saveEmail) D.Blue else D.White)
                            .border(1.5.dp, if (saveEmail) D.Blue else D.Line, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                            if (saveEmail) HiIcon(Hi.Tick, D.White, 14.dp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("Save this email for next time", style = Type.body, color = D.Black)
                    }
                    Spacer(Modifier.height(Space.inline))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Pay with", color = D.Black, style = Type.body, modifier = Modifier.weight(1f))
                        Row(Modifier.clip(RoundedCornerShape(999.dp)).background(D.Black).heightIn(min = 40.dp).padding(start = 8.dp, end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.token_usdc), null, Modifier.size(24.dp).clip(CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text("USDC", color = D.White, style = Type.caption)
                        }
                    }
                    Spacer(Modifier.height(Space.inline))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("You pay", color = D.Black, style = Type.body, modifier = Modifier.weight(1f))
                        Text("${plan.usdc} USDC", color = D.Black, style = Type.heading.figure())
                    }
                    Spacer(Modifier.height(Space.tight))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Network fee", color = D.Quiet, style = Type.caption, modifier = Modifier.weight(1f))
                        Text("A little SOL, from your wallet", color = D.Quiet, style = Type.caption)
                    }
                    problem?.let { msg ->
                        Spacer(Modifier.height(Space.inline))
                        Text(msg, color = D.DangerInk, style = Type.caption,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(D.DangerInk.copy(alpha = 0.08f))
                                .padding(horizontal = 14.dp, vertical = 12.dp))
                    }
                    Spacer(Modifier.height(Space.inline))
                    PrimaryButton(busy ?: if (connected == null) "Connect wallet" else "Proceed with payment", { pay(plan) },
                        Modifier.fillMaxWidth(), loading = busy != null)
                    Spacer(Modifier.height(Space.inline))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        HiIcon(Hi.Info, D.Quiet, 14.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Delivered by ${if (plan.id.startsWith("br:")) "Bitrefill" else "Cryptorefills"}. No ${App.NAME} fee.", color = D.Quiet, style = Type.caption, maxLines = 1)
                    }
                }
                is Sheet.Paid -> {
                    val o = s.order
                    val st = statusOf(o)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(st.wash), contentAlignment = Alignment.Center) {
                            when (st.kind) {
                                Kind.DONE -> HiIcon(Hi.Tick, st.ink, 26.dp)
                                Kind.FAILED, Kind.CANCELLED, Kind.REFUNDED -> HiIcon(Hi.Alert, st.ink, 24.dp)
                                else -> DottedSpinner(Modifier.size(26.dp), st.ink)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(st.headline, color = D.Black, style = Type.heading)
                            Spacer(Modifier.height(2.dp))
                            Text(st.detail(o), color = D.Quiet, style = Type.caption)
                        }
                        Spacer(Modifier.width(8.dp))
                        HelpPill { sheet = Sheet.Help(o) }
                    }
                    Spacer(Modifier.height(Space.lead))
                    Timeline(o)
                    Spacer(Modifier.height(Space.tight))
                    RowLine()
                    Spacer(Modifier.height(Space.tight))
                    PurchaseFact("Number", Phones.pretty(o.phone))
                    PurchaseFact("Network", o.operator)
                    PurchaseFact("Plan", orderPlan(o))
                    PurchaseFact("Amount", "${o.usdc} USDC")
                    if (o.createdAt > 0) PurchaseFact("Placed", java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(o.createdAt)))
                    PurchaseFact("Order", o.id.removePrefix("cu_").take(10).uppercase())
                    Spacer(Modifier.height(Space.inline))
                    if (o.finished && s.plan == null) {
                        PrimaryButton(if (againBusy) "Opening plans…" else "Recharge again", { if (!againBusy) again(o) },
                            Modifier.fillMaxWidth(), loading = againBusy)
                    } else
                    PrimaryButton(if (o.finished) "Done" else "OK, notify me", { sheet = null }, Modifier.fillMaxWidth())
                    s.signature?.let { sig ->
                        TextLink("View on explorer", {
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://solscan.io/tx/$sig"))) }
                        }, modifier = Modifier.fillMaxWidth())
                    }
                }
                is Sheet.Help -> HelpSheet(s)
                is Sheet.Problem -> {
                    Text(s.title, color = D.DangerInk, style = Type.heading)
                    Spacer(Modifier.height(Space.tight))
                    Text(s.detail, color = D.Black, style = Type.body)
                    Spacer(Modifier.height(Space.section))
                    PrimaryButton("Try again", { problem = null; sheet = Sheet.Review(s.plan) }, Modifier.fillMaxWidth())
                    TextLink("Close", { sheet = null }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (s !is Sheet.Paid && s !is Sheet.Help) Spacer(Modifier.height(Space.lead))
          }
          }
        }
    }

    /** The way to support from any order: a quiet pill, never louder than the order itself. */
    @Composable
    private fun HelpPill(onClick: () -> Unit) {
        Row(Modifier.clip(RoundedCornerShape(50)).background(D.Fill).clickable(onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            HiIcon(Hi.Info, D.Black, 16.dp)
            Spacer(Modifier.width(6.dp))
            Text("Help", style = Type.caption.copy(fontWeight = Type.body.fontWeight), color = D.Black)
        }
    }

    /** What support will read about [o]: the same facts the server attaches from its records. */
    private fun supportSubject(o: TopUp.Order) =
        "CellUp help · Order ${o.id.removePrefix("cu_").take(10).uppercase()} · ${o.operator} ${orderPlan(o)} · ${statusOf(o).word}"

    private fun supportFacts(o: TopUp.Order): List<Pair<String, String>> = listOfNotNull(
        "Order" to o.id.removePrefix("cu_").take(10).uppercase(),
        "Status" to statusOf(o).word,
        "Network" to o.operator,
        "Number" to Phones.pretty(o.phone),
        "Plan" to (orderPlan(o)),
        "Amount paid" to "${o.usdc} USDC",
        if (o.createdAt > 0) "Placed" to java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(o.createdAt)) else null,
    )

    private var helpQuery by mutableStateOf("")
    private var helpBusy by mutableStateOf(false)
    private var helpProblem by mutableStateOf<String?>(null)

    private fun sendHelp(o: TopUp.Order) {
        val mail = email.trim()
        val q = helpQuery.trim()
        if (!Identity.looksLikeEmail(mail)) { helpProblem = "Enter the email we should reply to."; return }
        if (q.length < 5) { helpProblem = "Tell us what went wrong, in a sentence or two."; return }
        val addr = connected ?: return connect { sendHelp(o) }
        if (helpBusy) return
        helpBusy = true; helpProblem = null
        lifecycleScope.launch {
            try {
                if (!ensureSignedIn(addr)) { helpProblem = "Approve the sign-in in your wallet to send."; return@launch }
                when (val r = TopUp.support(o.id, mail, q)) {
                    is TopUp.Helped.Sent -> {
                        if (Identity.email(this@CellUpActivity).isNullOrBlank()) Identity.setEmail(this@CellUpActivity, mail)
                        if (!r.mailed) mailApp(o, q)
                        helpQuery = ""
                        sheet = Sheet.Help(o, sentTo = mail, viaMailApp = !r.mailed, query = q)
                        refreshTickets()
                        Analytics.event("support_sent", "state" to o.state)
                    }
                    TopUp.Helped.MailOff -> mailApp(o, q)
                    is TopUp.Helped.Refused -> helpProblem = r.message
                    TopUp.Helped.SignIn -> helpProblem = "Please sign in again, then send."
                    TopUp.Helped.Offline -> helpProblem = "You're offline. Check your connection and send again."
                }
            } finally {
                helpBusy = false
            }
        }
    }

    /** The email body support reads: the customer's words first, then the order, one fact a line. */
    private fun supportBody(o: TopUp.Order, q: String): String = buildString {
        appendLine("Hi CellUp support,")
        appendLine()
        appendLine(q.trim())
        appendLine()
        appendLine("Thanks,")
        appendLine(email.trim())
        appendLine()
        appendLine("━━━━━━━━━━━━━━━━━━━━")
        appendLine("ORDER DETAILS")
        appendLine("━━━━━━━━━━━━━━━━━━━━")
        supportFacts(o).forEach { (k, v) -> appendLine("$k: $v") }
        appendLine("Delivered by: ${if (o.supplier == "bitrefill") "Bitrefill" else "Cryptorefills"}")
        appendLine("Reference: ${o.id}")
        appendLine()
        appendLine("Sent from the CellUp app")
    }

    /** No server mail account: the same message, ready to send from the phone's mail app. */
    private fun mailApp(o: TopUp.Order, q: String) {
        // Extras, not only a mailto query: Gmail reads EXTRA_TEXT and keeps its line breaks.
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, supportSubject(o))
            putExtra(Intent.EXTRA_TEXT, supportBody(o, q))
        }
        runCatching { startActivity(intent) }
            .onFailure { helpProblem = "No mail app found. Write to $SUPPORT_EMAIL with order ${o.id.removePrefix("cu_").take(10).uppercase()}." }
    }

    /**
     * After Send. Through our server the message is known to have reached support. Through the
     * phone's mail app it is not: CellUp only handed it over, and cannot see whether Send was
     * pressed there. So that path asks, on the way back, instead of claiming it went.
     */
    @Composable
    private fun HelpDone(s: Sheet.Help) {
        val o = s.order
        val waiting = s.viaMailApp && !s.confirmed
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(if (waiting) PLAN_WASH else Color(0xFFE5F5EB)),
                contentAlignment = Alignment.Center) {
                if (waiting) HiIcon(Hi.LinkOut, D.BlueInk, 28.dp) else HiIcon(Hi.Tick, D.SuccessInk, 32.dp)
            }
            Spacer(Modifier.height(Space.lead))
            Text(if (waiting) "Did your email go out?" else "Message sent", style = Type.title, color = D.Black, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Space.tight))
            Text(
                if (waiting) "We opened your mail app with your message and the order details. It only reaches us once you press Send there."
                else "We have received your message and all the details of this recharge.",
                style = Type.body, color = D.Quiet, textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(Space.lead))
        if (!waiting) {
            // Where the answer goes and when: the two things a worried person wants to know.
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(D.Fill).padding(14.dp)) {
                Text("We'll reply to", style = Type.micro, color = D.Quiet)
                Spacer(Modifier.height(2.dp))
                Text(s.sentTo.orEmpty(), style = Type.body.copy(fontWeight = Type.heading.fontWeight), color = D.BlueInk,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Text("Usually within", style = Type.micro, color = D.Quiet)
                Spacer(Modifier.height(2.dp))
                Text("24 hours", style = Type.body.copy(fontWeight = Type.heading.fontWeight), color = D.Black)
                Spacer(Modifier.height(10.dp))
                Text("About", style = Type.micro, color = D.Quiet)
                Spacer(Modifier.height(2.dp))
                Text("Order #${o.id.removePrefix("cu_").take(10).uppercase()} · ${o.operator} ${orderPlan(o)}",
                    style = Type.body, color = D.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(Space.lead))
            PrimaryButton("Done", { sheet = Sheet.Paid(null, o, null) }, Modifier.fillMaxWidth())
        } else {
            PrimaryButton("Yes, I sent it", { sheet = s.copy(confirmed = true) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.tight))
            TextLink("Open my mail app again", { mailApp(o, s.query) }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(Space.tight))
    }

    @Composable
    private fun HelpSheet(s: Sheet.Help) {
        val o = s.order
        if (s.sentTo != null) { HelpDone(s); return }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Get help", color = D.Black, style = Type.title, modifier = Modifier.weight(1f))
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { sheet = Sheet.Paid(null, o, null) }, contentAlignment = Alignment.Center) {
                HiIcon(Hi.Close, D.Black, 22.dp, contentDescription = "Close")
            }
        }
        Text("Tell us what happened. The order details go with your message.", style = Type.caption, color = D.Quiet)
        Spacer(Modifier.height(Space.lead))
        // With the keyboard up, the form scrolls in a shorter window so Send stays in reach.
        val typing = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        Column(Modifier.heightIn(max = if (typing) 230.dp else 460.dp).verticalScroll(rememberScrollState())) {
            Text("Your email", color = D.Black, style = Type.caption)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.5.dp, D.Line, RoundedCornerShape(14.dp))
                .padding(14.dp)) {
                if (email.isEmpty()) Text("you@example.com", style = Type.body, color = D.Quiet)
                BasicTextField(email, { email = it.trim().take(120) }, singleLine = true, enabled = !helpBusy,
                    textStyle = Type.body.copy(color = D.Black), cursorBrush = SolidColor(D.Blue),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            Text("What happened?", color = D.Black, style = Type.caption)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 110.dp).clip(RoundedCornerShape(14.dp))
                .border(1.5.dp, D.Line, RoundedCornerShape(14.dp)).padding(14.dp)) {
                if (helpQuery.isEmpty()) Text("For example: I paid but the recharge hasn't arrived.", style = Type.body, color = D.Quiet)
                BasicTextField(helpQuery, { helpQuery = it.take(2000) }, enabled = !helpBusy,
                    textStyle = Type.body.copy(color = D.Black), cursorBrush = SolidColor(D.Blue),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 82.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("Subject", color = D.Black, style = Type.caption)
            Spacer(Modifier.height(6.dp))
            Text(supportSubject(o), style = Type.body, color = D.Quiet,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(D.Fill).padding(14.dp))
            Spacer(Modifier.height(12.dp))
            Text("Sent with your message", color = D.Black, style = Type.caption)
            Spacer(Modifier.height(4.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(D.Fill).padding(horizontal = 14.dp, vertical = 8.dp)) {
                supportFacts(o).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(k, style = Type.caption, color = D.Quiet, modifier = Modifier.width(80.dp))
                        Text(v, style = Type.caption, color = D.Black, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        helpProblem?.let { Spacer(Modifier.height(8.dp)); Text(it, style = Type.caption, color = D.DangerInk) }
        Spacer(Modifier.height(Space.lead))
        PrimaryButton(if (helpBusy) "Sending…" else "Send", { sendHelp(o) }, Modifier.fillMaxWidth(), loading = helpBusy)
        Spacer(Modifier.height(Space.tight))
    }

    private val tickets = mutableStateListOf<TopUp.Ticket>()
    private var ticketsLoading by mutableStateOf(false)

    private fun refreshTickets() {
        if (!Session.valid(connected)) return
        ticketsLoading = tickets.isEmpty()
        lifecycleScope.launch {
            TopUp.tickets()?.let { tickets.clear(); tickets.addAll(it) }
            ticketsLoading = false
        }
    }

    /** Every help request this wallet has sent, each with the recharge it is about. */
    @Composable
    private fun SupportScreen() {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            Header("Support") { screen = Screen.HOME }
            when {
                ticketsLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = D.Blue, strokeWidth = 2.dp)
                }
                tickets.isEmpty() -> Column(Modifier.padding(Space.gutter)) {
                    Text("No help requests yet", style = Type.heading, color = D.Black)
                    Spacer(Modifier.height(6.dp))
                    Text("If a recharge goes wrong, open it under Recent and tap Help. Your requests show up here.",
                        style = Type.body, color = D.Quiet)
                }
                else -> LazyColumn(contentPadding = PaddingValues(horizontal = Space.gutter, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("We reply by email. Tap a request to see its recharge.", style = Type.caption, color = D.Quiet)
                    }
                    items(tickets, key = { it.id }) { t -> TicketCard(t) }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    /** One help request: when and about which order up top, the recharge, your words, where we reply. */
    @Composable
    private fun TicketCard(t: TopUp.Ticket) {
        val o = t.order
        val shape = RoundedCornerShape(20.dp)
        val sent = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(t.createdAt))
        Column(Modifier.fillMaxWidth().clip(shape).border(1.5.dp, D.Line, shape)
            .clickable(enabled = o != null) { o?.let { openOrder(it) } }) {
            // Top strip: when it was asked, and the order number support will look up.
            Row(Modifier.fillMaxWidth().background(D.Fill).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Help request", style = Type.micro, color = D.Quiet)
                    Text(sent, style = Type.caption, color = D.Black)
                }
                if (o != null) {
                    Text("#" + o.id.removePrefix("cu_").take(10).uppercase(),
                        style = Type.caption.copy(fontWeight = Type.body.fontWeight, letterSpacing = 0.6.sp).figure(), color = D.BlueInk,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(PLAN_WASH)
                            .border(1.dp, PLAN_EDGE, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
            Column(Modifier.padding(14.dp)) {
                if (o != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Phones.flag(o.country), style = Type.heading)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${o.operator} · ${orderPlan(o)}", style = Type.body.copy(fontWeight = Type.heading.fontWeight),
                                color = D.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${Phones.pretty(o.phone)} · ${o.usdc} USDC", style = Type.caption.figure(), color = D.Quiet, maxLines = 1)
                        }
                        Spacer(Modifier.width(8.dp))
                        StatusBadge(statusOf(o))
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(t.query, style = Type.body, color = D.Black, maxLines = 5, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(D.Fill)
                        .padding(horizontal = 12.dp, vertical = 10.dp))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("We'll reply to", style = Type.micro, color = D.Quiet)
                        Text(t.email, style = Type.caption, color = D.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (t.mailed) "Emailed" else "Via your mail app", style = Type.micro,
                        color = if (t.mailed) D.SuccessInk else D.Quiet)
                }
            }
        }
    }

    /** Your wallet: who you are here, everything it did, and the way out, kept apart and red. */
    @Composable
    private fun HistoryScreen() {
        val addr = connected
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            Header("Your wallet") { screen = Screen.HOME }
            if (addr == null) {
                Column(Modifier.padding(Space.gutter)) {
                    Text("Connect your wallet to see your recharges.", style = Type.body, color = D.Quiet)
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Connect wallet", { connect() })
                }
                return@Column
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = Space.gutter, vertical = 4.dp)) {
                item {
                    val shape = RoundedCornerShape(20.dp)
                    Column(Modifier.fillMaxWidth().clip(shape).background(PLAN_WASH).border(1.5.dp, PLAN_EDGE, shape).padding(16.dp)) {
                        Text("Connected wallet", style = Type.micro, color = D.Quiet)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${addr.take(6)}…${addr.takeLast(6)}", style = Type.heading.figure(), color = D.Black, modifier = Modifier.weight(1f))
                            Row(Modifier.clip(RoundedCornerShape(50)).background(D.White).clickable {
                                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                    .setPrimaryClip(android.content.ClipData.newPlainText("Wallet", addr))
                            }.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                HiIcon(Hi.Copy, D.Black, 14.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("Copy", style = Type.caption, color = D.Black)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.clip(RoundedCornerShape(50)).clickable {
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://solscan.io/account/$addr"))) }
                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("All transactions on Solscan", style = Type.body.copy(fontWeight = Type.heading.fontWeight), color = D.BlueInk)
                            Spacer(Modifier.width(6.dp))
                            HiIcon(Hi.LinkOut, D.BlueInk, 16.dp)
                        }
                    }
                    Spacer(Modifier.height(Space.lead))
                    Text("Recharges", style = Type.heading, color = D.Black)
                    if (!Session.valid(addr)) {
                        // Orders are only shown to the wallet that placed them: it signs in once.
                        Spacer(Modifier.height(8.dp))
                        Text("Approve a quick sign-in in your wallet to see the recharges made from it.",
                            style = Type.body, color = D.Quiet)
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton(if (signingIn) "Approve in your wallet…" else "Show my recharges", {
                            if (!signingIn) lifecycleScope.launch {
                                signingIn = true
                                try { if (ensureSignedIn(addr)) { refreshHistory(); refreshTickets() } } finally { signingIn = false }
                            }
                        }, Modifier.fillMaxWidth(), loading = signingIn)
                    } else if (history.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("No recharges from this wallet yet.", style = Type.body, color = D.Quiet)
                    }
                }
                items(history, key = { it.id }) { o ->
                    OrderRow(o) { openOrder(o) }
                    RowLine()
                }
            }
            // Leaving is its own decision: apart from everything else, and red.
            Box(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 12.dp)
                .clip(RoundedCornerShape(50)).background(D.Danger).clickable {
                    lifecycleScope.launch {
                        wallet.disconnect(); Session.clear(this@CellUpActivity)
                        connected = null; history.clear(); tickets.clear(); screen = Screen.HOME
                    }
                }.padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                Text("Disconnect wallet", style = Type.body.copy(fontWeight = Type.heading.fontWeight), color = D.White)
            }
        }
    }

    @Composable
    private fun OrderRow(o: TopUp.Order, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Space.row), verticalAlignment = Alignment.CenterVertically) {
            Text(Phones.flag(o.country), style = Type.heading)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${o.operator} · ${orderPlan(o)}", style = Type.body, color = D.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Phones.pretty(o.phone), style = Type.caption, color = D.Quiet)
            }
            StatusBadge(statusOf(o))
        }
    }

    @Composable
    private fun CountryPicker() {
        var q by remember { mutableStateOf("") }
        Tray(onClose = { picking = null }) {
            Column(Modifier.padding(horizontal = 4.dp).heightIn(max = 560.dp)) {
                Text("Country", style = Type.title, color = D.Black)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.5.dp, D.Line, RoundedCornerShape(14.dp)).padding(14.dp)) {
                    if (q.isEmpty()) Text("Search", style = Type.body, color = D.Quiet)
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = Type.body.copy(color = D.Black),
                        cursorBrush = SolidColor(D.Blue), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                val list = countries.filter { q.isBlank() || it.name.contains(q, true) || it.code.equals(q, true) || Phones.dialCode(it.code).contains(q) }
                LazyColumn {
                    items(list, key = { it.code }) { c ->
                        Row(Modifier.fillMaxWidth().clickable { pickRegion(c.code) { number?.let { detect(it) } }; picking = null }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(Phones.flag(c.code), style = Type.heading)
                            Spacer(Modifier.width(12.dp))
                            Text(c.name, style = Type.body, color = D.Black, modifier = Modifier.weight(1f))
                            Text(Phones.dialCode(c.code), style = Type.body, color = D.Quiet)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun OperatorPicker() {
        Tray(onClose = { picking = null }) {
            Column(Modifier.padding(horizontal = 4.dp).heightIn(max = 560.dp)) {
                Text("Network", style = Type.title, color = D.Black)
                Spacer(Modifier.height(4.dp))
                Text("Pick the network this number is on.", style = Type.body, color = D.Quiet)
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(operators, key = { it.id }) { op ->
                        Row(Modifier.fillMaxWidth().clickable {
                            val changed = op.id != operator?.id
                            operator = op; detectedNote = null; picking = null
                            if (number != null && (changed || screen == Screen.HOME)) openPlans()
                        }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            OperatorLogo(op, 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(op.name, style = Type.body, color = D.Black, modifier = Modifier.weight(1f))
                            if (op.id == operator?.id) HiIcon(Hi.Tick, D.BlueInk, 20.dp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // ---- order status ------------------------------------------------------------------------
    // Few states, each true to what the supplier and the chain say. "Waiting" from the supplier
    // after this phone already sent the USDC reads as confirming, not unpaid.
    private enum class Kind { AWAITING, PROCESSING, DONE, CANCELLED, FAILED, REFUNDED }

    private class Status(val kind: Kind, val word: String, val ink: Color, val wash: Color, val headline: String) {
        fun detail(o: TopUp.Order) = when (kind) {
            Kind.DONE -> "${orderPlan(o)} is on ${Phones.pretty(o.phone)}."
            Kind.PROCESSING -> "Your payment is in. The recharge reaches ${Phones.pretty(o.phone)} in a minute or two. We'll notify you."
            Kind.AWAITING -> "Nothing has been paid for this order yet."
            Kind.CANCELLED -> "It was never paid, so nothing was charged."
            Kind.REFUNDED -> "It could not be delivered, so the USDC went back to your wallet."
            Kind.FAILED -> if (o.state == "partial") "Less than the full amount arrived. Contact support with the order number."
                else "This recharge did not go through. If USDC left your wallet, contact support with the order number."
        }
    }

    private fun statusOf(o: TopUp.Order): Status {
        val stale = o.createdAt > 0 && System.currentTimeMillis() - o.createdAt > 31 * 60_000
        val paidHere = o.id in sent
        return when {
            o.delivered -> Status(Kind.DONE, "Successful", D.SuccessInk, Color(0xFFE5F5EB), "Recharge successful")
            o.state == "refunded" -> Status(Kind.REFUNDED, "Refunded", D.Black, D.Fill, "Refunded")
            o.state == "failed" || o.state == "partial" -> Status(Kind.FAILED, "Failed", D.DangerInk, Color(0xFFFDE8E8), "Not completed")
            o.state == "cancelled" || o.state == "expired" || (o.state == "waiting" && stale && !paidHere) ->
                Status(Kind.CANCELLED, "Cancelled", D.Quiet, D.Fill, "Cancelled")
            o.state == "waiting" && !paidHere -> Status(Kind.AWAITING, "Awaiting payment", D.OrangeInk, Color(0xFFFFF1E3), "Waiting for payment")
            else -> Status(Kind.PROCESSING, "Processing", D.BlueInk, PLAN_WASH, "Processing your recharge")
        }
    }

    @Composable
    private fun StatusBadge(st: Status) {
        Text(st.word, style = Type.micro, color = st.ink, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(st.wash).padding(horizontal = 10.dp, vertical = 5.dp))
    }

    private enum class Step { DONE, ACTIVE, WAITING, SKIPPED, FAILED }

    /** Placed, paid, delivered: where this order is, one line each. */
    @Composable
    private fun Timeline(o: TopUp.Order) {
        val st = statusOf(o)
        val paid: Pair<Step, String> = when {
            st.kind == Kind.CANCELLED -> Step.SKIPPED to "Not paid"
            o.state == "partial" -> Step.FAILED to "Only part of the amount arrived"
            o.state == "failed" && o.id !in sent -> Step.FAILED to "Payment failed"
            st.kind == Kind.AWAITING -> Step.WAITING to "Waiting for your payment"
            o.state == "waiting" || o.state == "paying" -> Step.ACTIVE to "Confirming your payment"
            else -> Step.DONE to "${o.usdc} USDC received"
        }
        val delivered: Pair<Step, String> = when {
            o.delivered -> Step.DONE to "Delivered to ${Phones.pretty(o.phone)}"
            o.state == "delivering" -> Step.ACTIVE to "Sending to ${o.operator}"
            o.state == "refunded" -> Step.FAILED to "Not delivered. Refunded"
            o.state == "failed" -> Step.FAILED to "Not delivered"
            st.kind == Kind.CANCELLED -> Step.SKIPPED to "Not sent"
            else -> Step.WAITING to "Recharge delivered"
        }
        Column(Modifier.fillMaxWidth()) {
            TimelineRow(Step.DONE, "Order placed", last = false)
            TimelineRow(paid.first, paid.second, last = false)
            TimelineRow(delivered.first, delivered.second, last = true)
        }
    }

    @Composable
    private fun TimelineRow(step: Step, text: String, last: Boolean) {
        Row(Modifier.fillMaxWidth().height(if (last) 26.dp else 38.dp)) {
            Column(Modifier.width(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(when (step) {
                    Step.DONE -> D.Success; Step.FAILED -> D.Danger; Step.ACTIVE -> PLAN_WASH; else -> D.Fill
                }), contentAlignment = Alignment.Center) {
                    when (step) {
                        Step.DONE -> HiIcon(Hi.Tick, D.White, 14.dp)
                        Step.FAILED -> HiIcon(Hi.Alert, D.White, 13.dp)
                        Step.ACTIVE -> DottedSpinner(Modifier.size(14.dp))
                        else -> Box(Modifier.size(6.dp).clip(CircleShape).background(D.Neutral))
                    }
                }
                if (!last) Box(Modifier.width(2.dp).weight(1f).background(if (step == Step.DONE) D.Success else D.Line))
            }
            Spacer(Modifier.width(12.dp))
            Text(text, style = Type.body, color = if (step == Step.WAITING || step == Step.SKIPPED) D.Quiet else D.Black,
                modifier = Modifier.padding(top = 1.dp))
        }
    }

    private fun openOrder(o: TopUp.Order) {
        sheet = Sheet.Paid(null, o, null)
        if (!o.finished) follow(o.id)
    }

    // ---- words ---------------------------------------------------------------------------
    private fun countryName(code: String) = countries.firstOrNull { it.code == code }?.name
        ?: java.util.Locale("", code).displayCountry.ifBlank { code }

    /** "₹199" or "NGN 1,000": the face value in the local money, when the plan says one. */
    /** The plan's price as people there read it: ₹49, Rp7,650, ₦1,000; past 100,000 it shortens to Rp150K. */
    private fun face(p: TopUp.Plan): String? {
        val amount = p.faceAmount?.toBigDecimalOrNull() ?: return null
        val cur = p.faceCurrency?.uppercase() ?: return null
        val whole = amount.stripTrailingZeros().scale() <= 0
        val big = whole && amount >= java.math.BigDecimal(100_000)
        val f = java.text.NumberFormat.getNumberInstance(java.util.Locale.getDefault()).apply {
            maximumFractionDigits = if (big) 1 else if (whole) 0 else 2
            minimumFractionDigits = if (whole || big) 0 else 2
        }
        val num = if (big) f.format(amount.divide(java.math.BigDecimal(1000))) + "K" else f.format(amount)
        return SYMBOLS[cur]?.let { "$it$num" } ?: "$cur $num"
    }

    private fun validityOf(p: TopUp.Plan): String? = p.validityDays?.let { if (it == 1) "1 day" else "$it days" }

    /** The second column: data for a plan or a data pack, calls or talktime otherwise. */
    private fun mainOf(p: TopUp.Plan): Pair<String, String> {
        val data = p.benefits.firstOrNull { it.type.equals("DATA", true) && (it.amount ?: 0.0) > 0 }
        if (data != null) return "Data" to "${trim(data.amount!!)} ${data.unit ?: "GB"}"
        val talk = p.benefits.firstOrNull { it.type.equals("TALKTIME", true) }
        if (talk != null) return "Calls" to if ((talk.amount ?: -1.0) < 0) "Unlimited" else "${trim(talk.amount!!)} ${talk.unit?.lowercase() ?: "min"}"
        val credit = p.benefits.firstOrNull { it.type.equals("CREDITS", true) && (it.amount ?: 0.0) > 0 }
        if (credit != null) return "Talktime" to (face(p.copy(faceAmount = credit.amount.toString())) ?: trim(credit.amount!!))
        return (if (TopUp.Shelf.of(p.category) == TopUp.Shelf.CREDIT) "Talktime" else "Plan") to p.title
    }

    private fun summaryOf(p: TopUp.Plan): List<String> {
        val out = mutableListOf<String>()
        p.benefits.forEach { b ->
            val a = b.amount
            when (b.type.uppercase()) {
                "TALKTIME" -> out += if (a == null || a < 0) "Unlimited calls" else "${trim(a)} ${b.unit?.lowercase() ?: "min"} of calls"
                "SMS" -> if (a != null && a > 0) out += "${trim(a)} SMS"
            }
        }
        // The plan's name only when it says something the card does not already say.
        val shown = mainOf(p).second.lowercase().replace(" ", "")
        if (out.isEmpty() && face(p) != null && p.title != face(p) && shown !in p.title.lowercase().replace(" ", "")
            && !Regex("""^([\d.,]+\s*[A-Za-z]{3}|[A-Za-z]{3}\s*[\d.,]+)$""").matches(p.title.trim())) out += p.title  // "10 INR" says only the price
        return out
    }

    /** The plan's own headline, as AirPay's folder tab. Only what the operator says: 5G. */
    private fun tagOf(p: TopUp.Plan): String? =
        if ("5g" in (p.label + " " + p.description.orEmpty()).lowercase()) "5G" else null

    /** Operator copy as it arrives has markdown stars and doubled stops ("Play;."); keep the words. */
    private fun tidy(t: String) = t.replace("*", "").replace(Regex("[;,]\\s*\\."), ".")
        .replace(Regex("\\.{2,}"), ".").replace(Regex("[ \t]{2,}"), " ").trim()

    private fun trim(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else "%.1f".format(d)
}


/** One line that shrinks (to 60% at most) rather than clip: long prices in narrow columns. */
@Composable
private fun FitText(text: androidx.compose.ui.text.AnnotatedString, style: androidx.compose.ui.text.TextStyle,
                    modifier: Modifier = Modifier) {
    var scale by remember(text) { mutableStateOf(1f) }
    Text(text, style = style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale), maxLines = 1, softWrap = false,
        modifier = modifier, onTextLayout = { if (it.didOverflowWidth && scale > 0.6f) scale -= 0.06f })
}

private val SYMBOLS = mapOf("INR" to "₹", "IDR" to "Rp", "MYR" to "RM", "SGD" to "S$", "AUD" to "A$", "CAD" to "C$",
    "EGP" to "E£", "CNY" to "¥", "USD" to "$", "EUR" to "€", "GBP" to "£", "NGN" to "₦", "PHP" to "₱",
    "JPY" to "¥", "KRW" to "₩", "VND" to "₫", "TRY" to "₺", "UAH" to "₴", "ILS" to "₪", "THB" to "฿", "BDT" to "৳",
    "PKR" to "Rs", "LKR" to "Rs", "NPR" to "Rs", "BRL" to "R$", "MXN" to "MX$", "ZAR" to "R", "KES" to "KSh", "GHS" to "GH₵")

/** "49 INR" as a person reads it: ₹49. Unknown currencies keep their code: 49 XAF. */
internal fun readableMoney(face: String?): String? {
    val m = Regex("""^\s*([\d.,]+)\s*([A-Za-z]{3})\s*$""").find(face ?: return null) ?: return face
    val code = m.groupValues[2].uppercase()
    return SYMBOLS[code]?.let { it + m.groupValues[1] } ?: "${m.groupValues[1]} $code"
}

internal fun orderPlan(o: TopUp.Order): String = readableMoney(o.face) ?: o.label
