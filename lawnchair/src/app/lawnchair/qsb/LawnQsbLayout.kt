package app.lawnchair.qsb

import android.content.ComponentName
import android.content.Context
import android.widget.Toast
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import app.lawnchair.LawnchairLauncher
import app.lawnchair.animateToAllApps
import app.lawnchair.launcher
import app.lawnchair.preferences.observeAsState
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.asState
import app.lawnchair.preferences2.firstCached
import app.lawnchair.nexus.NexusAppActivity
import app.lawnchair.nexus.NexusConfig
import app.lawnchair.nexus.chat.MicGesture
import app.lawnchair.nexus.chat.NexusChatOverlay
import app.lawnchair.nexus.voice.MicPermission
import app.lawnchair.qsb.providers.AppSearch
import app.lawnchair.qsb.providers.Google
import app.lawnchair.qsb.providers.Nexus
import app.lawnchair.qsb.providers.PixelSearch
import app.lawnchair.qsb.providers.QsbSearchProvider
import app.lawnchair.qsb.providers.QsbSearchProviderType
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.Search
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.util.ProvideLifecycleState
import app.lawnchair.util.repeatOnAttached
import com.android.launcher3.BaseActivity
import com.android.launcher3.DeviceProfile
import com.android.launcher3.R
import com.android.launcher3.logging.StatsLogManager
import com.android.launcher3.views.ActivityContext
import com.android.launcher3.views.OptionsPopupView
import com.patrykmichalik.opto.core.firstBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LawnQsbLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    private val activity: ActivityContext = ActivityContext.lookupContext<BaseActivity>(context)
    private val composeView = ComposeView(context)
    private lateinit var preferenceManager2: PreferenceManager2

    private lateinit var searchProvider: QsbSearchProvider

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onFinishInflate() {
        super.onFinishInflate()

        preferenceManager2 = PreferenceManager2.getInstance(context)
        searchProvider = getSearchProvider(context, preferenceManager2)

        composeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                LawnchairTheme {
                    ProvideLifecycleState {
                        val context = LocalContext.current

                        val prefs = preferenceManager()
                        val prefs2 = preferenceManager2

                        val searchProviderPref by prefs2.hotseatQsbProvider.asState()
                        val searchProvider = remember(searchProviderPref, context) {
                            getSearchProvider(context, searchProviderPref)
                        }
                        val themed by prefs2.themedHotseatQsb.asState()

                        val supportsLens = searchProvider == Google || searchProvider == PixelSearch
                        val voiceIntent = remember(searchProvider, context) {
                            getVoiceIntent(searchProvider, context)
                        }
                        val lensIntent = remember(supportsLens, context) {
                            if (supportsLens) getLensIntent(context) else null
                        }

                        // The Nexus server's speech being off (its GPU Max mode) is read from the app's
                        // presence cache, which changes while this bar is up, so it is read again.
                        val nexusVoiceOff by produceState(false, searchProvider) {
                            if (searchProvider != Nexus) return@produceState
                            while (true) {
                                value = withContext(Dispatchers.IO) { NexusConfig.read(context).voiceOff }
                                delay(VOICE_OFF_POLL_MS)
                            }
                        }

                        val state = rememberHotseatQsbState(
                            searchProvider = searchProvider,
                            themed = themed,
                            showMic = voiceIntent != null,
                            showLens = lensIntent != null,
                            nexusVoiceOff = nexusVoiceOff,
                        )

                        val style = buildQsbStyle(
                            context = LocalContext.current,
                            themed = themed,
                            backgroundColor = getHotseatBackgroundColor(context, themed),
                            backgroundAlpha = prefs.hotseatQsbAlpha.observeAsState().value,
                            cornerRadius = prefs.hotseatQsbCornerRadius.observeAsState().value,
                            // Use light color as strokeColor is a static color that doesn't use darkColor
                            strokeColor = prefs2.strokeColorStyle.asState().value.colorPreferenceEntry.lightColor.invoke(context),
                            strokeWidth = prefs.hotseatQsbStrokeWidth.observeAsState().value,
                        )

                        // Nexus mic tap-vs-hold classifier (mirrors the Flutter 250ms timer).
                        val micClassifier = remember { NexusMicClassifier() }
                        val actions = QsbActions(
                            onQsbClick = {
                                val launcher = context.launcher
                                launcher.lifecycleScope.launch {
                                    if (searchProvider == Nexus) {
                                        // The field is the launcher's own search (the drawer, its field
                                        // on top and focused), whatever the hotseat style is.
                                        AppSearch.launch(launcher)
                                    } else if (prefs2.matchHotseatQsbStyle.firstCached()) {
                                        val searchUiManager = launcher.appsView.searchUiManager
                                        searchUiManager.setDirectFocus(true)
                                        searchUiManager.editText?.showKeyboard()
                                        launcher.animateToAllApps()
                                    } else {
                                        searchProvider.launch(launcher)
                                    }
                                }
                            },
                            onQsbLongClick = ::openOptions,
                            // The Nexus logo opens the Nexus app, at its Home screen.
                            onStartIconClick = if (searchProvider == Nexus) {
                                { context.startActivity(NexusAppActivity.homeIntent(context)) }
                            } else {
                                null
                            },
                            onEndIconClick = { id ->
                                runCatching {
                                    when {
                                        // Nexus mic is driven by press/release below, not click.
                                        searchProvider == Nexus && id == QsbIconId.MIC -> Unit
                                        searchProvider == Nexus && id == QsbIconId.CAMERA ->
                                            Toast.makeText(context, "Image input — coming soon", Toast.LENGTH_SHORT).show()
                                        id == QsbIconId.MIC -> voiceIntent?.let { context.startActivity(it) }
                                        id == QsbIconId.LENS -> lensIntent?.let { context.startActivity(it) }
                                        else -> null
                                    }
                                }
                            },
                            onEndIconPressDown = if (searchProvider == Nexus) {
                                { id -> if (id == QsbIconId.MIC) micClassifier.onDown(context.launcher) }
                            } else {
                                null
                            },
                            onEndIconPressUp = if (searchProvider == Nexus) {
                                { id, cancelled -> if (id == QsbIconId.MIC) micClassifier.onUp(context.launcher, cancelled) }
                            } else {
                                null
                            },
                        )

                        LawnQsbUi(
                            state = state,
                            style = style,
                            actions = actions,
                        )
                    }
                }
            }
        }

        // Stop Compose QSB from disappearing
        // https://stackoverflow.com/questions/72781705/jetpack-compose-view-not-drawing-when-coming-back-to-fragment/77496737#77496737
        composeView.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                requestLayout()
                composeView.disposeComposition()
            }
            override fun onViewDetachedFromWindow(v: View) {
                composeView.disposeComposition()
            }
        })

        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        if (searchProvider == Google) {
            repeatOnAttached {
                val forceWebsite = preferenceManager2.hotseatQsbForceWebsite.get()
                forceWebsite
                    .flatMapLatest {
                        if (it) Google.getSearchIntent(context) else flowOf(null)
                    }
                    .collect()
            }
        }
    }

    private fun openOptions() {
        val launcher = context.launcher
        val pos = Rect()
        launcher.dragLayer.getDescendantRectRelativeToSelf(composeView, pos)
        OptionsPopupView.show<LawnchairLauncher>(launcher, RectF(pos), listOf(getCustomizeOption()), true)
    }

    private fun getCustomizeOption() = OptionsPopupView.OptionItem(
        context,
        R.string.action_customize,
        R.drawable.ic_setting,
        StatsLogManager.LauncherEvent.IGNORE,
    ) {
        context.startActivity(PreferenceActivity.createIntent(context, Search()))
        true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val dp = activity.deviceProfile
        // Unlike Phone, for Foldable/Tablet we let the original onMeasure do that instead since it
        // matched what we need. It perfectly fit the QSB with the grid.
        if (!dp.deviceProperties.isPhone) {
            if (!composeView.isAttachedToWindow) {
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
                return
            }

            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val requestedWidth = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val cellWidth = DeviceProfile.calculateCellWidth(
            requestedWidth,
            dp.cellLayoutBorderSpacePx.x,
            dp.numShownHotseatIcons,
        )
        val iconSize = (dp.iconSizePx * 0.92f).toInt()
        val widthReduction = cellWidth - iconSize
        val width = requestedWidth - widthReduction
        setMeasuredDimension(width, height)

        if (!composeView.isAttachedToWindow) {
            // Ignore to prevent crash on preview contexts
            return
        }

        children.forEach { child ->
            measureChildWithMargins(child, widthMeasureSpec, widthReduction, heightMeasureSpec, 0)
        }
    }

    companion object {
        private const val LENS_PACKAGE = "com.google.ar.lens"
        private const val LENS_ACTIVITY = "com.google.vr.apps.ornament.app.lens.LensLauncherActivity"

        fun getVoiceIntent(
            provider: QsbSearchProvider,
            context: Context,
        ): Intent? {
            val intent = if (provider.supportVoiceIntent) provider.createVoiceIntent() else null

            return if (intent == null || !resolveIntent(context, intent)) {
                null
            } else {
                intent
            }
        }

        fun getLensIntent(context: Context): Intent? {
            val lensIntent = Intent.makeMainActivity(ComponentName(LENS_PACKAGE, LENS_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            if (context.packageManager.resolveActivity(lensIntent, 0) == null) return null

            return lensIntent
        }

        fun getSearchProvider(
            context: Context,
            provider: QsbSearchProvider,
        ): QsbSearchProvider {
            return if (provider == AppSearch ||
                provider.type == QsbSearchProviderType.LOCAL ||
                resolveIntent(context, provider.createSearchIntent()) ||
                resolveIntent(context, provider.createWebsiteIntent())
            ) {
                provider
            } else {
                AppSearch
            }
        }

        fun getSearchProvider(
            context: Context,
            preferenceManager: PreferenceManager2,
        ): QsbSearchProvider {
            return getSearchProvider(context, preferenceManager.hotseatQsbProvider.firstCached())
        }

        fun resolveIntent(context: Context, intent: Intent): Boolean = context.packageManager.resolveActivity(intent, 0) != null
    }
}

/** How often the Nexus bar reads whether the server has speech off (the app's presence cache changes under it). */
private const val VOICE_OFF_POLL_MS = 5_000L

/**
 * Classifies a press on the Nexus mic as tap vs hold (mirrors the Flutter 250ms `_MicPillState`):
 *
 *  - Held ≥ 250ms → open Voice mode in Hold, recording starts; release ([onUp]) sends.
 *  - Released < 250ms → open Voice mode in Tap; the on-screen stop button sends.
 *
 * Gated on mic permission: Blocked → app settings; Denied → request, and on grant open in Tap
 * (the finger has lifted for the system dialog, so Hold can't be honored). Runs on the main thread
 * (pointer + lifecycleScope-Main), so its mutable fields need no synchronization.
 */
private class NexusMicClassifier {
    private var timerJob: Job? = null
    private var overlay: NexusChatOverlay? = null
    private var resolvedHold = false

    fun onDown(launcher: app.lawnchair.LawnchairLauncher) {
        resolvedHold = false
        overlay = null
        when (MicPermission.state(launcher)) {
            MicPermission.State.Granted -> {
                timerJob = launcher.lifecycleScope.launch {
                    delay(250)
                    resolvedHold = true
                    overlay = NexusChatOverlay.openVoice(launcher, MicGesture.Hold)
                }
            }
            MicPermission.State.Blocked -> MicPermission.openSettings(launcher)
            MicPermission.State.Denied -> launcher.requestMicPermission { granted ->
                if (granted) NexusChatOverlay.openVoice(launcher, MicGesture.Tap)
            }
        }
    }

    fun onUp(launcher: app.lawnchair.LawnchairLauncher, cancelled: Boolean) {
        timerJob?.cancel()
        timerJob = null
        if (resolvedHold) {
            // Hold flow: releasing the mic sends the recorded utterance.
            overlay?.endRecordingAndSend()
        } else if (!cancelled && MicPermission.state(launcher) == MicPermission.State.Granted) {
            // Quick tap: open in Tap mode; the stop button ends recording.
            NexusChatOverlay.openVoice(launcher, MicGesture.Tap)
        }
        overlay = null
        resolvedHold = false
    }
}
