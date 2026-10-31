package com.example.resonant.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.resonant.R
import com.example.resonant.ui.explore.ExploreQuickCard
import com.example.resonant.utils.GlideDebug.logSource
import com.example.resonant.utils.ImageRequestHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Carátula por defecto de playlists y PlayMix sin canciones (sin portada): la misma tarjeta
 * de "Nueva playlist" / "Nueva PlayMix" (acento, logo, eyebrow y nombre), pintada en un bitmap
 * para poder usarla en cualquier ImageView y como respaldo de Glide.
 *
 * La tarjeta solo se pinta cuando hace falta (sin URL o cuando la portada falla), al tamaño del destino y fuera
 * del hilo principal; antes se pintaba a ~1050 px en Main en cada bind, también con portada.
 */
object DefaultCover {

    enum class Kind(val accent: ResonantAccent, val eyebrow: String, val fallbackTitle: String) {
        PLAYLIST(ResonantAccent.PLAYLISTS, "Playlist", "Mi playlist"),
        PLAYMIX(ResonantAccent.PLAYMIX, "PlayMix", "Mi PlayMix")
    }

    /** Tamaño de diseño de la tarjeta (view_explore_quick_card); el bitmap se escala al tamaño del destino. */
    private const val CARD_DP = 140
    private const val MAX_RENDER_PX = 1024

    /** Bitmaps por tipo, nombre y tamaño, limitados en bytes (antes 24 entradas de ~4 MB cada una). */
    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Pinta las tarjetas fuera del hilo principal; el resultado se aplica solo si la vista sigue esperándolo. */
    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Tarjeta síncrona, en el hilo llamante: para quien necesita el Drawable al momento. */
    fun drawable(context: Context, kind: Kind, title: String?, sizePx: Int? = null): Drawable =
        BitmapDrawable(context.resources, bitmap(context, kind, title, renderSize(context, sizePx)))

    fun apply(imageView: ImageView, kind: Kind, title: String?, sizePx: Int? = null) {
        clearTint(imageView)
        showFallback(imageView, kind, title, sizePx)
    }

    /**
     * Portada real si hay [url]; si no (lista o PlayMix sin canciones) o falla, la tarjeta por
     * defecto. Quita el tinte que algunos ImageView traían para el icono antiguo.
     */
    fun load(imageView: ImageView, url: String?, kind: Kind, title: String?, overridePx: Int? = null) {
        clearTint(imageView)
        if (url.isNullOrBlank()) {
            Glide.with(imageView).clear(imageView)
            showFallback(imageView, kind, title, overridePx)
            return
        }
        imageView.setTag(R.id.default_cover_pending, null)
        var request = Glide.with(imageView)
            .load(ImageRequestHelper.buildGlideModel(imageView.context, url))
            .placeholder(ColorDrawable(placeholderColor(imageView.context)))
            .centerCrop()
            .logSource("cover")
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    showFallback(imageView, kind, title, overridePx)
                    return true
                }

                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean = false
            })
        if (overridePx != null) request = request.override(overridePx)
        request.into(imageView)
    }

    private fun showFallback(imageView: ImageView, kind: Kind, title: String?, sizePx: Int?) {
        val context = imageView.context
        val px = renderSize(context, sizePx ?: max(imageView.width, imageView.height))
        val key = cacheKey(kind, title, px)
        imageView.setTag(R.id.default_cover_pending, key)
        cache.get(key)?.let {
            imageView.setImageDrawable(BitmapDrawable(context.resources, it))
            return
        }
        imageView.setImageDrawable(ColorDrawable(placeholderColor(context)))
        renderScope.launch {
            // Se infla con el contexto de la pantalla (tema claro/oscuro) y un inflater propio, como hace
            // AsyncLayoutInflater; si algún componente exigiera el hilo principal, se repinta allí.
            val bitmap = runCatching { bitmap(context, kind, title, px) }
                .getOrElse { withContext(Dispatchers.Main) { bitmap(context, kind, title, px) } }
            withContext(Dispatchers.Main.immediate) {
                if (imageView.getTag(R.id.default_cover_pending) == key) {
                    imageView.setImageDrawable(BitmapDrawable(context.resources, bitmap))
                }
            }
        }
    }

    private fun clearTint(imageView: ImageView) {
        imageView.imageTintList = null
        imageView.clearColorFilter()
    }

    private fun placeholderColor(context: Context): Int = context.getColor(R.color.cardsTheme)

    private fun displayName(kind: Kind, title: String?): String =
        title?.trim()?.takeIf { it.isNotEmpty() } ?: kind.fallbackTitle

    private fun cacheKey(kind: Kind, title: String?, px: Int): String = "${kind.name}|${displayName(kind, title)}|$px"

    private fun renderSize(context: Context, sizePx: Int?): Int {
        val designPx = (CARD_DP * context.resources.displayMetrics.density).toInt()
        return (sizePx?.takeIf { it > 0 } ?: designPx).coerceIn(1, MAX_RENDER_PX)
    }

    private fun cacheBytes(): Int = min(8L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 16).toInt()

    private fun bitmap(context: Context, kind: Kind, title: String?, px: Int): Bitmap {
        val name = displayName(kind, title)
        val key = cacheKey(kind, name, px)
        cache.get(key)?.let { return it }

        val card = LayoutInflater.from(context).cloneInContext(context)
            .inflate(R.layout.view_explore_quick_card, null, false)
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

        val scale = px / size.toFloat()
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            scale(scale, scale)
            card.draw(this)
        }
        cache.put(key, bitmap)
        return bitmap
    }
}
