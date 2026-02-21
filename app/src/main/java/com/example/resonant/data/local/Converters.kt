package com.example.resonant.data.local

import androidx.room.TypeConverter
import com.example.resonant.data.models.AlbumSimpleDTO
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromAlbumSimpleDTO(album: AlbumSimpleDTO?): String? {
        if (album == null) return null
        return gson.toJson(album)
    }

    @TypeConverter
    fun toAlbumSimpleDTO(json: String?): AlbumSimpleDTO? {
        if (json.isNullOrEmpty()) return null
        val type = object : TypeToken<AlbumSimpleDTO>() {}.type
        return gson.fromJson(json, type)
    }
}
