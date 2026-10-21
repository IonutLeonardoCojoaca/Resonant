package com.example.resonant.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.airbnb.lottie.LottieAnimationView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ListeningTopItemDTO
import com.example.resonant.managers.ListeningStatsManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.components.ResonantAccent
import com.example.resonant.ui.components.SlidingPillTabs
import com.example.resonant.ui.customviews.DailyMinutesChartView
import com.example.resonant.ui.viewmodels.ListeningStatsUiState
import com.example.resonant.ui.viewmodels.ListeningStatsViewModel
import com.example.resonant.ui.viewmodels.ListeningStatsViewModelFactory
import com.example.resonant.ui.viewmodels.StatsPeriod
import java.text.NumberFormat
import java.util.Locale

/**
 * "Tus estadísticas": minutos con comparación, tops, gráfico de minutos y "Hoy hace un año".
 * Lo que se reproduce desde aquí se atribuye a [QueueSource.STATS]. No ofrece borrar el historial.
 */
class ListeningStatsFragment : Fragment() {

    private lateinit var viewModel: ListeningStatsViewModel

    private lateinit var periodTabs: SlidingPillTabs
    private lateinit var tvPeriodLabel: TextView
    private lateinit var btnNextPeriod: ImageButton
    private lateinit var scrollContent: View
    private lateinit var progressLoading: LottieAnimationView
    private lateinit var errorState: View
    private lateinit var tvErrorMessage: TextView
    private lateinit var btnRetry: View
    private lateinit var tvInsufficientData: TextView
    private lateinit var tvMinutes: TextView
    private lateinit var tvMinutesComparison: TextView
    private lateinit var tvCounts: TextView
    private lateinit var chartMinutes: DailyMinutesChartView
    private lateinit var topSongsContainer: LinearLayout
    private lateinit var topArtistsContainer: LinearLayout
    private lateinit var topGenresContainer: LinearLayout
    private lateinit var onThisDaySection: View
    private lateinit var onThisDayContainer: LinearLayout

    private val numberFormat = NumberFormat.getIntegerInstance(Locale("es", "ES"))

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_listening_stats, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(
            this,
            ListeningStatsViewModelFactory(ListeningStatsManager(requireContext()))
        )[ListeningStatsViewModel::class.java]

        bindViews(view)
        viewModel.state.observe(viewLifecycleOwner) { render(it) }
    }

    private fun bindViews(view: View) {
        periodTabs = view.findViewById(R.id.periodTabs)
        tvPeriodLabel = view.findViewById(R.id.tvPeriodLabel)
        btnNextPeriod = view.findViewById(R.id.btnNextPeriod)
        scrollContent = view.findViewById(R.id.scrollContent)
        progressLoading = view.findViewById(R.id.progressLoading)
        errorState = view.findViewById(R.id.errorState)
        tvErrorMessage = view.findViewById(R.id.tvErrorMessage)
        btnRetry = view.findViewById(R.id.btnRetry)
        tvInsufficientData = view.findViewById(R.id.tvInsufficientData)
        tvMinutes = view.findViewById(R.id.tvMinutes)
        tvMinutesComparison = view.findViewById(R.id.tvMinutesComparison)
        tvCounts = view.findViewById(R.id.tvCounts)
        chartMinutes = view.findViewById(R.id.chartMinutes)
        topSongsContainer = view.findViewById(R.id.topSongsContainer)
        topArtistsContainer = view.findViewById(R.id.topArtistsContainer)
        topGenresContainer = view.findViewById(R.id.topGenresContainer)
        onThisDaySection = view.findViewById(R.id.onThisDaySection)
        onThisDayContainer = view.findViewById(R.id.onThisDayContainer)

        view.findViewById<View>(R.id.btnBack).setOnClickListener { findNavController().navigateUp() }
        view.findViewById<View>(R.id.btnPreviousPeriod).setOnClickListener { viewModel.previousPeriod() }
        btnNextPeriod.setOnClickListener { viewModel.nextPeriod() }
        btnRetry.setOnClickListener { viewModel.retry() }

        // Periodo: píldora deslizante con un único acento (el de la sección de historial).
        val periods = listOf(StatsPeriod.WEEK, StatsPeriod.MONTH, StatsPeriod.YEAR)
        val initial = periods.indexOf(viewModel.state.value?.period ?: StatsPeriod.MONTH).coerceAtLeast(0)
        periodTabs.setTabs(
            labels = listOf("Semana", "Mes", "Año"),
            tabAccents = periods.map { STATS_ACCENT.color },
            initialIndex = initial
        ) { index -> viewModel.selectPeriod(periods[index]) }
        chartMinutes.accentColor = STATS_ACCENT.color

        view.findViewById<View>(R.id.btnPlayTopSongs).setOnClickListener {
            val songs = viewModel.state.value?.topSongs.orEmpty().mapNotNull { it.song }
            playSongs(songs, 0, "STATS:top")
        }
        view.findViewById<View>(R.id.btnPlayOnThisDay).setOnClickListener {
            playSongs(viewModel.state.value?.onThisDay.orEmpty(), 0, "STATS:on-this-day")
        }
    }

    private fun render(state: ListeningStatsUiState) {
        val periodIndex = when (state.period) {
            StatsPeriod.WEEK -> 0
            StatsPeriod.MONTH -> 1
            StatsPeriod.YEAR -> 2
        }
        if (periodTabs.selectedIndex != periodIndex) periodTabs.select(periodIndex, animate = false)
        tvPeriodLabel.text = state.periodLabel
        btnNextPeriod.isEnabled = state.canGoNext
        btnNextPeriod.alpha = if (state.canGoNext) 1f else 0.3f

        val summary = state.summary
        val showLoader = state.isLoading && summary == null
        if (showLoader != progressLoading.isVisible) {
            progressLoading.isVisible = showLoader
            if (showLoader) progressLoading.playAnimation() else progressLoading.cancelAnimation()
        }
        errorState.isVisible = !state.isLoading && (state.isError || state.isDisabled)
        tvErrorMessage.text = if (state.isDisabled) {
            "Las estadísticas no están disponibles ahora mismo"
        } else {
            "No se pudieron cargar tus estadísticas"
        }
        btnRetry.isVisible = state.isError
        scrollContent.isVisible = summary != null && !state.isError && !state.isDisabled
        scrollContent.alpha = if (state.isLoading) 0.5f else 1f
        if (summary == null) return

        tvInsufficientData.isVisible = summary.insufficientData
        tvMinutes.text = numberFormat.format(summary.minutes)
        tvMinutesComparison.text = comparisonText(state)
        tvCounts.text = "${numberFormat.format(summary.plays)} escuchas · " +
            "${summary.distinctSongs} canciones · ${summary.distinctArtists} artistas · " +
            "${summary.activeDays} ${if (summary.activeDays == 1) "día" else "días"} activos"
        chartMinutes.bars = state.bars

        renderSongs(topSongsContainer, state.topSongs)
        renderArtists(state.topArtists)
        renderGenres(state.topGenres)

        onThisDaySection.isVisible = state.onThisDay.isNotEmpty()
        onThisDayContainer.removeAllViews()
        state.onThisDay.forEachIndexed { index, song ->
            onThisDayContainer.addView(songRow(null, song, null) { playSongs(state.onThisDay, index, "STATS:on-this-day") })
        }
    }

    private fun comparisonText(state: ListeningStatsUiState): String {
        val previous = state.summary?.previousMinutes ?: 0
        val delta = state.minutesDelta
        val unit = when (state.period) {
            StatsPeriod.WEEK -> "la semana anterior"
            StatsPeriod.MONTH -> "el mes anterior"
            StatsPeriod.YEAR -> "el año anterior"
        }
        return when {
            previous == 0 && delta == 0 -> "Sin escuchas en $unit tampoco"
            delta > 0 -> "+${numberFormat.format(delta)} min frente a $unit"
            delta < 0 -> "${numberFormat.format(delta)} min frente a $unit"
            else -> "Igual que $unit"
        }
    }

    private fun renderSongs(container: LinearLayout, items: List<ListeningTopItemDTO>) {
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(emptyRow())
            return
        }
        items.forEach { item ->
            val song = item.song ?: return@forEach
            container.addView(songRow(item.rank, song, "${item.plays} escuchas · ${item.minutes} min") { openSong(song) })
        }
    }

    private fun renderArtists(items: List<ListeningTopItemDTO>) {
        topArtistsContainer.removeAllViews()
        if (items.isEmpty()) {
            topArtistsContainer.addView(emptyRow())
            return
        }
        items.forEach { item ->
            val artist = item.artist ?: return@forEach
            topArtistsContainer.addView(
                row(item.rank, artist.name, "${item.plays} escuchas · ${item.minutes} min", artist.url) {
                    val bundle = Bundle().apply {
                        putString("artistId", artist.id)
                        putString("artistName", artist.name)
                        putString("artistImageUrl", artist.url)
                    }
                    findNavController().navigate(R.id.artistFragment, bundle)
                }
            )
        }
    }

    private fun renderGenres(items: List<ListeningTopItemDTO>) {
        topGenresContainer.removeAllViews()
        if (items.isEmpty()) {
            topGenresContainer.addView(emptyRow())
            return
        }
        items.forEach { item ->
            topGenresContainer.addView(
                row(item.rank, item.name.orEmpty(), "${item.plays} escuchas · ${item.minutes} min", null, null)
            )
        }
    }

    private fun songRow(rank: Int?, song: Song, subtitleOverride: String?, onClick: () -> Unit): View {
        val artists = song.artists.joinToString(", ") { it.name }
        val subtitle = listOfNotNull(artists.ifBlank { null }, subtitleOverride).joinToString(" · ")
        return row(rank, song.title, subtitle, song.coverUrl, onClick)
    }

    private fun openSong(song: Song) {
        val bundle = Bundle().apply { putParcelable("song", song) }
        findNavController().navigate(R.id.action_listeningStatsFragment_to_detailedSongFragment, bundle)
    }

    private fun row(rank: Int?, title: String, subtitle: String, imageUrl: String?, onClick: (() -> Unit)?): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dp, 0, 8.dp)
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                val outValue = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }
        if (rank != null) {
            row.addView(TextView(context).apply {
                text = rank.toString()
                textSize = 15f
                typeface = ResourcesCompat.getFont(context, R.font.unageo_bold)
                setTextColor(ContextCompat.getColor(context, R.color.textTheme))
                minWidth = 28.dp
            })
        }
        if (imageUrl != null) {
            val image = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(44.dp, 44.dp).apply { marginEnd = 12.dp }
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            Glide.with(this).load(imageUrl).into(image)
            row.addView(image)
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(context).apply {
            text = title
            textSize = 14f
            typeface = ResourcesCompat.getFont(context, R.font.unageo_medium)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(context, R.color.white))
        })
        if (subtitle.isNotBlank()) {
            texts.addView(TextView(context).apply {
                text = subtitle
                textSize = 12f
                typeface = ResourcesCompat.getFont(context, R.font.unageo_medium)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(context, R.color.textTheme))
            })
        }
        row.addView(texts)
        return row
    }

    private fun emptyRow(): View = TextView(requireContext()).apply {
        text = "Sin escuchas en este periodo"
        textSize = 13f
        typeface = ResourcesCompat.getFont(requireContext(), R.font.unageo_medium)
        setPadding(0, 8.dp, 0, 8.dp)
        setTextColor(ContextCompat.getColor(requireContext(), R.color.textTheme))
    }

    /** Reproduce [songs] desde [index] atribuyendo las escuchas a `STATS`. */
    private fun playSongs(songs: List<Song>, index: Int, sourceId: String) {
        val song = songs.getOrNull(index) ?: return
        val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_PLAY
            putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, song)
            putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, index)
            putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, ArrayList(songs))
            putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, QueueSource.STATS)
            putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, sourceId)
        }
        requireContext().startService(playIntent)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        /** Acento de la pantalla: tus escuchas son tu historial. */
        val STATS_ACCENT = ResonantAccent.HISTORY
    }
}
