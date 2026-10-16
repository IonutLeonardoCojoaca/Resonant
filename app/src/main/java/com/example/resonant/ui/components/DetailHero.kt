package com.example.resonant.ui.components

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.palette.graphics.Palette
import com.example.resonant.R
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import kotlin.math.abs

/**
 * Cabecera por capas de las fichas (`view_detail_hero`): portada desenfocada, velo y resplandor
 * del color de la portada, portada nítida y texto, cada capa con su propio parallax al plegar.
 */
object DetailHero {

    /** Colores sacados de la portada: [accent] legible sobre oscuro, [deep] para fondos. */
    data class Colors(@ColorInt val accent: Int, @ColorInt val accentAlt: Int, @ColorInt val deep: Int)

    private const val BACKDROP_FACTOR = 0.5f
    private const val GLOW_FACTOR = 0.65f
    private const val ART_FACTOR = 0.3f
    private const val TEXT_FACTOR = 0.15f

    fun bind(hero: View, eyebrow: String, title: String, subtitle: String?, circularArt: Boolean = false) {
        hero.findViewById<TextView>(R.id.heroEyebrow).text = eyebrow
        hero.findViewById<TextView>(R.id.heroTitle).text = title
        hero.findViewById<TextView>(R.id.heroSubtitle).apply {
            text = subtitle
            isVisible = !subtitle.isNullOrBlank()
        }
        if (circularArt) {
            hero.findViewById<ShapeableImageView>(R.id.heroArt).shapeAppearanceModel =
                ShapeAppearanceModel.builder().setAllCornerSizes(ShapeAppearanceModel.PILL).build()
        }
    }

    fun playButton(hero: View): MaterialButton = hero.findViewById(R.id.heroPlayButton)

    /** Píldoras de datos bajo el título (duración, reproducciones, año…). */
    fun setChips(hero: View, chips: List<String>) {
        val row = hero.findViewById<LinearLayout>(R.id.heroChips)
        row.removeAllViews()
        row.isVisible = chips.isNotEmpty()
        val density = hero.resources.displayMetrics.density
        chips.forEachIndexed { i, text ->
            val chip = TextView(hero.context, null, 0, R.style.PlaymixStatChip).apply {
                this.text = text
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (i > 0) marginStart = (6 * density).toInt() }
            }
            row.addView(chip)
        }
    }

    /** Pone la portada en las dos capas (nítida y desenfocada a sangre). */
    fun setArtwork(hero: View, bitmap: Bitmap) {
        hero.findViewById<ImageView>(R.id.heroArt).setImageBitmap(bitmap)
        hero.findViewById<ImageView>(R.id.heroBackdrop).apply {
            setImageBitmap(bitmap)
            setRenderEffect(RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP))
        }
    }

    /** Extrae los colores de la portada (en segundo plano) y tiñe velo, resplandor y barra plegada. */
    fun applyPalette(
        hero: View,
        bitmap: Bitmap,
        collapsing: CollapsingToolbarLayout?,
        onColors: (Colors) -> Unit
    ) {
        Palette.from(bitmap).maximumColorCount(24).generate { palette ->
            if (!hero.isAttachedToWindow) return@generate
            val fallback = ContextCompat.getColor(hero.context, R.color.playmix_accent)
            val base = palette?.vibrantSwatch?.rgb ?: palette?.lightVibrantSwatch?.rgb
                ?: palette?.dominantSwatch?.rgb ?: fallback
            val alt = palette?.mutedSwatch?.rgb ?: palette?.darkVibrantSwatch?.rgb
                ?: ColorUtils.blendARGB(base, Color.MAGENTA, 0.35f)
            val colors = Colors(
                accent = readable(base),
                accentAlt = readable(alt),
                deep = ColorUtils.blendARGB(base, Color.BLACK, 0.72f)
            )
            paint(hero, collapsing, colors)
            onColors(colors)
        }
    }

    private fun paint(hero: View, collapsing: CollapsingToolbarLayout?, palette: Colors) {
        val bg = ContextCompat.getColor(hero.context, R.color.primaryColorTheme)
        hero.findViewById<View>(R.id.heroWash).background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ColorUtils.setAlphaComponent(palette.deep, 0x66),
                ColorUtils.setAlphaComponent(palette.accent, 0x26),
                ColorUtils.setAlphaComponent(bg, 0xE6),
                bg
            )
        )
        val glowColor = ColorUtils.setAlphaComponent(palette.accent, 0x88)
        hero.findViewById<View>(R.id.heroGlow).background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 210 * hero.resources.displayMetrics.density
            colors = intArrayOf(glowColor, 0x00000000)
        }
        hero.findViewById<TextView>(R.id.heroEyebrow).setTextColor(palette.accent)
        playButton(hero).backgroundTintList = ColorStateList.valueOf(ColorUtils.blendARGB(palette.accent, Color.BLACK, 0.15f))
        collapsing?.setContentScrimColor(palette.deep)
        collapsing?.setStatusBarScrimColor(palette.deep)
    }

    /** Asegura que el color se lea sobre fondo oscuro sin perder su tono. */
    @ColorInt
    fun readable(@ColorInt color: Int): Int {
        var c = color
        repeat(6) {
            if (ColorUtils.calculateLuminance(c) >= 0.32) return c
            c = ColorUtils.blendARGB(c, Color.WHITE, 0.18f)
        }
        return c
    }

    /** Parallax por capas al plegar el AppBar; el título de la barra aparece al final. */
    fun attachParallax(appBar: AppBarLayout, hero: View, toolbarTitle: View?) {
        val backdrop = hero.findViewById<View>(R.id.heroBackdrop)
        val glow = hero.findViewById<View>(R.id.heroGlow)
        val art = hero.findViewById<View>(R.id.heroArt)
        val text = hero.findViewById<View>(R.id.heroTextBlock)
        appBar.addOnOffsetChangedListener { bar, offset ->
            val range = bar.totalScrollRange.takeIf { it > 0 } ?: return@addOnOffsetChangedListener
            val dy = -offset.toFloat()
            val progress = abs(offset).toFloat() / range
            backdrop.translationY = dy * BACKDROP_FACTOR
            backdrop.scaleX = 1.08f + progress * 0.12f
            backdrop.scaleY = backdrop.scaleX
            glow.translationY = dy * GLOW_FACTOR
            art.translationY = dy * ART_FACTOR
            art.scaleX = 1f - progress * 0.28f
            art.scaleY = art.scaleX
            art.alpha = 1f - (progress * 1.4f).coerceAtMost(1f)
            text.translationY = dy * TEXT_FACTOR
            text.alpha = 1f - (progress * 1.6f).coerceAtMost(1f)
            toolbarTitle?.alpha = ((progress - 0.75f) / 0.25f).coerceIn(0f, 1f)
        }
    }

    /** Entrada: la portada crece desde un poco más pequeña y el texto sube en cascada. */
    fun animateIn(hero: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val density = hero.resources.displayMetrics.density
        hero.findViewById<View>(R.id.heroArt).apply {
            alpha = 0f; scaleX = 0.9f; scaleY = 0.9f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700).setInterpolator(ResonantMotion.expoOut).start()
        }
        listOf(R.id.heroEyebrow, R.id.heroTitle, R.id.heroSubtitle, R.id.heroChips).forEachIndexed { i, id ->
            hero.findViewById<View>(id).apply {
                alpha = 0f; translationY = 14 * density
                animate().alpha(1f).translationY(0f).setStartDelay(180L + i * 70L).setDuration(600)
                    .setInterpolator(ResonantMotion.expoOut).start()
            }
        }
    }
}
