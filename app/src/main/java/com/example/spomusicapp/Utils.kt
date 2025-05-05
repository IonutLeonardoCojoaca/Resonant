package com.example.spomusicapp

import android.annotation.SuppressLint

object Utils {

    @SuppressLint("DefaultLocale")
    fun formatTime(millis: Int): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / 1000) / 60
        return String.format("%02d:%02d", minutes, seconds)
    }

}