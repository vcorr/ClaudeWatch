package com.vcorr.claudewatch

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * What surrounds the voice faces over the watch face: a dark [veil] whose depth follows the state,
 * the accent [glow] rising from the bottom of the screen (breathing while listening), and where the
 * [content] sits. MainActivity decides the values per state; this applies them, smoothly.
 */
class Overlay(
    private val veil: View,
    private val glow: View,
    private val content: LinearLayout,
    accent: Int,
) {
    private val density = glow.resources.displayMetrics.density

    private val breathing = ObjectAnimator.ofPropertyValuesHolder(
        glow,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f),
        PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.7f),
    ).apply {
        duration = 900
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
    }

    init {
        // The accent fading out from a point just below the screen's bottom edge, in several
        // stops so there is no visible rim.
        val rgb = accent and 0xFFFFFF
        glow.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = GLOW_RADIUS_DP * density
            setGradientCenter(0.5f, 0.5f)
            setColors(
                intArrayOf((0x8C shl 24) or rgb, (0x47 shl 24) or rgb, (0x14 shl 24) or rgb, rgb),
                floatArrayOf(0f, 0.35f, 0.7f, 1f),
            )
        }
    }

    /**
     * [veilAlpha]: how dark the veil over the watch face is. [glowSinkDp]: how far the glow sits
     * below the bottom edge (less is higher). [contentAtBottom]: content gathers near the glow
     * rather than centring. [breathe]: the glow pulses, as it does while listening.
     */
    fun show(veilAlpha: Float, glowVisible: Boolean, glowSinkDp: Int, contentAtBottom: Boolean, breathe: Boolean) {
        veil.animate().alpha(veilAlpha).setDuration(FADE_MS).start()
        glow.visibility = if (glowVisible) View.VISIBLE else View.GONE
        glow.animate().translationY(glowSinkDp * density).setDuration(FADE_MS).start()
        content.gravity = if (contentAtBottom) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        content.setPadding(
            content.paddingLeft,
            content.paddingTop,
            content.paddingRight,
            ((if (contentAtBottom) BOTTOM_PADDING_NEAR_GLOW_DP else BOTTOM_PADDING_DP) * density).toInt(),
        )
        breathing.runWhile(breathe)
    }

    fun release() = breathing.cancel()

    private companion object {
        const val FADE_MS = 250L
        const val GLOW_RADIUS_DP = 105f
        const val BOTTOM_PADDING_NEAR_GLOW_DP = 46
        const val BOTTOM_PADDING_DP = 18
    }
}

/** Runs an endless animation while [running]; otherwise stops it back where it started. */
fun ObjectAnimator.runWhile(running: Boolean) {
    if (running) {
        if (!isStarted) start()
    } else if (isStarted) {
        // Back to where it started, touching only what it animates.
        cancel()
        setCurrentFraction(0f)
    }
}
