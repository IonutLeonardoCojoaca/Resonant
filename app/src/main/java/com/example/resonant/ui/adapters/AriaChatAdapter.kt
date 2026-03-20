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
import com.example.resonant.ui.viewmodels.AriaMessage
import com.example.resonant.ui.viewmodels.AriaMessageRole
import com.example.resonant.utils.ImageRequestHelper
import com.google.android.material.imageview.ShapeableImageView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AriaChatAdapter(
    private val onFeedback: (messageId: String, rating: Int) -> Unit,
    private val onActionCardClick: (actionPayload: String) -> Unit
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
            is AriaViewHolder -> holder.bind(message, onFeedback, onActionCardClick)
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

        fun bind(
            message: AriaMessage,
            onFeedback: (String, Int) -> Unit,
            onActionCardClick: (String) -> Unit
        ) {
            ariaMessage.text = message.text
            ariaBubble.background = null
            ariaMessage.setTextColor(Color.parseColor("#E8E8E8"))

            // FIX 3: Hide intent label completely
            ariaIntentText.visibility = View.GONE

            // Action card

            // Action card
            val CARD_VISIBLE_TYPES = setOf(
                "crear_playlist", "recomendacion", "recomendar_cancion",
                "recomendar_artista", "consulta", "crear_sesion_dj",
                "añadir_canciones", "usuario", "proactive_recommendation"
            )
            val shouldShowCard = message.actionData != null &&
                message.isComplete &&
                message.intentType in CARD_VISIBLE_TYPES

            if (shouldShowCard) {
                val action = message.actionData!!
                bindRichCard(action, itemView.context)
                ariaPlaylistCard.visibility = View.VISIBLE
                ariaPlaylistCard.setOnClickListener {
                    message.actionPayload?.let { onActionCardClick(it) }
                }
            } else {
                ariaPlaylistCard.visibility = View.GONE
                Glide.with(itemView).clear(ariaCardCover)
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
