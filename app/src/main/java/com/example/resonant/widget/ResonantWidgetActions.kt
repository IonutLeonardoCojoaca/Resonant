package com.example.resonant.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.activities.MainActivity
import com.example.resonant.ui.activities.SplashActivity

/**
 * PendingIntents of the widget buttons (design D3).
 *
 * Media keys go the same way as the media notification and the headphones: an explicit
 * `ACTION_MEDIA_BUTTON` + `EXTRA_KEY_EVENT` to [MusicPlaybackService]. Its `onStartCommand` hands
 * them to `super`, Media3 delivers them to the session, and the session player
 * (`UnisoundAwarePlayer`) applies the Live and Unisound policies. The widget never creates a
 * player, a queue or a `MediaController`.
 *
 * Media3 check (task 3.1): Media3 1.10.1 sources are not available on the machine where this was
 * written, so this relies on the known behaviour of `MediaSessionService.onStartCommand`: it
 * resolves the session from the intent `data` and, when there is none and the intent is a media
 * action (`ACTION_MEDIA_BUTTON` with a key event), it asks `onGetSession(legacy controller)` and
 * dispatches the key to that session. `MusicPlaybackService.onGetSession` always returns its
 * single `mediaLibrarySession`, and `androidx.media3.session.MediaButtonReceiver` (declared in the
 * manifest) starts the service the same way, without `data`. The session `Uri` that
 * `DefaultActionFactory` puts in `data` cannot be rebuilt here anyway (it embeds an id and a
 * timestamp of the live session object and does not exist in a cold process), so no `Uri` is set.
 * To verify on device: pause/next from the widget with the app open and closed (Workflow follow-up).
 *
 * Kinds: `getForegroundService` for PLAY (may start the service cold: a widget tap is one of the
 * Android 12+ exemptions to start a foreground service) and `getService` for the other keys, as
 * Media3's `DefaultActionFactory` does. All are `FLAG_IMMUTABLE`.
 *
 * Openers: "Abrir Resonant" goes through `SplashActivity` (deviation from the design, which says
 * `MainActivity`): it is the launcher entry and sends logged-out users to the login screen, which
 * `MainActivity` alone would not. Room and Live accesses open `MainActivity` with the widget actions.
 */
object ResonantWidgetActions {
    const val ACTION_OPEN_APP = "com.example.resonant.widget.OPEN_APP"
    const val ACTION_OPEN_ROOM = "com.example.resonant.widget.OPEN_ROOM"
    const val ACTION_OPEN_LIVE = "com.example.resonant.widget.OPEN_LIVE"
    const val EXTRA_LIVE_SLUG = "com.example.resonant.widget.EXTRA_LIVE_SLUG"

    /** Number of distinct actions per widget, used to space the opener request codes. */
    private const val ACTIONS_PER_WIDGET = 8

    fun keyCodeFor(action: WidgetButtonAction): Int? = when (action) {
        WidgetButtonAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
        WidgetButtonAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
        WidgetButtonAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        WidgetButtonAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        else -> null
    }

    fun isMediaKey(action: WidgetButtonAction): Boolean = when (action) {
        WidgetButtonAction.PLAY,
        WidgetButtonAction.PAUSE,
        WidgetButtonAction.NEXT,
        WidgetButtonAction.PREVIOUS -> true
        else -> false
    }

    /**
     * Media keys share one code per key (negative, so they never meet an opener); openers get a
     * code per widget so their extras (the Live slug) never overwrite another widget's.
     */
    fun requestCodeFor(action: WidgetButtonAction, appWidgetId: Int): Int =
        if (isMediaKey(action)) {
            -(action.ordinal + 1)
        } else {
            appWidgetId.coerceAtLeast(0) * ACTIONS_PER_WIDGET + action.ordinal
        }

    fun pendingIntent(
        context: Context,
        action: WidgetButtonAction,
        appWidgetId: Int,
        liveSlug: String? = null
    ): PendingIntent {
        val requestCode = requestCodeFor(action, appWidgetId)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val keyCode = keyCodeFor(action)
        if (keyCode != null) {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                component = ComponentName(context, MusicPlaybackService::class.java)
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            }
            return if (action == WidgetButtonAction.PLAY) {
                PendingIntent.getForegroundService(context, requestCode, intent, flags)
            } else {
                PendingIntent.getService(context, requestCode, intent, flags)
            }
        }
        val intent = when (action) {
            WidgetButtonAction.OPEN_ROOM -> Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_ROOM)
            WidgetButtonAction.OPEN_LIVE -> Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_LIVE)
                .putExtra(EXTRA_LIVE_SLUG, liveSlug)
            else -> Intent(context, SplashActivity::class.java).setAction(ACTION_OPEN_APP)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }
}
