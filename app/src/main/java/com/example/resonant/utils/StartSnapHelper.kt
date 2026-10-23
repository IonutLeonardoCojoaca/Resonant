package com.example.resonant.utils

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.OrientationHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Imantado horizontal alineado al inicio: al soltar el scroll, la columna más cercana queda pegada
 * al borde izquierdo (respetando el padding) y la siguiente sigue asomando. [LinearSnapHelper] a secas
 * centra, lo que dejaría la primera página cortada. Al llegar al final no fuerza nada para no rebotar.
 */
class StartSnapHelper : LinearSnapHelper() {

    private var horizontalHelper: OrientationHelper? = null

    override fun calculateDistanceToFinalSnap(
        layoutManager: RecyclerView.LayoutManager,
        targetView: View
    ): IntArray {
        if (!layoutManager.canScrollHorizontally()) {
            return super.calculateDistanceToFinalSnap(layoutManager, targetView) ?: IntArray(2)
        }
        val helper = horizontalHelper(layoutManager)
        return intArrayOf(helper.getDecoratedStart(targetView) - helper.startAfterPadding, 0)
    }

    override fun findSnapView(layoutManager: RecyclerView.LayoutManager): View? {
        if (!layoutManager.canScrollHorizontally()) return super.findSnapView(layoutManager)
        if (layoutManager is LinearLayoutManager &&
            layoutManager.findLastCompletelyVisibleItemPosition() == layoutManager.itemCount - 1
        ) return null

        val helper = horizontalHelper(layoutManager)
        var closest: View? = null
        var closestDistance = Int.MAX_VALUE
        for (i in 0 until layoutManager.childCount) {
            val child = layoutManager.getChildAt(i) ?: continue
            val distance = abs(helper.getDecoratedStart(child) - helper.startAfterPadding)
            if (distance < closestDistance) {
                closestDistance = distance
                closest = child
            }
        }
        return closest
    }

    private fun horizontalHelper(layoutManager: RecyclerView.LayoutManager): OrientationHelper {
        val current = horizontalHelper
        if (current != null && current.layoutManager === layoutManager) return current
        return OrientationHelper.createHorizontalHelper(layoutManager).also { horizontalHelper = it }
    }
}
