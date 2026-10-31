package com.example.resonant.managers

import android.content.Context
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.PlaymixCopyRequest
import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.data.network.PlaymixMergeRequest
import com.example.resonant.data.network.PlaymixPageDTO
import com.example.resonant.data.network.PlaymixSortRequest
import com.example.resonant.data.network.PlaymixSortResponseDTO
import com.example.resonant.data.network.PlaymixVisibilityRequest
import com.example.resonant.data.network.services.PlaymixService

/**
 * Compartir y descubrir Playmix (playmix-sharing-discovery): visibilidad, me gusta, listados, duplicar,
 * ordenar y fusionar. Todo depende de `features.playmixSharing` ([CapabilitiesManager.isPlaymixSharingEnabled]).
 * Las funciones son `open` para poder sustituirlas por fakes en los tests de ViewModel.
 */
open class PlaymixSharingManager(private val context: Context?) {

    private val service: PlaymixService by lazy { ApiClient.getPlaymixService(context!!) }
    private val playmixManager by lazy { PlaymixManager(context) }

    open suspend fun setVisibility(playmixId: String, visibility: String): PlaymixDetailDTO {
        val detail = service.setVisibility(playmixId, PlaymixVisibilityRequest(visibility))
        playmixManager.markPlaymixesChanged()
        PlaymixManager.invalidateDetail(playmixId)
        return detail
    }

    open suspend fun getPublicPlaymixes(sort: String, cursor: String?, limit: Int = PAGE_SIZE): PlaymixPageDTO =
        service.getPublicPlaymixes(sort, limit, cursor)

    /** La primera página se guarda 60 s: la biblioteca la pide en cada apertura y en cada cambio de pestaña. */
    open suspend fun getLikedPlaymixes(cursor: String?, limit: Int = PAGE_SIZE): PlaymixPageDTO {
        if (cursor == null) {
            likedFirstPage?.let { (page, fetchedAtMs, pageLimit) ->
                if (pageLimit == limit && System.currentTimeMillis() - fetchedAtMs < LIKED_TTL_MS) return page
            }
        }
        return service.getLikedPlaymixes(limit, cursor).also {
            if (cursor == null) likedFirstPage = Triple(it, System.currentTimeMillis(), limit)
        }
    }

    open suspend fun setLiked(playmixId: String, liked: Boolean) {
        val response = if (liked) service.likePlaymix(playmixId) else service.unlikePlaymix(playmixId)
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
        likedFirstPage = null
        PlaymixManager.invalidateDetail(playmixId)
    }

    open suspend fun copyPlaymix(playmixId: String, name: String?): PlaymixDetailDTO {
        val copy = service.copyPlaymix(playmixId, PlaymixCopyRequest(name?.trim()?.takeIf { it.isNotEmpty() }))
        playmixManager.markPlaymixesChanged()
        return copy
    }

    open suspend fun sortPlaymix(
        playmixId: String,
        criterion: String,
        direction: String,
        preview: Boolean,
        seed: Int? = null
    ): PlaymixSortResponseDTO {
        val result = service.sortPlaymix(playmixId, PlaymixSortRequest(criterion, direction, preview, seed))
        if (!preview) {
            playmixManager.markPlaymixesChanged()
            PlaymixManager.invalidateDetail(playmixId)
        }
        return result
    }

    open suspend fun mergePlaymixes(playmixIds: List<String>, name: String): PlaymixDetailDTO {
        val merged = service.mergePlaymixes(PlaymixMergeRequest(playmixIds, name.trim()))
        playmixManager.markPlaymixesChanged()
        (playmixIds + merged.id).forEach(PlaymixManager::invalidateDetail)
        return merged
    }

    companion object {
        const val PAGE_SIZE = 20
        const val SHARE_BASE_URL = "https://resonantapp.ddns.net"
        private const val LIKED_TTL_MS = 60 * 1000L

        @Volatile
        private var likedFirstPage: Triple<PlaymixPageDTO, Long, Int>? = null

        /** Vacía la caché estática (cierre de sesión, cambio de cuenta). */
        fun clear() {
            likedFirstPage = null
        }
    }
}
