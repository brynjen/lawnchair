package app.lawnchair.nexus.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lawnchair.nexus.net.NexusSource
import coil.compose.AsyncImage
import coil.request.ImageRequest
import app.lawnchair.nexuslauncher.NeuralOrb
import app.lawnchair.nexuslauncher.OrbState

// Nexus design tokens (mirrors nexus_mobile core/tokens.dart, dark palette).
private val Bg = Color(0xFF0A0A12)
private val Surface = Color(0xFF14141E)
private val Surface2 = Color(0xFF1C1C2A)
private val BorderC = Color(0x14FFFFFF)
private val TextC = Color(0xFFF0F0F5)
private val Text2 = Color(0x9EF0F0F5)
private val Text3 = Color(0x61F0F0F5)
private val Accent = Color(0xFF7AB8FF)
private val Good = Color(0xFF5EEAD4)
private val OnAccent = Color(0xFF0A1828)
private val OnGood = Color(0xFF063A33)

private const val ANIM_IN_MS = 260
private const val ANIM_OUT_MS = 220
private const val SCRIM_ALPHA = 0.72f

/**
 * The home-screen-integrated Nexus chat. Layers over the (partially dimmed) launcher: a gradient
 * scrim that dims only the region we use, the neural orb (top-left in Text mode, centered + enlarged
 * in Voice mode), the conversation, and mode-specific chrome. All state comes from [controller];
 * this composable is a pure projection that forwards intents. [onClosed] runs after the exit anim.
 */
@Composable
fun NexusChatScreen(
    controller: NexusChatController,
    statusBarTopPx: Int,
    onClosed: () -> Unit,
) {
    val state by controller.state.collectAsState()

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(ANIM_IN_MS, easing = EaseOutCubic)) }
    LaunchedEffect(state.dismissing) {
        if (state.dismissing) {
            appear.animateTo(0f, tween(ANIM_OUT_MS, easing = EaseInCubic))
            onClosed()
        }
    }

    val orbState = orbStateFor(state.phase)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Swipe right to close (independent of the OS back-gesture config).
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (total > 140f) controller.dismiss()
                        total = 0f
                    },
                    onDragCancel = { total = 0f },
                ) { _, dragAmount -> total += dragAmount }
            },
    ) {
        if (state.mode == Mode.Voice) {
            VoiceLayout(state, controller, appear.value, orbState)
        } else {
            TextLayout(state, controller, appear.value, orbState, statusBarTopPx)
        }
    }
}

/**
 * A vertical scrim that leaves the top of the screen clear and dims only from [fadeStart]→bottom
 * (fractions of height), so we darken just the area the chat uses rather than the whole home screen.
 */
@Composable
private fun PartialScrim(appear: Float, fadeStart: Float, fadeEnd: Float, onTap: () -> Unit) {
    val a = SCRIM_ALPHA * appear
    val brush = Brush.verticalGradient(
        0f to Color.Transparent,
        fadeStart to Color.Transparent,
        fadeEnd to Color.Black.copy(alpha = a),
        1f to Color.Black.copy(alpha = a),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
    )
}

@Composable
private fun BoxScope.TextLayout(
    state: NexusChatUiState,
    controller: NexusChatController,
    appear: Float,
    orbState: OrbState,
    statusBarTopPx: Int,
) {
    val orbTopPadding = with(androidx.compose.ui.platform.LocalDensity.current) {
        statusBarTopPx.toDp()
    } + 8.dp
    // With no messages yet, dim only a thin band just above the text field; once bubbles appear,
    // extend the dimming up over the chat area.
    val empty = state.messages.isEmpty()
    PartialScrim(
        appear,
        fadeStart = if (empty) 0.80f else 0.12f,
        fadeEnd = if (empty) 0.90f else 0.22f,
        onTap = { controller.dismiss() },
    )

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Conversation(
            state = state,
            controller = controller,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 76.dp),
            bottomAnchored = true, // bubbles stack directly on top of the text field
        )
        Composer(
            input = state.input,
            placeholder = if (state.phase == TurnPhase.AwaitingAnswer) "Answer Nexus…" else "Message Nexus…",
            enabled = state.phase != TurnPhase.Thinking && state.phase != TurnPhase.Speaking,
            onInput = controller::onInput,
            onSend = controller::send,
            appear = appear,
        )
    }

    // Orb: top-left, just below the status bar so it's fully tappable. Slides in from the left.
    // The orb sits edge-to-edge on a solid-black core that fades radially out into a soft halo, so
    // it reads clearly over the (undimmed) wallpaper without a hard-edged circle.
    Box(
        modifier = Modifier
            .padding(start = 10.dp, top = orbTopPadding)
            .size(76.dp)
            .graphicsLayer {
                alpha = appear
                translationX = (appear - 1f) * 64.dp.toPx()
            }
            .background(
                Brush.radialGradient(
                    0.0f to Color.Black,
                    0.55f to Color.Black,
                    1.0f to Color.Transparent,
                ),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { controller.dismiss() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Fill the backing so the orb's sphere reads large (the composable has internal glow margin).
        NeuralOrb(
            modifier = Modifier.fillMaxSize(),
            accent = Accent,
            state = orbState,
            externalAmplitude = controller::externalAmplitude,
            failureSignal = state.failureSignal,
        )
    }
}

@Composable
private fun BoxScope.VoiceLayout(
    state: NexusChatUiState,
    controller: NexusChatController,
    appear: Float,
    orbState: OrbState,
) {
    // Dim only the orb-and-below region; the top of the screen stays clear.
    PartialScrim(appear, fadeStart = 0.32f, fadeEnd = 0.48f, onTap = { controller.dismiss() })

    // Orb, centred and enlarging in from the middle.
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .graphicsLayer { translationY = -40.dp.toPx() }
            .size(260.dp)
            .graphicsLayer {
                alpha = appear
                scaleX = 0.6f + 0.4f * appear
                scaleY = 0.6f + 0.4f * appear
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { controller.dismiss() },
            ),
    ) {
        NeuralOrb(
            modifier = Modifier.fillMaxSize(),
            accent = Accent,
            state = orbState,
            externalAmplitude = controller::externalAmplitude,
            failureSignal = state.failureSignal,
        )
    }

    // Controls + transcript + conversation occupy the lower area, below the centred orb.
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.44f)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = state.phase == TurnPhase.Listening,
            enter = fadeIn(tween(ANIM_OUT_MS)),
            exit = fadeOut(tween(ANIM_OUT_MS)),
        ) {
            StopButton(
                label = if (state.micGesture == MicGesture.Hold) "Release to send" else "Tap to stop",
                enabled = state.micGesture == MicGesture.Tap,
                onClick = { controller.endRecordingAndSend() },
            )
        }
        // Nexus asked something: answer it the way the turn started, by voice.
        if (state.phase == TurnPhase.AwaitingAnswer) {
            StopButton(label = "Tap to answer", enabled = true, onClick = { controller.answerByVoice() })
        }
        if (state.userTranscript.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            BasicText(text = state.userTranscript, style = TextStyle(color = Text2, fontSize = 16.sp))
        }
        Spacer(Modifier.height(12.dp))
        Conversation(state = state, controller = controller, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun Conversation(
    state: NexusChatUiState,
    controller: NexusChatController,
    modifier: Modifier = Modifier,
    bottomAnchored: Boolean = false,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.messages.isEmpty() && state.userTranscript.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                BasicText(text = "Ask Nexus anything.", style = TextStyle(color = Text3, fontSize = 15.sp))
            }
            state.messages.forEach { line ->
                if (line.imageUrl != null) ImageBubble(line.imageUrl, controller) else MessageBubble(line)
            }
            if (state.sources.isNotEmpty()) SourcesBlock(state.sources)
            Spacer(Modifier.height(12.dp))
        }
    }
    if (bottomAnchored) {
        // Content sits at the bottom (just above the composer) and grows upward; scrolls when tall.
        Box(modifier = modifier, contentAlignment = Alignment.BottomStart) { content() }
    } else {
        Box(modifier = modifier) { content() }
    }
}

@Composable
private fun StopButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Good)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = OnGood, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun Composer(
    input: String,
    placeholder: String,
    enabled: Boolean,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    appear: Float,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // The keyboard is tied strictly to this field's presence: raise it when the composer enters
    // (Text mode only — Voice mode has no composer), and hide it when the composer leaves.
    DisposableEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
        onDispose { keyboard?.hide() }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = appear }
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
                BasicText(text = placeholder, style = TextStyle(color = Text3, fontSize = 15.sp))
            }
            BasicTextField(
                value = input,
                onValueChange = onInput,
                singleLine = false,
                maxLines = 4,
                cursorBrush = SolidColor(Accent),
                textStyle = TextStyle(color = TextC, fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (enabled && input.isNotBlank()) Accent else Surface)
                .clickable(enabled = enabled) { onSend() },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "➤",
                style = TextStyle(
                    color = if (enabled && input.isNotBlank()) OnAccent else Text3,
                    fontSize = 16.sp,
                ),
            )
        }
    }
}

@Composable
private fun MessageBubble(line: ChatLine) {
    val isUser = line.fromUser
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.85f).dp
    // Tail corner (6dp) on the sender's inner-bottom corner, mirroring the Flutter TextBubble.
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isUser) 18.dp else 6.dp,
        bottomEnd = if (isUser) 6.dp else 18.dp,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = maxWidth),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            if (!isUser) {
                // "● NEXUS" header above the assistant bubble.
                Row(
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(Accent))
                    Spacer(Modifier.width(6.dp))
                    BasicText(
                        text = "NEXUS",
                        style = TextStyle(color = Accent, fontSize = 10.sp, letterSpacing = 1.4.sp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(if (isUser) Accent else Surface)
                    .then(if (isUser) Modifier else Modifier.border(1.dp, BorderC, shape))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                BasicText(
                    text = line.text.ifEmpty { "…" },
                    style = TextStyle(
                        color = if (isUser) OnAccent else TextC,
                        fontSize = 14.5.sp,
                        letterSpacing = (-0.1).sp,
                    ),
                )
            }
        }
    }
}

/**
 * A picture the assistant drew, fetched from the server's `/uploads` with the access token (the
 * mobile app's `ServerImage`). Tap for full screen.
 */
@Composable
private fun ImageBubble(path: String, controller: NexusChatController) {
    val context = LocalContext.current
    val request = remember(path) {
        ImageRequest.Builder(context)
            .data(controller.imageUrl(path))
            .apply { controller.imageAuthorization?.let { addHeader("Authorization", it) } }
            .crossfade(true)
            .build()
    }
    var fullScreen by remember { mutableStateOf(false) }
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.72f).dp
    AsyncImage(
        model = request,
        contentDescription = "Image from Nexus",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .widthIn(max = maxWidth)
            .height(maxWidth)
            .clip(RoundedCornerShape(16.dp))
            .background(Surface2)
            .border(1.dp, BorderC, RoundedCornerShape(16.dp))
            .clickable { fullScreen = true },
    )
    if (fullScreen) {
        // A dialog is its own window, so back closes it before it reaches the overlay.
        Dialog(
            onDismissRequest = { fullScreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { fullScreen = false },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = request,
                    contentDescription = "Image from Nexus",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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

private fun orbStateFor(phase: TurnPhase): OrbState = when (phase) {
    TurnPhase.Listening -> OrbState.Listening
    TurnPhase.Thinking -> OrbState.Thinking
    TurnPhase.Speaking -> OrbState.Speaking
    TurnPhase.Idle, TurnPhase.AwaitingAnswer, TurnPhase.Done, TurnPhase.Failed -> OrbState.Idle
}
