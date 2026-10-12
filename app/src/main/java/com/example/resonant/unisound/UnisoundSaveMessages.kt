package com.example.resonant.unisound

/** Text shown after "Guardar como playlist". */
object UnisoundSaveMessages {
    fun forResult(result: UnisoundResult<UnisoundSavedPlaylist>): String = when (result) {
        is UnisoundResult.Success -> if (result.value.created) {
            "Playlist creada"
        } else {
            "Playlist actualizada · ${result.value.playlist.addedCount} nuevas"
        }
        is UnisoundResult.Failure -> result.message
    }
}
