package com.example.resonant.utils

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.example.resonant.R
import com.example.resonant.data.models.Song
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Tipo semántico del snackbar: define el color de acento, el icono y la
 * respuesta háptica. El resto del diseño (superficie, tipografía, timer)
 * es común a todos.
 */
enum class SnackType(@ColorRes val accentRes: Int, @DrawableRes val iconRes: Int) {
    SUCCESS(R.color.snackbar_success, R.drawable.ic_success),
    ERROR(R.color.snackbar_error, R.drawable.ic_error),
    WARNING(R.color.snackbar_warning, R.drawable.ic_warning),
    INFO(R.color.snackbar_info, R.drawable.ic_info),
    /** Me gusta / añadido a favoritos. */
    LIKE(R.color.snackbar_brand, R.drawable.ic_favorite),
    /** Quitado de favoritos: neutro, no es un error. */
    UNLIKE(R.color.snackbar_neutral, R.drawable.ic_favorite_border),
    DOWNLOAD(R.color.snackbar_info, R.drawable.ic_download),
    /** Operación en curso: spinner, sin timeout. Se resuelve con [ResonantSnackbarHandle.success]/[ResonantSnackbarHandle.error]. */
    LOADING(R.color.snackbar_brand, 0)
}

object SnackbarUtils {

    fun Activity.showResonantSnackbar(
        text: String,
        type: SnackType,
        title: String? = null,
        @DrawableRes iconRes: Int? = null,
        actionText: String? = null,
        onAction: (() -> Unit)? = null,
        durationMs: Int? = null
    ): ResonantSnackbarHandle? {
        if (isFinishing || isDestroyed) return null
        val handle = ResonantSnackbarHandle(this)
        handle.update(text, type, title, iconRes, actionText, onAction, durationMs)
        return handle
    }

    /**
     * No-op si el fragment ya no está adjunto (callbacks asíncronos tardíos).
     *
     * @param inDialog en un BottomSheet/Dialog que **sigue abierto**, muestra el snackbar dentro de su
     * ventana (si no quedaría detrás del diálogo). No usarlo si el diálogo se cierra justo después.
     */
    fun Fragment.showResonantSnackbar(
        text: String,
        type: SnackType,
        title: String? = null,
        @DrawableRes iconRes: Int? = null,
        actionText: String? = null,
        onAction: (() -> Unit)? = null,
        durationMs: Int? = null,
        inDialog: Boolean = false
    ): ResonantSnackbarHandle? {
        val host = activity ?: return null
        if (host.isFinishing || host.isDestroyed) return null
        val dialogRoot = if (inDialog) {
            (this as? DialogFragment)?.dialog?.takeIf { it.isShowing }
                ?.window?.decorView?.findViewById<View>(android.R.id.content)
        } else null
        val handle = ResonantSnackbarHandle(host, dialogRoot)
        handle.update(text, type, title, iconRes, actionText, onAction, durationMs)
        return handle
    }

    /**
     * Callback para `FavoritesViewModel.toggleFavoriteSong`: muestra el resultado real
     * (tras la respuesta del servidor), no el estado optimista.
     */
    fun Activity.favoriteSongFeedback(song: Song): (Boolean, Boolean) -> Unit {
        val ref = WeakReference(this)
        return { success, isNowFavorite ->
            val host = ref.get()
            when {
                host == null -> Unit
                !success -> host.showResonantSnackbar(
                    text = "Comprueba tu conexión e inténtalo de nuevo",
                    type = SnackType.ERROR,
                    title = "No se pudieron actualizar tus favoritos"
                )
                isNowFavorite -> host.showResonantSnackbar(song.title, SnackType.LIKE, title = "Añadida a tus favoritos")
                else -> host.showResonantSnackbar(song.title, SnackType.UNLIKE, title = "Eliminada de favoritos")
            }
        }
    }

    fun Fragment.favoriteSongFeedback(song: Song): (Boolean, Boolean) -> Unit =
        activity?.favoriteSongFeedback(song) ?: { _, _ -> }

    // region Firmas legacy (color + icono). Se traducen al tipo semántico para que
    // todas las llamadas existentes usen el diseño nuevo sin tocarlas.

    fun Activity.showResonantSnackbar(
        text: String,
        colorRes: Int,
        iconRes: Int,
        actionText: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        showResonantSnackbar(
            text = text,
            type = legacyType(colorRes, iconRes),
            iconRes = legacyIcon(iconRes),
            actionText = actionText,
            onAction = onAction
        )
    }

    fun Fragment.showResonantSnackbar(
        text: String,
        colorRes: Int,
        iconRes: Int,
        actionText: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        activity?.showResonantSnackbar(text, colorRes, iconRes, actionText, onAction)
    }

    private fun legacyType(colorRes: Int, iconRes: Int): SnackType = when {
        iconRes == R.drawable.ic_download -> SnackType.DOWNLOAD
        colorRes == R.color.successColor -> SnackType.SUCCESS
        colorRes == R.color.errorColor -> SnackType.ERROR
        colorRes == R.color.adviseColor || colorRes == R.color.secondaryColorTheme -> SnackType.WARNING
        else -> SnackType.INFO
    }

    /** Los iconos genéricos los decide el tipo; solo se respetan iconos específicos. */
    private fun legacyIcon(iconRes: Int): Int? = when (iconRes) {
        R.drawable.ic_success, R.drawable.ic_error, R.drawable.ic_warning, R.drawable.ic_download -> null
        else -> iconRes
    }

    // endregion
}

/**
 * Referencia a un snackbar mostrado. Permite transformar uno de carga en su
 * resultado final sin que el usuario vea dos snackbars distintos:
 *
 * ```
 * val snack = showResonantSnackbar("Guardando…", SnackType.LOADING)
 * ...
 * snack?.success("Transición guardada")
 * ```
 */
class ResonantSnackbarHandle internal constructor(activity: Activity, rootOverride: View? = null) {

    private val activityRef = WeakReference(activity)
    private val rootOverrideRef = rootOverride?.let { WeakReference(it) }
    private var snackbar: Snackbar? = null
    private var views: Views? = null
    private var content: Content? = null

    private var isOnScreen = false
    private var timerAnimator: ValueAnimator? = null
    private var timerTotalMs = 0L
    private var timerRemainingMs = 0L
    private var timerResumedAt = 0L

    val isShowing: Boolean get() = snackbar?.isShownOrQueued == true

    fun success(text: String, title: String? = null, actionText: String? = null, onAction: (() -> Unit)? = null) =
        update(text, SnackType.SUCCESS, title, actionText = actionText, onAction = onAction)

    fun error(text: String, title: String? = null, actionText: String? = null, onAction: (() -> Unit)? = null) =
        update(text, SnackType.ERROR, title, actionText = actionText, onAction = onAction)

    fun update(
        text: String,
        type: SnackType,
        title: String? = null,
        @DrawableRes iconRes: Int? = null,
        actionText: String? = null,
        onAction: (() -> Unit)? = null,
        durationMs: Int? = null
    ) {
        val next = Content(text, type, title, iconRes, actionText, onAction, durationMs)
        content = next
        val bar = snackbar
        if (bar == null || !bar.isShownOrQueued) {
            activityRef.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.let { create(it, next) }
            return
        }
        bind(next, animate = true)
        bar.duration = next.duration()
        // show() sobre el snackbar actual solo reprograma su timeout, no lo re-anima.
        bar.show()
        if (isOnScreen) startTimer(bar.duration)
    }

    fun dismiss() {
        snackbar?.dismiss()
    }

    private fun create(activity: Activity, c: Content) {
        val dialogRoot = rootOverrideRef?.get()?.takeIf { it.isAttachedToWindow }
        val root = dialogRoot
            ?: activity.findViewById<View>(R.id.main)
            ?: activity.findViewById(android.R.id.content)
            ?: return
        val density = activity.resources.displayMetrics.density
        val bar = Snackbar.make(root, "", c.duration())
        if (dialogRoot == null) {
            val anchor = activity.findViewById<View>(R.id.bottomOverlaySnackbarAnchor)
                ?: activity.findViewById<View>(R.id.mini_player)?.takeIf { it.visibility == View.VISIBLE }
                ?: activity.findViewById(R.id.bottom_navigation)
            anchor?.let { bar.setAnchorView(it) }
        }
        bar.animationMode = BaseTransientBottomBar.ANIMATION_MODE_FADE

        val layout = bar.view as ViewGroup
        (layout.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            val horizontal = (14 * density).toInt()
            params.setMargins(horizontal, params.topMargin, horizontal, (8 * density).toInt())
            layout.layoutParams = params
        }
        layout.setPadding(0, 0, 0, 0)
        layout.background = ContextCompat.getDrawable(activity, R.drawable.bg_resonant_snackbar)
        layout.clipToOutline = true
        layout.elevation = 10 * density
        // Se oculta el contenido estándar de Material y se usa el propio.
        for (i in 0 until layout.childCount) layout.getChildAt(i).visibility = View.GONE
        val contentView = LayoutInflater.from(activity).inflate(R.layout.view_resonant_snackbar, layout, false)
        layout.addView(contentView, 0)

        views = Views(contentView)
        snackbar = bar
        isOnScreen = false
        bind(c, animate = false)
        installGestures(layout)

        bar.addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
            override fun onShown(transientBottomBar: Snackbar) {
                if (snackbar !== transientBottomBar) return
                isOnScreen = true
                content?.let { playEntrance(it) }
                startTimer(transientBottomBar.duration)
            }

            override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                if (snackbar !== transientBottomBar) return
                isOnScreen = false
                timerAnimator?.cancel()
            }
        })
        bar.show()
    }

    private fun bind(c: Content, animate: Boolean) {
        val v = views ?: return
        val ctx = v.root.context
        val accent = ContextCompat.getColor(ctx, c.type.accentRes)

        v.iconContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(accent, 0x2E))
        }
        if (c.type == SnackType.LOADING) {
            v.icon.visibility = View.GONE
            v.progress.visibility = View.VISIBLE
            v.progress.setIndicatorColor(accent)
            v.progress.trackColor = ColorUtils.setAlphaComponent(accent, 0x33)
        } else {
            v.progress.visibility = View.GONE
            v.icon.visibility = View.VISIBLE
            v.icon.setImageResource(c.iconRes ?: c.type.iconRes)
            v.icon.imageTintList = ColorStateList.valueOf(accent)
        }

        v.title.visibility = if (c.title.isNullOrBlank()) View.GONE else View.VISIBLE
        v.title.text = c.title
        v.message.text = c.text
        v.message.setTextColor(
            ContextCompat.getColor(ctx, if (c.title.isNullOrBlank()) R.color.snackbar_title else R.color.snackbar_message)
        )

        if (c.actionText != null && c.onAction != null) {
            val radius = 999f
            val pill = GradientDrawable().apply {
                cornerRadius = radius
                setColor(ColorUtils.setAlphaComponent(accent, 0x24))
            }
            val mask = GradientDrawable().apply { cornerRadius = radius; setColor(accent) }
            v.action.background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x40)), pill, mask
            )
            v.action.visibility = View.VISIBLE
            v.action.text = c.actionText
            v.action.setTextColor(accent)
            v.action.setOnClickListener {
                c.onAction.invoke()
                snackbar?.dismiss()
            }
        } else {
            v.action.visibility = View.GONE
            v.action.setOnClickListener(null)
        }

        v.timer.background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(ColorUtils.setAlphaComponent(accent, 0xB3))
        }
        v.timer.pivotX = 0f
        v.timer.scaleX = 1f
        v.timer.visibility = View.INVISIBLE

        if (animate) playEntrance(c)
    }

    /** Pop del icono + háptica. Se ejecuta al aparecer y en cada cambio de estado. */
    private fun playEntrance(c: Content) {
        val v = views ?: return
        v.iconContainer.animate().cancel()
        v.iconContainer.scaleX = 0.5f
        v.iconContainer.scaleY = 0.5f
        v.iconContainer.alpha = 0f
        v.iconContainer.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(360)
            .setInterpolator(OvershootInterpolator(2.4f))
            .withEndAction {
                if (c.type == SnackType.LIKE) {
                    v.icon.animate().scaleX(1.22f).scaleY(1.22f).setDuration(110)
                        .withEndAction { v.icon.animate().scaleX(1f).scaleY(1f).setDuration(170).start() }
                        .start()
                }
            }
            .start()
        when (c.type) {
            SnackType.LIKE, SnackType.SUCCESS -> v.root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            SnackType.ERROR -> v.root.performHapticFeedback(HapticFeedbackConstants.REJECT)
            else -> Unit
        }
    }

    private fun startTimer(durationMs: Int) {
        timerAnimator?.cancel()
        val v = views ?: return
        if (durationMs <= 0 || isTouchExplorationEnabled(v.root)) {
            v.timer.visibility = View.INVISIBLE
            timerTotalMs = 0L
            return
        }
        timerTotalMs = durationMs.toLong()
        timerRemainingMs = timerTotalMs
        runTimer()
    }

    private fun runTimer() {
        val v = views ?: return
        v.timer.visibility = View.VISIBLE
        timerResumedAt = SystemClock.uptimeMillis()
        timerAnimator = ValueAnimator.ofFloat(timerRemainingMs.toFloat() / timerTotalMs, 0f).apply {
            duration = timerRemainingMs
            interpolator = LinearInterpolator()
            addUpdateListener { v.timer.scaleX = it.animatedValue as Float }
            start()
        }
    }

    /** Mantener pulsado pausa el timeout; deslizar en horizontal descarta; tocar cierra. */
    @SuppressLint("ClickableViewAccessibility")
    private fun installGestures(layout: View) {
        val slop = ViewConfiguration.get(layout.context).scaledTouchSlop
        var downX = 0f
        var dragging = false
        layout.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    dragging = false
                    pauseTimeout()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    if (!dragging && abs(dx) > slop) dragging = true
                    if (dragging) {
                        view.translationX = dx
                        view.alpha = (1f - abs(dx) / view.width).coerceIn(0.2f, 1f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.rawX - downX
                    val isTap = !dragging && event.actionMasked == MotionEvent.ACTION_UP
                    when {
                        dragging && abs(dx) > view.width * 0.3f -> swipeAway(view, dx)
                        isTap && content?.type != SnackType.LOADING -> snackbar?.dismiss()
                        else -> {
                            view.animate().translationX(0f).alpha(1f).setDuration(180)
                                .setInterpolator(DecelerateInterpolator()).start()
                            resumeTimeout()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun swipeAway(view: View, dx: Float) {
        view.animate()
            .translationX(if (dx > 0) view.width.toFloat() else -view.width.toFloat())
            .alpha(0f)
            .setDuration(160)
            .withEndAction {
                // Invisible antes de dismiss para que el fade de salida de Material no la reaparezca.
                view.visibility = View.INVISIBLE
                snackbar?.dismiss()
            }
            .start()
    }

    private fun pauseTimeout() {
        val bar = snackbar ?: return
        if (bar.duration <= 0 || !isOnScreen) return
        timerAnimator?.cancel()
        if (timerTotalMs > 0) {
            val elapsed = SystemClock.uptimeMillis() - timerResumedAt
            timerRemainingMs = (timerRemainingMs - elapsed).coerceAtLeast(0L)
        } else {
            timerRemainingMs = bar.duration.toLong()
        }
        bar.duration = Snackbar.LENGTH_INDEFINITE
        bar.show()
    }

    private fun resumeTimeout() {
        val bar = snackbar ?: return
        if (bar.duration != Snackbar.LENGTH_INDEFINITE || content?.type == SnackType.LOADING) return
        if (content?.durationMs == Snackbar.LENGTH_INDEFINITE) return
        // Margen mínimo para que el usuario pueda terminar de leer tras soltar.
        val remaining = timerRemainingMs.coerceAtLeast(1200L)
        bar.duration = remaining.toInt()
        bar.show()
        if (timerTotalMs > 0) {
            timerRemainingMs = remaining.coerceAtMost(timerTotalMs)
            runTimer()
        }
    }

    private fun isTouchExplorationEnabled(view: View): Boolean {
        val am = view.context.getSystemService(AccessibilityManager::class.java)
        return am?.isTouchExplorationEnabled == true
    }

    private class Views(val root: View) {
        val iconContainer: View = root.findViewById(R.id.snackIconContainer)
        val icon: ImageView = root.findViewById(R.id.snackIcon)
        val progress: CircularProgressIndicator = root.findViewById(R.id.snackProgress)
        val title: TextView = root.findViewById(R.id.snackTitle)
        val message: TextView = root.findViewById(R.id.snackMessage)
        val action: TextView = root.findViewById(R.id.snackAction)
        val timer: View = root.findViewById(R.id.snackTimer)
    }

    private class Content(
        val text: String,
        val type: SnackType,
        val title: String?,
        val iconRes: Int?,
        val actionText: String?,
        val onAction: (() -> Unit)?,
        val durationMs: Int?
    ) {
        fun duration(): Int {
            durationMs?.let { return it }
            if (type == SnackType.LOADING) return Snackbar.LENGTH_INDEFINITE
            val base = if (type == SnackType.ERROR || type == SnackType.WARNING) 4000 else 3000
            val chars = text.length + (title?.length ?: 0)
            val reading = (chars - 50).coerceAtLeast(0) * 45
            val action = if (actionText != null) 1500 else 0
            return (base + reading).coerceAtMost(7000) + action
        }
    }
}
