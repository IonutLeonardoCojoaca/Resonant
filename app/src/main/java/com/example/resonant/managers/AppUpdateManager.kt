package com.example.resonant.managers

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.getSystemService
import com.example.resonant.services.ApiResonantService
import com.example.resonant.data.models.UpdateDecision
import com.example.resonant.utils.Utils
import com.example.resonant.utils.VersionProvider
import com.example.resonant.data.models.AppUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AppUpdateManager(
    private val context: Context,
    private val api: ApiResonantService,
    private val baseUrl: String // se inyecta desde ApiClient.baseUrl()
) {
    private val platform = "Android"

    suspend fun checkForUpdate(): UpdateDecision = withContext(Dispatchers.IO) {
        try {
            val latest = api.getLatestAppVersion(platform)
            val current = VersionProvider.appVersionName(context).trim()
            val latestV = latest.version.trim()
            val cmp = Utils.compareSemver(latestV, current)
            Log.d(
                "AppUpdate",
                "Current=$current, Latest=$latestV, cmp=$cmp, force=${latest.forceUpdate}"
            )

            if (cmp <= 0) {
                UpdateDecision.NoUpdate
            } else {
                val apiDownloadEndpoint = buildApiDownloadEndpoint(latestV)
                if (latest.forceUpdate) UpdateDecision.Forced(latest, apiDownloadEndpoint)
                else UpdateDecision.Optional(latest, apiDownloadEndpoint)
            }
        } catch (e: Exception) {
            Log.e("AppUpdate", "Failed to check update", e)
            UpdateDecision.NoUpdate
        }
    }

    fun enqueueDownload(latest: AppUpdate, presignedUrl: String): Long {
        val request = DownloadManager.Request(Uri.parse(presignedUrl))
            .setTitle(latest.title ?: "Actualización ${latest.version}")
            .setDescription(latest.description ?: "Descargando actualización")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                latest.fileName ?: "resonant-${latest.version}.apk"
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)


        val dm: DownloadManager? = context.getSystemService()
        return dm?.enqueue(request)
            ?: throw IllegalStateException("DownloadManager no disponible")
    }

    suspend fun getPresignedDownloadUrl(version: String): String = withContext(Dispatchers.IO) {
        val resp = api.getPresignedDownloadUrl(version = version, platform = platform)
        if (!resp.isSuccessful) {
            throw IllegalStateException("Download endpoint failed: HTTP ${resp.code()}")
        }
        val raw = resp.body()?.string()?.trim().orEmpty()
        if (raw.isBlank()) throw IllegalStateException("Empty response from download endpoint")

        when {
            raw.startsWith("http", ignoreCase = true) -> raw
            raw.startsWith("{") -> {
                runCatching {
                    val url = JSONObject(raw).optString("url").ifBlank {
                        JSONObject(raw).optString("Url")
                    }
                    if (url.isBlank()) throw IllegalStateException("No 'url' in JSON")
                    url
                }.getOrElse { throw IllegalStateException("Cannot parse presigned url JSON", it) }
            }

            else -> throw IllegalStateException("Unexpected download response: $raw")
        }
    }

    private fun buildApiDownloadEndpoint(version: String): String {
        val sep = if (baseUrl.endsWith("/")) "" else "/"
        return "${baseUrl}${sep}api/App/Download?version=$version&platform=$platform"
    }
}