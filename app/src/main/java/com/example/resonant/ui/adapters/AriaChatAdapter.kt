package com.example.resonant.ui.adapters

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.ui.viewmodels.AriaAction
import com.example.resonant.ui.viewmodels.AriaMessage
import com.example.resonant.ui.viewmodels.AriaMessageRole
import com.example.resonant.ui.viewmodels.AriaNamePlays
import com.example.resonant.ui.viewmodels.AriaSongCard
import com.example.resonant.utils.ImageRequestHelper
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AriaChatAdapter(
    private val onFeedback: (messageId: String, rating: Int) -> Unit,
    private val onActionCardClick: (actionPayload: String) -> Unit,
    private val onSongCardClick: (songId: String) -> Unit = {}
) : ListAdapter<AriaMessage, RecyclerView.ViewHolder>(AriaDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_ARIA = 1
        private const val VIEW_TYPE_STATUS = 2
        const val STATUS_INTENT = "__status__"
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return when {
            item.role == AriaMessageRole.USER -> VIEW_TYPE_USER
            item.intentType == STATUS_INTENT -> VIEW_TYPE_STATUS
            else -> VIEW_TYPE_ARIA
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_USER -> UserViewHolder(inflater.inflate(R.layout.item_aria_message_user, parent, false))
            VIEW_TYPE_STATUS -> StatusViewHolder(inflater.inflate(R.layout.item_aria_message_status, parent, false))
            else -> AriaViewHolder(inflater.inflate(R.layout.item_aria_message_aria, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is UserViewHolder -> holder.bind(message)
            is StatusViewHolder -> holder.bind(message)
            is AriaViewHolder -> holder.bind(message, onFeedback, onActionCardClick, onSongCardClick)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is StatusViewHolder) holder.stopAnimation()
    }

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val userMessage: TextView = view.findViewById(R.id.userMessage)
        private val userTimestamp: TextView = view.findViewById(R.id.userTimestamp)

        fun bind(message: AriaMessage) {
            userMessage.text = message.text
            userTimestamp.text = formatTime(message.timestamp)
        }
    }

    class AriaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ariaBubble: LinearLayout = view.findViewById(R.id.ariaBubble)
        private val ariaMessage: TextView = view.findViewById(R.id.ariaMessage)
        private val ariaIntentText: TextView = view.findViewById(R.id.ariaIntentText)
        private val ariaPlaylistCard: CardView = view.findViewById(R.id.ariaPlaylistCard)
        private val ariaCardCover: ShapeableImageView = view.findViewById(R.id.ariaCardCover)
        private val ariaCardKindLabel: TextView = view.findViewById(R.id.ariaCardKindLabel)
        private val ariaPlaylistCardTitle: TextView = view.findViewById(R.id.ariaPlaylistCardTitle)
        private val ariaPlaylistCardSub: TextView = view.findViewById(R.id.ariaPlaylistCardSub)
        private val ariaCardDivider: View = view.findViewById(R.id.ariaCardDivider)
        private val ariaCardGenresScroll: View = view.findViewById(R.id.ariaCardGenresScroll)
        private val ariaCardGenresRow: LinearLayout = view.findViewById(R.id.ariaCardGenresRow)
        private val ariaCardTracksContainer: LinearLayout = view.findViewById(R.id.ariaCardTracksContainer)
        private val ariaCardTrack1: TextView = view.findViewById(R.id.ariaCardTrack1)
        private val ariaCardTrack2: TextView = view.findViewById(R.id.ariaCardTrack2)
        private val ariaCardTrack3: TextView = view.findViewById(R.id.ariaCardTrack3)
        private val feedbackRow: LinearLayout = view.findViewById(R.id.ariaFeedbackRow)
        private val ariaTimestamp: TextView = view.findViewById(R.id.ariaTimestamp)
        private val likeButton: ImageButton = view.findViewById(R.id.ariaLikeButton)
        private val dislikeButton: ImageButton = view.findViewById(R.id.ariaDislikeButton)

        // User stats card views
        private val userStatsCard: CardView = view.findViewById(R.id.ariaUserStatsCard)
        private val userStatsKindLabel: TextView = view.findViewById(R.id.userStatsKindLabel)
        private val userStatsTitle: TextView = view.findViewById(R.id.userStatsTitle)
        private val userStatsGridContainer: LinearLayout = view.findViewById(R.id.userStatsGridContainer)
        private val userStatsTotalPlaysValue: TextView = view.findViewById(R.id.userStatsTotalPlaysValue)
        private val userStatsListenHoursValue: TextView = view.findViewById(R.id.userStatsListenHoursValue)
        private val userStatsPlaylistsValue: TextView = view.findViewById(R.id.userStatsPlaylistsValue)
        private val userStatsDaysActiveValue: TextView = view.findViewById(R.id.userStatsDaysActiveValue)
        private val userStatsAvgPerDayValue: TextView = view.findViewById(R.id.userStatsAvgPerDayValue)
        private val userStatsLast7dValue: TextView = view.findViewById(R.id.userStatsLast7dValue)
        private val userStatsRow1: LinearLayout = view.findViewById(R.id.userStatsRow1)
        private val userStatsRow2: LinearLayout = view.findViewById(R.id.userStatsRow2)
        private val userStatsRow3: LinearLayout = view.findViewById(R.id.userStatsRow3)
        private val userStatsDivider1: View = view.findViewById(R.id.userStatsDivider1)
        private val userStatsFavRow: LinearLayout = view.findViewById(R.id.userStatsFavRow)
        private val userStatsFavArtistCol: LinearLayout = view.findViewById(R.id.userStatsFavArtistCol)
        private val userStatsFavArtistName: TextView = view.findViewById(R.id.userStatsFavArtistName)
        private val userStatsFavGenreCol: LinearLayout = view.findViewById(R.id.userStatsFavGenreCol)
        private val userStatsFavGenreName: TextView = view.findViewById(R.id.userStatsFavGenreName)
        private val userStatsDivider2: View = view.findViewById(R.id.userStatsDivider2)
        private val userStatsTopArtistsContainer: LinearLayout = view.findViewById(R.id.userStatsTopArtistsContainer)
        private val userStatsTopArtistsList: LinearLayout = view.findViewById(R.id.userStatsTopArtistsList)
        private val userStatsTopGenresContainer: LinearLayout = view.findViewById(R.id.userStatsTopGenresContainer)
        private val userStatsTopGenresList: LinearLayout = view.findViewById(R.id.userStatsTopGenresList)
        private val userStatsFavoritesContainer: LinearLayout = view.findViewById(R.id.userStatsFavoritesContainer)
        private val userStatsFavoritesList: LinearLayout = view.findViewById(R.id.userStatsFavoritesList)
        private val userStatsMoodContainer: LinearLayout = view.findViewById(R.id.userStatsMoodContainer)
        private val userStatsMoodValue: TextView = view.findViewById(R.id.userStatsMoodValue)
        private val userStatsMoodTrend: TextView = view.findViewById(R.id.userStatsMoodTrend)
        private val userStatsHistoryContainer: LinearLayout = view.findViewById(R.id.userStatsHistoryContainer)
        private val userStatsHistoryList: LinearLayout = view.findViewById(R.id.userStatsHistoryList)

        // Song recommendation card views
        private val songCardsContainer: LinearLayout = view.findViewById(R.id.ariaSongCardsContainer)
        private val songCardsKindLabel: TextView = view.findViewById(R.id.songCardsKindLabel)
        private val songCardsArtistName: TextView = view.findViewById(R.id.songCardsArtistName)
        private val songCardsCatalogCount: TextView = view.findViewById(R.id.songCardsCatalogCount)
        private val songCardsList: LinearLayout = view.findViewById(R.id.songCardsList)

        fun bind(
            message: AriaMessage,
            onFeedback: (String, Int) -> Unit,
            onActionCardClick: (String) -> Unit,
            onSongCardClick: (String) -> Unit
        ) {
            ariaMessage.text = message.text
            ariaBubble.background = null
            ariaMessage.setTextColor(Color.parseColor("#E8E8E8"))

            // FIX 3: Hide intent label completely
            ariaIntentText.visibility = View.GONE

            // Action card
            val CARD_VISIBLE_TYPES = setOf(
                "crear_playlist", "recomendacion", "recomendar_cancion",
                "recomendar_artista", "consulta", "crear_sesion_dj",
                "añadir_canciones", "usuario", "proactive_recommendation"
            )

            val isConsultaUsuario = message.actionData?.type == "consulta_usuario"
            val isSongRecs = message.actionData?.type == "recomendar_cancion" &&
                !message.actionData.songRecommendations.isNullOrEmpty()
            val shouldShowCard = message.actionData != null &&
                message.isComplete &&
                (message.intentType in CARD_VISIBLE_TYPES || isConsultaUsuario)

            // Reset all card containers
            ariaPlaylistCard.visibility = View.GONE
            userStatsCard.visibility = View.GONE
            songCardsContainer.visibility = View.GONE
            Glide.with(itemView).clear(ariaCardCover)

            if (shouldShowCard && isConsultaUsuario) {
                bindUserStatsCard(message.actionData!!, itemView.context)
                userStatsCard.visibility = View.VISIBLE
            } else if (shouldShowCard && isSongRecs) {
                bindSongRecommendations(message.actionData!!, itemView.context, onSongCardClick)
                songCardsContainer.visibility = View.VISIBLE
            } else if (shouldShowCard) {
                val action = message.actionData!!
                bindRichCard(action, itemView.context)
                ariaPlaylistCard.visibility = View.VISIBLE
                ariaPlaylistCard.setOnClickListener {
                    message.actionPayload?.let { onActionCardClick(it) }
                }
            }

            // Feedback row
            if (message.isComplete && message.text.isNotBlank()) {
                feedbackRow.visibility = View.VISIBLE
                ariaTimestamp.text = formatTime(message.timestamp)
                updateFeedbackButtons(message.feedbackRating)
                likeButton.setOnClickListener {
                    onFeedback(message.id, if (message.feedbackRating == 1) 0 else 1)
                }
                dislikeButton.setOnClickListener {
                    onFeedback(message.id, if (message.feedbackRating == -1) 0 else -1)
                }
            } else {
                feedbackRow.visibility = View.GONE
            }
        }

        private fun bindRichCard(action: com.example.resonant.ui.viewmodels.AriaAction, context: Context) {
            val isArtist = action.entityKind?.contains("artist") == true || action.type == "recomendar_artista"
            val isPlaylist = action.entityKind?.contains("playlist") == true || action.type == "crear_playlist"

            // --- Cover shape: circle for artists, rounded rect for playlists/albums ---
            val cornerRadiusPx = if (isArtist) Float.MAX_VALUE
                                 else context.resources.displayMetrics.density * 8f
            ariaCardCover.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, cornerRadiusPx)
                .build()

            // --- Cover image ---
            val imageUrl = action.entityImageUrl
            Glide.with(context).clear(ariaCardCover)
            if (!imageUrl.isNullOrBlank()) {
                val model = ImageRequestHelper.buildGlideModel(context, imageUrl)
                Glide.with(context)
                    .load(model)
                    .centerCrop()
                    .placeholder(if (isArtist) R.drawable.ic_user else R.drawable.ic_playlist_stack)
                    .error(if (isArtist) R.drawable.ic_user else R.drawable.ic_playlist_stack)
                    .into(ariaCardCover)
            } else {
                ariaCardCover.setImageResource(
                    when {
                        isArtist -> R.drawable.ic_user
                        isPlaylist -> R.drawable.ic_playlist_stack
                        else -> R.drawable.ic_song
                    }
                )
            }

            // --- Kind label ---
            val kindText = when {
                isArtist -> "Artista"
                isPlaylist -> "Playlist"
                action.entityKind?.contains("album") == true -> "Álbum"
                else -> null
            }
            if (kindText != null) {
                ariaCardKindLabel.text = kindText
                ariaCardKindLabel.visibility = View.VISIBLE
            } else {
                ariaCardKindLabel.visibility = View.GONE
            }

            // --- Title ---
            ariaPlaylistCardTitle.text = when {
                !action.entityName.isNullOrBlank() -> action.entityName
                action.type == "crear_playlist" -> "Playlist creada"
                action.type == "recomendar_artista" -> action.artistas?.firstOrNull() ?: "Artista"
                else -> action.type.replace("_", " ").replaceFirstChar { it.uppercase() }
            }

            // --- Subtitle (stats line) ---
            val sub = buildSubtitle(action)
            ariaPlaylistCardSub.text = sub
            ariaPlaylistCardSub.visibility = if (sub.isNotBlank()) View.VISIBLE else View.GONE

            // --- Genres ---
            val genres = action.topGenres
            if (!genres.isNullOrEmpty()) {
                ariaCardGenresRow.removeAllViews()
                genres.take(4).forEach { genre ->
                    val chip = TextView(context).apply {
                        text = genre
                        setTextColor(Color.parseColor("#CCFFFFFF"))
                        textSize = 10f
                        typeface = android.graphics.Typeface.DEFAULT
                        setPadding(16, 6, 16, 6)
                        background = context.getDrawable(R.drawable.bg_aria_suggestion_chip)
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        lp.marginEnd = 6
                        layoutParams = lp
                    }
                    ariaCardGenresRow.addView(chip)
                }
                ariaCardDivider.visibility = View.VISIBLE
                ariaCardGenresScroll.visibility = View.VISIBLE
            } else {
                ariaCardGenresScroll.visibility = View.GONE
                ariaCardDivider.visibility = View.GONE
            }

            // --- Top tracks ---
            val tracks = action.topTracks
            if (!tracks.isNullOrEmpty()) {
                val trackViews = listOf(ariaCardTrack1, ariaCardTrack2, ariaCardTrack3)
                tracks.take(3).forEachIndexed { i, track ->
                    trackViews[i].text = "  ${i + 1}.  ${track.title}"
                    trackViews[i].visibility = View.VISIBLE
                }
                for (i in tracks.size until 3) trackViews[i].visibility = View.GONE
                ariaCardTracksContainer.visibility = View.VISIBLE
                ariaCardDivider.visibility = View.VISIBLE
            } else {
                ariaCardTracksContainer.visibility = View.GONE
                if (ariaCardGenresScroll.visibility == View.GONE) ariaCardDivider.visibility = View.GONE
            }
        }

        private fun buildSubtitle(action: com.example.resonant.ui.viewmodels.AriaAction): String {
            val parts = mutableListOf<String>()
            if ((action.totalSongs ?: 0) > 0) parts.add("${action.totalSongs} canciones")
            if ((action.totalAlbums ?: 0) > 0) parts.add("${action.totalAlbums} álbumes")
            if (action.firstReleaseYear != null && action.lastReleaseYear != null) {
                parts.add("${action.firstReleaseYear} – ${action.lastReleaseYear}")
            }
            if (parts.isEmpty()) {
                if ((action.nCanciones ?: 0) > 0) parts.add("${action.nCanciones} canciones")
                else parts.add("Ver detalles")
            }
            return parts.joinToString("  ·  ")
        }

        private fun bindUserStatsCard(action: AriaAction, context: Context) {
            val kind = action.userStatsKind ?: "user_stats"

            // Reset all sections to GONE
            userStatsGridContainer.visibility = View.GONE
            userStatsDivider1.visibility = View.GONE
            userStatsFavRow.visibility = View.GONE
            userStatsFavArtistCol.visibility = View.GONE
            userStatsFavGenreCol.visibility = View.GONE
            userStatsDivider2.visibility = View.GONE
            userStatsTopArtistsContainer.visibility = View.GONE
            userStatsTopGenresContainer.visibility = View.GONE
            userStatsFavoritesContainer.visibility = View.GONE
            userStatsMoodContainer.visibility = View.GONE
            userStatsHistoryContainer.visibility = View.GONE
            userStatsRow1.visibility = View.GONE
            userStatsRow2.visibility = View.GONE
            userStatsRow3.visibility = View.GONE

            // Kind label
            val kindLabel = when (kind) {
                "user_stats" -> "TU PERFIL"
                "user_top_artists" -> "TOP ARTISTAS"
                "user_top_genres" -> "TOP GENEROS"
                "user_favorites" -> "FAVORITAS"
                "user_history" -> "HISTORIAL"
                "user_mood" -> "MOOD"
                else -> "PERFIL"
            }
            userStatsKindLabel.text = kindLabel
            userStatsKindLabel.visibility = View.VISIBLE

            // Title
            val title = when (kind) {
                "user_stats" -> "Tus estadisticas"
                "user_top_artists" -> "Tus artistas favoritos"
                "user_top_genres" -> "Tus generos favoritos"
                "user_favorites" -> "Tus canciones favoritas"
                "user_history" -> "Tu historial reciente"
                "user_mood" -> "Tu mood actual"
                else -> "Tu perfil"
            }
            userStatsTitle.text = title

            when (kind) {
                "user_stats" -> bindFullStats(action, context)
                "user_top_artists" -> bindTopArtists(action, context)
                "user_top_genres" -> bindTopGenres(action, context)
                "user_favorites" -> bindFavorites(action, context)
                "user_history" -> bindHistory(action, context)
                "user_mood" -> bindMood(action)
                else -> bindFullStats(action, context)
            }
        }

        private fun bindFullStats(action: AriaAction, context: Context) {
            userStatsGridContainer.visibility = View.VISIBLE

            val hasPlays = action.totalPlays != null
            val hasHours = action.totalListenTimeHours != null
            val hasPlaylists = action.totalPlaylists != null
            val hasDays = action.daysActive != null
            val hasAvg = action.avgPlaysPerDay != null
            val hasLast7 = action.totalPlaysLast7Days != null

            if (hasPlays || hasHours) {
                userStatsRow1.visibility = View.VISIBLE
                userStatsTotalPlaysValue.text = if (hasPlays) formatNumber(action.totalPlays!!) else "—"
                userStatsListenHoursValue.text = if (hasHours) formatHours(action.totalListenTimeHours!!) else "—"
            }
            if (hasPlaylists || hasDays) {
                userStatsRow2.visibility = View.VISIBLE
                userStatsPlaylistsValue.text = if (hasPlaylists) action.totalPlaylists.toString() else "—"
                userStatsDaysActiveValue.text = if (hasDays) action.daysActive.toString() else "—"
            }
            if (hasAvg || hasLast7) {
                userStatsRow3.visibility = View.VISIBLE
                userStatsAvgPerDayValue.text = if (hasAvg) String.format("%.1f", action.avgPlaysPerDay) else "—"
                userStatsLast7dValue.text = if (hasLast7) formatNumber(action.totalPlaysLast7Days!!) else "—"
            }

            // Favorite artist / genre
            val hasFavArtist = !action.favoriteArtist.isNullOrBlank()
            val hasFavGenre = !action.favoriteGenre.isNullOrBlank()
            if (hasFavArtist || hasFavGenre) {
                userStatsDivider1.visibility = View.VISIBLE
                userStatsFavRow.visibility = View.VISIBLE
                if (hasFavArtist) {
                    userStatsFavArtistCol.visibility = View.VISIBLE
                    userStatsFavArtistName.text = action.favoriteArtist
                }
                if (hasFavGenre) {
                    userStatsFavGenreCol.visibility = View.VISIBLE
                    userStatsFavGenreName.text = action.favoriteGenre
                }
            }

            // Top artists
            if (!action.topArtistsWithPlays.isNullOrEmpty()) {
                userStatsDivider2.visibility = View.VISIBLE
                userStatsTopArtistsContainer.visibility = View.VISIBLE
                populateRankedList(userStatsTopArtistsList, action.topArtistsWithPlays, context)
            }

            // Top genres
            if (!action.topGenresWithPlays.isNullOrEmpty()) {
                if (userStatsDivider2.visibility != View.VISIBLE) userStatsDivider2.visibility = View.VISIBLE
                userStatsTopGenresContainer.visibility = View.VISIBLE
                populateRankedList(userStatsTopGenresList, action.topGenresWithPlays, context)
            }
        }

        private fun bindTopArtists(action: AriaAction, context: Context) {
            if (!action.topArtistsWithPlays.isNullOrEmpty()) {
                userStatsTopArtistsContainer.visibility = View.VISIBLE
                populateRankedList(userStatsTopArtistsList, action.topArtistsWithPlays, context)
            }
        }

        private fun bindTopGenres(action: AriaAction, context: Context) {
            if (!action.topGenresWithPlays.isNullOrEmpty()) {
                userStatsTopGenresContainer.visibility = View.VISIBLE
                populateRankedList(userStatsTopGenresList, action.topGenresWithPlays, context)
            }
        }

        private fun bindFavorites(action: AriaAction, context: Context) {
            val favs = action.userFavorites
            if (!favs.isNullOrEmpty()) {
                userStatsFavoritesContainer.visibility = View.VISIBLE
                userStatsFavoritesList.removeAllViews()
                favs.take(10).forEachIndexed { i, track ->
                    val row = TextView(context).apply {
                        text = "  ${i + 1}.  ${track.title}"
                        setTextColor(Color.parseColor(if (i < 3) "#CCFFFFFF" else "#88FFFFFF"))
                        textSize = 12f
                        typeface = android.graphics.Typeface.DEFAULT
                        setPadding(0, 4, 0, 4)
                    }
                    userStatsFavoritesList.addView(row)
                }
            }
        }

        private fun bindHistory(action: AriaAction, context: Context) {
            val days = action.userHistoryDays
            if (!days.isNullOrEmpty()) {
                userStatsHistoryContainer.visibility = View.VISIBLE
                userStatsHistoryList.removeAllViews()
                days.take(7).forEach { (date, plays) ->
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 4, 0, 4)
                    }
                    val dateView = TextView(context).apply {
                        text = date
                        setTextColor(Color.parseColor("#88FFFFFF"))
                        textSize = 12f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val playsView = TextView(context).apply {
                        text = "$plays plays"
                        setTextColor(Color.parseColor("#CCFFFFFF"))
                        textSize = 12f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    row.addView(dateView)
                    row.addView(playsView)
                    userStatsHistoryList.addView(row)
                }
            }

            // Also show total plays if available
            if (action.totalPlays != null) {
                userStatsGridContainer.visibility = View.VISIBLE
                userStatsRow1.visibility = View.VISIBLE
                userStatsTotalPlaysValue.text = formatNumber(action.totalPlays)
                userStatsListenHoursValue.text = "—"
            }
        }

        private fun bindMood(action: AriaAction) {
            userStatsMoodContainer.visibility = View.VISIBLE
            userStatsMoodValue.text = action.userMood ?: "—"
            if (!action.userMoodTrend.isNullOrBlank()) {
                userStatsMoodTrend.text = "Tendencia: ${action.userMoodTrend}"
                userStatsMoodTrend.visibility = View.VISIBLE
            }

            // Show recent genres as chips if available
            if (!action.topGenresWithPlays.isNullOrEmpty()) {
                userStatsDivider1.visibility = View.VISIBLE
                userStatsTopGenresContainer.visibility = View.VISIBLE
                val ctx = userStatsTopGenresList.context
                populateRankedList(userStatsTopGenresList, action.topGenresWithPlays, ctx)
            }
        }

        private fun populateRankedList(container: LinearLayout, items: List<AriaNamePlays>, context: Context) {
            container.removeAllViews()
            val maxPlays = items.maxOfOrNull { it.plays } ?: 1
            items.take(5).forEachIndexed { i, item ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 5, 0, 5)
                }

                val rank = TextView(context).apply {
                    text = "${i + 1}"
                    setTextColor(Color.parseColor("#55FFFFFF"))
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    val lp = LinearLayout.LayoutParams(
                        (context.resources.displayMetrics.density * 18).toInt(),
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    layoutParams = lp
                }

                val nameCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val nameView = TextView(context).apply {
                    text = item.name
                    setTextColor(Color.parseColor(if (i == 0) "#FFFFFF" else "#CCFFFFFF"))
                    textSize = 12f
                    typeface = if (i == 0) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                    maxLines = 1
                }
                // Mini bar
                val barBg = LinearLayout(context).apply {
                    val density = context.resources.displayMetrics.density
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (density * 3).toInt()
                    ).also { it.topMargin = (density * 3).toInt() }
                    setBackgroundColor(Color.parseColor("#12FFFFFF"))
                }
                val barFill = View(context).apply {
                    val fraction = if (maxPlays > 0) item.plays.toFloat() / maxPlays else 0f
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT
                    ).also { it.weight = fraction }
                    setBackgroundColor(Color.parseColor(if (i == 0) "#E21616" else "#55FFFFFF"))
                }
                val barSpace = View(context).apply {
                    val fraction = if (maxPlays > 0) 1f - (item.plays.toFloat() / maxPlays) else 1f
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT
                    ).also { it.weight = fraction }
                }
                barBg.addView(barFill)
                barBg.addView(barSpace)
                nameCol.addView(nameView)
                nameCol.addView(barBg)

                val playsView = TextView(context).apply {
                    text = formatNumber(item.plays)
                    setTextColor(Color.parseColor("#66FFFFFF"))
                    textSize = 11f
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.marginStart = (context.resources.displayMetrics.density * 8).toInt()
                    layoutParams = lp
                }

                row.addView(rank)
                row.addView(nameCol)
                row.addView(playsView)
                container.addView(row)
            }
        }

        private fun formatNumber(n: Int): String {
            return when {
                n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
                n >= 10_000 -> String.format("%.1fK", n / 1_000.0)
                n >= 1_000 -> String.format("%,d", n)
                else -> n.toString()
            }
        }

        private fun formatHours(h: Double): String {
            return if (h >= 1.0) String.format("%.0f", h) else String.format("%.1f", h)
        }

        private fun bindSongRecommendations(action: AriaAction, context: Context, onSongCardClick: (String) -> Unit) {
            songCardsList.removeAllViews()

            // Header
            if (!action.songRecArtistName.isNullOrBlank()) {
                songCardsArtistName.text = action.songRecArtistName
                songCardsArtistName.visibility = View.VISIBLE
            } else {
                songCardsArtistName.visibility = View.GONE
            }

            if (action.songRecTotalInCatalog != null && action.songRecTotalInCatalog > 0) {
                songCardsCatalogCount.text = "${action.songRecTotalInCatalog} canciones en catalogo"
                songCardsCatalogCount.visibility = View.VISIBLE
            } else {
                songCardsCatalogCount.visibility = View.GONE
            }

            val songs = action.songRecommendations ?: return
            val density = context.resources.displayMetrics.density

            songs.forEachIndexed { idx, song ->
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    background = context.getDrawable(R.drawable.bg_aria_action_card)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    if (idx > 0) lp.topMargin = (density * 4).toInt()
                    layoutParams = lp
                    setPadding(
                        (density * 10).toInt(),
                        (density * 10).toInt(),
                        (density * 10).toInt(),
                        (density * 10).toInt()
                    )
                }

                // Track number
                val rankView = TextView(context).apply {
                    text = "${idx + 1}"
                    setTextColor(Color.parseColor("#44FFFFFF"))
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    val lp = LinearLayout.LayoutParams(
                        (density * 22).toInt(),
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    layoutParams = lp
                }

                // Middle column: title + artist + album
                val infoCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }

                val titleView = TextView(context).apply {
                    text = song.title
                    setTextColor(Color.parseColor("#FFFFFF"))
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                val subtitleParts = mutableListOf<String>()
                if (song.artistNames.isNotBlank()) subtitleParts.add(song.artistNames)
                if (!song.albumTitle.isNullOrBlank()) subtitleParts.add(song.albumTitle)
                val subtitleView = TextView(context).apply {
                    text = subtitleParts.joinToString(" · ")
                    setTextColor(Color.parseColor("#88FFFFFF"))
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    if (subtitleParts.isEmpty()) visibility = View.GONE
                }

                // Genre chips row
                val genresRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.topMargin = (density * 4).toInt()
                    layoutParams = lp
                }
                song.genres.take(3).forEach { genre ->
                    val chip = TextView(context).apply {
                        text = genre
                        setTextColor(Color.parseColor("#AAFFFFFF"))
                        textSize = 9f
                        setPadding(
                            (density * 8).toInt(),
                            (density * 2).toInt(),
                            (density * 8).toInt(),
                            (density * 2).toInt()
                        )
                        background = context.getDrawable(R.drawable.bg_aria_suggestion_chip)
                        val chipLp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        chipLp.marginEnd = (density * 4).toInt()
                        layoutParams = chipLp
                    }
                    genresRow.addView(chip)
                }

                infoCol.addView(titleView)
                infoCol.addView(subtitleView)
                if (song.genres.isNotEmpty()) infoCol.addView(genresRow)

                // Right column: duration + year
                val metaCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.END
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.marginStart = (density * 8).toInt()
                    layoutParams = lp
                }

                if (song.durationSeconds > 0) {
                    val mins = song.durationSeconds / 60
                    val secs = song.durationSeconds % 60
                    val durationView = TextView(context).apply {
                        text = String.format("%d:%02d", mins, secs)
                        setTextColor(Color.parseColor("#88FFFFFF"))
                        textSize = 11f
                        typeface = android.graphics.Typeface.DEFAULT
                    }
                    metaCol.addView(durationView)
                }

                if (song.releaseYear > 0) {
                    val yearView = TextView(context).apply {
                        text = song.releaseYear.toString()
                        setTextColor(Color.parseColor("#55FFFFFF"))
                        textSize = 10f
                    }
                    metaCol.addView(yearView)
                }

                if (song.streams > 0) {
                    val streamsView = TextView(context).apply {
                        text = formatNumber(song.streams)
                        setTextColor(Color.parseColor("#44FFFFFF"))
                        textSize = 9f
                    }
                    metaCol.addView(streamsView)
                }

                card.addView(rankView)
                card.addView(infoCol)
                card.addView(metaCol)

                card.setOnClickListener { onSongCardClick(song.songId) }

                songCardsList.addView(card)
            }
        }

        private fun updateFeedbackButtons(rating: Int?) {
            val activeColor = Color.parseColor("#E21616")
            val inactiveColor = Color.parseColor("#666666")
            when (rating) {
                1 -> { likeButton.setColorFilter(activeColor); dislikeButton.setColorFilter(inactiveColor) }
                -1 -> { likeButton.setColorFilter(inactiveColor); dislikeButton.setColorFilter(activeColor) }
                else -> { likeButton.setColorFilter(inactiveColor); dislikeButton.setColorFilter(inactiveColor) }
            }
        }
    }

    class StatusViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val starIcon: ImageView = view.findViewById(R.id.statusStarIcon)
        private val messageText: TextView = view.findViewById(R.id.statusMessageText)
        private val handler = Handler(Looper.getMainLooper())
        private var spinAnimator: ObjectAnimator? = null
        private var typingRunnable: Runnable? = null

        fun bind(message: AriaMessage) {
            startAnimation()
            typeText(message.text)
        }

        fun stopAnimation() {
            spinAnimator?.cancel()
            typingRunnable?.let { handler.removeCallbacks(it) }
            starIcon.rotation = 0f
        }

        private fun startAnimation() {
            if (spinAnimator?.isRunning == true) return
            spinAnimator = ObjectAnimator.ofFloat(starIcon, View.ROTATION, 0f, 360f).apply {
                duration = 2000
                interpolator = android.view.animation.LinearInterpolator()
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                start()
            }
        }

        private fun typeText(fullText: String) {
            typingRunnable?.let { handler.removeCallbacks(it) }
            messageText.text = ""
            var index = 0
            fun next() {
                if (index < fullText.length) {
                    val r = Runnable {
                        messageText.append(fullText[index].toString())
                        index++
                        next()
                    }
                    typingRunnable = r
                    handler.postDelayed(r, 28L)
                }
            }
            next()
        }
    }

    class AriaDiffCallback : DiffUtil.ItemCallback<AriaMessage>() {
        override fun areItemsTheSame(oldItem: AriaMessage, newItem: AriaMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AriaMessage, newItem: AriaMessage): Boolean {
            return oldItem == newItem
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
