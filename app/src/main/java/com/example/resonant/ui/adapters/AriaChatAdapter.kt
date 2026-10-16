package com.example.resonant.ui.adapters

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.aria.AriaFeatureCards
import com.example.resonant.aria.AriaPickExplanationCard
import com.example.resonant.aria.AriaStreakCard
import com.example.resonant.ui.bottomsheets.StreakDetailsBottomSheet
import com.example.resonant.ui.components.ResonantMotion
import com.example.resonant.ui.viewmodels.AriaAction
import com.example.resonant.ui.viewmodels.AriaMessage
import com.example.resonant.ui.viewmodels.AriaMessageRole
import com.example.resonant.ui.viewmodels.AriaNamePlays
import com.example.resonant.ui.viewmodels.AriaRelatedArtist
import com.example.resonant.ui.viewmodels.AriaSongCard
import com.example.resonant.ui.viewmodels.AriaSongDisambiguationChoice
import com.example.resonant.ui.views.AriaAgentOrbView
import com.example.resonant.utils.ImageRequestHelper
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object AriaSongDisambiguationUi {
    const val CARD_TITLE = "ELIGE UNA VERSIÓN"

    fun sortedChoices(action: AriaAction): List<AriaSongDisambiguationChoice> =
        action.songDisambiguation.orEmpty().sortedBy { it.choiceNumber }

    fun selectionPrompt(choiceNumber: Int): String = "Elijo la opción $choiceNumber"

    fun subtitle(choice: AriaSongDisambiguationChoice): String =
        listOfNotNull(
            choice.artistNames?.takeIf { it.isNotBlank() },
            choice.albumTitle?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")

    fun duration(durationSeconds: Int?): String? = durationSeconds
        ?.takeIf { it > 0 }
        ?.let { String.format(Locale.ROOT, "%d:%02d", it / 60, it % 60) }
}

/**
 * Chip de agente de cada respuesta: qué ha hecho Aria (la "herramienta" que ha usado)
 * y cómo ha terminado. Hace que el chat se lea como un agente que actúa, no solo
 * como un bot que contesta.
 */
internal object AriaAgentTraceUi {
    enum class Tone { DONE, NEEDS_INPUT, FAILED }

    data class Trace(val label: String, @DrawableRes val icon: Int, val tone: Tone)

    fun traceFor(action: AriaAction?): Trace? {
        if (action == null) return null
        val tone = when {
            action.success == false || action.executionStatus == "failed" -> Tone.FAILED
            action.executionStatus == "needs_input" || action.entityKind == "song_disambiguation" -> Tone.NEEDS_INPUT
            else -> Tone.DONE
        }
        val type = action.type
        val (label, icon) = when {
            action.entityKind == "song_disambiguation" -> "Necesita tu elección" to R.drawable.ic_song
            type == "crear_playlist" -> "Playlist creada" to R.drawable.ic_playlist_stack
            type == "añadir_canciones" -> "Canciones añadidas" to R.drawable.ic_queue_add
            type == "recomendar_cancion" -> "Buscó en el catálogo" to R.drawable.ic_search_small
            type == "recomendar_artista" || !action.relatedArtists.isNullOrEmpty() ->
                "Artistas encontrados" to R.drawable.ic_artist_person
            type == "descubrir" -> "Descubrimientos" to R.drawable.ic_star_ai
            type == "consulta_usuario" -> "Analizó tu perfil" to R.drawable.ic_trending_up
            type == "crear_sesion_dj" -> "Sesión DJ lista" to R.drawable.ic_playmix
            type == "streak_status" -> "Consultó tu racha" to R.drawable.ic_streak_flame
            type == "explain_pick" -> "Explicó la recomendación" to R.drawable.ic_star_ai
            type == "live_tune_in" || type == "live_leave" -> "Controló Live" to R.drawable.ic_play
            type == "flow_feedback" || type == "flow_set_preferences" -> "Ajustó Flow" to R.drawable.ic_star_ai
            !action.playbackCommand.isNullOrBlank() -> "Controló la reproducción" to R.drawable.ic_play
            type == "consulta" -> "Consultó el catálogo" to R.drawable.ic_search_small
            type == "recomendacion" || type == "proactive_recommendation" ->
                "Recomendación" to R.drawable.ic_star_ai
            else -> return null
        }
        return Trace(if (tone == Tone.FAILED) "No se pudo completar" else label, icon, tone)
    }
}

class AriaChatAdapter(
    private val onFeedback: (messageId: String, rating: Int) -> Unit,
    private val onActionCardClick: (actionPayload: String) -> Unit,
    private val onSongCardClick: (songId: String) -> Unit = {},
    private val onSongDisambiguationChoiceClick: (prompt: String) -> Unit = {},
    private val onArtistCardClick: (artistId: String) -> Unit = {},
    private val onSuggestedFollowupClick: (prompt: String) -> Unit = {},
    private val loadAlbumCover: (albumId: String, target: ImageView) -> Unit = { _, _ -> },
    private val loadArtistImage: (artistId: String, target: ImageView) -> Unit = { _, _ -> }
) : ListAdapter<AriaMessage, RecyclerView.ViewHolder>(AriaDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_ARIA = 1
        private const val VIEW_TYPE_STATUS = 2
        const val STATUS_INTENT = "__status__"

        /** Solo se anima la entrada de resultados recién llegados, no al restaurar historial. */
        private const val REVEAL_WINDOW_MS = 120_000L
    }

    private val revealedIds = HashSet<String>()

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
            is AriaViewHolder -> {
                holder.bind(
                    message,
                    onFeedback,
                    onActionCardClick,
                    onSongCardClick,
                    onSongDisambiguationChoiceClick,
                    onArtistCardClick,
                    onSuggestedFollowupClick,
                    loadAlbumCover,
                    loadArtistImage
                )
                logFeatureCardShown(holder.itemView.context, message)
                val isFresh = System.currentTimeMillis() - message.timestamp < REVEAL_WINDOW_MS
                if (message.isComplete && message.actionData != null && isFresh && revealedIds.add(message.id)) {
                    holder.revealResults()
                }
            }
        }
    }

    private val loggedCardIds = HashSet<String>()

    /** Firebase `aria_card_shown`, once per message, for the streak and "why this pick" cards. */
    private fun logFeatureCardShown(context: Context, message: AriaMessage) {
        if (!message.isComplete) return
        val action = message.actionData ?: return
        val kind = when {
            action.streakCard != null -> AriaFeatureCards.KIND_STREAK
            action.pickExplanation != null -> AriaFeatureCards.KIND_PICK_EXPLANATION
            else -> return
        }
        if (!loggedCardIds.add(message.id)) return
        runCatching {
            com.google.firebase.analytics.FirebaseAnalytics.getInstance(context.applicationContext)
                .logEvent("aria_card_shown", android.os.Bundle().apply {
                    putString("kind", kind)
                    action.pickExplanation?.sourceLabel?.let { putString("source", it) }
                    action.logId?.let { putString("log_id", it.take(100)) }
                })
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
        private val ariaMessage: TextView = view.findViewById(R.id.ariaMessage)
        private val ariaIntentText: TextView = view.findViewById(R.id.ariaIntentText)

        // Tarjeta de entidad
        private val ariaPlaylistCard: View = view.findViewById(R.id.ariaPlaylistCard)
        private val ariaCardBackdrop: ImageView = view.findViewById(R.id.ariaCardBackdrop)
        private val ariaCardCover: ShapeableImageView = view.findViewById(R.id.ariaCardCover)
        private val ariaCardKindLabel: TextView = view.findViewById(R.id.ariaCardKindLabel)
        private val ariaPlaylistCardTitle: TextView = view.findViewById(R.id.ariaPlaylistCardTitle)
        private val ariaPlaylistCardSub: TextView = view.findViewById(R.id.ariaPlaylistCardSub)
        private val ariaCardOpenButton: TextView = view.findViewById(R.id.ariaCardOpenButton)
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

        // Estadísticas del usuario
        private val userStatsCard: View = view.findViewById(R.id.ariaUserStatsCard)
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

        // Racha y motivo de una recomendación (add-aria-context-v2)
        private val streakCard: View = view.findViewById(R.id.ariaStreakCard)
        private val streakDays: TextView = view.findViewById(R.id.ariaStreakDays)
        private val streakLongest: TextView = view.findViewById(R.id.ariaStreakLongest)
        private val streakToday: TextView = view.findViewById(R.id.ariaStreakToday)
        private val streakProgress: android.widget.ProgressBar = view.findViewById(R.id.ariaStreakProgress)
        private val streakFreezes: TextView = view.findViewById(R.id.ariaStreakFreezes)
        private val streakMilestone: TextView = view.findViewById(R.id.ariaStreakMilestone)
        private val pickCard: View = view.findViewById(R.id.ariaPickCard)
        private val pickSource: TextView = view.findViewById(R.id.ariaPickSource)
        private val pickSongRow: View = view.findViewById(R.id.ariaPickSongRow)
        private val pickCover: ImageView = view.findViewById(R.id.ariaPickCover)
        private val pickTitle: TextView = view.findViewById(R.id.ariaPickTitle)
        private val pickArtist: TextView = view.findViewById(R.id.ariaPickArtist)
        private val pickReason: TextView = view.findViewById(R.id.ariaPickReason)
        private val pickEvidenceScroll: View = view.findViewById(R.id.ariaPickEvidenceScroll)
        private val pickEvidenceRow: LinearLayout = view.findViewById(R.id.ariaPickEvidenceRow)

        // Panel de canciones
        private val songCardsContainer: LinearLayout = view.findViewById(R.id.ariaSongCardsContainer)
        private val songCardsIcon: ImageView = view.findViewById(R.id.songCardsIcon)
        private val songCardsKindLabel: TextView = view.findViewById(R.id.songCardsKindLabel)
        private val songCardsArtistName: TextView = view.findViewById(R.id.songCardsArtistName)
        private val songCardsCatalogCount: TextView = view.findViewById(R.id.songCardsCatalogCount)
        private val songCardsCountPill: TextView = view.findViewById(R.id.songCardsCountPill)
        private val songCardsList: LinearLayout = view.findViewById(R.id.songCardsList)
        private val songCardsMoreButton: TextView = view.findViewById(R.id.songCardsMoreButton)

        // Artistas relacionados y siguientes pasos
        private val relatedArtistsContainer: LinearLayout = view.findViewById(R.id.ariaRelatedArtistsContainer)
        private val relatedArtistsLabel: TextView = view.findViewById(R.id.relatedArtistsLabel)
        private val relatedArtistsList: LinearLayout = view.findViewById(R.id.relatedArtistsList)
        private val followupsBlock: View = view.findViewById(R.id.ariaFollowupsBlock)
        private val followupsRow: LinearLayout = view.findViewById(R.id.ariaFollowupsRow)

        private val context: Context get() = itemView.context
        private val density = view.resources.displayMetrics.density
        private val isDark = (view.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        private val fontBold: Typeface? by lazy { ResourcesCompat.getFont(context, R.font.unageo_bold) }
        private val fontSemiBold: Typeface? by lazy { ResourcesCompat.getFont(context, R.font.unageo_semi_bold) }
        private val fontMedium: Typeface? by lazy { ResourcesCompat.getFont(context, R.font.unageo_medium) }
        private val fontRegular: Typeface? by lazy { ResourcesCompat.getFont(context, R.font.unageo_regular) }

        private val resultViews: List<View>
            get() = listOf(
                ariaPlaylistCard, userStatsCard, streakCard, pickCard,
                songCardsContainer, relatedArtistsContainer, followupsBlock
            )

        init {
            // Portada desenfocada como capa de fondo de la tarjeta de entidad.
            ariaCardBackdrop.setRenderEffect(RenderEffect.createBlurEffect(36f, 36f, Shader.TileMode.CLAMP))
        }

        fun bind(
            message: AriaMessage,
            onFeedback: (String, Int) -> Unit,
            onActionCardClick: (String) -> Unit,
            onSongCardClick: (String) -> Unit,
            onSongDisambiguationChoiceClick: (String) -> Unit,
            onArtistCardClick: (String) -> Unit,
            onSuggestedFollowupClick: (String) -> Unit,
            loadAlbumCover: (String, ImageView) -> Unit = { _, _ -> },
            loadArtistImage: (String, ImageView) -> Unit = { _, _ -> }
        ) {
            ariaMessage.text = message.text
            ariaMessage.visibility = if (message.text.isBlank()) View.GONE else View.VISIBLE

            val action = message.actionData
            bindAgentTrace(if (message.isComplete) AriaAgentTraceUi.traceFor(action) else null)

            val cardVisibleTypes = setOf(
                "crear_playlist", "recomendacion", "recomendar_cancion",
                "recomendar_artista", "consulta", "crear_sesion_dj",
                "añadir_canciones", "descubrir", "usuario", "proactive_recommendation"
            )
            val isConsultaUsuario = action?.type == "consulta_usuario"
            val isSongRecs = !action?.songRecommendations.isNullOrEmpty() ||
                !action?.previewSongs.isNullOrEmpty()
            val isSongDisambiguation = action?.entityKind == "song_disambiguation" &&
                !action.songDisambiguation.isNullOrEmpty()
            val hasRelatedArtists = !action?.relatedArtists.isNullOrEmpty()
            val shouldShowCard = action != null &&
                message.isComplete &&
                (message.intentType in cardVisibleTypes || action.type in cardVisibleTypes ||
                    isConsultaUsuario || isSongDisambiguation)

            // Reset de contenedores (y de cualquier animación de entrada a medias)
            resultViews.forEach {
                it.animate().cancel()
                it.alpha = 1f
                it.translationY = 0f
                it.visibility = View.GONE
            }
            songCardsMoreButton.visibility = View.GONE
            Glide.with(itemView).clear(ariaCardCover)
            Glide.with(itemView).clear(ariaCardBackdrop)
            Glide.with(itemView).clear(pickCover)

            if (message.isComplete && bindFeatureCard(action, onSongCardClick)) {
                // Tarjeta de racha o de motivo ya pintada.
            } else if (shouldShowCard && isSongDisambiguation) {
                bindSongDisambiguation(action!!, onSongDisambiguationChoiceClick)
                songCardsContainer.visibility = View.VISIBLE
            } else if (shouldShowCard && isConsultaUsuario) {
                bindUserStatsCard(action!!)
                userStatsCard.visibility = View.VISIBLE
            } else if (shouldShowCard) {
                if (action?.type != "recomendar_cancion" || !isSongRecs) {
                    bindRichCard(action!!)
                    ariaPlaylistCard.visibility = View.VISIBLE
                    val open = View.OnClickListener { message.actionPayload?.let { onActionCardClick(it) } }
                    ariaPlaylistCard.setOnClickListener(open)
                    ariaCardOpenButton.setOnClickListener(open)
                }
                if (isSongRecs) {
                    bindSongRecommendations(action!!, onSongCardClick, loadAlbumCover)
                    songCardsContainer.visibility = View.VISIBLE
                }
                if (hasRelatedArtists) {
                    bindRelatedArtists(action!!, onArtistCardClick, loadArtistImage)
                    relatedArtistsContainer.visibility = View.VISIBLE
                }
            }

            if (message.isComplete && !action?.suggestedFollowups.isNullOrEmpty()) {
                bindSuggestedFollowups(action!!.suggestedFollowups!!, onSuggestedFollowupClick)
                followupsBlock.visibility = View.VISIBLE
            }

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

        /** Entrada escalonada de los resultados recién llegados (tarjetas y filas). */
        fun revealResults() {
            if (!ValueAnimator.areAnimatorsEnabled()) return
            var delay = 60L
            resultViews.filter { it.visibility == View.VISIBLE }.forEach { v ->
                v.alpha = 0f
                v.translationY = 22 * density
                v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(560)
                    .setInterpolator(ResonantMotion.expoOut).start()
                if (v === songCardsContainer) {
                    for (i in 0 until songCardsList.childCount) {
                        val row = songCardsList.getChildAt(i)
                        row.alpha = 0f
                        row.translationX = 18 * density
                        row.animate().alpha(1f).translationX(0f).setStartDelay(delay + 120L + i * 55L)
                            .setDuration(480).setInterpolator(ResonantMotion.expoOut).start()
                    }
                }
                delay += 110L
            }
        }

        // ── Chip de agente ──────────────────────────────────────────────────

        private fun bindAgentTrace(trace: AriaAgentTraceUi.Trace?) {
            if (trace == null) {
                ariaIntentText.visibility = View.GONE
                return
            }
            @ColorInt val tone = when (trace.tone) {
                AriaAgentTraceUi.Tone.DONE -> color(R.color.aria_accent_text)
                AriaAgentTraceUi.Tone.NEEDS_INPUT -> color(R.color.adviseColor)
                AriaAgentTraceUi.Tone.FAILED -> color(R.color.errorColor)
            }
            ariaIntentText.text = trace.label
            ariaIntentText.setTextColor(tone)
            ariaIntentText.background = GradientDrawable().apply {
                cornerRadius = 100 * density
                setColor(ColorUtils.setAlphaComponent(tone, if (isDark) 0x2E else 0x1F))
            }
            val icon = ContextCompat.getDrawable(context, trace.icon)?.mutate()?.apply {
                setTint(tone)
                val s = (11 * density).toInt()
                setBounds(0, 0, s, s)
            }
            ariaIntentText.setCompoundDrawablesRelative(icon, null, null, null)
            ariaIntentText.visibility = View.VISIBLE
        }

        // ── Tarjeta de entidad (playlist / artista / álbum) ─────────────────

        private fun bindRichCard(action: AriaAction) {
            val isArtist = action.entityKind?.contains("artist") == true || action.type == "recomendar_artista"
            val isPlaylist = action.entityKind?.contains("playlist") == true || action.type == "crear_playlist"
            val isAlbum = action.entityKind?.contains("album") == true

            ariaCardCover.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, if (isArtist) Float.MAX_VALUE else 14 * density)
                .build()

            val imageUrl = action.entityImageUrl
            if (!imageUrl.isNullOrBlank()) {
                val model = ImageRequestHelper.buildGlideModel(context, imageUrl)
                ariaCardCover.scaleType = ImageView.ScaleType.CENTER_CROP
                ariaCardCover.imageTintList = null
                Glide.with(itemView).load(model).centerCrop().into(ariaCardCover)
                ariaCardBackdrop.alpha = if (isDark) 0.55f else 0.35f
                Glide.with(itemView).load(model).centerCrop().into(ariaCardBackdrop)
            } else {
                ariaCardCover.scaleType = ImageView.ScaleType.CENTER
                ariaCardCover.setImageResource(
                    when {
                        isArtist -> R.drawable.ic_artist_person
                        isPlaylist -> R.drawable.ic_playlist_stack
                        else -> R.drawable.ic_song
                    }
                )
                ariaCardCover.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                ariaCardBackdrop.alpha = 0f
            }

            val kindText = when {
                isArtist -> "Artista"
                isPlaylist && action.type == "crear_playlist" -> "Playlist · creada por Aria"
                isPlaylist -> "Playlist"
                isAlbum -> "Álbum"
                else -> null
            }
            ariaCardKindLabel.text = kindText
            ariaCardKindLabel.visibility = if (kindText != null) View.VISIBLE else View.GONE

            ariaPlaylistCardTitle.text = when {
                !action.entityName.isNullOrBlank() -> action.entityName
                action.type == "crear_playlist" -> "Playlist creada"
                action.type == "recomendar_artista" -> action.artistas?.firstOrNull() ?: "Artista"
                else -> action.type.replace("_", " ").replaceFirstChar { it.uppercase() }
            }

            val sub = buildSubtitle(action)
            ariaPlaylistCardSub.text = sub
            ariaPlaylistCardSub.visibility = if (sub.isNotBlank()) View.VISIBLE else View.GONE

            ariaCardOpenButton.text = when {
                isArtist -> "Ver artista"
                isPlaylist -> "Abrir playlist"
                isAlbum -> "Ver álbum"
                else -> "Ver detalles"
            }

            val genres = action.topGenres
            if (!genres.isNullOrEmpty()) {
                ariaCardGenresRow.removeAllViews()
                genres.take(5).forEach { ariaCardGenresRow.addView(genreChip(it)) }
                ariaCardGenresScroll.visibility = View.VISIBLE
            } else {
                ariaCardGenresScroll.visibility = View.GONE
            }

            val tracks = action.topTracks
            val trackViews = listOf(ariaCardTrack1, ariaCardTrack2, ariaCardTrack3)
            if (!tracks.isNullOrEmpty()) {
                val accent = color(R.color.aria_accent_text)
                tracks.take(3).forEachIndexed { i, track ->
                    val number = String.format(Locale.ROOT, "%02d", i + 1)
                    trackViews[i].text = SpannableStringBuilder().apply {
                        append(number)
                        setSpan(ForegroundColorSpan(accent), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        append("   ")
                        append(track.title)
                    }
                    trackViews[i].visibility = View.VISIBLE
                }
                for (i in tracks.size until 3) trackViews[i].visibility = View.GONE
                ariaCardTracksContainer.visibility = View.VISIBLE
            } else {
                ariaCardTracksContainer.visibility = View.GONE
            }
            ariaCardDivider.visibility =
                if (!genres.isNullOrEmpty() || !tracks.isNullOrEmpty()) View.VISIBLE else View.GONE
        }

        private fun buildSubtitle(action: AriaAction): String {
            val parts = mutableListOf<String>()
            if ((action.totalSongs ?: 0) > 0) parts.add("${action.totalSongs} canciones")
            if ((action.totalAlbums ?: 0) > 0) parts.add("${action.totalAlbums} álbumes")
            if (action.firstReleaseYear != null && action.lastReleaseYear != null) {
                parts.add("${action.firstReleaseYear} – ${action.lastReleaseYear}")
            }
            if (parts.isEmpty()) {
                if ((action.nCanciones ?: 0) > 0) parts.add("${action.nCanciones} canciones")
                else if (!action.artistas.isNullOrEmpty()) parts.add(action.artistas.take(3).joinToString(", "))
            }
            return parts.joinToString("  ·  ")
        }

        // ── Estadísticas del usuario ────────────────────────────────────────

        /** Streak or "why this pick" card; false when the action has none (fields missing → text only). */
        private fun bindFeatureCard(action: AriaAction?, onSongCardClick: (String) -> Unit): Boolean {
            val streak = action?.streakCard
            if (streak != null) {
                bindStreakCard(streak)
                streakCard.visibility = View.VISIBLE
                return true
            }
            val pick = action?.pickExplanation ?: return false
            bindPickCard(pick, onSongCardClick)
            pickCard.visibility = View.VISIBLE
            return true
        }

        private fun bindStreakCard(card: AriaStreakCard) {
            streakDays.text = AriaFeatureCards.daysText(card.currentDays)
            streakLongest.text = "Récord: ${AriaFeatureCards.daysText(card.longestDays)}"
            streakToday.text = AriaFeatureCards.todayText(card)
            streakProgress.progress = if (card.todayQualified) 100 else card.progressPercent
            streakFreezes.text = AriaFeatureCards.freezesText(card)
            val milestone = AriaFeatureCards.milestoneText(card)
            streakMilestone.text = milestone.orEmpty()
            streakMilestone.visibility = if (milestone != null) View.VISIBLE else View.GONE
            streakCard.setOnClickListener { openStreakDetails(card) }
        }

        private fun openStreakDetails(card: AriaStreakCard) {
            var current: Context? = context
            while (current is android.content.ContextWrapper && current !is androidx.fragment.app.FragmentActivity) {
                current = current.baseContext
            }
            val activity = current as? androidx.fragment.app.FragmentActivity ?: return
            val manager = activity.supportFragmentManager
            if (manager.isStateSaved || manager.findFragmentByTag(StreakDetailsBottomSheet.TAG) != null) return
            StreakDetailsBottomSheet.newInstance(AriaFeatureCards.toStreakStatus(card))
                .show(manager, StreakDetailsBottomSheet.TAG)
        }

        private fun bindPickCard(card: AriaPickExplanationCard, onSongCardClick: (String) -> Unit) {
            pickSource.text = card.sourceLabel.orEmpty()
            pickSource.visibility = if (card.sourceLabel != null) View.VISIBLE else View.GONE
            pickTitle.text = card.title
            pickArtist.text = card.artist.orEmpty()
            pickArtist.visibility = if (card.artist != null) View.VISIBLE else View.GONE
            card.coverUrl?.let { url -> Glide.with(itemView).load(url).centerCrop().into(pickCover) }
            pickReason.text = card.reason.orEmpty()
            pickReason.visibility = if (card.reason != null) View.VISIBLE else View.GONE
            pickEvidenceRow.removeAllViews()
            card.evidence.forEach { pickEvidenceRow.addView(genreChip(it)) }
            pickEvidenceScroll.visibility = if (card.evidence.isNotEmpty()) View.VISIBLE else View.GONE
            pickSongRow.setOnClickListener { onSongCardClick(card.songId) }
        }

        private fun bindUserStatsCard(action: AriaAction) {
            val kind = action.userStatsKind ?: "user_stats"

            listOf(
                userStatsGridContainer, userStatsDivider1, userStatsFavRow, userStatsFavArtistCol,
                userStatsFavGenreCol, userStatsDivider2, userStatsTopArtistsContainer,
                userStatsTopGenresContainer, userStatsFavoritesContainer, userStatsMoodContainer,
                userStatsHistoryContainer, userStatsRow1, userStatsRow2, userStatsRow3
            ).forEach { it.visibility = View.GONE }

            userStatsKindLabel.text = when (kind) {
                "user_stats" -> "TU PERFIL"
                "user_top_artists" -> "TOP ARTISTAS"
                "user_top_genres" -> "TOP GÉNEROS"
                "user_favorites" -> "FAVORITAS"
                "user_history" -> "HISTORIAL"
                "user_mood" -> "MOOD"
                else -> "PERFIL"
            }
            userStatsKindLabel.visibility = View.VISIBLE

            userStatsTitle.text = when (kind) {
                "user_stats" -> "Tus estadísticas"
                "user_top_artists" -> "Tus artistas favoritos"
                "user_top_genres" -> "Tus géneros favoritos"
                "user_favorites" -> "Tus canciones favoritas"
                "user_history" -> "Tu historial reciente"
                "user_mood" -> "Tu mood actual"
                else -> "Tu perfil"
            }

            when (kind) {
                "user_top_artists" -> bindTopArtists(action)
                "user_top_genres" -> bindTopGenres(action)
                "user_favorites" -> bindFavorites(action)
                "user_history" -> bindHistory(action)
                "user_mood" -> bindMood(action)
                else -> bindFullStats(action)
            }
        }

        private fun bindFullStats(action: AriaAction) {
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

            if (!action.topArtistsWithPlays.isNullOrEmpty()) {
                userStatsDivider2.visibility = View.VISIBLE
                userStatsTopArtistsContainer.visibility = View.VISIBLE
                populateRankedList(userStatsTopArtistsList, action.topArtistsWithPlays)
            }

            if (!action.topGenresWithPlays.isNullOrEmpty()) {
                userStatsDivider2.visibility = View.VISIBLE
                userStatsTopGenresContainer.visibility = View.VISIBLE
                populateRankedList(userStatsTopGenresList, action.topGenresWithPlays)
            }
        }

        private fun bindTopArtists(action: AriaAction) {
            if (!action.topArtistsWithPlays.isNullOrEmpty()) {
                userStatsTopArtistsContainer.visibility = View.VISIBLE
                populateRankedList(userStatsTopArtistsList, action.topArtistsWithPlays)
            }
        }

        private fun bindTopGenres(action: AriaAction) {
            if (!action.topGenresWithPlays.isNullOrEmpty()) {
                userStatsTopGenresContainer.visibility = View.VISIBLE
                populateRankedList(userStatsTopGenresList, action.topGenresWithPlays)
            }
        }

        private fun bindFavorites(action: AriaAction) {
            val favs = action.userFavorites
            if (favs.isNullOrEmpty()) return
            userStatsFavoritesContainer.visibility = View.VISIBLE
            userStatsFavoritesList.removeAllViews()
            val accent = color(R.color.aria_accent_text)
            favs.take(10).forEachIndexed { i, track ->
                val number = String.format(Locale.ROOT, "%02d", i + 1)
                userStatsFavoritesList.addView(TextView(context).apply {
                    text = SpannableStringBuilder().apply {
                        append(number)
                        setSpan(ForegroundColorSpan(accent), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        append("   ")
                        append(track.title)
                    }
                    setTextColor(color(if (i < 3) R.color.textEmphasisHigh else R.color.textSecondaryAlpha))
                    textSize = 12.5f
                    typeface = fontMedium
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(5f), 0, dp(5f))
                })
            }
        }

        private fun bindHistory(action: AriaAction) {
            val days = action.userHistoryDays
            if (!days.isNullOrEmpty()) {
                userStatsHistoryContainer.visibility = View.VISIBLE
                userStatsHistoryList.removeAllViews()
                days.take(7).forEach { (date, plays) ->
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(5f), 0, dp(5f))
                    }
                    row.addView(TextView(context).apply {
                        text = date
                        setTextColor(color(R.color.textSecondaryAlpha))
                        textSize = 12f
                        typeface = fontRegular
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(TextView(context).apply {
                        text = "$plays plays"
                        setTextColor(color(R.color.textEmphasisHigh))
                        textSize = 12f
                        typeface = fontBold
                    })
                    userStatsHistoryList.addView(row)
                }
            }

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
            } else {
                userStatsMoodTrend.visibility = View.GONE
            }
            if (!action.topGenresWithPlays.isNullOrEmpty()) {
                userStatsDivider1.visibility = View.VISIBLE
                userStatsTopGenresContainer.visibility = View.VISIBLE
                populateRankedList(userStatsTopGenresList, action.topGenresWithPlays)
            }
        }

        /** Ranking con barra proporcional; el primero con el degradado de Aria. */
        private fun populateRankedList(container: LinearLayout, items: List<AriaNamePlays>) {
            container.removeAllViews()
            val maxPlays = items.maxOfOrNull { it.plays }?.takeIf { it > 0 } ?: 1
            items.take(5).forEachIndexed { i, item ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6f), 0, dp(6f))
                }
                row.addView(TextView(context).apply {
                    text = "${i + 1}"
                    setTextColor(color(if (i == 0) R.color.aria_accent_text else R.color.textTertiaryAlpha))
                    textSize = 12f
                    typeface = fontBold
                    layoutParams = LinearLayout.LayoutParams(dp(20f), LinearLayout.LayoutParams.WRAP_CONTENT)
                })

                val nameCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                nameCol.addView(TextView(context).apply {
                    text = item.name
                    setTextColor(color(if (i == 0) R.color.white else R.color.textEmphasisHigh))
                    textSize = 12.5f
                    typeface = if (i == 0) fontBold else fontMedium
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                val fraction = item.plays.toFloat() / maxPlays
                val bar = LinearLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4f))
                        .also { it.topMargin = dp(5f) }
                    background = GradientDrawable().apply {
                        cornerRadius = 4 * density
                        setColor(color(R.color.dividerOverlay))
                    }
                }
                bar.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, fraction)
                    background = if (i == 0) {
                        GradientDrawable(
                            GradientDrawable.Orientation.LEFT_RIGHT,
                            intArrayOf(Color.parseColor("#FF1F3D"), Color.parseColor("#F21FD8"))
                        ).apply { cornerRadius = 4 * density }
                    } else {
                        GradientDrawable().apply {
                            cornerRadius = 4 * density
                            setColor(color(R.color.textTertiaryAlpha))
                        }
                    }
                })
                bar.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - fraction)
                })
                nameCol.addView(bar)
                row.addView(nameCol)

                row.addView(TextView(context).apply {
                    text = formatNumber(item.plays)
                    setTextColor(color(R.color.textSecondaryAlpha))
                    textSize = 11f
                    typeface = fontMedium
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginStart = dp(10f) }
                })
                container.addView(row)
            }
        }

        private fun formatNumber(n: Int): String = when {
            n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
            n >= 10_000 -> String.format("%.1fK", n / 1_000.0)
            n >= 1_000 -> String.format("%,d", n)
            else -> n.toString()
        }

        private fun formatHours(h: Double): String =
            if (h >= 1.0) String.format("%.0f", h) else String.format("%.1f", h)

        // ── Desambiguación ("elige una versión") ────────────────────────────

        private fun bindSongDisambiguation(action: AriaAction, onChoiceClick: (String) -> Unit) {
            songCardsList.removeAllViews()
            songCardsList.dividerDrawable = ContextCompat.getDrawable(context, R.drawable.divider_aria_choice_row)
            songCardsIcon.setImageResource(R.drawable.ic_song)
            songCardsKindLabel.text = AriaSongDisambiguationUi.CARD_TITLE
            songCardsArtistName.visibility = View.GONE
            songCardsCatalogCount.text = "Toca la que quieras y sigo"
            songCardsCatalogCount.visibility = View.VISIBLE

            val choices = AriaSongDisambiguationUi.sortedChoices(action)
            songCardsCountPill.text = "${choices.size} opciones"
            songCardsCountPill.visibility = View.VISIBLE

            choices.forEach { choice ->
                // Estructura fija (número · info · metadatos): la usan los tests instrumentados.
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    foreground = selectableBackground()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
                    tag = "aria_disambiguation_choice_${choice.choiceNumber}"
                    contentDescription = "Opción ${choice.choiceNumber}: ${choice.title}"
                    isClickable = true
                    isFocusable = true
                }

                card.addView(TextView(context).apply {
                    text = choice.choiceNumber.toString()
                    gravity = Gravity.CENTER
                    setTextColor(color(R.color.aria_accent_text))
                    textSize = 12f
                    typeface = fontBold
                    background = ContextCompat.getDrawable(context, R.drawable.bg_aria_number_badge)
                    layoutParams = LinearLayout.LayoutParams(dp(28f), dp(28f)).also { it.marginEnd = dp(12f) }
                })

                val infoColumn = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                infoColumn.addView(TextView(context).apply {
                    text = choice.title
                    setTextColor(color(R.color.white))
                    textSize = 13.5f
                    typeface = fontSemiBold
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                val subtitle = AriaSongDisambiguationUi.subtitle(choice)
                if (subtitle.isNotEmpty()) {
                    infoColumn.addView(TextView(context).apply {
                        text = subtitle
                        setTextColor(color(R.color.textTheme))
                        textSize = 11.5f
                        typeface = fontRegular
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    })
                }

                val metadataColumn = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginStart = dp(8f) }
                }
                AriaSongDisambiguationUi.duration(choice.durationSeconds)?.let { duration ->
                    metadataColumn.addView(TextView(context).apply {
                        text = duration
                        setTextColor(color(R.color.textSecondaryAlpha))
                        textSize = 11.5f
                        typeface = fontMedium
                    })
                }
                choice.releaseYear?.let { year ->
                    metadataColumn.addView(TextView(context).apply {
                        text = year.toString()
                        setTextColor(color(R.color.textTertiaryAlpha))
                        textSize = 10f
                        typeface = fontRegular
                    })
                }

                card.addView(infoColumn)
                card.addView(metadataColumn)
                card.setOnClickListener {
                    onChoiceClick(AriaSongDisambiguationUi.selectionPrompt(choice.choiceNumber))
                }
                songCardsList.addView(card)
            }
        }

        // ── Canciones (recomendaciones / vista previa) ──────────────────────

        private fun bindSongRecommendations(
            action: AriaAction,
            onSongCardClick: (String) -> Unit,
            loadAlbumCover: (String, ImageView) -> Unit
        ) {
            songCardsList.removeAllViews()
            songCardsList.dividerDrawable = ContextCompat.getDrawable(context, R.drawable.divider_aria_result_row)
            val isPreview = !action.previewSongs.isNullOrEmpty()
            songCardsKindLabel.text = when {
                isPreview && action.type == "crear_playlist" -> "VISTA PREVIA DE LA PLAYLIST"
                isPreview && action.type == "añadir_canciones" -> "CANCIONES AÑADIDAS"
                isPreview && action.type == "descubrir" -> "DESCUBRIMIENTOS"
                else -> "CANCIONES RECOMENDADAS"
            }
            songCardsIcon.setImageResource(
                when {
                    isPreview && action.type == "crear_playlist" -> R.drawable.ic_playlist_stack
                    isPreview && action.type == "añadir_canciones" -> R.drawable.ic_queue_add
                    else -> R.drawable.ic_song
                }
            )

            if (!action.songRecArtistName.isNullOrBlank()) {
                songCardsArtistName.text = action.songRecArtistName
                songCardsArtistName.visibility = View.VISIBLE
            } else {
                songCardsArtistName.visibility = View.GONE
            }

            if (action.songRecTotalInCatalog != null && action.songRecTotalInCatalog > 0) {
                songCardsCatalogCount.text = "${action.songRecTotalInCatalog} canciones en el catálogo"
                songCardsCatalogCount.visibility = View.VISIBLE
            } else {
                songCardsCatalogCount.visibility = View.GONE
            }

            val songs = action.songRecommendations ?: action.previewSongs ?: return
            songCardsCountPill.text = if (songs.size == 1) "1 canción" else "${songs.size} canciones"
            songCardsCountPill.visibility = View.VISIBLE

            // Colapsado: 5 filas + "ver N más" (no merece la pena esconder solo una).
            val collapsed = if (songs.size > COLLAPSED_SONGS + 1) COLLAPSED_SONGS else songs.size
            songs.take(collapsed).forEach {
                songCardsList.addView(buildSongRow(it, onSongCardClick, loadAlbumCover))
            }
            val hidden = songs.drop(collapsed)
            if (hidden.isNotEmpty()) {
                songCardsMoreButton.text = "Ver ${hidden.size} canciones más"
                songCardsMoreButton.visibility = View.VISIBLE
                songCardsMoreButton.setOnClickListener {
                    songCardsMoreButton.visibility = View.GONE
                    hidden.forEachIndexed { i, song ->
                        val row = buildSongRow(song, onSongCardClick, loadAlbumCover)
                        songCardsList.addView(row)
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            row.alpha = 0f
                            row.translationY = -8 * density
                            row.animate().alpha(1f).translationY(0f).setStartDelay(i * 40L)
                                .setDuration(380).setInterpolator(ResonantMotion.expoOut).start()
                        }
                    }
                }
            }
        }

        private fun buildSongRow(
            song: AriaSongCard,
            onSongCardClick: (String) -> Unit,
            loadAlbumCover: (String, ImageView) -> Unit
        ): View {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                foreground = selectableBackground()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
                isClickable = true
                isFocusable = true
                contentDescription = listOf(song.title, song.artistNames).filter { it.isNotBlank() }.joinToString(", ")
                setOnClickListener { onSongCardClick(song.songId) }
            }

            // Portada: hueco con degradado + icono, y la imagen del álbum encima cuando llega.
            val coverFrame = FrameLayout(context).apply {
                background = ContextCompat.getDrawable(context, R.drawable.bg_aria_cover_placeholder)
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(dp(46f), dp(46f))
            }
            coverFrame.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_song)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                alpha = 0.85f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = FrameLayout.LayoutParams(dp(18f), dp(18f), Gravity.CENTER)
            })
            val cover = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            coverFrame.addView(cover)
            song.albumId?.takeIf { it.isNotBlank() }?.let { loadAlbumCover(it, cover) }
            row.addView(coverFrame)

            val infoCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.marginStart = dp(12f) }
            }
            infoCol.addView(TextView(context).apply {
                text = song.title
                setTextColor(color(R.color.white))
                textSize = 13.5f
                typeface = fontSemiBold
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            val subtitle = listOfNotNull(
                song.artistNames.takeIf { it.isNotBlank() },
                song.albumTitle?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                infoCol.addView(TextView(context).apply {
                    text = subtitle
                    setTextColor(color(R.color.textTheme))
                    textSize = 11.5f
                    typeface = fontRegular
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            if (song.genres.isNotEmpty()) {
                infoCol.addView(TextView(context).apply {
                    text = song.genres.take(3).joinToString(" · ")
                    setTextColor(color(R.color.aria_accent_text))
                    textSize = 10f
                    typeface = fontMedium
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(2f), 0, 0)
                })
            }
            row.addView(infoCol)

            val metaCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = dp(10f) }
            }
            if (song.durationSeconds > 0) {
                metaCol.addView(TextView(context).apply {
                    text = String.format(Locale.ROOT, "%d:%02d", song.durationSeconds / 60, song.durationSeconds % 60)
                    setTextColor(color(R.color.textSecondaryAlpha))
                    textSize = 11.5f
                    typeface = fontMedium
                })
            }
            val secondary = when {
                song.releaseYear > 0 -> song.releaseYear.toString()
                song.streams > 0 -> "${formatNumber(song.streams)} plays"
                else -> null
            }
            secondary?.let {
                metaCol.addView(TextView(context).apply {
                    text = it
                    setTextColor(color(R.color.textTertiaryAlpha))
                    textSize = 10f
                    typeface = fontRegular
                })
            }
            if (metaCol.childCount > 0) row.addView(metaCol)
            return row
        }

        // ── Artistas relacionados (carrusel) ────────────────────────────────

        private fun bindRelatedArtists(
            action: AriaAction,
            onArtistCardClick: (String) -> Unit,
            loadArtistImage: (String, ImageView) -> Unit
        ) {
            relatedArtistsList.removeAllViews()
            relatedArtistsLabel.text = if (action.seedSource == "perfil") {
                "BASADO EN LO QUE ESCUCHAS"
            } else {
                val reference = action.referenceArtist ?: action.entityName
                reference?.let { "PARECIDOS A ${it.uppercase()}" } ?: "ARTISTAS RELACIONADOS"
            }

            val inflater = LayoutInflater.from(context)
            action.relatedArtists.orEmpty().forEach { artist ->
                val tile = inflater.inflate(R.layout.item_aria_related_artist, relatedArtistsList, false)
                bindArtistTile(tile, artist, loadArtistImage)
                tile.setOnClickListener { onArtistCardClick(artist.id) }
                relatedArtistsList.addView(tile)
            }
        }

        private fun bindArtistTile(
            tile: View,
            artist: AriaRelatedArtist,
            loadArtistImage: (String, ImageView) -> Unit
        ) {
            val (start, end) = ARTIST_GRADIENTS[Math.floorMod(artist.name.hashCode(), ARTIST_GRADIENTS.size)]
            tile.findViewById<View>(R.id.relatedArtistAvatarFrame).background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(start), Color.parseColor(end))
            ).apply { shape = GradientDrawable.OVAL }
            tile.findViewById<TextView>(R.id.relatedArtistInitials).text = initials(artist.name)
            tile.findViewById<TextView>(R.id.relatedArtistName).text = artist.name
            tile.findViewById<TextView>(R.id.relatedArtistReason).apply {
                text = artist.reason
                visibility = if (artist.reason.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            tile.findViewById<TextView>(R.id.relatedArtistMatch).apply {
                val score = artist.score
                if (score != null && score > 0.0 && score <= 1.0) {
                    text = "${(score * 100).toInt()}% afín"
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }
            loadArtistImage(artist.id, tile.findViewById(R.id.relatedArtistImage))
            tile.contentDescription = artist.name
        }

        private fun initials(name: String): String =
            name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2)
                .joinToString("") { it.first().uppercaseChar().toString() }
                .ifEmpty { "♪" }

        // ── Siguientes pasos ────────────────────────────────────────────────

        private fun bindSuggestedFollowups(followups: List<String>, onSuggestedFollowupClick: (String) -> Unit) {
            followupsRow.removeAllViews()
            followups.take(3).forEach { prompt ->
                followupsRow.addView(TextView(context).apply {
                    text = prompt
                    setTextColor(color(R.color.white))
                    textSize = 12.5f
                    typeface = fontMedium
                    gravity = Gravity.CENTER_VERTICAL
                    background = ContextCompat.getDrawable(context, R.drawable.bg_aria_prompt_chip)
                    setPadding(dp(12f), dp(9f), dp(14f), dp(9f))
                    compoundDrawablePadding = dp(7f)
                    val star = ContextCompat.getDrawable(context, R.drawable.ic_star_ai)?.mutate()?.apply {
                        setBounds(0, 0, dp(12f), dp(12f))
                    }
                    setCompoundDrawablesRelative(star, null, null, null)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = dp(8f) }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onSuggestedFollowupClick(prompt) }
                })
            }
        }

        // ── Utilidades ──────────────────────────────────────────────────────

        private fun genreChip(genre: String): TextView = TextView(context).apply {
            text = genre
            setTextColor(color(R.color.textEmphasisHigh))
            textSize = 11f
            typeface = fontMedium
            background = ContextCompat.getDrawable(context, R.drawable.bg_aria_genre_chip)
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(6f) }
        }

        private fun selectableBackground(): Drawable? {
            val tv = TypedValue()
            if (!context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) return null
            return if (tv.resourceId != 0) ContextCompat.getDrawable(context, tv.resourceId) else null
        }

        private fun updateFeedbackButtons(rating: Int?) {
            val activeColor = color(R.color.secondaryColorTheme)
            val inactiveColor = color(R.color.textTertiaryAlpha)
            likeButton.setColorFilter(if (rating == 1) activeColor else inactiveColor)
            dislikeButton.setColorFilter(if (rating == -1) activeColor else inactiveColor)
        }

        @ColorInt
        private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(context, id)

        private fun dp(value: Float): Int = (value * density).toInt()

        private companion object {
            const val COLLAPSED_SONGS = 5

            /** Pares de color para el avatar de artistas sin foto (familia del degradado de Aria). */
            val ARTIST_GRADIENTS = listOf(
                "#FF1F3D" to "#F21FD8",
                "#F21FD8" to "#7117FF",
                "#7117FF" to "#2E6BFF",
                "#FF2EA8" to "#FF8A3D",
                "#E21616" to "#7117FF"
            )
        }
    }

    class StatusViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val orb: AriaAgentOrbView = view.findViewById(R.id.statusOrb)
        private val messageText: TextView = view.findViewById(R.id.statusMessageText)
        private val handler = Handler(Looper.getMainLooper())
        private var typingRunnable: Runnable? = null

        fun bind(message: AriaMessage) {
            orb.setThinking(true)
            if (messageText.text.toString() != message.text) typeText(message.text)
        }

        fun stopAnimation() {
            typingRunnable?.let { handler.removeCallbacks(it) }
            typingRunnable = null
            messageText.text = ""
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
                    handler.postDelayed(r, 24L)
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
