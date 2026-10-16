package com.example.resonant.recap

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import androidx.core.content.FileProvider
import androidx.core.view.drawToBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Partes puras de [RecapShare]: nombre del PNG y limpieza de los antiguos. */
object RecapShareFiles {
    const val DIRECTORY = "recaps"
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** `recap_2026-09_2_top_songs.png`: solo periodo, posición y tipo, sin datos de la cuenta. */
    fun fileName(periodKey: String, cardIndex: Int, cardType: String): String {
        val safeType = cardType.filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "card" }
        val safePeriod = periodKey.filter { it.isDigit() || it == '-' }
        return "recap_${safePeriod}_${cardIndex + 1}_$safeType.png"
    }

    /** Nombres de los PNG de más de [MAX_AGE_MS] (a partir de su última modificación). */
    fun expired(files: Map<String, Long>, nowMs: Long): List<String> =
        files.filter { (name, modifiedMs) -> name.endsWith(".png") && nowMs - modifiedMs > MAX_AGE_MS }
            .keys.sorted()
}

/**
 * Comparte una tarjeta del resumen como imagen: dibuja solo el contenedor de la tarjeta (sin barras de
 * progreso ni botones), lo guarda como PNG en `cacheDir/recaps/` y abre la hoja de compartir del sistema
 * con el `FileProvider` existente.
 */
object RecapShare {

    suspend fun share(context: Context, cardView: View, periodKey: String, cardIndex: Int, cardType: String): Boolean {
        val bitmap = runCatching { cardView.drawToBitmap(Bitmap.Config.ARGB_8888) }.getOrNull() ?: return false
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, RecapShareFiles.DIRECTORY).apply { mkdirs() }
                cleanUp(dir)
                File(dir, RecapShareFiles.fileName(periodKey, cardIndex, cardType)).also { target ->
                    target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }.getOrNull().also { bitmap.recycle() }
        } ?: return false

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }

    private fun cleanUp(dir: File) {
        val files = dir.listFiles().orEmpty().associate { it.name to it.lastModified() }
        RecapShareFiles.expired(files, System.currentTimeMillis()).forEach { File(dir, it).delete() }
    }
}
