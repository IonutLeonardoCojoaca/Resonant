package com.example.resonant

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
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

    fun animateSongImage(imageView: ImageView, newBitmap: Bitmap, direction: Int) {
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



}