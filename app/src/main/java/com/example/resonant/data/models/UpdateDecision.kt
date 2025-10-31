package com.example.resonant.data.models

sealed class UpdateDecision {
    data object NoUpdate : UpdateDecision()
    data class Optional(val latest: AppUpdate, val downloadUrl: String) : UpdateDecision()
    data class Forced(val latest: AppUpdate, val downloadUrl: String) : UpdateDecision()
}