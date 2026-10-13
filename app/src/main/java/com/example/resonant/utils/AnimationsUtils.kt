package com.example.resonant.utils

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.example.resonant.R
import com.google.android.material.chip.Chip

object AnimationsUtils {

    fun animateChip(chip: Chip, checked: Boolean) {
        val scaleUp = ObjectAnimator.ofPropertyValuesHolder(
            chip,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f, 1f)
        ).apply {
            duration = 150
            interpolator = AccelerateDecelerateInterpolator()
        }

        scaleUp.start()
    }

    fun animateChipColor(chip: Chip, checked: Boolean) {
        val colorFrom = if (checked) {
            ContextCompat.getColor(chip.context, R.color.chip_unselected_background)
        } else {
            ContextCompat.getColor(chip.context, R.color.chip_selected_background)
        }

        val colorTo = if (checked) {
            ContextCompat.getColor(chip.context, R.color.chip_selected_background)
        } else {
            ContextCompat.getColor(chip.context, R.color.chip_unselected_background)
        }

        val colorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)
        colorAnimation.duration = 200 // ms
        colorAnimation.addUpdateListener { animator ->
            chip.chipBackgroundColor = ColorStateList.valueOf(animator.animatedValue as Int)
        }
        colorAnimation.start()
    }

     fun animateBlurryBackground(imageView: ImageView, newBitmap: Bitmap) {
        imageView.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                imageView.setImageBitmap(newBitmap)
                imageView.animate()
                    .alpha(0.3f)
                    .setDuration(300)
                    .start()
            }
            .start()
    }

    // En tu archivo AnimationsUtils.kt o donde tengas la función

    fun animateSongImage(
        imageView: ImageView,
        newBitmap: Bitmap,
        direction: Int,
        onComplete: () -> Unit // <-- AÑADE ESTE PARÁMETRO
    ) {
        val moveX = if (direction > 0) -200f else 200f

        imageView.animate()
            .translationX(moveX)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                imageView.setImageBitmap(newBitmap)
                imageView.translationX = -moveX
                imageView.scaleX = 0.8f
                imageView.scaleY = 0.8f
                imageView.alpha = 0f

                imageView.animate()
                    .translationX(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(250)
                    // 👇 ESTE ES EL CAMBIO CLAVE 👇
                    .withEndAction {
                        onComplete() // Avisamos que TODO ha terminado
                    }
                    .start()
            }
            .start()
    }

    fun animateOpenFragment (view: View){
        view.translationY = view.height.toFloat()
        view.alpha = 0f

        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(350)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun setMiniPlayerVisibility(show: Boolean, miniPlayer: View, context: Context) {
        if (show && miniPlayer.visibility != View.VISIBLE) {
            animateFloatingBubblesRelayout(miniPlayer)
            miniPlayer.startAnimation(AnimationUtils.loadAnimation(context, R.anim.fade_in_mini_player))
            miniPlayer.visibility = View.VISIBLE
        } else if (!show && miniPlayer.visibility == View.VISIBLE) {
            val anim = AnimationUtils.loadAnimation(context, R.anim.fade_out_mini_player)
            anim.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation) {}

                override fun onAnimationEnd(animation: Animation) {
                    // GONE (no INVISIBLE): las burbujas flotantes están ancladas encima
                    // del mini player; con INVISIBLE seguía ocupando su hueco y ellas se
                    // quedaban arriba aunque no hubiera nada debajo.
                    animateFloatingBubblesRelayout(miniPlayer)
                    miniPlayer.visibility = View.GONE
                }

                override fun onAnimationRepeat(animation: Animation) {}
            })
            miniPlayer.startAnimation(anim)
        }
    }

    /**
     * Desliza a su nueva posición las vistas flotantes de activity_main que cuelgan
     * del mini player (burbujas de dispositivos/Aria y tarjeta de descarga) en vez
     * de que salten de golpe cuando él aparece o desaparece.
     */
    private fun animateFloatingBubblesRelayout(miniPlayer: View) {
        val parent = miniPlayer.parent as? ViewGroup ?: return
        TransitionManager.beginDelayedTransition(parent, ChangeBounds().apply {
            duration = 280
            interpolator = FastOutSlowInInterpolator()
            addTarget(R.id.playbackDeviceBar)
            addTarget(R.id.ariaFloatingLauncher)
            addTarget(R.id.downloadProgressCard)
        })
    }




}