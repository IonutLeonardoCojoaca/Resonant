package com.example.resonant.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import com.example.resonant.managers.SessionManager

/**
 * Home screen widget (design D6). Paints from [WidgetStateStore] with
 * `serviceActive = WidgetUpdateCoordinator.isAttached`, which is false in a fresh process: after a
 * reboot or a killed process the widget shows the last song paused without opening the app.
 * It never touches `playback/`: live updates come from [WidgetUpdateCoordinator].
 */
class ResonantWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        paintFromStore(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        paintFromStore(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = WidgetStateStore.get(context)
        appWidgetIds.forEach(store::removeWidget)
        WidgetUpdateCoordinator.onWidgetsChanged(context)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdateCoordinator.onWidgetsChanged(context)
    }

    override fun onDisabled(context: Context) {
        WidgetStateStore.get(context).clearAllWidgets()
        WidgetUpdateCoordinator.onWidgetsChanged(context)
    }

    companion object {

        /** Repaints every widget from the store (logout, service gone). Safe from any thread. */
        fun refreshAll(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, ResonantWidgetProvider::class.java))
            if (ids.isNotEmpty()) paintFromStore(appContext, manager, ids)
        }

        /** Repaints one widget (after the configuration screen). */
        fun refresh(context: Context, appWidgetId: Int) {
            val appContext = context.applicationContext
            paintFromStore(appContext, AppWidgetManager.getInstance(appContext), intArrayOf(appWidgetId))
        }

        /** Coordinator path: the snapshot it has just persisted, with the service alive. */
        fun paintAll(context: Context, snapshot: WidgetSnapshot, cover: Bitmap?, serviceActive: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ResonantWidgetProvider::class.java))
            val loggedIn = SessionManager.getInstance(context).hasLocalCredentials()
            val store = WidgetStateStore.get(context)
            ids.forEach { id -> paint(context, manager, id, snapshot, cover, serviceActive, loggedIn, store) }
        }

        private fun paintFromStore(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val store = WidgetStateStore.get(context)
            val snapshot = store.load()
            val cover = if (snapshot.state.hasSong) store.loadCover() else null
            val loggedIn = SessionManager.getInstance(context).hasLocalCredentials()
            val serviceActive = WidgetUpdateCoordinator.isAttached
            ids.forEach { id -> paint(context, manager, id, snapshot, cover, serviceActive, loggedIn, store) }
        }

        private fun paint(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            snapshot: WidgetSnapshot,
            cover: Bitmap?,
            serviceActive: Boolean,
            loggedIn: Boolean,
            store: WidgetStateStore
        ) {
            try {
                val options = manager.getAppWidgetOptions(appWidgetId)
                val size = WidgetSizeClassifier.classify(
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                )
                val quickAccess = WidgetQuickAccessResolver.resolve(
                    config = store.quickAccess(appWidgetId),
                    inRoom = snapshot.inRoom,
                    lastLive = snapshot.lastLive,
                    liveEnabled = snapshot.liveEnabled,
                    unisoundEnabled = snapshot.unisoundEnabled
                )
                val spec = ResonantWidgetRenderer.render(snapshot.state, size, quickAccess, serviceActive, loggedIn)
                manager.updateAppWidget(appWidgetId, ResonantWidgetViews.build(context, appWidgetId, spec, cover))
            } catch (e: Exception) {
                Log.w(WidgetUpdateCoordinator.TAG, "paint failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
