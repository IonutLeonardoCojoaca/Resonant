package com.example.resonant.notifications

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** Where the background sync keeps its [NotificationAlertMark]. */
interface NotificationAlertStore {
    suspend fun load(): NotificationAlertMark?
    suspend fun save(mark: NotificationAlertMark)
}

private val Context.notificationAlertDataStore: DataStore<Preferences> by preferencesDataStore(name = "notification_alerts")

/** Own DataStore file, separate from `settings`, so it never touches user preferences. */
class DataStoreNotificationAlertStore(private val context: Context) : NotificationAlertStore {

    override suspend fun load(): NotificationAlertMark? {
        val prefs = context.notificationAlertDataStore.data.first()
        val userId = prefs[USER_ID] ?: return null
        val createdAt = prefs[LAST_ALERTED_CREATED_AT] ?: return null
        return NotificationAlertMark(userId, createdAt, prefs[LAST_ALERTED_IDS].orEmpty())
    }

    override suspend fun save(mark: NotificationAlertMark) {
        context.notificationAlertDataStore.edit {
            it[USER_ID] = mark.userId
            it[LAST_ALERTED_CREATED_AT] = mark.createdAtUtcMs
            it[LAST_ALERTED_IDS] = mark.ids
        }
    }

    private companion object {
        val USER_ID = stringPreferencesKey("user_id")
        val LAST_ALERTED_CREATED_AT = longPreferencesKey("last_alerted_created_at_utc_ms")
        val LAST_ALERTED_IDS = stringSetPreferencesKey("last_alerted_ids")
    }
}
