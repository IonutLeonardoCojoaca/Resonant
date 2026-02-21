package com.example.resonant.utils

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.palette.graphics.Palette

object MiniPlayerColorizer {

    data class Targets(
        val container: View,
        val title: TextView? = null,
        val subtitle: TextView? = null,
        val iconButtons: List<ImageView> = emptyList(),
        val seekBar: SeekBar? = null,
        val gradientOverlay: View? = null
    )

    fun applyFromImageView(
        imageView: ImageView,
        targets: Targets,
        fallbackColor: Int,
        animateMillis: Long = 400L // 👈 más suave
    ) {
        val drawable = imageView.drawable ?: run {
            tintBackgroundAndGradient(
                targets.container, targets.gradientOverlay,
                Color.TRANSPARENT, fallbackColor, animateMillis
            )
            tintTextAndIcons(targets, pickTextColorFor(fallbackColor), animateMillis)
            return
        }

        val bitmap = (drawable as? BitmapDrawable)?.bitmap
        if (bitmap == null || bitmap.isRecycled) {
            tintBackgroundAndGradient(
                targets.container, targets.gradientOverlay,
                Color.TRANSPARENT, fallbackColor, animateMillis
            )
            tintTextAndIcons(targets, pickTextColorFor(fallbackColor), animateMillis)
            return
        }

        Palette.from(bitmap).maximumColorCount(32).generate { palette ->
            val swatches = listOfNotNull(
                palette?.vibrantSwatch,
                palette?.darkVibrantSwatch,
                palette?.lightVibrantSwatch,
                palette?.mutedSwatch,
                palette?.darkMutedSwatch,
                palette?.lightMutedSwatch,
                palette?.dominantSwatch
            )

            val bestSwatch = swatches.maxByOrNull { it.population }
            val rawBgColor = bestSwatch?.rgb ?: fallbackColor

            // Asegurar contraste adecuado
            // Preferimos texto blanco a menos que el fondo sea muy claro
            val isBgDark = ColorUtils.calculateLuminance(rawBgColor) < 0.6
            val textColor = if (isBgDark) Color.WHITE else Color.BLACK

            val bgColor = ensureContrast(rawBgColor, textColor)

            val fromColor = targets.container.backgroundTintList?.defaultColor ?: Color.TRANSPARENT

            // 🔥 Una sola animación sincronizada
            tintBackgroundAndGradient(
                targets.container, targets.gradientOverlay,
                fromColor, bgColor, animateMillis
            )

            tintTextAndIcons(targets, textColor, animateMillis)
        }
    }

    private fun tintBackgroundAndGradient(
        container: View,
        gradientView: View?,
        fromColor: Int,
        toColor: Int,
        animateMillis: Long
    ) {
        val background = gradientView?.background as? GradientDrawable

        val anim = ValueAnimator.ofArgb(fromColor, toColor).setDuration(animateMillis)
        anim.addUpdateListener { va ->
            val color = va.animatedValue as Int

            // Fondo principal
            ViewCompat.setBackgroundTintList(container, ColorStateList.valueOf(color))

            // Gradiente lateral
            background?.let {
                val startColor = it.colors?.first() ?: Color.TRANSPARENT
                it.colors = intArrayOf(startColor, color)
                gradientView.background = it
            }
        }
        anim.start()
    }

    private fun ensureContrast(color: Int, textColor: Int): Int {
        var adjusted = color
        var tries = 0
        val isTextDark = ColorUtils.calculateLuminance(textColor) < 0.5
        
        while (ColorUtils.calculateContrast(textColor, adjusted) < 4.5 && tries < 5) {
            adjusted = if (isTextDark) {
                // Si el texto es oscuro (Negro), queremos aclarar el fondo
                ColorUtils.blendARGB(adjusted, Color.WHITE, 0.15f)
            } else {
                 // Si el texto es claro (Blanco), queremos oscurecer el fondo
                ColorUtils.blendARGB(adjusted, Color.BLACK, 0.15f)
            }
            tries++
        }
        return adjusted
    }

    private fun tintTextAndIcons(targets: Targets, toColor: Int, animateMillis: Long) {
        fun animateTextColor(tv: TextView?, target: Int) {
            tv ?: return
            val from = tv.currentTextColor
            val anim = ValueAnimator.ofArgb(from, target).setDuration(animateMillis)
            anim.addUpdateListener { va ->
                tv.setTextColor(va.animatedValue as Int)
            }
            anim.start()
        }

        fun animateIconTint(iv: ImageView, target: Int) {
            val from = iv.imageTintList?.defaultColor ?: Color.WHITE
            val anim = ValueAnimator.ofArgb(from, target).setDuration(animateMillis)
            anim.addUpdateListener { va ->
                iv.imageTintList = ColorStateList.valueOf(va.animatedValue as Int)
            }
            anim.start()
        }

        animateTextColor(targets.title, toColor)
        animateTextColor(targets.subtitle, adjustAlpha(toColor, 0.85f))
        targets.iconButtons.forEach { iv -> animateIconTint(iv, toColor) }

        targets.seekBar?.let { sb ->
            sb.progressTintList = ColorStateList.valueOf(toColor)
            sb.thumbTintList = ColorStateList.valueOf(toColor)
        }
    }

    private fun pickTextColorFor(backgroundColor: Int): Int {
        val luminance = ColorUtils.calculateLuminance(backgroundColor)
        return if (luminance > 0.5) Color.BLACK else Color.WHITE
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}