package com.example.resonant.ui.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import com.example.resonant.services.MusicPlaybackService
import kotlin.math.abs

/**
 * Handles horizontal swipe gestures on the mini-player's song info area
 * (title + artist).  Swipe left = next track, swipe right = previous.
 *
 * The text follows the user's finger with a dampened translationX, and
 * on release either snaps back (insufficient swipe) or slides out and
 * a new track slides in from the opposite side.
 *
 * Also provides a one-time "hint" animation on first attachment so the
 * user discovers the gesture without documentation.
 */
class MiniPlayerSwipeHandler(
    private val context: Context,
    private val songDataContainer: View,
    private val onSkipNext: () -> Unit,
    private val onSkipPrevious: () -> Unit,
    private val onClick: () -> Unit
) : View.OnTouchListener {

    companion object {
        /** Minimum horizontal travel (dp) to trigger a skip. */
        private const val SWIPE_THRESHOLD_DP = 60f
        /** Dampening factor so the text doesn't fly away under the finger. */
        private const val DRAG_RESISTANCE = 0.45f
        /** Duration for slide-out + slide-in animation (ms). */
        private const val TRANSITION_DURATION_MS = 200L
        /** Duration for the snap-back when swipe didn't meet threshold. */
        private const val SNAP_BACK_DURATION_MS = 250L
        /** Duration for the hint wobble on first show. */
        private const val HINT_DURATION_MS = 600L
        /** Key for SharedPreferences to track whether hint was shown. */
        private const val PREF_KEY_HINT_SHOWN = "mini_player_swipe_hint_shown"
        private const val PREFS_NAME = "mini_player_prefs"
    }

    private val swipeThresholdPx =
        SWIPE_THRESHOLD_DP * context.resources.displayMetrics.density

    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private var isAnimating = false

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onClick()
                return true
            }
        }
    )

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (isAnimating) return true

        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                isDragging = false
                // Don't consume yet — let the gesture detector decide
                // if it's a tap.
                view.parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY

                if (!isDragging) {
                    // Only start dragging if horizontal movement dominates.
                    if (abs(dx) > abs(dy) * 1.5f && abs(dx) > 10f) {
                        isDragging = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (abs(dy) > abs(dx)) {
                        // Vertical scroll — bail out, let parent handle it.
                        return false
                    }
                }

                if (isDragging) {
                    // Apply dampened translation so the text follows the finger
                    // but with resistance, giving a satisfying elastic feel.
                    songDataContainer.translationX = dx * DRAG_RESISTANCE
                    // Slight alpha reduction at edges for depth cue.
                    val progress = (abs(dx) / swipeThresholdPx).coerceIn(0f, 1f)
                    songDataContainer.alpha = 1f - (progress * 0.15f)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return false

                val dx = event.rawX - downX
                view.parent?.requestDisallowInterceptTouchEvent(false)

                if (abs(dx) >= swipeThresholdPx / DRAG_RESISTANCE) {
                    // Threshold met — commit the skip.
                    val isNext = dx < 0
                    animateSkipTransition(isNext)
                    if (isNext) onSkipNext() else onSkipPrevious()
                } else {
                    // Below threshold — snap back.
                    animateSnapBack()
                }
                isDragging = false
                return true
            }
        }
        return false
    }

    /**
     * Slides the current text out and brings the new text in from the
     * opposite side (since the song change will update the TextViews).
     */
    private fun animateSkipTransition(isNext: Boolean) {
        isAnimating = true
        val slideOutTarget = if (isNext) {
            -songDataContainer.width.toFloat()
        } else {
            songDataContainer.width.toFloat()
        }

        // Phase 1: slide current text out.
        val slideOut = ObjectAnimator.ofFloat(
            songDataContainer, View.TRANSLATION_X,
            songDataContainer.translationX, slideOutTarget
        ).apply {
            duration = TRANSITION_DURATION_MS
            interpolator = DecelerateInterpolator()
        }
        val fadeOut = ObjectAnimator.ofFloat(
            songDataContainer, View.ALPHA,
            songDataContainer.alpha, 0f
        ).apply {
            duration = TRANSITION_DURATION_MS
        }

        val outSet = AnimatorSet().apply { playTogether(slideOut, fadeOut) }

        outSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Reposition off-screen on the opposite side, then slide in.
                val entryX = if (isNext) {
                    songDataContainer.width.toFloat() * 0.3f
                } else {
                    -songDataContainer.width.toFloat() * 0.3f
                }
                songDataContainer.translationX = entryX
                songDataContainer.alpha = 0f

                val slideIn = ObjectAnimator.ofFloat(
                    songDataContainer, View.TRANSLATION_X, entryX, 0f
                ).apply {
                    duration = TRANSITION_DURATION_MS
                    interpolator = OvershootInterpolator(1.2f)
                }
                val fadeIn = ObjectAnimator.ofFloat(
                    songDataContainer, View.ALPHA, 0f, 1f
                ).apply {
                    duration = TRANSITION_DURATION_MS
                }

                AnimatorSet().apply {
                    playTogether(slideIn, fadeIn)
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            isAnimating = false
                        }
                    })
                    start()
                }
            }
        })
        outSet.start()
    }

    /** Bounce the text back to its original position with overshoot. */
    private fun animateSnapBack() {
        isAnimating = true
        val snapX = ObjectAnimator.ofFloat(
            songDataContainer, View.TRANSLATION_X,
            songDataContainer.translationX, 0f
        ).apply {
            duration = SNAP_BACK_DURATION_MS
            interpolator = OvershootInterpolator(2f)
        }
        val snapAlpha = ObjectAnimator.ofFloat(
            songDataContainer, View.ALPHA,
            songDataContainer.alpha, 1f
        ).apply {
            duration = SNAP_BACK_DURATION_MS
        }
        AnimatorSet().apply {
            playTogether(snapX, snapAlpha)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isAnimating = false
                }
            })
            start()
        }
    }

    /**
     * Shows a subtle wobble hint the first time the mini-player appears,
     * so the user discovers the swipe gesture organically.  Call this from
     * the Activity when the mini-player becomes visible.
     */
    fun maybeShowHint() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_KEY_HINT_SHOWN, false)) return

        // Small delay so it doesn't fight with layout animations.
        songDataContainer.postDelayed({
            if (isAnimating) return@postDelayed
            isAnimating = true

            val wobble = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = HINT_DURATION_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    // Dampened sine wave: moves left a bit, right a bit, settles.
                    val offset = (Math.sin(t * Math.PI * 3.0) * (1f - t) * 12f).toFloat()
                    songDataContainer.translationX = offset
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        songDataContainer.translationX = 0f
                        isAnimating = false
                    }
                })
            }
            wobble.start()

            prefs.edit().putBoolean(PREF_KEY_HINT_SHOWN, true).apply()
        }, 800L)
    }
}
