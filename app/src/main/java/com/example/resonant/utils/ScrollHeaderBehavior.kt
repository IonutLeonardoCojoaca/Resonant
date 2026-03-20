package com.example.resonant.utils

import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.google.android.material.imageview.ShapeableImageView

class ScrollHeaderBehavior(
    private val normalHeader: View,
    private val searchHeader: View,
    private val onSearchClick: () -> Unit
) {
    private val THRESHOLD_DP = 60f
    private val ANIMATION_DURATION = 250L

    private var isSearchHeaderVisible = false
    private var totalScrolled = 0f
    private val thresholdPx = THRESHOLD_DP * normalHeader.context.resources.displayMetrics.density

    init {
        // translationY is pre-set to -56dp via XML; only enforce proper hidden state
        searchHeader.visibility = View.INVISIBLE
        searchHeader.alpha = 0f

        searchHeader.findViewById<View>(R.id.searchInputClickable)
            ?.setOnClickListener { onSearchClick() }

        loadAvatarInSearchHeader()
    }

    private fun searchHeaderTranslation(): Float {
        val height = searchHeader.height
        return if (height > 0) -height.toFloat()
        else -(56f * searchHeader.context.resources.displayMetrics.density)
    }

    private fun loadAvatarInSearchHeader() {
        val avatar = searchHeader.findViewById<ShapeableImageView>(R.id.searchHeaderAvatar) ?: return
        Utils.loadUserProfile(searchHeader.context, avatar)
    }

    fun attachToRecyclerView(recyclerView: RecyclerView) {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                totalScrolled += dy
                onScrollChanged(dy)
            }
        })
    }

    fun attachToNestedScrollView(scrollView: NestedScrollView) {
        scrollView.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                val dy = scrollY - oldScrollY
                totalScrolled = scrollY.toFloat()
                onScrollChanged(dy)
            }
        )
    }

    private fun onScrollChanged(dy: Int) {
        if (dy > 0 && totalScrolled > thresholdPx && !isSearchHeaderVisible) {
            showSearchHeader()
        } else if (dy < 0 && isSearchHeaderVisible) {
            hideSearchHeader()
        }
    }

    private fun showSearchHeader() {
        if (isSearchHeaderVisible) return
        isSearchHeaderVisible = true

        normalHeader.animate()
            .alpha(0f)
            .translationY(-normalHeader.height.toFloat())
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        searchHeader.visibility = View.VISIBLE
        searchHeader.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun hideSearchHeader() {
        if (!isSearchHeaderVisible) return
        isSearchHeaderVisible = false

        normalHeader.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        searchHeader.animate()
            .alpha(0f)
            .translationY(searchHeaderTranslation())
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { searchHeader.visibility = View.INVISIBLE }
            .start()
    }

    fun reset() {
        normalHeader.clearAnimation()
        searchHeader.clearAnimation()
        normalHeader.alpha = 1f
        normalHeader.translationY = 0f
        searchHeader.alpha = 0f
        searchHeader.translationY = searchHeaderTranslation()
        searchHeader.visibility = View.INVISIBLE
        isSearchHeaderVisible = false
        totalScrolled = 0f
    }
}
