package com.example.resonant.ui.explore

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.example.resonant.R
import com.example.resonant.ui.components.ResonantAccent
import com.example.resonant.ui.components.ResonantMotion

/**
 * Binds and animates `view_explore_quick_card` (the 1x1 shortcuts at the top of Explore).
 *
 * The translationX/Y declared in the XML are the resting positions of both logos;
 * [animateIn] starts them off-position and brings them back to exactly those values.
 */
object ExploreQuickCard {

    data class Spec(
        val eyebrow: String,
        val title: String,
        val accent: ResonantAccent
    )

    private const val STAGGER_MS = 90L
    private const val GLOW_ALPHA = 0x47

    fun bind(card: View, spec: Spec, onClick: () -> Unit) {
        card.findViewById<TextView>(R.id.quickCardEyebrow).apply {
            text = spec.eyebrow
            setTextColor(spec.accent.color)
        }
        card.findViewById<TextView>(R.id.quickCardTitle).text = spec.title
        card.findViewById<ImageView>(R.id.quickCardIconTop).setImageResource(spec.accent.icon)
        card.findViewById<ImageView>(R.id.quickCardIconBottom).setImageResource(spec.accent.icon)
        card.findViewById<View>(R.id.quickCardGlow).background = glowFor(card, spec.accent.color)
        card.contentDescription = "${spec.title}. ${spec.eyebrow}"
        card.setOnClickListener { onClick() }
    }

    /**
     * Entrada escalonada: el logo grande sube con fade, el pequeño cae desde arriba
     * con un rebote amortiguado, y después aparecen glow, textos y badge.
     */
    fun animateIn(card: View, index: Int) {
        if (!ValueAnimator.areAnimatorsEnabled()) return

        val density = card.resources.displayMetrics.density
        val base = index * STAGGER_MS

        val glow = card.findViewById<View>(R.id.quickCardGlow)
        val bottomIcon = card.findViewById<View>(R.id.quickCardIconBottom)
        val topIcon = card.findViewById<View>(R.id.quickCardIconTop)
        val textBlock = card.findViewById<View>(R.id.quickCardTextBlock)
        val badge = card.findViewById<View>(R.id.quickCardBadge)

        glow.alpha = 0f
        glow.animate().alpha(1f)
            .setStartDelay(base + 120).setDuration(1100).setInterpolator(ResonantMotion.expoOut).start()

        val bottomRestY = bottomIcon.translationY
        bottomIcon.alpha = 0f
        bottomIcon.translationY = bottomRestY + 56 * density
        bottomIcon.animate().alpha(1f).translationY(bottomRestY)
            .setStartDelay(base).setDuration(950).setInterpolator(ResonantMotion.expoOut).start()

        val topRestY = topIcon.translationY
        topIcon.alpha = 0f
        topIcon.translationY = topRestY - 44 * density
        topIcon.animate().translationY(topRestY)
            .setStartDelay(base + 180).setDuration(1300).setInterpolator(ResonantMotion.softBounce).start()
        ObjectAnimator.ofFloat(topIcon, View.ALPHA, 0f, 1f).apply {
            startDelay = base + 180
            duration = 280
            start()
        }

        textBlock.alpha = 0f
        textBlock.translationY = 10 * density
        textBlock.animate().alpha(1f).translationY(0f)
            .setStartDelay(base + 280).setDuration(650).setInterpolator(ResonantMotion.expoOut).start()

        badge.alpha = 0f
        badge.scaleX = 0.4f
        badge.scaleY = 0.4f
        badge.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(base + 460).setDuration(520).setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    private fun glowFor(card: View, @ColorInt accent: Int): GradientDrawable =
        GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(ColorUtils.setAlphaComponent(accent, GLOW_ALPHA), 0x00000000)
            setGradientCenter(0.82f, 0.92f)
            gradientRadius = 130 * card.resources.displayMetrics.density
        }
}
