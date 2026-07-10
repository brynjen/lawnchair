package app.lawnchair.nexus.chat

import android.widget.Toast
import androidx.compose.foundation.background
import app.lawnchair.nexus.NexusConfig
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableIntStateOf
import app.lawnchair.nexus.net.NexusClient
import app.lawnchair.nexus.net.NexusSource
import app.lawnchair.nexus.net.NexusTurnEvent
import app.lawnchair.nexuslauncher.NeuralOrb
import app.lawnchair.nexuslauncher.OrbState
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch

// Nexus design tokens (mirrors nexus_mobile core/tokens.dart, dark palette — kept in sync with
// NexusNewsScreen.kt).
private val Bg = Color(0xFF0A0A12)
private val Surface = Color(0xFF14141E)
private val Surface2 = Color(0xFF1C1C2A)
private val BorderC = Color(0x14FFFFFF)
private val TextC = Color(0xFFF0F0F5)
private val Text2 = Color(0x9EF0F0F5)
private val Text3 = Color(0x61F0F0F5)
private val Accent = Color(0xFF7AB8FF)
private val Danger = Color(0xFFFF6B6B)
private val OnAccent = Color(0xFF0A1828)

private data class ChatLine(val fromUser: Boolean, val text: String)

/**
 * Attack/decay envelope fed by streamed-token arrival: each TextDelta kicks the level up
 * (bigger deltas kick harder), and it decays at ~2.5/s between bursts — so the orb literally
 * pulses with the answer's cadence instead of a synthetic sine.
 */
private class TokenEnvelope {
    private var peak = 0f
    private var bumpedAtNanos = 0L

    fun bump(chars: Int) {
        peak = max(level(), min(1f, 0.5f + chars / 40f))
        bumpedAtNanos = System.nanoTime()
    }

    fun level(): Float {
        if (bumpedAtNanos == 0L) return 0f
        val age = (System.nanoTime() - bumpedAtNanos) / 1e9
        return (peak * exp(-2.5 * age)).toFloat()
    }
}

/**
 * The Nexus assistant chat, rendered in-launcher. Types a query, opens a `streamTurn` method-stream
 * and appends the streamed answer; shows search sources when the turn returns any. The camera and
 * mic are roadmap stubs (a "coming soon" toast) — image input and push-to-talk are not wired yet.
 *
 * [serverUrl] and [token] come from the Nexus app's config provider (read by the caller before this
 * screen is shown), so the bar is only ever opened in an enabled/configured state.
 */
@Composable
fun NexusChatScreen(
    serverUrl: String,
    token: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // Installed but not configured against a server yet → guide the user to finish setup instead
    // of showing a dead composer.
    if (serverUrl.isBlank()) {
        NexusSetupPrompt(onClose = onClose)
        return
    }
    val scope = rememberCoroutineScope()
    val client = remember { NexusClient() }

    val messages = remember { mutableStateListOf<ChatLine>() }
    var input by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf(false) }
    var conversationId by remember { mutableStateOf<Int?>(null) }
    var sources by remember { mutableStateOf<List<NexusSource>>(emptyList()) }
    // Orb: Thinking while waiting for the first token, Speaking while text streams, Idle after;
    // failures flick the failure counter (red flicker) and tokens feed the pulse envelope.
    var orbState by remember { mutableStateOf(OrbState.Idle) }
    var failureSignal by remember { mutableIntStateOf(0) }
    val tokenEnvelope = remember { TokenEnvelope() }

    val scroll = rememberScrollState()

    fun send() {
        val query = input.trim()
        if (query.isEmpty() || streaming) return
        input = ""
        sources = emptyList()
        messages.add(ChatLine(fromUser = true, text = query))
        val answerIndex = messages.size
        messages.add(ChatLine(fromUser = false, text = ""))
        streaming = true
        orbState = OrbState.Thinking
        scope.launch {
            val cid = conversationId
                ?: runCatching { client.createConversation(serverUrl, token) }.getOrNull()
            if (cid == null) {
                messages[answerIndex] = ChatLine(false, "⚠ Couldn't reach Nexus.")
                streaming = false
                orbState = OrbState.Idle
                failureSignal++
                return@launch
            }
            conversationId = cid
            runCatching {
                client.streamTurn(
                    base = serverUrl,
                    token = token,
                    conversationId = cid,
                    onReady = {
                        // Trigger the turn only after the stream handshake (omsr success).
                        scope.launch {
                            runCatching { client.submitTurn(serverUrl, token, cid, query) }
                        }
                    },
                ).collect { ev ->
                    when (ev) {
                        is NexusTurnEvent.TextDelta -> {
                            if (orbState != OrbState.Speaking) orbState = OrbState.Speaking
                            tokenEnvelope.bump(ev.text.length)
                            val cur = messages[answerIndex]
                            messages[answerIndex] = cur.copy(text = cur.text + ev.text)
                            scope.launch { scroll.animateScrollTo(scroll.maxValue) }
                        }
                        is NexusTurnEvent.Sources -> sources = ev.sources
                        is NexusTurnEvent.Failed -> {
                            failureSignal++
                            val cur = messages[answerIndex]
                            messages[answerIndex] =
                                cur.copy(text = cur.text.ifEmpty { "⚠ ${ev.message}" })
                        }
                        NexusTurnEvent.Completed -> Unit
                        is NexusTurnEvent.Narrator -> Unit // pre-roll narration — not shown in text UI
                    }
                }
            }
            streaming = false
            orbState = OrbState.Idle // covers Completed, Failed, and stream errors alike
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding(),
    ) {
        // Header: orb + title + close
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeuralOrb(
                modifier = Modifier.size(28.dp),
                accent = Accent,
                state = orbState,
                externalAmplitude = {
                    if (orbState == OrbState.Speaking) tokenEnvelope.level() else null
                },
                failureSignal = failureSignal,
            )
            Spacer(Modifier.width(12.dp))
            BasicText(
                text = "Nexus",
                style = TextStyle(color = TextC, fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .padding(8.dp),
            ) {
                BasicText(text = "✕", style = TextStyle(color = Text2, fontSize = 18.sp))
            }
        }

        // Conversation
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (messages.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                BasicText(
                    text = "Ask Nexus anything.",
                    style = TextStyle(color = Text3, fontSize = 15.sp),
                )
            }
            messages.forEach { line -> MessageBubble(line) }
            if (sources.isNotEmpty()) SourcesBlock(sources)
            Spacer(Modifier.height(12.dp))
        }

        // Composer
        Composer(
            input = input,
            onInput = { input = it },
            onSend = ::send,
            enabled = !streaming,
            onCamera = {
                Toast.makeText(context, "Image input — coming soon", Toast.LENGTH_SHORT).show()
            },
            onMic = {
                // TODO(nexus): push-to-talk. Wire AudioRecord PCM16 16kHz → audio.sendAudioChunk
                // input-stream method-stream → transcript → submitTurn (mirror the in-app
                // voice_session_repository_impl.dart). Stubbed for now.
                Toast.makeText(context, "Voice — coming soon", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun NexusSetupPrompt(onClose: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeuralOrb(modifier = Modifier.size(28.dp), accent = Accent, state = OrbState.Idle)
            Spacer(Modifier.width(12.dp))
            BasicText(
                text = "Nexus",
                style = TextStyle(color = TextC, fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(8.dp),
            ) { BasicText(text = "✕", style = TextStyle(color = Text2, fontSize = 18.sp)) }
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BasicText(
                text = "Finish setting up Nexus",
                style = TextStyle(color = TextC, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(10.dp))
            BasicText(
                text = "Open the Nexus app and connect it to a server to start chatting from here.",
                style = TextStyle(color = Text3, fontSize = 15.sp),
            )
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Accent)
                    .clickable {
                        runCatching {
                            context.packageManager.getLaunchIntentForPackage(NexusConfig.APP_PACKAGE)
                                ?.let { context.startActivity(it) }
                        }
                        onClose()
                    }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                BasicText(
                    text = "Open Nexus",
                    style = TextStyle(color = OnAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(line: ChatLine) {
    val isUser = line.fromUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (isUser) Accent else Surface)
                .border(1.dp, if (isUser) Color.Transparent else BorderC, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            BasicText(
                text = line.text.ifEmpty { "…" },
                style = TextStyle(
                    color = if (isUser) OnAccent else TextC,
                    fontSize = 15.sp,
                ),
            )
        }
    }
}

@Composable
private fun SourcesBlock(sources: List<NexusSource>) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            text = "Sources",
            style = TextStyle(color = Text3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        )
        sources.forEach { s ->
            BasicText(
                text = s.title.ifEmpty { s.url },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Accent, fontSize = 13.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(s.url),
                                ),
                            )
                        }
                    },
            )
        }
    }
}

@Composable
private fun Composer(
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    onCamera: () -> Unit,
    onMic: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Surface2)
                .border(1.dp, BorderC, RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (input.isEmpty()) {
                BasicText(
                    text = "Message Nexus…",
                    style = TextStyle(color = Text3, fontSize = 15.sp),
                )
            }
            BasicTextField(
                value = input,
                onValueChange = onInput,
                singleLine = false,
                maxLines = 4,
                cursorBrush = SolidColor(Accent),
                textStyle = TextStyle(color = TextC, fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IconButton(label = "📷", onClick = onCamera)
        IconButton(label = "🎙", onClick = onMic)
        IconButton(
            label = "➤",
            onClick = { if (enabled) onSend() },
            tint = if (enabled && input.isNotBlank()) Accent else Text3,
        )
    }
}

@Composable
private fun IconButton(label: String, onClick: () -> Unit, tint: Color = Text2) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = label, style = TextStyle(color = tint, fontSize = 16.sp))
    }
}
