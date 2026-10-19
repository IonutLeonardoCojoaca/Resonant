package com.example.resonant.ui.fragments

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.recap.RecapCard
import com.example.resonant.recap.RecapShare
import com.example.resonant.recap.RecapStoryState
import com.example.resonant.recap.RecapTexts
import com.example.resonant.ui.viewmodels.RecapStoriesViewModel
import com.example.resonant.ui.viewmodels.RecapStoriesViewModel.PlaylistEvent
import com.example.resonant.ui.viewmodels.RecapStoriesViewModel.UiState
import com.example.resonant.utils.ImageRequestHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import com.example.resonant.utils.SnackType
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar

/**
 * Resumen mensual como historias a pantalla completa (destino `recapStoriesFragment`, argumento
 * [ARG_PERIOD_KEY]). Tocar a la derecha avanza, a la izquierda retrocede y mantener pulsado pausa.
 * No toca la reproducción en curso.
 */
class RecapStoriesFragment : Fragment() {

    private lateinit var viewModel: RecapStoriesViewModel
    private lateinit var progressContainer: LinearLayout
    private lateinit var cardContainer: ViewGroup
    private lateinit var shareButton: MaterialButton
    private lateinit var loading: ProgressBar
    private lateinit var errorText: TextView

    private var periodKey: String = ""
    private var cards: List<RecapCard> = emptyList()
    private var segments: List<LinearProgressIndicator> = emptyList()
    private var shownIndex = -1
    private var animator: ValueAnimator? = null
    private var holding = false
    private val enableShare = Runnable { if (::shareButton.isInitialized) shareButton.isEnabled = true }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_recap_stories, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[RecapStoriesViewModel::class.java]
        periodKey = arguments?.getString(ARG_PERIOD_KEY).orEmpty()

        progressContainer = view.findViewById(R.id.recapProgressContainer)
        cardContainer = view.findViewById(R.id.recapCardContainer)
        shareButton = view.findViewById(R.id.recapShareButton)
        loading = view.findViewById(R.id.recapLoading)
        errorText = view.findViewById(R.id.recapErrorText)

        view.findViewById<View>(R.id.recapCloseButton).setOnClickListener { findNavController().popBackStack() }
        shareButton.setOnClickListener { shareCurrentCard() }
        setupGestures()

        viewModel.state.observe(viewLifecycleOwner, ::render)
        viewModel.playlistEvent.observe(viewLifecycleOwner, ::onPlaylistEvent)
        viewModel.load(periodKey)
    }

    override fun onResume() {
        super.onResume()
        startTimer()
    }

    override fun onPause() {
        // El progreso queda guardado en el estado del ViewModel.
        stopTimer()
        super.onPause()
    }

    override fun onDestroyView() {
        stopTimer()
        shareButton.removeCallbacks(enableShare)
        shownIndex = -1
        segments = emptyList()
        super.onDestroyView()
    }

    private fun render(state: UiState) {
        loading.isVisible = state == UiState.Loading
        errorText.isVisible = state == UiState.Error
        shareButton.visibility = if (state is UiState.Ready) View.VISIBLE else View.INVISIBLE
        if (state !is UiState.Ready) return

        val firstRender = segments.size != state.cards.size
        cards = state.cards
        if (firstRender) buildSegments(state.cards.size)
        // Un cambio de datos (p. ej. playlist guardada) vuelve a pintar la tarjeta sin reiniciar el progreso.
        shownIndex = -1
        showCurrentCard()
        if (firstRender && isResumed) startTimer()
    }

    private fun buildSegments(count: Int) {
        progressContainer.removeAllViews()
        segments = List(count) {
            LinearProgressIndicator(requireContext()).apply {
                max = PROGRESS_MAX
                trackCornerRadius = 4
                setIndicatorColor(requireContext().getColor(R.color.recap_progress_indicator))
                trackColor = requireContext().getColor(R.color.recap_progress_track)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = 3
                    marginEnd = 3
                }
            }.also(progressContainer::addView)
        }
    }

    // region Temporizador y gestos

    private fun startTimer() {
        val story = viewModel.story ?: return
        stopTimer()
        if (!story.shouldRun || holding) return
        animator = ValueAnimator.ofFloat(story.progress, 1f).apply {
            duration = story.remainingMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                story.updateProgress(it.animatedValue as Float)
                updateSegments(story)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled || view == null) return
                    story.onTimerFinished()
                    showCurrentCard()
                    startTimer()
                }
            })
            start()
        }
    }

    private fun stopTimer() {
        animator?.cancel()
        animator = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val detector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val story = viewModel.story ?: return false
                if (e.x < cardContainer.width / 3f) story.previous() else if (!story.next()) return true
                showCurrentCard()
                startTimer()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                holding = true
                viewModel.story?.pause()
                animator?.pause()
            }
        })
        cardContainer.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            if (holding && (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL)) {
                holding = false
                viewModel.story?.resume()
                if (animator?.isPaused == true) animator?.resume() else startTimer()
            }
            false
        }
    }

    private fun updateSegments(story: RecapStoryState) {
        segments.forEachIndexed { index, segment ->
            segment.progress = when {
                index < story.index -> PROGRESS_MAX
                index == story.index -> (story.progress * PROGRESS_MAX).toInt()
                else -> 0
            }
        }
    }

    // endregion

    // region Tarjetas

    private fun showCurrentCard() {
        val story = viewModel.story ?: return
        updateSegments(story)
        if (story.index == shownIndex) return
        shownIndex = story.index
        val card = cards.getOrNull(story.index) ?: return

        cardContainer.removeAllViews()
        val view = layoutInflater.inflate(layoutFor(card), cardContainer, false)
        cardContainer.addView(view)
        bind(view, card)

        // Compartir se habilita cuando han podido cargar las portadas (o enseguida si no hay).
        shareButton.removeCallbacks(enableShare)
        val hasImages = card is RecapCard.TopSongs || card is RecapCard.TopArtists
        shareButton.isEnabled = !hasImages
        if (hasImages) shareButton.postDelayed(enableShare, SHARE_DELAY_MS)
    }

    private fun layoutFor(card: RecapCard): Int = when (card) {
        is RecapCard.Minutes -> R.layout.item_recap_card_minutes
        is RecapCard.TopSongs -> R.layout.item_recap_card_top_songs
        is RecapCard.TopArtists -> R.layout.item_recap_card_top_artists
        is RecapCard.Genres -> R.layout.item_recap_card_genres
        is RecapCard.Moment -> R.layout.item_recap_card_moment
        is RecapCard.Streak -> R.layout.item_recap_card_streak
        is RecapCard.Unisound -> R.layout.item_recap_card_unisound
        is RecapCard.Aria -> R.layout.item_recap_card_aria
        is RecapCard.Playlist -> R.layout.item_recap_card_playlist
    }

    private fun bind(view: View, card: RecapCard) {
        val value = view.findViewById<TextView?>(R.id.recapCardValue)
        val subtitle = view.findViewById<TextView?>(R.id.recapCardSubtitle)
        when (card) {
            is RecapCard.Minutes -> {
                value?.text = RecapTexts.minutes(card.minutes)
                subtitle?.text = RecapTexts.change(card.changePercent, periodKey).orEmpty()
                subtitle?.isVisible = card.changePercent != null
            }
            is RecapCard.TopSongs -> {
                val list = view.findViewById<LinearLayout>(R.id.recapRankList)
                card.songs.forEach { item ->
                    val row = layoutInflater.inflate(R.layout.item_recap_rank_row, list, false)
                    bindRow(row, item.rank, item.song.title,
                        "${item.song.artistName.orEmpty()} · ${RecapTexts.plays(item.plays)}", item.song.coverUrl)
                    list.addView(row)
                }
            }
            is RecapCard.TopArtists -> {
                val list = view.findViewById<LinearLayout>(R.id.recapRankList)
                card.artists.forEach { item ->
                    val row = layoutInflater.inflate(R.layout.item_recap_rank_row, list, false)
                    bindRow(row, item.rank, item.artist.name, RecapTexts.plays(item.plays), item.artist.url)
                    list.addView(row)
                }
                val newRow = view.findViewById<View>(R.id.recapNewArtistRow)
                view.findViewById<View>(R.id.recapNewArtistLabel).isVisible = card.newArtist != null
                newRow.isVisible = card.newArtist != null
                card.newArtist?.let { bindRow(newRow, null, it.artist.name, RecapTexts.plays(it.plays), it.artist.url) }
            }
            is RecapCard.Genres -> {
                view.findViewById<TextView>(R.id.recapGenreTop).text = card.top.name
                view.findViewById<TextView>(R.id.recapGenreTopShare).text = RecapTexts.percent(card.top.share)
                val rising = card.rising
                listOf(R.id.recapGenreRisingLabel, R.id.recapGenreRising, R.id.recapGenreRisingShare)
                    .forEach { view.findViewById<View>(it).isVisible = rising != null }
                if (rising != null) {
                    view.findViewById<TextView>(R.id.recapGenreRising).text = rising.name
                    view.findViewById<TextView>(R.id.recapGenreRisingShare).text = RecapTexts.percent(rising.share)
                }
            }
            is RecapCard.Moment -> {
                value?.text = RecapTexts.moment(card.slot)
                subtitle?.text = RecapTexts.percent(card.share)
            }
            is RecapCard.Streak -> {
                value?.text = RecapTexts.streak(card.longestStreakDays)
                subtitle?.text = RecapTexts.daysListened(card.daysListened, card.daysInMonth)
            }
            is RecapCard.Unisound -> value?.text = RecapTexts.unisound(card.sessions, card.people)
            is RecapCard.Aria -> value?.text = card.text
            is RecapCard.Playlist -> {
                value?.text = card.name
                subtitle?.text = RecapTexts.songs(card.songCount)
                val button = view.findViewById<MaterialButton>(R.id.recapPlaylistButton)
                button.setText(if (card.playlistId != null) R.string.recap_playlist_open else R.string.recap_playlist_save)
                button.setOnClickListener {
                    val id = card.playlistId
                    if (id != null) openPlaylist(id) else viewModel.savePlaylist()
                }
            }
        }
    }

    private fun bindRow(row: View, rank: Int?, title: String, subtitle: String, imageUrl: String?) {
        row.findViewById<TextView>(R.id.recapRankNumber).apply {
            isVisible = rank != null
            text = rank?.toString().orEmpty()
        }
        row.findViewById<TextView>(R.id.recapRankTitle).text = title
        row.findViewById<TextView>(R.id.recapRankSubtitle).text = subtitle
        val image = row.findViewById<ImageView>(R.id.recapRankImage)
        if (imageUrl.isNullOrBlank()) {
            image.setImageResource(R.drawable.ic_disc)
        } else {
            Glide.with(this).load(ImageRequestHelper.buildGlideModel(requireContext(), imageUrl))
                .placeholder(R.drawable.ic_disc).into(image)
        }
    }

    // endregion

    private fun shareCurrentCard() {
        val story = viewModel.story ?: return
        val card = cards.getOrNull(story.index) ?: return
        val cardView = cardContainer.getChildAt(0) ?: return
        // La imagen lleva solo la tarjeta: sin el botón de la playlist.
        val playlistButton = cardView.findViewById<View?>(R.id.recapPlaylistButton)
        val buttonWasVisible = playlistButton?.isVisible == true
        playlistButton?.visibility = View.INVISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val shared = RecapShare.share(requireContext(), cardView, periodKey, story.index, card.type)
            if (buttonWasVisible) playlistButton?.visibility = View.VISIBLE
            if (!shared) showResonantSnackbar(getString(R.string.recap_share_failed), SnackType.ERROR)
        }
    }

    private fun onPlaylistEvent(event: PlaylistEvent?) {
        event ?: return
        viewModel.consumePlaylistEvent()
        view ?: return
        when (event) {
            is PlaylistEvent.Saved -> showResonantSnackbar(
                text = getString(R.string.recap_playlist_saved),
                type = SnackType.SUCCESS,
                actionText = getString(R.string.recap_playlist_open),
                onAction = { openPlaylist(event.playlistId) }
            )
            PlaylistEvent.Empty -> showResonantSnackbar(getString(R.string.recap_playlist_empty), SnackType.INFO)
            PlaylistEvent.Failed -> showResonantSnackbar(getString(R.string.recap_playlist_failed), SnackType.ERROR)
        }
    }

    private fun openPlaylist(playlistId: String) {
        findNavController().navigate(R.id.playlistFragment, Bundle().apply { putString("playlistId", playlistId) })
    }

    companion object {
        const val ARG_PERIOD_KEY = "periodKey"
        private const val PROGRESS_MAX = 1000
        private const val SHARE_DELAY_MS = 2_000L
    }
}
