package so.duffle.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * "Pay anyone": a `.skr`, `.sol` or `.air` name, or a wallet address, in place of a QR code.
 *
 * Opens with the box focused and the keyboard up. As the name is typed the registries are asked
 * (after a short pause, so it is not a request per keystroke) and every person it could be is
 * listed with the end of their wallet address, so two people called alike can be told apart.
 * Pay lights up when somebody has been picked, or when what was typed IS a full name or address.
 * Nothing is sent from here: the result goes to the same confirm sheet a scanned address does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PayAnyoneSheet(onClose: () -> Unit, onFound: (address: String, label: String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Names.Candidate?>(null) }
    var found by remember { mutableStateOf<List<Names.Candidate>>(emptyList()) }
    var looking by remember { mutableStateOf(false) }
    // Why the list is empty, when it is not simply that nobody has that name.
    var unreachable by remember { mutableStateOf(false) }
    var unsafe by remember { mutableStateOf(false) }
    // Bumped by "Try again", so a lookup that failed can be asked again without retyping.
    var attempt by remember { mutableStateOf(0) }
    // Which address is ours. Paying it is refused further on by the payment sheet closing
    // itself, which from here looked like Pay had done nothing at all.
    val context = LocalContext.current
    val me = remember { Wallet.addressOf(context) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Keyed on the text: a new keystroke cancels the pause and the lookup in flight.
    LaunchedEffect(text, attempt) {
        if (picked != null && picked!!.name == text) return@LaunchedEffect
        picked = null
        unreachable = false; unsafe = false
        if (text.trim().length < 2) { found = emptyList(); looking = false; return@LaunchedEffect }
        looking = true
        delay(350)
        val answer = Names.lookup(text)
        found = answer.people
        unreachable = answer.unreachable
        unsafe = answer.unsafe
        looking = false
    }
    // The sheet has to be on screen before it can take focus.
    LaunchedEffect(Unit) { delay(250); focus.requestFocus(); keyboard?.show() }

    // What Pay would pay: a pick, or an exact match for what is typed.
    // Never our own wallet: see [me].
    val target = (picked ?: found.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) })
        ?.takeIf { it.address != me }
    val go = { target?.let { t -> onFound(t.address, if (t.name == t.address) ADDRESS_PLACEHOLDER else t.name) }; Unit }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = D.White, dragHandle = null) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                .padding(horizontal = Space.gutter, vertical = 20.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(D.Line, CircleShape))
            Spacer(Modifier.height(16.dp))
            Text("Pay anyone", color = D.Black, style = Type.title)
            Spacer(Modifier.height(Space.tight))
            Text("A .skr, .sol or .air name, or a wallet address.", color = D.Quiet, style = Type.caption)
            Spacer(Modifier.height(Space.lead))

            // Matches the message bar of a thread: a round field and a round button at its end.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).heightIn(min = 52.dp).clip(CircleShape).background(D.White)
                        .border(1.5.dp, D.Line, CircleShape).padding(horizontal = 20.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) Text("name.skr, name.sol or name.air", color = D.Quiet, style = Type.body, maxLines = 1)
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> !c.isWhitespace() }.take(64) },
                        singleLine = true,
                        textStyle = Type.body.copy(color = D.Black),
                        cursorBrush = SolidColor(D.Blue),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { go() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Spacer(Modifier.width(8.dp))
                val on = target != null
                Box(
                    Modifier.width(88.dp).height(52.dp).clip(CircleShape)
                        .background(if (on) D.Blue else D.Line)
                        .clickable(enabled = on) { go() },
                    contentAlignment = Alignment.Center,
                ) { Text("Pay", color = if (on) D.White else D.Quiet, style = Type.heading, maxLines = 1) }
            }

            Spacer(Modifier.height(Space.lead))
            // Takes what room the keyboard leaves and scrolls inside it. `.air` answers a
            // half-typed name with everybody it could be, and a list taller than the space
            // above the keyboard used to run off the sheet with no way to reach its end.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            when {
                found.isNotEmpty() -> found.forEach { c ->
                    val on = picked?.address == c.address && picked?.name == c.name
                    val mine = c.address == me
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            // Choosing somebody IS the decision to pay them: the row opens the
                            // payment itself. It used to only tick the row and wait for Pay.
                            .clickable(enabled = !mine) {
                                picked = c; keyboard?.hide()
                                onFound(c.address, if (c.name == c.address) ADDRESS_PLACEHOLDER else c.name)
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RegistryMark(c.name)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (c.name == c.address) ADDRESS_PLACEHOLDER else c.name, color = D.Black,
                                style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (mine) Text("Your own wallet. Pick someone else to pay.", color = D.Quiet, style = Type.caption)
                            else Text("…${c.address.takeLast(4)}", color = D.Quiet, style = Type.mono)
                        }
                        if (on) HiIcon(Hi.Tick, D.BlueInk, 22.dp)
                    }
                }
                looking -> Text("Looking…", color = D.Quiet, style = Type.caption)
                // Said apart from "no one found": the name may be spelled perfectly, and this
                // is money that must not go there.
                unsafe -> Text(
                    "That belongs to a program or a token account, not a person's wallet, so it can't be paid here.",
                    // Ink, not red: red is kept for the three places the palette names.
                    color = D.Black, style = Type.caption,
                )
                // A failed lookup is not an answer, and must not read as "nobody has that name".
                unreachable -> {
                    Text("Couldn't look that up. Check your connection.", color = D.Quiet, style = Type.caption)
                    TextLink("Try again", onClick = { attempt++ })
                }
                text.startsWith("solana:", ignoreCase = true) ->
                    Text("That's a payment link. Paste only the wallet address.", color = D.Quiet, style = Type.caption)
                text.trim().length >= 2 -> Text("No one found. Check the spelling.", color = D.Quiet, style = Type.caption)
            }
            }
            Spacer(Modifier.height(Space.lead))
        }
    }
}


/**
 * Which registry a name came from, as its own picture: the Seeker mark for `.skr` (the Genesis
 * Token's face), the Solana Name Service hexagons for `.sol`, AirPay's rings for `.air`. A bare
 * wallet address has no registry and gets a plain wallet glyph.
 */
@Composable
internal fun RegistryMark(name: String, size: androidx.compose.ui.unit.Dp = 40.dp) {
    when (registryOf(name)) {
        ".skr" -> androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.drawable.brand_seeker), name,
            Modifier.size(size).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        ".sol" -> androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.drawable.id_sol), name,
            Modifier.size(size).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        ".air" -> Box(Modifier.size(size).clip(CircleShape).background(D.Blue), contentAlignment = Alignment.Center) {
            MarkCanvas(Mark.Rings, size * 0.6f, notch = D.Blue, tint = D.White)
        }
        else -> Box(Modifier.size(size).clip(CircleShape).background(D.Blue.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            HiIcon(Hi.Wallet, D.BlueInk, size * 0.55f)
        }
    }
}

/** Which registry a name belongs to, by its ending: ".skr", ".sol", ".air", or null for none. */
internal fun registryOf(name: String?): String? =
    name?.trim()?.lowercase()?.let { n -> listOf(".skr", ".sol", ".air").firstOrNull { n.endsWith(it) && n.length > it.length } }
