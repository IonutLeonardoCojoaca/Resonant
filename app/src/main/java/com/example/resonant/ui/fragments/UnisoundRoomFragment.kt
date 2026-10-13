package com.example.resonant.ui.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.UnisoundMemberDTO
import com.example.resonant.data.network.UnisoundQueueItemDTO
import com.example.resonant.data.network.UnisoundReactionDTO
import com.example.resonant.data.network.UnisoundSettingsUpdateDTO
import com.example.resonant.ui.adapters.UnisoundQueueAdapter
import com.example.resonant.ui.adapters.UnisoundQueueRow
import com.example.resonant.ui.adapters.UnisoundSearchAdapter
import com.example.resonant.ui.adapters.UnisoundSearchRow
import com.example.resonant.ui.bottomsheets.UnisoundShareBottomSheet
import com.example.resonant.ui.dialogs.ResonantDialog
import com.example.resonant.ui.dialogs.UnisoundSavePlaylistDialog
import com.example.resonant.ui.viewmodels.UnisoundViewModel
import com.example.resonant.unisound.UnisoundConnection
import com.example.resonant.unisound.UnisoundControl
import com.example.resonant.unisound.UnisoundItemLabels
import com.example.resonant.unisound.UnisoundQueueMove
import com.example.resonant.unisound.UnisoundReactionThrottle
import com.example.resonant.unisound.UnisoundResult
import com.example.resonant.unisound.UnisoundRoomState
import com.example.resonant.unisound.UnisoundSaveMessages
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The shared room, laid out like the web: header (role, health, code, actions), tabs
 * Sala / Añadir / Miembros. Sala = now playing, reactions and the queue; Añadir = search;
 * Miembros = people in the room and the host settings.
 */
class UnisoundRoomFragment : Fragment(R.layout.fragment_unisound_room) {

    private enum class Tab { ROOM, ADD, MEMBERS }

    private lateinit var viewModel: UnisoundViewModel
    private lateinit var views: Views
    private lateinit var queueAdapter: UnisoundQueueAdapter
    private lateinit var searchAdapter: UnisoundSearchAdapter
    private lateinit var dragHelper: ItemTouchHelper
    private val reactionThrottle = UnisoundReactionThrottle()
    private val settingRows = mutableMapOf<String, SwitchMaterial>()
    private var renderingSettings = false
    private var currentTab = Tab.ROOM
    private var lastCoverSongId: String? = null
    private var dragging = false
    private var dragFrom = -1
    private var rebindQueue = false
    private var searchJob: Job? = null

    /** View references, resolved once. */
    private class Views(root: View) {
        val roleText: TextView = root.findViewById(R.id.unisoundRoleText)
        val healthText: TextView = root.findViewById(R.id.unisoundHealthText)
        val codeText: TextView = root.findViewById(R.id.unisoundCodeText)
        val actionEnd: View = root.findViewById(R.id.unisoundActionEnd)
        val tabRoom: TextView = root.findViewById(R.id.unisoundTabRoom)
        val tabAdd: TextView = root.findViewById(R.id.unisoundTabAdd)
        val tabMembers: TextView = root.findViewById(R.id.unisoundTabMembers)
        val banner: TextView = root.findViewById(R.id.unisoundBanner)
        val roomTab: NestedScrollView = root.findViewById(R.id.unisoundRoomTab)
        val addTab: View = root.findViewById(R.id.unisoundAddTab)
        val membersTab: View = root.findViewById(R.id.unisoundMembersTab)
        val cover: ImageView = root.findViewById(R.id.unisoundCover)
        val heroBackdrop: ImageView = root.findViewById(R.id.unisoundHeroBackdrop)
        val heroTint: View = root.findViewById(R.id.unisoundHeroTint)
        val eyebrow: TextView = root.findViewById(R.id.unisoundEyebrow)
        val songTitle: TextView = root.findViewById(R.id.unisoundSongTitle)
        val songArtist: TextView = root.findViewById(R.id.unisoundSongArtist)
        val progressRow: View = root.findViewById(R.id.unisoundProgressRow)
        val elapsed: TextView = root.findViewById(R.id.unisoundElapsed)
        val duration: TextView = root.findViewById(R.id.unisoundDuration)
        val progress: LinearProgressIndicator = root.findViewById(R.id.unisoundProgress)
        val controls: View = root.findViewById(R.id.unisoundControls)
        val previous: ImageButton = root.findViewById(R.id.unisoundPreviousButton)
        val playPause: ImageButton = root.findViewById(R.id.unisoundPlayPauseButton)
        val next: ImageButton = root.findViewById(R.id.unisoundNextButton)
        val lockedNote: View = root.findViewById(R.id.unisoundLockedNote)
        val reengage: View = root.findViewById(R.id.unisoundReengageButton)
        val reactionLayer: FrameLayout = root.findViewById(R.id.unisoundReactionLayer)
        val reactionBar: LinearLayout = root.findViewById(R.id.unisoundReactionBar)
        val queueCount: TextView = root.findViewById(R.id.unisoundQueueCount)
        val queue: RecyclerView = root.findViewById(R.id.unisoundQueue)
        val emptyQueue: View = root.findViewById(R.id.unisoundEmptyQueue)
        val searchInput: TextView = root.findViewById(R.id.unisoundSearchInput)
        val searchHint: TextView = root.findViewById(R.id.unisoundSearchHint)
        val searchResults: RecyclerView = root.findViewById(R.id.unisoundSearchResults)
        val memberList: LinearLayout = root.findViewById(R.id.unisoundMemberList)
        val settingsList: LinearLayout = root.findViewById(R.id.unisoundSettingsList)
        val newCode: View = root.findViewById(R.id.unisoundNewCodeButton)
        val settingsNote: View = root.findViewById(R.id.unisoundSettingsNote)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[UnisoundViewModel::class.java]
        views = Views(view)
        lastCoverSongId = null

        view.findViewById<View>(R.id.unisoundBackButton).setOnClickListener { findNavController().popBackStack() }
        setupHeader(view)
        setupTabs()
        setupNowPlaying()
        setupQueue()
        setupSearch()
        setupSettings()

        if (savedInstanceState == null && arguments?.getBoolean(ARG_SHOW_SHARE) == true) {
            UnisoundShareBottomSheet().show(parentFragmentManager, UnisoundShareBottomSheet.TAG)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        // The room is over: MainActivity shows the summary or the reason.
                        if (state == null) {
                            if (findNavController().currentDestination?.id == R.id.unisoundRoomFragment) {
                                findNavController().popBackStack()
                            }
                        } else {
                            render(state)
                        }
                    }
                }
                launch { viewModel.hiddenEmojis.collect(::renderReactionBar) }
                launch { viewModel.reactions.collect(::showReaction) }
                launch {
                    while (true) {
                        viewModel.state.value?.let(::renderProgress)
                        delay(PROGRESS_TICK_MS)
                    }
                }
            }
        }
    }

    // region Header

    private fun setupHeader(root: View) {
        root.findViewById<View>(R.id.unisoundActionCopy).setOnClickListener {
            val code = viewModel.state.value?.code ?: return@setOnClickListener
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Código de Unisound", code))
            showResonantSnackbar("Código copiado", R.color.successColor, R.drawable.ic_success)
        }
        root.findViewById<View>(R.id.unisoundActionShare).setOnClickListener {
            UnisoundShareBottomSheet().show(parentFragmentManager, UnisoundShareBottomSheet.TAG)
        }
        root.findViewById<View>(R.id.unisoundActionSave).setOnClickListener { savePlaylist() }
        root.findViewById<View>(R.id.unisoundActionLeave).setOnClickListener { confirmLeave() }
        views.actionEnd.setOnClickListener { confirmEnd() }
    }

    private fun renderHeader(state: UnisoundRoomState) {
        val connected = state.connectedMembers
        val role = if (state.isHost) "Eres anfitrión" else "Eres invitado"
        views.roleText.text = "$role · ${if (connected == 1) "1 conectado" else "$connected conectados"}"
        views.codeText.text = "Sala · ${state.code.orEmpty()}"
        views.actionEnd.isVisible = state.isHost

        val (text, colorRes) = when {
            state.connection != UnisoundConnection.CONNECTED -> "Reconectando…" to R.color.adviseColor
            state.localHold && state.isPlaying -> "En pausa en este móvil" to R.color.adviseColor
            !state.settings.joinOpen -> "Todo correcto · entrada cerrada" to R.color.successColor
            else -> "Todo correcto" to R.color.successColor
        }
        val color = ContextCompat.getColor(requireContext(), colorRes)
        views.healthText.text = text
        views.healthText.setTextColor(color)
        views.healthText.compoundDrawablesRelative[0]?.mutate()?.setTint(color)
        views.banner.isVisible = state.connection != UnisoundConnection.CONNECTED
    }

    // endregion

    // region Tabs

    private fun setupTabs() {
        views.tabRoom.setOnClickListener { selectTab(Tab.ROOM) }
        views.tabAdd.setOnClickListener { selectTab(Tab.ADD) }
        views.tabMembers.setOnClickListener { selectTab(Tab.MEMBERS) }
        selectTab(Tab.ROOM)
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        views.roomTab.isVisible = tab == Tab.ROOM
        views.addTab.isVisible = tab == Tab.ADD
        views.membersTab.isVisible = tab == Tab.MEMBERS
        listOf(views.tabRoom to Tab.ROOM, views.tabAdd to Tab.ADD, views.tabMembers to Tab.MEMBERS)
            .forEach { (label, value) -> styleTab(label, selected = value == tab) }
        if (tab == Tab.ADD) views.searchInput.requestFocus()
    }

    private fun styleTab(label: TextView, selected: Boolean) {
        val color = ContextCompat.getColor(requireContext(), if (selected) R.color.white else R.color.textTheme)
        label.setTextColor(color)
        label.background = if (selected) ContextCompat.getDrawable(requireContext(), R.drawable.bg_unisound_tab_selected) else null
        label.typeface = ResourcesCompat.getFont(
            requireContext(),
            if (selected) R.font.unageo_bold else R.font.unageo_medium
        )
        val size = dp(TAB_ICON_DP)
        label.compoundDrawablesRelative[0]?.mutate()?.let { icon ->
            icon.setBounds(0, 0, size, size)
            icon.setTint(color)
            label.setCompoundDrawablesRelative(icon, null, null, null)
        }
    }

    // endregion

    // region Now playing and reactions

    private fun setupNowPlaying() {
        views.previous.setOnClickListener { viewModel.control(UnisoundControl.PREVIOUS) }
        views.next.setOnClickListener { viewModel.control(UnisoundControl.NEXT) }
        views.reengage.setOnClickListener { viewModel.control(UnisoundControl.PLAY) }
        views.playPause.setOnClickListener { togglePlayback() }
        views.heroBackdrop.setRenderEffect(RenderEffect.createBlurEffect(HERO_BLUR_PX, HERO_BLUR_PX, Shader.TileMode.CLAMP))
        // Parallax: the blurred backdrop trails the page and the cover drifts a little, giving depth.
        views.roomTab.setOnScrollChangeListener { _: NestedScrollView, _, scrollY, _, _ ->
            views.heroBackdrop.translationY = scrollY * BACKDROP_PARALLAX
            views.cover.translationY = scrollY * COVER_PARALLAX
        }
    }

    private fun loadHeroCover(url: String?) {
        if (url.isNullOrBlank()) {
            views.cover.setImageResource(R.drawable.ic_disc)
            views.heroBackdrop.setImageDrawable(null)
            tintHero(HERO_FALLBACK_TINT)
            return
        }
        Glide.with(this).asBitmap().load(url).into(object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                if (view == null) return
                views.cover.setImageBitmap(resource)
                views.heroBackdrop.setImageBitmap(resource)
                Palette.from(resource).generate { palette ->
                    if (view == null) return@generate
                    val swatch = palette?.vibrantSwatch ?: palette?.dominantSwatch ?: palette?.mutedSwatch
                    tintHero(swatch?.rgb ?: HERO_FALLBACK_TINT)
                }
            }

            override fun onLoadCleared(placeholder: Drawable?) = Unit

            override fun onLoadFailed(errorDrawable: Drawable?) {
                if (view == null) return
                views.cover.setImageResource(R.drawable.ic_disc)
                views.heroBackdrop.setImageDrawable(null)
                tintHero(HERO_FALLBACK_TINT)
            }
        })
    }

    /** Cross-fades the hero's tone layer to a darkened version of the cover's colour. */
    private fun tintHero(color: Int) {
        val target = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(color, Color.BLACK, HERO_TINT_DARKEN), HERO_TINT_ALPHA)
        val from = (views.heroTint.background as? ColorDrawable)?.color ?: Color.TRANSPARENT
        ValueAnimator.ofArgb(from, target).apply {
            duration = HERO_TINT_FADE_MS
            addUpdateListener { views.heroTint.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun renderNowPlaying(state: UnisoundRoomState) {
        val current = state.current
        val song = current?.song
        val hasSong = current != null
        views.songTitle.text = song?.title ?: "La sala está en silencio"
        views.songArtist.text = if (hasSong) {
            song?.artistName ?: song?.artists?.joinToString(", ") { it.name }.orEmpty()
        } else if (state.queue.isNotEmpty()) {
            "Pulsa play para empezar la cola."
        } else {
            "Añade canciones para empezar a escuchar juntos."
        }
        views.eyebrow.text = current?.let { UnisoundItemLabels.labelFor(it, state.members.size, state.myUserId) }.orEmpty()
        views.eyebrow.isVisible = hasSong && views.eyebrow.text.isNotBlank()
        views.progressRow.isVisible = hasSong
        if (song?.id != lastCoverSongId || !hasSong) {
            lastCoverSongId = song?.id
            loadHeroCover(song?.coverUrl ?: song?.imageFileName)
        }

        val connected = state.connection == UnisoundConnection.CONNECTED
        val controlsEnabled = connected && state.canControl && (hasSong || state.queue.isNotEmpty())
        listOf(views.playPause, views.previous, views.next).forEach {
            it.isEnabled = controlsEnabled
            it.alpha = if (controlsEnabled) 1f else DISABLED_ALPHA
        }
        views.playPause.setImageResource(if (state.isPlaying && !state.localHold) R.drawable.ic_pause else R.drawable.ic_play)
        views.lockedNote.isVisible = !state.canControl
        views.reengage.isVisible = state.localHold && state.isPlaying
        renderProgress(state)
    }

    private fun renderProgress(state: UnisoundRoomState) {
        val duration = state.currentDurationMs
        val position = state.expectedPositionMs(viewModel.clock.now())
        views.progress.progress = if (duration > 0L) (position * 1000L / duration).toInt() else 0
        views.elapsed.text = formatMs(position)
        views.duration.text = formatMs(duration)
    }

    private fun renderReactionBar(hidden: Set<String>) {
        views.reactionBar.removeAllViews()
        REACTIONS.filterNot(hidden::contains).forEach { emoji ->
            val button = TextView(requireContext()).apply {
                text = emoji
                textSize = 22f
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(context, R.drawable.bg_unisound_round_button)
                    ?.mutate()?.also { it.alpha = 0 }
                contentDescription = "Reaccionar con $emoji"
                setOnClickListener { react(emoji) }
            }
            views.reactionBar.addView(button, LinearLayout.LayoutParams(0, dp(44f), 1f))
        }
    }

    private fun react(emoji: String) {
        if (!reactionThrottle.tryTap(System.currentTimeMillis())) return
        viewLifecycleOwner.lifecycleScope.launch { viewModel.react(emoji) }
    }

    /** Ephemeral floating emoji with the author's name, over the now-playing card. */
    private fun showReaction(reaction: UnisoundReactionDTO) {
        val layer = views.reactionLayer
        if (layer.width == 0 || layer.childCount >= MAX_FLOATING_REACTIONS) return
        val bubble = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(context).apply {
                text = reaction.emoji
                textSize = 30f
            })
            addView(TextView(context).apply {
                text = reaction.name
                textSize = 11f
                typeface = ResourcesCompat.getFont(context, R.font.unageo_bold)
                setTextColor(ContextCompat.getColor(context, R.color.white))
            })
        }
        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = Random.nextInt(dp(24f), (layer.width - dp(72f)).coerceAtLeast(dp(25f)))
            bottomMargin = dp(24f)
        }
        layer.addView(bubble, params)
        bubble.animate()
            .translationY(-layer.height * REACTION_RISE)
            .alpha(0f)
            .setDuration(REACTION_DURATION_MS)
            .withEndAction { layer.removeView(bubble) }
            .start()
    }

    private fun togglePlayback() {
        val state = viewModel.state.value ?: return
        if (state.isPlaying && !state.localHold) {
            viewModel.control(UnisoundControl.PAUSE, state.expectedPositionMs(viewModel.clock.now()))
        } else {
            viewModel.control(UnisoundControl.PLAY)
        }
    }

    // endregion

    // region Queue

    private fun setupQueue() {
        queueAdapter = UnisoundQueueAdapter(
            onRemove = ::removeItem,
            onMove = { item, toIndex -> moveItem(item, toIndex) },
            onStartDrag = { holder -> dragHelper.startDrag(holder) }
        )
        views.queue.layoutManager = LinearLayoutManager(requireContext())
        views.queue.adapter = queueAdapter
        dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    dragging = true
                    dragFrom = viewHolder.bindingAdapterPosition
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                queueAdapter.notifyItemMoved(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                val from = dragFrom
                val to = viewHolder.bindingAdapterPosition
                dragging = false
                dragFrom = -1
                val item = queueAdapter.currentList.getOrNull(from)?.item
                val target = UnisoundQueueMove.targetIndex(from, to, queueAdapter.itemCount)
                if (item != null && target != null) moveItem(item, target)
                // Redraw from the room state: the server answer (or snapshot) decides the order.
                rebindQueue = true
                viewModel.state.value?.let(::renderQueue)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        })
        dragHelper.attachToRecyclerView(views.queue)
    }

    private fun renderQueue(state: UnisoundRoomState) {
        if (dragging) return
        val rows = UnisoundQueueRow.rowsOf(state)
        // After a drag the rows were only moved on screen: rebind them from the real order.
        if (rebindQueue) {
            rebindQueue = false
            queueAdapter.submitList(null)
        }
        queueAdapter.submitList(rows)
        views.queueCount.text = "${state.queue.size}/$MAX_QUEUE_ITEMS"
        views.emptyQueue.isVisible = state.queue.isEmpty()
    }

    private fun moveItem(item: UnisoundQueueItemDTO, toIndex: Int) {
        val size = viewModel.state.value?.queue?.size ?: return
        val from = viewModel.state.value?.queue?.indexOfFirst { it.itemId == item.itemId } ?: return
        val target = UnisoundQueueMove.targetIndex(from, toIndex, size) ?: return
        viewLifecycleOwner.lifecycleScope.launch { viewModel.moveItem(item.itemId, target) }
    }

    private fun removeItem(item: UnisoundQueueItemDTO) {
        viewLifecycleOwner.lifecycleScope.launch { viewModel.removeItem(item.itemId) }
    }

    // endregion

    // region Search ("Añadir")

    private fun setupSearch() {
        searchAdapter = UnisoundSearchAdapter(onAdd = ::addToRoom)
        views.searchResults.layoutManager = LinearLayoutManager(requireContext())
        views.searchResults.adapter = searchAdapter
        views.searchInput.doAfterTextChanged { text ->
            searchJob?.cancel()
            val query = text?.toString()?.trim().orEmpty()
            if (query.length < MIN_QUERY_LENGTH) {
                searchAdapter.submitList(emptyList())
                views.searchHint.text = SEARCH_HINT
                views.searchHint.isVisible = true
                return@doAfterTextChanged
            }
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                val results = viewModel.search(query)
                searchAdapter.submitList(results)
                views.searchHint.text = "Sin resultados para «$query»"
                views.searchHint.isVisible = results.isEmpty()
            }
        }
    }

    private fun addToRoom(row: UnisoundSearchRow) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (row.type == "song") viewModel.addSong(row.id) else viewModel.addSource(row.type, row.id)
            if (result is UnisoundResult.Success) {
                val count = result.value.size
                val text = when {
                    row.type == "song" -> "Añadida a la sala"
                    count == 1 -> "1 canción añadida a la sala"
                    else -> "$count canciones añadidas a la sala"
                }
                showResonantSnackbar(text, R.color.successColor, R.drawable.ic_success)
            }
        }
    }

    // endregion

    // region Members and settings

    private fun renderMembers(state: UnisoundRoomState) {
        views.tabMembers.text = "Miembros · ${state.members.size}"
        styleTab(views.tabMembers, currentTab == Tab.MEMBERS)
        views.memberList.removeAllViews()
        val members = state.members.sortedWith(
            compareByDescending<UnisoundMemberDTO> { it.role == UnisoundRoomState.ROLE_HOST }
                .thenBy { it.joinedAtUtc.orEmpty() }
        )
        members.forEach { member -> views.memberList.addView(memberRow(state, member)) }
    }

    private fun memberRow(state: UnisoundRoomState, member: UnisoundMemberDTO): View {
        val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_unisound_member, views.memberList, false)
        val isMe = member.userId == state.myUserId
        val name = member.name.ifBlank { "Usuario" }
        val isHost = member.role == UnisoundRoomState.ROLE_HOST
        row.findViewById<TextView>(R.id.unisoundMemberInitial).text = name.take(1).uppercase()
        row.findViewById<View>(R.id.unisoundMemberPresence).background.mutate().setTint(
            ContextCompat.getColor(requireContext(), if (member.connected) R.color.successColor else R.color.textTheme)
        )
        row.findViewById<TextView>(R.id.unisoundMemberName).text = if (isMe) "$name (tú)" else name
        row.findViewById<TextView>(R.id.unisoundMemberMeta).apply {
            text = "${if (isHost) "Anfitrión" else "Invitado"} · ${if (member.connected) "Conectado" else "Desconectado"}"
            setTextColor(ContextCompat.getColor(requireContext(), if (isHost) R.color.secondaryColorTheme else R.color.textTheme))
        }
        val canManage = state.isHost && !isMe
        row.findViewById<View>(R.id.unisoundMemberMakeHost).apply {
            isVisible = canManage
            setOnClickListener { confirmTransfer(member) }
        }
        row.findViewById<View>(R.id.unisoundMemberKick).apply {
            isVisible = canManage
            setOnClickListener { confirmKick(member) }
        }
        return row
    }

    private fun setupSettings() {
        SETTINGS.forEach { setting ->
            val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_unisound_setting, views.settingsList, false)
            row.findViewById<TextView>(R.id.unisoundSettingTitle).text = setting.title
            row.findViewById<TextView>(R.id.unisoundSettingHint).text = setting.hint
            val switch = row.findViewById<SwitchMaterial>(R.id.unisoundSettingSwitch)
            switch.setOnCheckedChangeListener { _, checked ->
                if (!renderingSettings) updateSetting(setting.key, checked)
            }
            row.tag = setting.key
            settingRows[setting.key] = switch
            views.settingsList.addView(row)
        }
        views.newCode.setOnClickListener {
            hostAction { viewModel.regenerateCode() }
        }
    }

    private fun renderSettings(state: UnisoundRoomState) {
        renderingSettings = true
        val values = mapOf(
            KEY_GUESTS_ADD to state.settings.guestsCanAdd,
            KEY_GUESTS_CONTROL to state.settings.guestsCanControl,
            KEY_JOIN_OPEN to state.settings.joinOpen,
            KEY_ROOM_FLOW to state.settings.roomFlowEnabled
        )
        for (index in 0 until views.settingsList.childCount) {
            val row = views.settingsList.getChildAt(index)
            val key = row.tag as? String ?: continue
            val value = values[key]
            // Servers without room Flow do not send the setting: no switch that does nothing.
            row.isVisible = value != null
            settingRows[key]?.apply {
                isChecked = value == true
                isEnabled = state.isHost
            }
        }
        renderingSettings = false
        views.newCode.isVisible = state.isHost
        views.settingsNote.isVisible = !state.isHost
    }

    private fun updateSetting(key: String, value: Boolean) {
        val update = when (key) {
            KEY_GUESTS_ADD -> UnisoundSettingsUpdateDTO(guestsCanAdd = value)
            KEY_GUESTS_CONTROL -> UnisoundSettingsUpdateDTO(guestsCanControl = value)
            KEY_JOIN_OPEN -> UnisoundSettingsUpdateDTO(joinOpen = value)
            else -> UnisoundSettingsUpdateDTO(roomFlowEnabled = value)
        }
        hostAction { viewModel.updateSettings(update) }
    }

    // endregion

    // region Actions

    private fun render(state: UnisoundRoomState) {
        renderHeader(state)
        renderNowPlaying(state)
        renderQueue(state)
        renderMembers(state)
        renderSettings(state)
    }

    private fun savePlaylist() {
        val sessionId = viewModel.state.value?.sessionId ?: return
        UnisoundSavePlaylistDialog(requireContext()) { name ->
            val activity = requireActivity()
            activity.lifecycleScope.launch {
                val result = viewModel.savePlaylist(sessionId, name)
                val success = result is UnisoundResult.Success
                activity.showResonantSnackbar(
                    UnisoundSaveMessages.forResult(result),
                    if (success) R.color.successColor else R.color.errorColor,
                    if (success) R.drawable.ic_success else R.drawable.ic_warning
                )
            }
        }.show()
    }

    private fun confirmLeave() {
        val state = viewModel.state.value ?: return
        ResonantDialog(requireContext())
            .setSection("Unisound")
            .setTitle("Salir de la sala")
            .setMessage(
                if (state.isHost) "Si sales, otro miembro pasará a ser el anfitrión. La sala seguirá sin ti."
                else "Dejarás de escuchar con la sala. Podrás volver con el código si sigue abierta."
            )
            .setPositiveButton("Salir") { viewLifecycleOwner.lifecycleScope.launch { viewModel.leaveRoom() } }
            .setNegativeButton("Cancelar")
            .show()
    }

    private fun confirmEnd() {
        ResonantDialog(requireContext())
            .setSection("Unisound")
            .setTitle("Terminar la sala")
            .setMessage("La sala se cerrará para todos los miembros.")
            .setPositiveButton("Terminar") { hostAction { viewModel.endRoom() } }
            .setDestructive()
            .setNegativeButton("Cancelar")
            .show()
    }

    private fun confirmKick(member: UnisoundMemberDTO) {
        ResonantDialog(requireContext())
            .setSection("Unisound")
            .setTitle("Expulsar de la sala")
            .setMessage("${member.name} saldrá de la sala y no podrá volver a entrar.")
            .setPositiveButton("Expulsar") { hostAction { viewModel.kick(member.userId) } }
            .setDestructive()
            .setNegativeButton("Cancelar")
            .show()
    }

    private fun confirmTransfer(member: UnisoundMemberDTO) {
        ResonantDialog(requireContext())
            .setSection("Unisound")
            .setTitle("Ceder el rol de anfitrión")
            .setMessage("${member.name} pasará a ser el anfitrión y tú serás invitado.")
            .setPositiveButton("Hacer anfitrión") { hostAction { viewModel.transferHost(member.userId) } }
            .setNegativeButton("Cancelar")
            .show()
    }

    /** Runs a REST room action and reports its error (if the view is gone, on the activity). */
    private fun hostAction(action: suspend () -> UnisoundResult<*>) {
        val activity = requireActivity()
        activity.lifecycleScope.launch {
            val result = action()
            if (result is UnisoundResult.Failure) {
                activity.showResonantSnackbar(result.message, R.color.errorColor, R.drawable.ic_warning)
            }
        }
    }

    // endregion

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun formatMs(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private data class Setting(val key: String, val title: String, val hint: String)

    companion object {
        const val ARG_SHOW_SHARE = "showShare"
        private const val PROGRESS_TICK_MS = 500L
        private const val DISABLED_ALPHA = 0.4f
        private const val TAB_ICON_DP = 16f
        private const val MAX_QUEUE_ITEMS = 200
        private const val MAX_FLOATING_REACTIONS = 6
        private const val REACTION_DURATION_MS = 2_600L
        private const val REACTION_RISE = 0.7f
        private const val HERO_BLUR_PX = 28f
        private const val BACKDROP_PARALLAX = 0.45f
        private const val COVER_PARALLAX = 0.18f
        private const val HERO_FALLBACK_TINT = 0xFF3A3A44.toInt()
        private const val HERO_TINT_DARKEN = 0.35f
        private const val HERO_TINT_ALPHA = 0x99
        private const val HERO_TINT_FADE_MS = 450L
        private const val MIN_QUERY_LENGTH = 2
        private const val SEARCH_DEBOUNCE_MS = 350L
        private const val SEARCH_HINT =
            "Escribe para buscar canciones, álbumes o playlists."
        private const val KEY_GUESTS_ADD = "guestsCanAdd"
        private const val KEY_GUESTS_CONTROL = "guestsCanControl"
        private const val KEY_JOIN_OPEN = "joinOpen"
        private const val KEY_ROOM_FLOW = "roomFlowEnabled"

        /** Same list as the server default (`Unisound__ReactionEmojis`) and the web. */
        val REACTIONS = listOf("🔥", "❤️", "😂", "👏", "🙌", "😮", "😢", "🎉")

        private val SETTINGS = listOf(
            Setting(KEY_GUESTS_ADD, "Los invitados añaden canciones", "Pueden buscar y añadir a la cola"),
            Setting(
                KEY_GUESTS_CONTROL,
                "Los invitados controlan la reproducción",
                "Play, pausa, saltar, reordenar y quitar de la cola"
            ),
            Setting(KEY_JOIN_OPEN, "Permitir unirse", "Si lo desactivas, nadie nuevo puede entrar"),
            Setting(
                KEY_ROOM_FLOW,
                "Flow de sala",
                "Cuando quedan pocas canciones, se añaden otras que encajen con los gustos de la sala"
            )
        )
    }
}
