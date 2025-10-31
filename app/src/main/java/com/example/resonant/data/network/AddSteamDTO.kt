package com.example.resonant.data.network

// En algún lugar de tu app (ej: data/network/dto/AddStreamDTO.kt)
data class AddStreamDTO(
    val songId: String,
    val userId: String,
    val listenDurationInSeconds: Int,
    val wasSkipped: Boolean,
    val playSource: String
)
