package com.example.resonant.data.models

data class User(
    val id: String,
    val name: String?,
    val email: String,
    val isBanned: Boolean
)