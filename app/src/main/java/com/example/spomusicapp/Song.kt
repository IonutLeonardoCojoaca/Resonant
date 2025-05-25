package com.example.spomusicapp

import com.google.firebase.firestore.DocumentId
import com.google.gson.annotations.SerializedName

data class Song(
    @SerializedName("name")
    val title: String = "",
    @DocumentId
    val id: String = "",
    val url: String = "",
    var artistName: String? = null,
    val albumName: String? = null,
    var duration: String? = null,
    val localCoverPath: String? = null,
    var streams: Int = 0// posición por defecto
){
    constructor() : this("", "", "")
}

