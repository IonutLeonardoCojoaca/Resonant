package com.example.resonant.live

import com.example.resonant.data.network.CapabilitiesDTO
import com.example.resonant.data.network.LiveChannelsResponseDTO
import com.example.resonant.data.network.LiveNowResponseDTO
import com.example.resonant.data.network.ServerTimeDTO
import com.example.resonant.data.network.services.LiveService
import com.example.resonant.data.network.services.PlatformService
import retrofit2.Response
import java.io.IOException

/** Hand-written REST fake: each call returns the configured response, or throws [IOException] when null. */
class FakeLiveService : LiveService {
    var channelsResponse: Response<LiveChannelsResponseDTO>? = null
    var nowResponse: Response<LiveNowResponseDTO>? = null

    var channelsCalls = 0
    var nowCalls = 0

    override suspend fun getChannels(): Response<LiveChannelsResponseDTO> {
        channelsCalls++
        return channelsResponse ?: throw IOException("offline")
    }

    override suspend fun getNow(slug: String): Response<LiveNowResponseDTO> {
        nowCalls++
        return nowResponse ?: throw IOException("offline")
    }
}

/** Server clock fake: answers [serverNowMs] or fails with [IOException]. */
class FakePlatformService(private val serverNowMs: () -> Long) : PlatformService {
    var fail = false
    var timeCalls = 0

    override suspend fun getServerTime(): ServerTimeDTO {
        timeCalls++
        if (fail) throw IOException("offline")
        return ServerTimeDTO(serverNowMs())
    }

    override suspend fun getCapabilities(): CapabilitiesDTO = throw IOException("not used")
}
