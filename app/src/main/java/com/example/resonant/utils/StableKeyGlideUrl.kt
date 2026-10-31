package com.example.resonant.utils

import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.example.resonant.playback.PlaybackUrlResolver
import java.io.InputStream

/**
 * URL prefirmada (S3/MinIO) cuya clave de caché ignora la firma (X-Amz-*): el servidor firma de nuevo cada
 * portada en cada respuesta, y con la URL completa como clave Glide no encontraba nunca el fichero en disco.
 * Se descarga con la URL íntegra (la firma hace falta para el GET); solo cambia la identidad en caché.
 */
class StableKeyGlideUrl(private val url: String) : GlideUrl(url) {

    override fun getCacheKey(): String = PlaybackUrlResolver.normalizeUrl(url) ?: super.getCacheKey()
}

/**
 * Convierte las cadenas prefirmadas en [StableKeyGlideUrl] antes de que Glide las trate como GlideUrl
 * normales, de modo que todos los Glide.with(...).load(url) de la app comparten la clave estable sin
 * tocarlos uno a uno. Se registra en [ResonantGlideModule].
 */
class PresignedStringLoader(
    private val urlLoader: ModelLoader<GlideUrl, InputStream>
) : ModelLoader<String, InputStream> {

    override fun buildLoadData(model: String, width: Int, height: Int, options: Options): ModelLoader.LoadData<InputStream>? =
        urlLoader.buildLoadData(StableKeyGlideUrl(model), width, height, options)

    override fun handles(model: String): Boolean = ImageRequestHelper.isPresignedUrl(model)

    class Factory : ModelLoaderFactory<String, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<String, InputStream> =
            PresignedStringLoader(multiFactory.build(GlideUrl::class.java, InputStream::class.java))

        override fun teardown() = Unit
    }
}
