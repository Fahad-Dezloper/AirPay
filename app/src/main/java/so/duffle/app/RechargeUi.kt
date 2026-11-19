package so.duffle.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Small pieces the recharge screens are built from.

/** A caption over a value — the shape that lets validity and data be compared down a column. */
@Composable
internal fun Labelled(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(end = 8.dp)) {
        Text(label.uppercase(), color = D.Quiet, style = Type.micro)
        Spacer(Modifier.height(Space.tight))
        Text(value, color = D.Black, style = Type.body)
    }
}

/** The hairline that replaced the row box. Decorative — never the only boundary. */
@Composable
internal fun RowLine() = Box(Modifier.fillMaxWidth().height(1.dp).background(D.Line))

/** A label over a value, for the two things a plan is chosen on: how long, and how much. */
@Composable
internal fun SheetStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(end = 12.dp)) {
        Text(label, color = D.Quiet, style = Type.caption)
        Spacer(Modifier.height(Space.tight))
        Text(value, color = D.Black, style = Type.heading)
    }
}

/** One labelled fact: the label in a fixed column, the value beside it, so a list of them lines up. */
@Composable
internal fun PurchaseFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = D.Quiet, style = Type.body, modifier = Modifier.width(150.dp).padding(end = 12.dp))
        Text(value, color = D.Black, style = Type.body, modifier = Modifier.weight(1f))
    }
}

/** One point of a list: a dot in its own narrow column, and the text hanging to the right of it. */
@Composable
internal fun BulletLine(
    text: String, color: Color = D.Quiet, style: androidx.compose.ui.text.TextStyle = Type.caption,
    maxLines: Int = Int.MAX_VALUE,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text("\u2022", color = color, style = style, modifier = Modifier.width(14.dp))
        Text(text, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}



/** The recharge screens' card ground: the brand blue at a few percent, with an edge a step darker. */
internal val PLAN_WASH = Color(0xFFF2F8FF)
internal val PLAN_EDGE = Color(0xFFDCEAF9)

/**
 * One tab over the plan list. The label keeps ONE size and weight whether it is chosen or not, so
 * choosing a tab never changes its width; only its colour and the bar under it move. The bar is as
 * wide as the word, which is what it used not to be: a fixed 28dp stub under a label that had just
 * grown.
 */
@Composable
internal fun PlanTab(label: String, selected: Boolean, onPick: () -> Unit) {
    val ink by androidx.compose.animation.animateColorAsState(if (selected) D.Black else D.Quiet, tween(180), label = "tabInk")
    val bar by animateFloatAsState(if (selected) 1f else 0f, tween(220, easing = FastOutSlowInEasing), label = "tabBar")
    Column(
        Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max)
            .clip(RoundedCornerShape(10.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onPick)
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(10.dp))
        Text(label, color = ink, style = Type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(3.dp)
                .graphicsLayer { scaleX = bar; alpha = bar }
                .background(D.Blue, RoundedCornerShape(2.dp)),
        )
    }
}

