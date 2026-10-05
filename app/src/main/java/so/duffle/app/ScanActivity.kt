package so.duffle.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Pointing the camera at a payment code.
 *
 * A tap needs two phones touching. A code needs neither — it is how a shop, a market stall or
 * someone across a table gets paid, and it is the one payment gesture everybody already knows.
 *
 * **Decoding happens on this phone.** ML Kit's barcode model runs on-device, so no frame and no
 * address leaves the handset, and the camera is bound to this activity's lifecycle so it stops
 * the moment the screen does.
 *
 * Its own activity rather than a screen inside the main one, for the same reason the tap popup
 * is: a full-bleed camera has nothing in common with a white page that has a header, and
 * CameraX wants a lifecycle it can own.
 */
class ScanActivity : ComponentActivity() {

    private var problem by mutableStateOf<String?>(null)
    private var granted by mutableStateOf(false)

    /** One code, once. Frames keep arriving after the first hit and each would open a screen. */
    private var handled = false

    /** Where the code is on screen, in the preview's pixels. Kept after it leaves, to spring back from. */
    private var code by mutableStateOf<Rect?>(null)
    private var locked by mutableStateOf(false)

    /** The text of the code the frame is on. Decided once, not once per frame. */
    private var held: String? = null
    private var misses = 0

    private val analyser: ExecutorService by lazy { Executors.newSingleThreadExecutor() }

    // QR only: every other format is a model pass that can never produce a payment.
    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
    }

    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) problem = NO_CAMERA
    }

    override fun onResume() {
        super.onResume()
        // Back from Settings with the camera allowed. Nothing else tells this screen, and it
        // used to sit black with the refusal still on it until it was closed and opened again.
        if (!granted && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            granted = true
            if (problem == NO_CAMERA) problem = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!granted) ask.launch(Manifest.permission.CAMERA)

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(background = Color.Black, surface = Color.Black)) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    Box(Modifier.fillMaxSize()) {
                        if (granted) CameraView()

                        // Not where to aim — there is nowhere to aim. The whole picture is
                        // read, and this frame goes to wherever the code turns out to be.
                        // Laid over the preview edge to edge, so the code's position in the
                        // preview is its position here with no conversion.
                        val lock by animateFloatAsState(
                            if (locked) 1f else 0f, ScanFrameSpec.lockSpring(), label = "scanLock",
                        )
                        ScanFrame(code, locked, lock, Modifier.fillMaxSize())

                        Column(
                            Modifier.align(Alignment.TopStart).statusBarsPadding()
                                .padding(horizontal = 22.dp, vertical = 18.dp),
                        ) {
                            // On the scale (ui/PORT.md §3): this is a screen title, so
                            // `Type.title`. It was 22sp in `Display` — both wrong. 22 is
                            // between two steps, and §2 makes Bricolage the wordmark's face
                            // alone; a screen heading borrowing it made this look like a
                            // second logo.
                            // The instructions step back once the frame has hold of a code:
                            // they have been followed. A problem is the opposite — it is about
                            // the code being held, so it stays.
                            val words = if (problem != null) 1f else (1f - lock).coerceIn(0f, 1f)
                            Text(
                                "Scan any QR", color = Color.White, style = Type.title,
                                modifier = Modifier.alpha(words),
                            )
                            Spacer(Modifier.height(Space.tight))
                            Text(
                                problem ?: "Point it at a Solana Pay code or a wallet address.",
                                modifier = Modifier.alpha(words),
                                // D.Danger is the FILL, and on this ground that is right: the
                                // inks are darkened to carry on WHITE and would disappear over
                                // a camera picture. #FF8A80 was an invented colour in neither
                                // layer of the palette.
                                color = if (problem != null) D.Danger else Color(0xCCFFFFFF),
                                style = Type.caption,
                            )
                        }

                        Column(
                            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                                .padding(horizontal = 22.dp, vertical = 26.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (!granted) {
                                // Refused for good, Android will not ask again, so the only way
                                // on is the app's own page in Settings. The sentence above said
                                // so and gave no way to get there.
                                Text(
                                    "Open Settings", color = D.Black, style = Type.heading,
                                    modifier = Modifier.clip(RoundedCornerShape(14.dp))
                                        .background(D.White)
                                        .clickable {
                                            runCatching {
                                                startActivity(
                                                    Intent(
                                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                        android.net.Uri.fromParts("package", packageName, null),
                                                    ),
                                                )
                                            }
                                        }
                                        .padding(horizontal = 30.dp, vertical = 13.dp),
                                )
                                Spacer(Modifier.height(Space.inline))
                            }
                            Text(
                                // Every button label is `Type.heading` (§3).
                                "Cancel", color = Color.White, style = Type.heading,
                                modifier = Modifier.clip(RoundedCornerShape(14.dp))
                                    .background(Color(0x26FFFFFF))
                                    .clickable { finish() }
                                    .padding(horizontal = 30.dp, vertical = 13.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analyser.shutdown()
        scanner.close()
    }

    @Composable
    private fun CameraView() {
        val owner = this
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    // LifecycleCameraController rather than ProcessCameraProvider: the latter
                    // hands back a Guava ListenableFuture that CameraX does not put on the
                    // compile classpath (the artifact that resolves is Guava's deliberately
                    // EMPTY stub), so every call to it is an unresolved reference. This does
                    // the same binding with none of that, and owns the camera lifecycle too.
                    val controller = LifecycleCameraController(ctx)
                    controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
                    controller.setImageAnalysisBackpressureStrategy(
                        // Only the newest frame matters. A queue means decoding pictures of
                        // where the phone used to be pointing.
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST,
                    )
                    // MlKitAnalyzer rather than calling the scanner by hand, for one reason:
                    // VIEW_REFERENCED. The model sees the sensor's picture — rotated, and
                    // cropped differently from the FILL_CENTER preview — and this hands the
                    // corners back already mapped onto the preview, which is the only place
                    // the frame is drawn. It also closes each frame itself once the model is
                    // done with it. Answers arrive on the main thread: they move UI state.
                    controller.setImageAnalysisAnalyzer(
                        analyser,
                        MlKitAnalyzer(
                            listOf(scanner),
                            ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
                            ContextCompat.getMainExecutor(ctx),
                        ) { result -> onFrame(result.getValue(scanner)) },
                    )
                    controller.bindToLifecycle(owner)
                    this.controller = controller
                }
            },
        )
    }

    /**
     * One answer from the model, for one camera frame — usually "nothing here".
     *
     * Two separate jobs. Following the code is immediate and unconditional: any QR at all gets
     * the frame, because the frame says "I can see this", not "I can pay this". Deciding what
     * the code means happens once per sighting and a beat later, so the person sees the frame
     * arrive before the screen changes under them.
     */
    private fun onFrame(codes: List<Barcode>?) {
        val seen = codes.orEmpty().mapNotNull { c ->
            val raw = c.rawValue ?: return@mapNotNull null
            val at = boundsOf(c) ?: return@mapNotNull null
            raw to at
        }
        // Two codes in shot: stay on the one already held, otherwise take the biggest, which
        // is the one being held up rather than the poster behind it.
        val hit = seen.firstOrNull { it.first == held } ?: seen.maxByOrNull { it.second.width * it.second.height }

        if (hit == null) {
            // One blurred frame is not the code leaving. Letting go on the first miss makes
            // the frame flap between the code and the middle with every shake of the hand.
            if (locked && ++misses > MISSES_TO_LET_GO) {
                locked = false
                held = null
            }
            return
        }
        misses = 0
        code = hit.second
        locked = true

        val raw = hit.first
        if (raw == held) return
        held = raw
        // A new code is a new try: what was wrong with the last one is no longer the news.
        problem = null
        Haptics.select(this)
        lifecycleScope.launch {
            delay(HOLD_MS)
            // Swept past rather than pointed at: it was gone before the frame settled.
            if (held != raw) return@launch
            // A camera loop must not be able to kill the app. It runs on whatever a stranger
            // printed on a wall, and the one time this did throw — an MWA registration at the
            // wrong point in the lifecycle — the crash dialog was the only thing the user saw.
            // Still logged, so a real bug stays visible.
            runCatching { onCode(raw) }.onFailure { e ->
                Log.e(TAG, "reading that code failed", e)
                problem = "That code didn't read. Try again, or ask for the address."
            }
        }
    }

    /** The box round a code's four corners, which is tighter than ML Kit's own when it is tilted. */
    private fun boundsOf(c: Barcode): Rect? {
        val pts = c.cornerPoints
        if (pts.isNullOrEmpty()) return c.boundingBox?.let {
            Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
        }
        return Rect(
            pts.minOf { it.x }.toFloat(), pts.minOf { it.y }.toFloat(),
            pts.maxOf { it.x }.toFloat(), pts.maxOf { it.y }.toFloat(),
        )
    }

    /** What to do with the text off a code. */
    private fun onCode(text: String) {
        if (handled) return
        Log.i(TAG, "scanned ${text.take(24)}…")
        val got = SolanaPay.parse(text)

        // Your own wallet, which is the single most likely thing to point this at: a Phantom
        // receive code IS your address, and holding it up to see what happens is the obvious
        // first thing anybody does. The confirm sheet refuses a self-payment by closing itself
        // — a guard written for NFC, where tapping your own phone is nonsense — so through the
        // camera it looked like the scanner had failed and dumped you back on the home screen.
        // Checked here, where the camera is still pointed at the code and there is somewhere
        // to say it.
        val target = when (got) {
            is SolanaPay.Scanned.Request -> got.recipient
            is SolanaPay.Scanned.Address -> got.recipient
            else -> null
        }
        // `Wallet.addressOf`, not `Wallet(this)`: constructing a Wallet registers an MWA
        // ActivityResultSender, which is only legal before the activity is STARTED. Called
        // here, mid-scan at RESUMED, it threw IllegalStateException and took the app down.
        // Nothing on this path needs a wallet session — only which address is ours.
        if (target != null && target == Wallet.addressOf(this)) {
            problem = "That's your own wallet. Scan the code of whoever you're paying."
            return
        }

        when (got) {
            is SolanaPay.Scanned.Request -> {
                handled = true
                // The mint decides what "0.2" means, and a Solana Pay code never says. This
                // used to assume 6 for every SPL token, which silently builds a transferChecked
                // for the wrong number of base units — the token program refuses it, so the
                // failure is loud rather than costly, but it makes any mint that isn't 6 simply
                // unpayable. Asked on a background thread, because this runs on the main one.
                lifecycleScope.launch {
                    val decimals = if (got.token == "SOL") 9 else {
                        withContext(Dispatchers.IO) { Chain.mintInfo(got.token) }?.decimals
                    }
                    if (decimals == null) {
                        handled = false
                        problem = "Couldn't read that token. Nothing was sent."
                        return@launch
                    }
                    val req = PayRequest(
                        recipient = got.recipient,
                        amount = got.amount,
                        token = got.token,
                        label = got.label.ifBlank { "Scanned code" },
                        // A code with no reference of its own still needs one: it is how this
                        // phone finds its own payment afterwards. Inventing it costs the payee
                        // nothing — one extra read-only account — and claims nothing for them.
                        reference = got.reference ?: PayRequest.newReference(),
                        decimals = decimals,
                        // USDC by name. It was shown as "EPjF", the start of its address.
                        symbol = when (got.token) {
                            "SOL" -> "SOL"
                            Shop.USDC_MAINNET -> "USDC"
                            else -> got.token.take(4)
                        },
                    )
                    if (req.validate() != null || req.units() == null) {
                        handled = false
                        problem = "That code didn't add up to a payment this app can make."
                        return@launch
                    }
                    // Saved before it is shown, exactly like a tapped request: from here it
                    // survives a closed sheet or a killed app and can be paid from Activity.
                    Inbox(this@ScanActivity).add(req)
                    open(TapPayload.Request(req))
                }
            }
            is SolanaPay.Scanned.Address -> {
                handled = true
                open(TapPayload.Card(TapCard(got.recipient, got.label.ifBlank { "Scanned code" }, null, null)))
            }
            // Understood and declined. Left on screen rather than closed: the camera is still
            // pointed at it, and a screen that vanished would look like it had worked.
            is SolanaPay.Scanned.Refused -> problem = got.why
            SolanaPay.Scanned.Unknown -> problem = "That isn't a Solana payment code."
        }
    }

    private fun open(payload: TapPayload) {
        startActivity(TapPopupActivity.intent(this, payload, cameFrom = "scan"))
        finish()
    }

    companion object {
        private const val TAG = "AirPayScan"
        private const val NO_CAMERA = "AirPay needs the camera to read a code. You can allow it in Settings."

        /** Telegram's number: more than four empty frames in a row before the frame lets go. */
        private const val MISSES_TO_LET_GO = 4

        /** Long enough for the lock spring to land, short enough not to feel like a wait. */
        private const val HOLD_MS = 350L
        fun intent(context: Context) = Intent(context, ScanActivity::class.java)
    }
}
