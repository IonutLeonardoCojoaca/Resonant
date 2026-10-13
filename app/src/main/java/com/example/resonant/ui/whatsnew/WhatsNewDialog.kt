package com.example.resonant.ui.whatsnew

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.resonant.BuildConfig
import com.example.resonant.R
import com.example.resonant.feature.collabfinder.ui.views.CollabOrbitMotifView
import com.example.resonant.ui.components.ChartHero
import com.example.resonant.ui.components.ResonantMotion
import com.google.android.material.button.MaterialButton

/**
 * Presentación de novedades a pantalla completa, una sola vez por versión
 * (ver [showIfNeeded]). Cada página reutiliza la cabecera por capas de los rankings
 * con el acento de su sección: al deslizar, las capas se mueven a distinta velocidad
 * y el color de fondo/botón se mezcla de forma continua entre páginas.
 */
class WhatsNewDialog : DialogFragment() {

    companion object {
        private const val TAG = "WhatsNewDialog"
        private const val PREFS = "whats_new"
        private const val KEY_SEEN_VERSION = "seen_version"

        /** Muestra las novedades si esta versión las tiene y el usuario aún no las ha visto. */
        fun showIfNeeded(activity: FragmentActivity) {
            if (BuildConfig.VERSION_NAME != WhatsNewContent.VERSION) return
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_SEEN_VERSION, null) == WhatsNewContent.VERSION) return
            if (activity.supportFragmentManager.findFragmentByTag(TAG) != null) return
            WhatsNewDialog().show(activity.supportFragmentManager, TAG)
        }
    }

    private val pages = WhatsNewContent.pages
    private val animatedPages = mutableSetOf<Int>()
    private val argb = ArgbEvaluator()

    private lateinit var pager: ViewPager2
    private lateinit var glow: View
    private lateinit var nextButton: MaterialButton
    private val progressFills = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_SpoMusicApp)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.dialog_whats_new, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(R.color.primaryColorTheme)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pager = view.findViewById(R.id.whatsNewPager)
        glow = view.findViewById(R.id.whatsNewGlow)
        nextButton = view.findViewById(R.id.btnWhatsNewNext)

        applyInsets(view)
        buildProgress(view.findViewById(R.id.whatsNewProgress))

        pager.adapter = PageAdapter()
        pager.offscreenPageLimit = 1
        pager.setPageTransformer(::transformPage)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                blendAccent(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                onPageShown(position)
            }
        })

        view.findViewById<View>(R.id.btnWhatsNewSkip).setOnClickListener { dismiss() }
        nextButton.setOnClickListener {
            if (pager.currentItem < pages.lastIndex) pager.currentItem += 1 else dismiss()
        }
        blendAccent(0, 0f)
        // La primera página no siempre recibe onPageSelected: se arranca a mano.
        pager.post { if (0 !in animatedPages) onPageShown(0) }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_SEEN_VERSION, WhatsNewContent.VERSION)?.apply()
    }

    private fun applyInsets(root: View) {
        val topBar = root.findViewById<View>(R.id.whatsNewTopBar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.updatePadding(top = bars.top + dp(10))
            (nextButton.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + dp(20)
            nextButton.requestLayout()
            pager.updatePadding(top = bars.top)
            insets
        }
    }

    // ── Progreso tipo "stories" ─────────────────────────────────────────

    private fun buildProgress(container: LinearLayout) {
        pages.indices.forEach { index ->
            val segment = FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (index > 0) marginStart = dp(4)
                }
                background = rounded(ColorUtils.setAlphaComponent(Color.GRAY, 0x55))
            }
            val fill = View(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                pivotX = 0f
                scaleX = 0f
            }
            segment.addView(fill)
            container.addView(segment)
            progressFills += fill
        }
    }

    private fun updateProgress(current: Int) {
        progressFills.forEachIndexed { index, fill ->
            fill.background = rounded(pages[current].accent.color)
            val target = if (index <= current) 1f else 0f
            if (index == current && fill.scaleX < 1f && ValueAnimator.areAnimatorsEnabled()) {
                fill.animate().scaleX(1f).setDuration(450).setInterpolator(ResonantMotion.expoOut).start()
            } else {
                fill.animate().cancel()
                fill.scaleX = target
            }
        }
    }

    // ── Color continuo entre páginas ────────────────────────────────────

    private fun blendAccent(position: Int, offset: Float) {
        val from = pages[position].accent.color
        val to = pages.getOrNull(position + 1)?.accent?.color ?: from
        val color = argb.evaluate(offset, from, to) as Int
        glow.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(ColorUtils.setAlphaComponent(color, 0x33), 0x00000000)
            setGradientCenter(0.5f, 0.75f)
            gradientRadius = 420 * resources.displayMetrics.density
        }
        nextButton.backgroundTintList = ColorStateList.valueOf(ColorUtils.blendARGB(color, Color.BLACK, 0.3f))
    }

    private fun onPageShown(position: Int) {
        updateProgress(position)
        nextButton.text = if (position == pages.lastIndex) "Empezar a escuchar" else "Siguiente"
        view?.findViewById<View>(R.id.btnWhatsNewSkip)?.isVisible = position < pages.lastIndex
        if (animatedPages.add(position)) {
            pager.post { pageHolder(position)?.animateIn() }
        }
    }

    private fun pageHolder(position: Int): PageHolder? =
        (pager.getChildAt(0) as? RecyclerView)?.findViewHolderForAdapterPosition(position) as? PageHolder

    // ── Parallax por capas al deslizar ──────────────────────────────────

    /**
     * [position] va de -1 (página a la izquierda) a 1 (a la derecha). Las capas del
     * fondo se quedan atrás, el logo delantero se adelanta y los puntos llegan con
     * un ligero retraso: sensación de profundidad al pasar de página.
     */
    private fun transformPage(page: View, position: Float) {
        val width = page.width.toFloat()
        val clamped = position.coerceIn(-1f, 1f)
        page.findViewById<View>(R.id.heroLayerGlow)?.translationX = clamped * width * 0.6f
        page.findViewById<View>(R.id.heroLayerBack)?.translationX = clamped * width * 0.45f
        page.findViewById<View>(R.id.heroLayerFront)?.translationX = -clamped * width * 0.25f
        page.findViewById<View>(R.id.heroLayerText)?.translationX = clamped * width * 0.12f
        page.findViewById<View>(R.id.pagePoints)?.apply {
            translationX = clamped * width * 0.2f
            alpha = 1f - kotlin.math.abs(clamped) * 0.7f
        }
    }

    // ── Páginas ─────────────────────────────────────────────────────────

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_whats_new_page, parent, false)
            return PageHolder(view)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.bind(pages[position], startHidden = position !in animatedPages)
        }
    }

    private inner class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val hero: View = view.findViewById(R.id.pageHero)
        private val points: LinearLayout = view.findViewById(R.id.pagePoints)
        private val motif = CollabOrbitMotifView(view.context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(250), dp(250), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(10)
                marginEnd = -dp(40)
            }
            isVisible = false
        }

        init {
            hero.findViewById<FrameLayout>(R.id.heroLayerBack).addView(motif)
        }

        fun bind(page: WhatsNewContent.Page, startHidden: Boolean) {
            ChartHero.bind(hero, page.accent, page.eyebrow, page.title, page.subtitle)
            motif.isVisible = page.orbitMotif
            motif.accentColor = page.accent.color
            hero.findViewById<ImageView>(R.id.heroIconBack).isVisible = !page.orbitMotif
            hero.findViewById<ImageView>(R.id.heroIconFront).isVisible = !page.orbitMotif

            points.removeAllViews()
            page.points.forEach { point ->
                val row = LayoutInflater.from(itemView.context).inflate(R.layout.item_whats_new_point, points, false)
                row.findViewById<TextView>(R.id.pointTitle).text = point.title
                row.findViewById<TextView>(R.id.pointDescription).text = point.description
                row.findViewById<TextView>(R.id.pointBadge).apply {
                    setTextColor(page.accent.color)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ColorUtils.setAlphaComponent(page.accent.color, 0x2E))
                    }
                }
                points.addView(row)
            }
            // Hasta que la página se muestra por primera vez, todo espera oculto
            // (así no aparece entera al deslizar y luego "parpadea" al animarse).
            if (startHidden && ValueAnimator.areAnimatorsEnabled()) {
                ChartHero.prepareHidden(hero)
                motif.alpha = 0f
                for (i in 0 until points.childCount) points.getChildAt(i).alpha = 0f
            } else {
                // Vista reciclada de otra página: lo ya visto se muestra tal cual.
                listOf(R.id.heroGlow, R.id.heroIconBack, R.id.heroIconFront).forEach { hero.findViewById<View>(it).alpha = 1f }
                val textBlock = hero.findViewById<ViewGroup>(R.id.heroTextBlock)
                for (i in 0 until textBlock.childCount) textBlock.getChildAt(i).apply { alpha = 1f; translationY = 0f }
                motif.alpha = 1f
            }
        }

        /** Entrada de la página: cabecera (logos + textos) y después los puntos en cascada. */
        fun animateIn() {
            ChartHero.animateIn(hero)
            if (!ValueAnimator.areAnimatorsEnabled()) return
            if (motif.isVisible) {
                motif.alpha = 0f
                motif.scaleX = 0.7f
                motif.scaleY = 0.7f
                motif.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(1000)
                    .setInterpolator(ResonantMotion.expoOut).start()
            }
            for (i in 0 until points.childCount) {
                points.getChildAt(i).apply {
                    alpha = 0f
                    translationY = dp(18).toFloat()
                    animate().alpha(1f).translationY(0f).setStartDelay(520L + i * 90L).setDuration(600)
                        .setInterpolator(ResonantMotion.expoOut).start()
                }
            }
        }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(2).toFloat()
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
