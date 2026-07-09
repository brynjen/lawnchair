package app.lawnchair.nexuslauncher

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.InsettableFrameLayout
import com.android.systemui.plugins.shared.LauncherOverlayManager
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlay
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayCallbacks

/**
 * Nexus's in-process leftmost ("-1") overlay panel — an edge-to-edge Compose News surface rendered
 * inside the launcher process (no external feed app). Attached to `LauncherRootView` (the DragLayer's
 * parent) with `ignoreInsets` so it's full-bleed and above the DragLayer's custom draw order.
 * `setLauncherOverlay` is triggered once the DragLayer is laid out (that enables the overscroll);
 * the Workspace then drives [onScrollChange] 0→1 as the user swipes right.
 */
class NexusNewsOverlay(private val launcher: LawnchairLauncher) :
    LauncherOverlayManager,
    LauncherOverlay {

    private var callbacks: LauncherOverlayCallbacks? = null
    private var panel: View? = null
    private var attached = false
    private var progress = 0f
    private var flingVelocity = 0f
    private var settleAnim: ValueAnimator? = null

    private val panelParent: ViewGroup?
        get() = launcher.dragLayer?.parent as? ViewGroup

    private val panelWidth: Int
        get() = (panel?.width ?: panelParent?.width)?.takeIf { it > 0 }
            ?: launcher.resources.displayMetrics.widthPixels

    override fun onAttachedToWindow() = attachOverlay()
    override fun onActivityResumed() = attachOverlay()

    private fun attachOverlay() {
        if (attached) return
        val dragLayer = launcher.dragLayer
        if (dragLayer == null || dragLayer.width == 0) {
            dragLayer?.post { attachOverlay() }
                ?: launcher.window?.decorView?.post { attachOverlay() }
            return
        }
        val parent = panelParent ?: dragLayer
        ensurePanel(parent)
        launcher.setLauncherOverlay(this)
        attached = true
    }

    private fun ensurePanel(parent: ViewGroup) {
        if (panel != null) return
        val v = ComposeView(launcher).apply {
            setContent { NexusNewsScreen(onClose = { animateTo(0f) }) }
        }
        parent.addView(
            v,
            InsettableFrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
                ignoreInsets = true
            },
        )
        v.bringToFront()
        v.translationX = -parent.width.toFloat()
        panel = v
        applyProgressToPanel()
    }

    override fun setOverlayCallbacks(callbacks: LauncherOverlayCallbacks?) {
        this.callbacks = callbacks
    }

    override fun onScrollInteractionBegin() {
        settleAnim?.cancel()
    }

    override fun onScrollChange(progress: Float, rtl: Boolean) {
        setProgress(progress)
    }

    override fun onScrollInteractionEnd() {
        val target = when {
            flingVelocity > FLING_THRESHOLD -> 1f
            flingVelocity < -FLING_THRESHOLD -> 0f
            else -> if (progress > 0.5f) 1f else 0f
        }
        flingVelocity = 0f
        animateTo(target)
    }

    override fun onFlingVelocity(velocity: Float) {
        flingVelocity = velocity
    }

    private fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        applyProgressToPanel()
        callbacks?.onOverlayScrollChanged(progress)
    }

    private fun applyProgressToPanel() {
        panel?.translationX = -panelWidth * (1f - progress)
    }

    private fun animateTo(target: Float) {
        settleAnim?.cancel()
        settleAnim = ValueAnimator.ofFloat(progress, target).apply {
            duration = 200
            addUpdateListener { setProgress(it.animatedValue as Float) }
            start()
        }
    }

    override fun openOverlay() = animateTo(1f)

    override fun hideOverlay(animate: Boolean) = hideOverlay(if (animate) 200 else 0)

    override fun hideOverlay(duration: Int) {
        if (duration <= 0) setProgress(0f) else animateTo(0f)
    }

    override fun onActivityDestroyed() {
        settleAnim?.cancel()
        (panel?.parent as? ViewGroup)?.removeView(panel)
        panel = null
        attached = false
    }

    companion object {
        private const val FLING_THRESHOLD = 1000f
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
