package com.example.resonant.ui.components

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.example.resonant.R
import com.google.android.material.appbar.AppBarLayout
import kotlin.math.abs

/**
 * Binds and animates `view_chart_hero`, the layered header shared by the ranking
 * screens (charts, top artists, top albums, community playlists).
 *
 * - Entrada: el logo grande sube con fade, el pequeño cae con rebote amortiguado y
 *   los textos entran en cascada (misma coreografía que las tarjetas de Explorar).
 * - Parallax: cada capa se desplaza a distinta velocidad al colapsar el AppBarLayout.
 * - Pulso: pequeño rebote de los logos + crossfade de textos al cambiar de periodo.
 */
object ChartHero {

    private const val GLOW_ALPHA = 0x5C

    // Fracción del scroll que cada capa "devuelve": cuanto mayor, más lenta se mueve
    // (más lejos parece). El texto va entre medias y además se desvanece.
    private const val PARALLAX_GLOW = 0.65f
    private const val PARALLAX_BACK = 0.45f
    private const val PARALLAX_TEXT = 0.3f
    private const val PARALLAX_FRONT = 0.12f

    fun bind(
        hero: View,
        accent: ResonantAccent,
        eyebrow: String,
        title: String,
        subtitle: String?
    ) {
        hero.findViewById<TextView>(R.id.heroEyebrow).apply {
            text = eyebrow
            setTextColor(accent.color)
        }
        hero.findViewById<ImageView>(R.id.heroIconBack).apply {
            setImageResource(accent.icon)
            restY(this)
        }
        hero.findViewById<ImageView>(R.id.heroIconFront).apply {
            setImageResource(accent.icon)
            restY(this)
        }
        hero.findViewById<View>(R.id.heroGlow).background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(ColorUtils.setAlphaComponent(accent.color, GLOW_ALPHA), 0x00000000)
            setGradientCenter(0.8f, 0.42f)
            gradientRadius = 250 * hero.resources.displayMetrics.density
        }
        setText(hero, title, subtitle, animate = false)
    }

    /** Cambia título/subtítulo; con [animate] hace un crossfade con un leve deslizamiento. */
    fun setText(hero: View, title: String, subtitle: String?, animate: Boolean) {
        val titleView = hero.findViewById<TextView>(R.id.heroTitle)
        val subtitleView = hero.findViewById<TextView>(R.id.heroSubtitle)
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            titleView.text = title
            subtitleView.text = subtitle
            subtitleView.isVisible = !subtitle.isNullOrBlank()
            return
        }
        crossfade(titleView, 0L) { titleView.text = title }
        crossfade(subtitleView, 50L) {
            subtitleView.text = subtitle
            subtitleView.isVisible = !subtitle.isNullOrBlank()
        }
    }

    /** Pastilla opcional bajo el subtítulo (p. ej. "48 playlists · 12 creadores"). */
    fun setMeta(hero: View, meta: String?) {
        hero.findViewById<TextView>(R.id.heroMeta).apply {
            text = meta
            isVisible = !meta.isNullOrBlank()
        }
    }

    fun animateIn(hero: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val density = hero.resources.displayMetrics.density

        val glow = hero.findViewById<View>(R.id.heroGlow)
        glow.alpha = 0f
        glow.animate().alpha(1f)
            .setStartDelay(100).setDuration(1300).setInterpolator(ResonantMotion.expoOut).start()

        val back = hero.findViewById<View>(R.id.heroIconBack)
        val backRestY = restY(back)
        back.alpha = 0f
        back.translationY = backRestY + 96 * density
        back.scaleX = 1.08f
        back.scaleY = 1.08f
        back.animate().alpha(1f).translationY(backRestY).scaleX(1f).scaleY(1f)
            .setStartDelay(0).setDuration(1050).setInterpolator(ResonantMotion.expoOut).start()

        val front = hero.findViewById<View>(R.id.heroIconFront)
        val frontRestY = restY(front)
        front.alpha = 0f
        front.translationY = frontRestY - 64 * density
        front.animate().translationY(frontRestY)
            .setStartDelay(200).setDuration(1400).setInterpolator(ResonantMotion.softBounce).start()
        ObjectAnimator.ofFloat(front, View.ALPHA, 0f, 1f).apply {
            startDelay = 200
            duration = 300
            start()
        }

        val textBlock = hero.findViewById<ViewGroup>(R.id.heroTextBlock)
        for (i in 0 until textBlock.childCount) {
            val child = textBlock.getChildAt(i)
            child.alpha = 0f
            child.translationY = 18 * density
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(260L + i * 70L).setDuration(750).setInterpolator(ResonantMotion.expoOut).start()
        }
    }

    /** Rebote corto de los logos para acompañar un cambio de periodo. */
    fun pulse(hero: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val density = hero.resources.displayMetrics.density

        val front = hero.findViewById<View>(R.id.heroIconFront)
        front.animate().cancel()
        val frontRestY = restY(front)
        front.translationY = frontRestY - 26 * density
        front.animate().translationY(frontRestY)
            .setStartDelay(0).setDuration(950).setInterpolator(ResonantMotion.softBounce).start()

        val back = hero.findViewById<View>(R.id.heroIconBack)
        back.scaleX = 0.94f
        back.scaleY = 0.94f
        back.animate().scaleX(1f).scaleY(1f)
            .setStartDelay(0).setDuration(700).setInterpolator(ResonantMotion.expoOut).start()

        val glow = hero.findViewById<View>(R.id.heroGlow)
        glow.alpha = 0.35f
        glow.animate().alpha(1f)
            .setStartDelay(0).setDuration(800).setInterpolator(ResonantMotion.expoOut).start()
    }

    /**
     * Parallax por capas ligado al colapso del AppBarLayout. [toolbarTitle], si se
     * pasa, aparece en el último 25% del colapso (cuando el título grande ya no se ve).
     */
    fun attachParallax(appBar: AppBarLayout, hero: View, toolbarTitle: View? = null) {
        val glowLayer = hero.findViewById<View>(R.id.heroLayerGlow)
        val backLayer = hero.findViewById<View>(R.id.heroLayerBack)
        val frontLayer = hero.findViewById<View>(R.id.heroLayerFront)
        val textLayer = hero.findViewById<View>(R.id.heroLayerText)
        toolbarTitle?.alpha = 0f

        appBar.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { bar, verticalOffset ->
            val range = bar.totalScrollRange
            if (range == 0) return@OnOffsetChangedListener
            val scrolled = abs(verticalOffset).toFloat()
            val progress = scrolled / range

            glowLayer.translationY = scrolled * PARALLAX_GLOW
            backLayer.translationY = scrolled * PARALLAX_BACK
            backLayer.alpha = 1f - progress * 0.55f
            frontLayer.translationY = scrolled * PARALLAX_FRONT
            frontLayer.alpha = 1f - progress * 0.8f
            textLayer.translationY = scrolled * PARALLAX_TEXT
            textLayer.alpha = 1f - (progress * 1.7f).coerceAtMost(1f)

            toolbarTitle?.alpha = ((progress - 0.75f) / 0.25f).coerceIn(0f, 1f)
        })
    }

    /**
     * Imagen destacada dentro de una tarjeta (portada / galería del #1): se amplía un
     * poco y se desplaza más despacio que el contenido, como una capa de fondo.
     * Cuenta tanto el colapso del AppBarLayout como el scroll del contenido.
     */
    fun attachMediaParallax(appBar: AppBarLayout, scroll: NestedScrollView, media: View) {
        val maxShift = 22 * media.resources.displayMetrics.density
        media.scaleX = 1.14f
        media.scaleY = 1.14f
        var appBarScrolled = 0
        var contentScrolled = 0
        val apply = {
            media.translationY = ((appBarScrolled + contentScrolled) * 0.12f).coerceAtMost(maxShift)
        }
        appBar.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { _, verticalOffset ->
            appBarScrolled = abs(verticalOffset)
            apply()
        })
        scroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
            contentScrolled = scrollY
            apply()
        })
    }

    /** Posición de reposo (la del XML), capturada la primera vez que se pide. */
    private fun restY(view: View): Float =
        view.getTag(view.id) as? Float ?: view.translationY.also { view.setTag(view.id, it) }

    private fun crossfade(view: View, delay: Long, swap: () -> Unit) {
        val shift = 8 * view.resources.displayMetrics.density
        view.animate().cancel()
        view.animate().alpha(0f).translationY(-shift)
            .setStartDelay(delay).setDuration(140).setInterpolator(null)
            .withEndAction {
                swap()
                view.translationY = shift
                view.animate().alpha(1f).translationY(0f)
                    .setStartDelay(0).setDuration(380).setInterpolator(ResonantMotion.expoOut).start()
            }
            .start()
    }
}
