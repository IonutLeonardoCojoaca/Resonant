package com.example.resonant.utils

import android.graphics.drawable.Drawable
import android.util.Log
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.resonant.BuildConfig

/** Medición (solo debug): de dónde sale cada imagen (REMOTE, DATA_DISK_CACHE, MEMORY_CACHE...) con tag GlideSource. */
object GlideDebug {

    fun RequestBuilder<Drawable>.logSource(tag: String): RequestBuilder<Drawable> {
        if (!BuildConfig.DEBUG) return this
        return addListener(object : RequestListener<Drawable> {
            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                Log.d(TAG, "$tag FAILED ${e?.message}")
                return false
            }

            override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                Log.d(TAG, "$tag ${dataSource.name}")
                return false
            }
        })
    }

    private const val TAG = "GlideSource"
}
