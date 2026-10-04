package app.lawnchair.nexuslauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lawnchair.nexus.NexusAppActivity
import app.lawnchair.nexus.coach.EirCoach

/**
 * Eir, the health coach, on the News overlay: the readiness light, the coach's word for today and the
 * check-ins waiting. Tapping it opens the Nexus app at `/coach`, where the chat is. Shown only when this
 * build carries the relay key the coach is sealed to; a day that could not be read is a plain "Open Eir".
 */
@Composable
fun EirCard(modifier: Modifier = Modifier) {
    if (!EirCoach.configured) return
    val context = LocalContext.current
    var today by remember { mutableStateOf<EirCoach.Today?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        today = EirCoach.load(context)
        loaded = true
    }
    val light = today?.light
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(EirSurface)
            .border(1.dp, EirBorder, RoundedCornerShape(18.dp))
            .clickable { openEir(context) }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                "EIR",
                style = TextStyle(color = EirText3, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium),
            )
            Spacer(Modifier.weight(1f))
            val pending = today?.pendingCheckIns ?: 0
            if (pending > 0) {
                BasicText(
                    if (pending == 1) "1 check-in" else "$pending check-ins",
                    style = TextStyle(color = EirAccent, fontSize = 12.sp),
                )
            }
            Spacer(Modifier.size(2.dp))
            Spacer(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when (light) {
                            "normal" -> EirGood
                            "watch" -> EirWarn
                            "strain" -> EirBad
                            else -> EirText3
                        },
                    ),
            )
        }
        BasicText(
            when {
                today?.suggestion != null -> today!!.suggestion!!
                today != null -> "Not enough data yet."
                loaded -> "Open Eir"
                else -> " "
            },
            style = TextStyle(color = EirText, fontSize = 15.sp, lineHeight = 21.sp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(0.dp))
    }
}

private fun openEir(context: Context) {
    // The route extra is Flutter's initial route on a cold start; the deep link carries the same path to
    // an app that is already running (the activity is singleTop).
    context.startActivity(
        Intent(context, NexusAppActivity::class.java)
            .setData(Uri.parse("nexus-health://coach/coach"))
            .putExtra("route", "/coach")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
}

private val EirSurface = Color(0xFF14141E)
private val EirBorder = Color(0x14FFFFFF)
private val EirText = Color(0xFFF0F0F5)
private val EirText3 = Color(0x61F0F0F5)
private val EirAccent = Color(0xFF7AB8FF)
private val EirGood = Color(0xFF5EEAD4)
private val EirWarn = Color(0xFFF5C475)
private val EirBad = Color(0xFFF87171)
