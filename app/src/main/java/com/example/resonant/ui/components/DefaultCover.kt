package com.example.resonant.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.example.resonant.R
import com.example.resonant.ui.explore.ExploreQuickCard

/**
 * Carátula por defecto de playlists y PlayMix sin canciones (sin portada): la misma tarjeta
 * de "Nueva playlist" / "Nueva PlayMix" (acento, logo, eyebrow y nombre), pintada en un bitmap
 * para poder usarla en cualquier ImageView y como placeholder/error de Glide.
 */
object DefaultCover {

    enum class Kind(val accent: ResonantAccent, val eyebrow: String, val fallbackTitle: String) {
        PLAYLIST(ResonantAccent.PLAYLISTS, "Playlist", "Mi playlist"),
        PLAYMIX(ResonantAccent.PLAYMIX, "PlayMix", "Mi PlayMix")
    }

    /** Tamaño de diseño de la tarjeta (view_explore_quick_card) y factor de nitidez del bitmap. */
    private const val CARD_DP = 140
    private const val RENDER_SCALE = 2.5f

    private val cache = LruCache<String, Bitmap>(24)

    fun drawable(context: Context, kind: Kind, title: String?): Drawable =
        BitmapDrawable(context.resources, bitmap(context, kind, title))

    fun apply(imageView: ImageView, kind: Kind, title: String?) {
        clearTint(imageView)
        imageView.setImageDrawable(drawable(imageView.context, kind, title))
    }

    /**
     * Portada real si hay [url]; si no (lista o PlayMix sin canciones) o falla, la tarjeta por
     * defecto. Quita el tinte que algunos ImageView traían para el icono antiguo.
     */
    fun load(imageView: ImageView, url: String?, kind: Kind, title: String?, overridePx: Int? = null) {
        clearTint(imageView)
        val fallback = drawable(imageView.context, kind, title)
        if (url.isNullOrBlank()) {
            com.bumptech.glide.Glide.with(imageView).clear(imageView)
            imageView.setImageDrawable(fallback)
            return
        }
        var request = com.bumptech.glide.Glide.with(imageView).load(url)
            .placeholder(fallback)
            .error(fallback)
            .centerCrop()
        if (overridePx != null) request = request.override(overridePx)
        request.into(imageView)
    }

    private fun clearTint(imageView: ImageView) {
        imageView.imageTintList = null
        imageView.clearColorFilter()
    }

    private fun bitmap(context: Context, kind: Kind, title: String?): Bitmap {
        val name = title?.trim()?.takeIf { it.isNotEmpty() } ?: kind.fallbackTitle
        val key = "${kind.name}|$name"
        cache.get(key)?.let { return it }

        val card = LayoutInflater.from(context).inflate(R.layout.view_explore_quick_card, null, false)
        ExploreQuickCard.bind(card, ExploreQuickCard.Spec(kind.eyebrow, name, kind.accent)) {}
        // Igual que la vista previa de las pantallas de creación: sin badge de "abrir".
        card.findViewById<View>(R.id.quickCardBadge).isVisible = false
        card.findViewById<TextView>(R.id.quickCardTitle).apply {
            maxLines = 2
            textSize = 16f
        }

        val size = (CARD_DP * context.resources.displayMetrics.density).toInt()
        val spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        card.measure(spec, spec)
        card.layout(0, 0, size, size)

        val px = (size * RENDER_SCALE).toInt()
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            scale(RENDER_SCALE, RENDER_SCALE)
            card.draw(this)
        }
        cache.put(key, bitmap)
        return bitmap
    }
}
