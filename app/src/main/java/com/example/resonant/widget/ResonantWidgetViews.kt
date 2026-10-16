package com.example.resonant.widget

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import com.example.resonant.R

/**
 * Copies a [WidgetRenderSpec] to `RemoteViews`. No decisions here: visibility, texts, image and
 * click intents only. Ids per layout:
 * - every layout: `widgetRoot`, `widgetOpenButton`;
 * - compact, horizontal, large: `widgetCover`, `widgetPlayPause`;
 * - horizontal, large: `widgetModeLabel`, `widgetTitle`, `widgetArtist`, `widgetPrevious`, `widgetNext`;
 * - large, empty: `widgetQuickAccess`.
 */
object ResonantWidgetViews {

    fun build(context: Context, appWidgetId: Int, spec: WidgetRenderSpec, cover: Bitmap?): RemoteViews {
        val layout = spec.layout
        val views = RemoteViews(context.packageName, layoutRes(layout))

        views.setOnClickPendingIntent(
            R.id.widgetRoot,
            ResonantWidgetActions.pendingIntent(context, WidgetButtonAction.OPEN_APP, appWidgetId)
        )

        val hasPlayer = layout != WidgetLayout.EMPTY
        val hasText = layout == WidgetLayout.HORIZONTAL || layout == WidgetLayout.LARGE
        val hasQuickAccess = layout == WidgetLayout.LARGE || layout == WidgetLayout.EMPTY

        if (hasPlayer) {
            if (spec.showCover && cover != null) {
                views.setImageViewBitmap(R.id.widgetCover, cover)
            } else {
                views.setImageViewResource(R.id.widgetCover, R.drawable.ic_disc)
            }
        }

        // Center button: an icon for the media keys, a text button for the "open" actions.
        val mediaKey = ResonantWidgetActions.isMediaKey(spec.centerAction)
        if (hasPlayer) {
            views.setViewVisibility(R.id.widgetPlayPause, if (mediaKey) View.VISIBLE else View.GONE)
            if (mediaKey) {
                val pause = spec.centerAction == WidgetButtonAction.PAUSE
                views.setImageViewResource(R.id.widgetPlayPause, if (pause) R.drawable.ic_pause else R.drawable.ic_play)
                views.setContentDescription(
                    R.id.widgetPlayPause,
                    context.getString(if (pause) R.string.widget_pause else R.string.widget_play)
                )
                views.setOnClickPendingIntent(
                    R.id.widgetPlayPause,
                    ResonantWidgetActions.pendingIntent(context, spec.centerAction, appWidgetId)
                )
            }
        }
        views.setViewVisibility(R.id.widgetOpenButton, if (mediaKey) View.GONE else View.VISIBLE)
        if (!mediaKey) {
            views.setTextViewText(
                R.id.widgetOpenButton,
                context.getString(
                    if (spec.centerAction == WidgetButtonAction.OPEN_ROOM) R.string.widget_open_room else R.string.widget_open_app
                )
            )
            views.setOnClickPendingIntent(
                R.id.widgetOpenButton,
                ResonantWidgetActions.pendingIntent(context, spec.centerAction, appWidgetId)
            )
        }

        if (hasText) {
            views.setTextViewText(R.id.widgetTitle, spec.title.orEmpty())
            views.setTextViewText(R.id.widgetArtist, spec.artist.orEmpty())
            views.setViewVisibility(R.id.widgetArtist, if (spec.artist.isNullOrBlank()) View.GONE else View.VISIBLE)
            val label = when (val modeLabel = spec.modeLabel) {
                null -> null
                WidgetModeLabel.Unisound -> context.getString(R.string.widget_label_unisound)
                is WidgetModeLabel.Live -> context.getString(R.string.widget_label_live, modeLabel.channelName)
            }
            views.setTextViewText(R.id.widgetModeLabel, label.orEmpty())
            views.setViewVisibility(R.id.widgetModeLabel, if (label != null) View.VISIBLE else View.GONE)

            val skipVisibility = if (spec.showSkipButtons) View.VISIBLE else View.GONE
            views.setViewVisibility(R.id.widgetPrevious, skipVisibility)
            views.setViewVisibility(R.id.widgetNext, skipVisibility)
            if (spec.showSkipButtons) {
                views.setOnClickPendingIntent(
                    R.id.widgetPrevious,
                    ResonantWidgetActions.pendingIntent(context, WidgetButtonAction.PREVIOUS, appWidgetId)
                )
                views.setOnClickPendingIntent(
                    R.id.widgetNext,
                    ResonantWidgetActions.pendingIntent(context, WidgetButtonAction.NEXT, appWidgetId)
                )
            }
        }

        if (hasQuickAccess) {
            val target = spec.quickAccess
            views.setViewVisibility(R.id.widgetQuickAccess, if (target != null) View.VISIBLE else View.GONE)
            when (target) {
                null -> Unit
                QuickAccessTarget.Room -> {
                    views.setTextViewText(R.id.widgetQuickAccess, context.getString(R.string.widget_quick_room))
                    views.setOnClickPendingIntent(
                        R.id.widgetQuickAccess,
                        ResonantWidgetActions.pendingIntent(context, WidgetButtonAction.OPEN_ROOM, appWidgetId)
                    )
                }
                is QuickAccessTarget.Live -> {
                    views.setTextViewText(R.id.widgetQuickAccess, context.getString(R.string.widget_quick_live, target.name))
                    views.setOnClickPendingIntent(
                        R.id.widgetQuickAccess,
                        ResonantWidgetActions.pendingIntent(context, WidgetButtonAction.OPEN_LIVE, appWidgetId, target.slug)
                    )
                }
            }
        }
        return views
    }

    private fun layoutRes(layout: WidgetLayout): Int = when (layout) {
        WidgetLayout.COMPACT -> R.layout.widget_resonant_compact
        WidgetLayout.HORIZONTAL -> R.layout.widget_resonant_horizontal
        WidgetLayout.LARGE -> R.layout.widget_resonant_large
        WidgetLayout.EMPTY -> R.layout.widget_resonant_empty
    }
}
