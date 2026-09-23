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
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
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

    private val analyser: ExecutorService by lazy { Executors.newSingleThreadExecutor() }

    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) problem = "Duffle needs the camera to read a code. You can allow it in Settings."
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

                        // Where to aim. An outline rather than a mask, because the scanner
                        // reads the whole frame and a mask would claim otherwise.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.size(248.dp)
                                    .border(2.dp, Color(0xCCFFFFFF), RoundedCornerShape(28.dp)),
                            )
                        }

                        Column(
                            Modifier.align(Alignment.TopStart).statusBarsPadding()
                                .padding(horizontal = 22.dp, vertical = 18.dp),
                        ) {
                            Text("Scan any QR", color = Color.White, fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold, fontFamily = Display)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                problem ?: "Point it at a Solana Pay code or a wallet address.",
                                color = if (problem != null) Color(0xFFFF8A80) else Color(0xCCFFFFFF),
                                fontSize = 13.sp,
                            )
                        }

                        Column(
                            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                                .padding(horizontal = 22.dp, vertical = 26.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // Said before anything is approved, not after: this app pays on
                            // devnet, and a real shop's code is for mainnet. Paying one here
                            // would settle perfectly and send the shop nothing.
                            Text(
                                "Duffle pays on devnet. A real shop's code settles on mainnet — it would not reach them.",
                                color = Color(0x99FFFFFF), fontSize = 11.sp, textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "Cancel", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
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
                    val scanner = BarcodeScanning.getClient()
                    controller.setImageAnalysisBackpressureStrategy(
                        // Only the newest frame matters. A queue means decoding pictures of
                        // where the phone used to be pointing.
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST,
                    )
                    controller.setImageAnalysisAnalyzer(analyser) { proxy ->
                        val media = proxy.image
                        if (media == null) {
                            proxy.close()
                            return@setImageAnalysisAnalyzer
                        }
                        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                        scanner.process(image)
                            .addOnSuccessListener { codes ->
                                codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                                    ?.rawValue
                                    // Back to the main thread: this opens a screen.
                                    ?.let { raw ->
                                        post {
                                            // A camera loop must not be able to kill the app.
                                            // It runs on whatever a stranger printed on a wall,
                                            // and the one time this did throw — an MWA
                                            // registration at the wrong point in the lifecycle
                                            // — the crash dialog was the only thing the user
                                            // saw. Still logged, so a real bug stays visible.
                                            runCatching { onCode(raw) }.onFailure { e ->
                                                Log.e(TAG, "reading that code failed", e)
                                                problem = "Something went wrong reading that code."
                                            }
                                        }
                                    }
                            }
                            // Always, and only once the model is done with the frame — closing
                            // the proxy early starves the camera and the preview freezes.
                            .addOnCompleteListener { proxy.close() }
                    }
                    controller.bindToLifecycle(owner)
                    this.controller = controller
                }
            },
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
                val req = PayRequest(
                    recipient = got.recipient,
                    amount = got.amount,
                    token = got.token,
                    label = got.label.ifBlank { "Scanned code" },
                    // A code with no reference of its own still needs one: it is how this phone
                    // finds its own payment afterwards. Inventing it costs the payee nothing —
                    // one extra read-only account — and claims nothing on their behalf.
                    reference = got.reference ?: PayRequest.newReference(),
                    decimals = if (got.token == "SOL") 9 else 6,
                    symbol = if (got.token == "SOL") "SOL" else got.token.take(4),
                )
                if (req.validate() != null || req.units() == null) {
                    problem = "That code didn't add up to a payment this app can make."
                    return
                }
                handled = true
                // Saved before it is shown, exactly like a tapped request: from here it
                // survives a closed sheet or a killed app and can be paid from Activity.
                Inbox(this).add(req)
                open(TapPayload.Request(req))
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
        private const val TAG = "DuffleScan"
        fun intent(context: Context) = Intent(context, ScanActivity::class.java)
    }
}
