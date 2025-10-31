package com.example.resonant.utils

import android.content.Context

object VersionProvider {
    fun appVersionName(context: Context): String {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val pInfo = pm.getPackageInfo(pkg, 0)
            pInfo.versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }
}