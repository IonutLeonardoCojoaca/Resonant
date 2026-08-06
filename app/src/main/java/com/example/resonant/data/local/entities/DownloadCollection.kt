package com.example.resonant.data.local.entities

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "download_collections",
    primaryKeys = ["userId", "collectionType", "collectionId"]
)
data class DownloadCollection(
    val userId: String,
    val collectionType: String,
    val collectionId: String,
    val title: String,
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "download_collection_songs",
    primaryKeys = ["userId", "collectionType", "collectionId", "songId"],
    indices = [
        Index(value = ["userId", "songId"]),
        Index(value = ["userId", "collectionType", "collectionId"])
    ]
)
data class DownloadCollectionSong(
    val userId: String,
    val collectionType: String,
    val collectionId: String,
    val songId: String
)
