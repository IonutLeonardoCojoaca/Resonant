package com.example.resonant.services

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.workmanager.WorkManagerScheduler
import com.example.resonant.R
import com.example.resonant.playback.OfflineDownloadProvider

@OptIn(UnstableApi::class)
class ResonantDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DOWNLOAD_CHANNEL_ID,
    R.string.download_notification_channel_name,
    0
) {
    override fun getDownloadManager(): DownloadManager {
        return OfflineDownloadProvider.getDownloadManager(this)
    }

    override fun getScheduler(): Scheduler {
        return WorkManagerScheduler(this, DOWNLOAD_WORK_NAME)
    }

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        return DownloadNotificationHelper(this, DOWNLOAD_CHANNEL_ID)
            .buildProgressNotification(
                this,
                R.drawable.ic_download,
                null,
                null,
                downloads,
                notMetRequirements
            )
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 7301
        private const val DOWNLOAD_CHANNEL_ID = "resonant_downloads"
        private const val DOWNLOAD_WORK_NAME = "resonant_download_scheduler"
    }
}
