package com.example.resonant.utils

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.example.resonant.data.network.ApiClient
import java.io.InputStream

/**
 * Configuración global de Glide:
 * - descarga con el OkHttpClient de medios de la app (mismo pool de conexiones y HTTP/2 que el audio y la
 *   API; sin cabecera de autorización, que las URLs prefirmadas no deben recibir) en vez de HttpURLConnection;
 * - las URLs prefirmadas usan una clave de caché sin firma ([PresignedStringLoader]);
 * - caché de disco de 250 MB fijada explícitamente para que no dependa de la versión de Glide.
 */
@GlideModule
class ResonantGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, DISK_CACHE_BYTES))
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.replace(GlideUrl::class.java, InputStream::class.java, OkHttpUrlLoader.Factory(ApiClient.getMediaHttpClient()))
        registry.prepend(String::class.java, InputStream::class.java, PresignedStringLoader.Factory())
    }

    /** No hay módulos declarados en el manifest: ahorra el parseo en el arranque de Glide. */
    override fun isManifestParsingEnabled(): Boolean = false

    private companion object {
        const val DISK_CACHE_BYTES = 250L * 1024 * 1024
    }
}
