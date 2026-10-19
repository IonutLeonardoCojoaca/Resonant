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
        return detail
    }

    open suspend fun getPublicPlaymixes(sort: String, cursor: String?, limit: Int = PAGE_SIZE): PlaymixPageDTO =
        service.getPublicPlaymixes(sort, limit, cursor)

    open suspend fun getLikedPlaymixes(cursor: String?, limit: Int = PAGE_SIZE): PlaymixPageDTO =
        service.getLikedPlaymixes(limit, cursor)

    open suspend fun setLiked(playmixId: String, liked: Boolean) {
        val response = if (liked) service.likePlaymix(playmixId) else service.unlikePlaymix(playmixId)
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
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
        if (!preview) playmixManager.markPlaymixesChanged()
        return result
    }

    open suspend fun mergePlaymixes(playmixIds: List<String>, name: String): PlaymixDetailDTO {
        val merged = service.mergePlaymixes(PlaymixMergeRequest(playmixIds, name.trim()))
        playmixManager.markPlaymixesChanged()
        return merged
    }

    companion object {
        const val PAGE_SIZE = 20
        const val SHARE_BASE_URL = "https://resonantapp.ddns.net"
    }
}
