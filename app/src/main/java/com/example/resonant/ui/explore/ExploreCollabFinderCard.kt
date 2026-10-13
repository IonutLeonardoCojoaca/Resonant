package com.example.resonant.ui.explore

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import com.example.resonant.R
import com.example.resonant.feature.collabfinder.ui.views.CollabOrbitMotifView
import com.example.resonant.ui.components.ResonantAccent
import com.example.resonant.ui.components.ResonantMotion

/** Binds and animates `view_explore_collab_finder_card` (the Collab Finder entry in Explore). */
object ExploreCollabFinderCard {

    private val accent = ResonantAccent.COLLAB

    fun bind(card: View, onOpen: () -> Unit) {
        val density = card.resources.displayMetrics.density
        card.findViewById<CollabOrbitMotifView>(R.id.collabCardMotif).accentColor = accent.color
        card.findViewById<View>(R.id.collabCardGlow).background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(ColorUtils.setAlphaComponent(accent.color, 0x52), 0x00000000)
            setGradientCenter(0.82f, 0.5f)
            gradientRadius = 200 * density
        }
        card.findViewById<View>(R.id.collabCardCta).background = GradientDrawable().apply {
            cornerRadius = 16 * density
            setColor(ColorUtils.blendARGB(accent.color, Color.BLACK, 0.35f))
        }
        card.setOnClickListener { onOpen() }
    }

    /** Las órbitas aparecen escalando desde el centro y el texto entra en cascada. */
    fun animateIn(card: View, startDelay: Long = 0L) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val density = card.resources.displayMetrics.density

        val motif = card.findViewById<View>(R.id.collabCardMotif)
        motif.alpha = 0f
        motif.scaleX = 0.7f
        motif.scaleY = 0.7f
        motif.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(startDelay).setDuration(1100).setInterpolator(ResonantMotion.expoOut).start()

        val glow = card.findViewById<View>(R.id.collabCardGlow)
        glow.alpha = 0f
        glow.animate().alpha(1f)
            .setStartDelay(startDelay + 120).setDuration(1200).setInterpolator(ResonantMotion.expoOut).start()

        val text = card.findViewById<ViewGroup>(R.id.collabCardText)
        for (i in 0 until text.childCount) {
            val child = text.getChildAt(i)
            child.alpha = 0f
            child.translationY = 12 * density
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(startDelay + 200 + i * 70L).setDuration(650)
                .setInterpolator(ResonantMotion.expoOut).start()
        }
    }
}
