package com.example.resonant.ui.fragments

import android.content.Intent
import android.content.res.Configuration
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import com.example.resonant.ui.bottomsheets.CopyTransitionBottomSheet
import com.google.android.material.appbar.AppBarLayout
import kotlin.math.abs
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.playback.PlaymixMixMode
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import kotlin.math.roundToInt
import com.example.resonant.databinding.FragmentPlaymixDetailBinding
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.PlaymixSongTransitionAdapter
import com.example.resonant.ui.bottomsheets.PlaymixOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.PlaymixSongOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.WaveformPreviewBottomSheet
import com.example.resonant.ui.dialogs.PlaymixDialog
import com.example.resonant.ui.dialogs.ResonantDialog
import com.example.resonant.ui.viewmodels.PlaymixDetailViewModel
import com.example.resonant.ui.viewmodels.CollectionDownloadViewModel
import com.example.resonant.ui.bottomsheets.CollectionQueueActions
import com.example.resonant.playback.OfflineCollectionStore
import com.example.resonant.playback.QueueCommands
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.resonant.ui.viewmodels.PlaymixDetailViewModelFactory
import com.example.resonant.ui.components.DefaultCover
import com.example.resonant.ui.components.OnImageTopBar
import com.example.resonant.utils.SnackType
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar

class PlaymixDetailFragment : BaseFragment(R.layout.fragment_playmix_detail) {

    private var _binding: FragmentPlaymixDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: PlaymixDetailViewModel
    private lateinit var adapter: PlaymixSongTransitionAdapter
    private var playmixId: String = ""
    private var isReadOnly: Boolean = false

    // ─── playmix-sharing-discovery ───
    /** Valor del argumento de navegación: solo se usa si el servidor no envía `isOwner` (backend anterior). */
    private var argReadOnly: Boolean = false
    /** Transición a destacar al abrir `/playmix/{id}/transition/{tid}`; se desplaza a ella una vez. */
    private var highlightTransitionId: String? = null
    private var scrolledToHighlight = false
    private lateinit var sharingViewModel: com.example.resonant.ui.viewmodels.PlaymixSharingViewModel
    private lateinit var sharingUi: PlaymixDetailSharing

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlaymixDetailBinding.bind(view)

        playmixId = arguments?.getString("playmixId") ?: return
        isReadOnly = arguments?.getBoolean("isReadOnly", false) ?: false
        argReadOnly = isReadOnly
        highlightTransitionId = arguments?.getString("highlightTransitionId")
        setupSharing()

        // Contexto de aplicación: el ViewModel sobrevive a la vista y no debe retener la Activity.
        val playmixManager = PlaymixManager(requireContext().applicationContext)
        viewModel = ViewModelProvider(this, PlaymixDetailViewModelFactory(playmixManager))
            .get(PlaymixDetailViewModel::class.java)
        viewModel.offlineContext = requireContext().applicationContext
        collectionDownloads = ViewModelProvider(requireActivity())[CollectionDownloadViewModel::class.java]

        setupRecyclerView()
        setupListeners()
        setupParallaxTitle()
        observeViewModel()
        observeDownload()
        enableSharingWhenCapabilitiesArrive()

        // Lo que la lista ya sabía (nombre, portada, nº de canciones): la cabecera no espera a la red.
        if (viewModel.screenState.value?.detail == null) PlaymixDetailArgs.preview(arguments)?.let(::bindPreview)
        openedAtMs = android.os.SystemClock.elapsedRealtime()
        viewModel.loadPlaymixDetail(playmixId)
        // Descargada y con red: se sincroniza al abrir (collection-queue-offline D6).
        collectionDownloads.refresh(OfflineCollectionStore.TYPE_PLAYMIX, playmixId)
    }

    private lateinit var collectionDownloads: CollectionDownloadViewModel

    /** Medición (debug): ms desde abrir la pantalla hasta pintar el detalle, y desde pulsar play hasta tener la cola. */
    private var openedAtMs = 0L

    private fun logTiming(what: String, sinceMs: Long) {
        if (sinceMs == 0L || !com.example.resonant.BuildConfig.DEBUG) return
        android.util.Log.d("PlaymixTiming", "$what after ${android.os.SystemClock.elapsedRealtime() - sinceMs} ms")
    }

    /** Chip con el estado de la descarga: descargando, descargada, pendiente de actualizar o ya no disponible. */
    private fun observeDownload() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    collectionDownloads.stateFor(OfflineCollectionStore.TYPE_PLAYMIX, playmixId).collect { state ->
                        val chip = _binding?.playmixDownloadChip ?: return@collect
                        chip.isVisible = state.isDownloaded
                        chip.text = when {
                            state.unavailable -> "Ya no disponible"
                            state.isRunning -> "Descargando ${state.progress.percent} %"
                            state.pendingUpdate -> "Pendiente de actualizar"
                            !state.progress.isComplete -> "Descargando ${state.progress.percent} %"
                            else -> "Descargada"
                        }
                    }
                }
                launch {
                    collectionDownloads.events.collect { message ->
                        showResonantSnackbar(message, SnackType.INFO)
                    }
                }
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = PlaymixSongTransitionAdapter(
            onTransitionClick = { transition -> navigateToCrossfadeEditor(transition) },
            onPreviewClick = { transition ->
                WaveformPreviewBottomSheet(playmixId, transition)
                    .show(childFragmentManager, "WaveformPreview")
            },
            onCopyTransitionClick = { transition ->
                showCopyTransitionDialog(transition)
            },
            onSongClick = { song ->
                playPlaymix(startSongId = song.songId)
            },
            onSongOptionsClick = { song ->
                PlaymixSongOptionsBottomSheet(
                    song = song,
                    onPlayClick = { s -> playPlaymix(startSongId = s.songId) },
                    onSeeSongClick = { s ->
                        val bundle = Bundle().apply { putString("songId", s.songId) }
                        findNavController().navigate(R.id.songFragment, bundle)
                    },
                    onRemoveClick = { s ->
                        ResonantDialog(requireContext())
                            .setSection("PlayMix")
                            .setTitle("Eliminar canción")
                            .setMessage("¿Eliminar \"${s.title ?: "esta canción"}\" del PlayMix?")
                            .setDestructive()
                            .setPositiveButton("Eliminar") {
                                viewModel.removeSong(s.playmixSongId) {
                                    showResonantSnackbar(
                                        text = s.title ?: "Canción eliminada",
                                        type = SnackType.SUCCESS,
                                        title = "Eliminada de la PlayMix"
                                    )
                                }
                            }
                            .setNegativeButton("Cancelar")
                            .show()
                    }
                ).show(childFragmentManager, "PlaymixSongOptions")
            },
            isReadOnly = isReadOnly,
            onShareTransitionClick = if (sharingUi.enabled) ({ transition -> sharingUi.shareTransition(transition) }) else null
        )
        binding.songTransitionList.layoutManager = LinearLayoutManager(requireContext())
        binding.songTransitionList.setHasFixedSize(true)
        binding.songTransitionList.adapter = adapter
    }

    private fun setupParallaxTitle() {
        OnImageTopBar.prepare(binding.arrowGoBackButton, binding.settingsButton)
        // Fondo de la barra (primaryColorTheme): transparente sobre la portada, opaco al plegar.
        val barBackground = binding.topBar.background.mutate().apply { alpha = 0 }
        setLightStatusBarIcons(false)
        // Se llama en cada frame de scroll: solo se toca lo que cambia (en las mesetas, nada).
        var lastCollapsed = -1f
        var lastDataAlpha = -1f
        binding.appBarLayout.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { appBar, offset ->
            if (appBar.totalScrollRange == 0) return@OnOffsetChangedListener
            val ratio = abs(offset).toFloat() / appBar.totalScrollRange.toFloat()
            // Último 25 % del plegado (como ChartHero/DetailHero): título, fondo y paso a los iconos del tema.
            val collapsed = ((ratio - 0.75f) / 0.25f).coerceIn(0f, 1f)
            if (collapsed != lastCollapsed) {
                lastCollapsed = collapsed
                binding.playmixTitle.alpha = collapsed
                barBackground.alpha = (collapsed * 255).roundToInt()
                OnImageTopBar.update(collapsed, binding.arrowGoBackButton, binding.settingsButton)
                // Sobre la portada, iconos claros; con la barra opaca (primaryColorTheme), los del tema.
                setLightStatusBarIcons(collapsed >= 0.5f && !isNightMode())
            }
            // Los datos se desvanecen antes de llegar a la barra: no se amontonan bajo ella ni bajo la de estado.
            val dataAlpha = 1f - ((ratio - 0.35f) / 0.4f).coerceIn(0f, 1f)
            if (dataAlpha != lastDataAlpha) {
                lastDataAlpha = dataAlpha
                binding.playmixDataContainer.alpha = dataAlpha
            }
        })
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** [light] = iconos oscuros (fondo claro). Se restaura al valor del tema en onDestroyView. */
    private var lastLightStatusBarIcons: Boolean? = null

    private fun setLightStatusBarIcons(light: Boolean) {
        if (lastLightStatusBarIcons == light) return
        lastLightStatusBarIcons = light
        val window = activity?.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (controller.isAppearanceLightStatusBars != light) controller.isAppearanceLightStatusBars = light
    }

    private fun setupListeners() {
        binding.arrowGoBackButton.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.settingsButton.setOnClickListener {
            showSettingsMenu()
        }
        // También en una Playmix ajena: cola y descarga (collection-queue-offline). Editar y borrar solo el dueño.
        binding.settingsButton.visibility = View.VISIBLE
        // Sin detalle no hay cola que preparar ni menú que montar: se habilitan cuando llega.
        binding.playButton.isEnabled = false
        binding.settingsButton.isEnabled = false

        binding.playButton.setOnClickListener {
            playPlaymix()
        }
    }

    /** Una sola preparación de cola a la vez, la pida el botón, una fila o la hoja de opciones. */
    private var playJob: Job? = null

    private fun playPlaymix(startSongId: String? = null) {
        if (playJob?.isActive == true) return
        val b = _binding ?: return
        b.playButton.isEnabled = false
        b.playButton.alpha = 0.5f
        val tappedAtMs = android.os.SystemClock.elapsedRealtime()

        playJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = viewModel.fetchSongsForPlayback(startSongId)
                logTiming("queue ready (${result?.first?.size ?: 0} songs)", tappedAtMs)
                val b2 = _binding ?: return@launch
                b2.playButton.isEnabled = true
                b2.playButton.alpha = 1f

                if (result == null) {
                    showResonantSnackbar("No se pudieron cargar las canciones", SnackType.ERROR)
                    return@launch
                }

                val (songs, index) = result
                val songList = ArrayList(songs)
                val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_PLAY
                    putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, songList[index])
                    putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, index)
                    putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songList)
                    putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, QueueSource.PLAYMIX)
                    putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, playmixId)
                }
                requireContext().startService(playIntent)
            } catch (e: Exception) {
                val b2 = _binding ?: return@launch
                b2.playButton.isEnabled = true
                b2.playButton.alpha = 1f
                showResonantSnackbar("Error al cargar las canciones de la PlayMix", SnackType.ERROR)
            }
        }
    }

    private fun observeViewModel() {
        viewModel.screenState.observe(viewLifecycleOwner) { state ->
            val hasDetail = state.detail != null
            // Esqueleto solo mientras no hay nada que pintar; una revalidación con detalle en pantalla es silenciosa.
            val skeleton = binding.detailSkeleton.root
            val showSkeleton = state.isLoading && !hasDetail && !state.notFound
            skeleton.isVisible = showSkeleton
            if (showSkeleton) skeleton.startShimmer() else skeleton.stopShimmer()
            binding.playButton.isEnabled = hasDetail && playJob?.isActive != true
            binding.playButton.alpha = if (binding.playButton.isEnabled) 1f else 0.5f
            binding.settingsButton.isEnabled = hasDetail

            binding.playmixUnavailableText.isVisible = state.notFound
            if (state.notFound) {
                binding.songTransitionList.visibility = View.GONE
                binding.noSongsText.visibility = View.GONE
                binding.settingsButton.visibility = View.GONE
                binding.playButton.visibility = View.GONE
            }

            state.detail?.let { detail ->
                logTiming("detail painted", openedAtMs)
                openedAtMs = 0L
                applyOwnership(detail)
                bindHeader(detail.name, detail.description, detail.numberOfTracks, detail.duration)
                bindCover(detail.coverUrl, detail.name)
                bindBlendSummary(detail)

                if (detail.songs.isEmpty()) {
                    binding.noSongsText.visibility = View.VISIBLE
                    binding.songTransitionList.visibility = View.GONE
                    adapter.submitList(emptyList())
                } else {
                    binding.noSongsText.visibility = View.GONE
                    binding.songTransitionList.visibility = View.VISIBLE
                    adapter.submitDetail(detail)
                    scrollToHighlightedTransition()
                }
            }
        }

        // Un solo canal de error, consumido: antes `screenState.error` y `error` mostraban el mismo fallo dos
        // veces y lo repetían en cada recreación de la vista.
        viewModel.error.observe(viewLifecycleOwner) { msg ->
            msg?.let {
                showResonantSnackbar(it, SnackType.ERROR)
                viewModel.consumeError()
            }
        }
    }

    /** Cabecera con lo que la lista ya sabía, antes de que responda la red (PlaymixDetailArgs). */
    private fun bindPreview(preview: PlaymixDetailArgs.Preview) {
        bindHeader(preview.name, preview.description, preview.trackCount, preview.duration)
        bindCover(preview.coverUrl, preview.name)
    }

    private fun bindHeader(name: String, description: String?, trackCount: Int, durationSeconds: Int) {
        binding.playmixTitle.text = name
        binding.playmixName.text = name
        if (!description.isNullOrEmpty()) {
            binding.playmixDescription.text = description
            binding.playmixDescription.visibility = View.VISIBLE
        } else {
            binding.playmixDescription.visibility = View.GONE
        }
        binding.playmixNumberOfTracks.text = if (trackCount == 1) "1 canción" else "$trackCount canciones"
        binding.playmixDuration.text = formatDuration(durationSeconds)
    }

    private var boundCoverKey: String? = null
    private var coverBlurApplied = false

    /** Sin canciones no hay portada: la tarjeta por defecto, desenfocada igual que una portada. Solo si cambia. */
    private fun bindCover(coverUrl: String?, name: String) {
        val key = "$coverUrl|$name"
        if (key == boundCoverKey) return
        boundCoverKey = key
        DefaultCover.load(binding.playmixCoverImage, coverUrl, DefaultCover.Kind.PLAYMIX, name)
        if (!coverBlurApplied) {
            coverBlurApplied = true
            binding.playmixCoverImage.setRenderEffect(RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP))
        }
    }

    /** Resumen de cómo se mezclan las parejas del set (automático / avanzada), en la píldora de la cabecera. */
    private fun bindBlendSummary(detail: PlaymixDetailDTO) {
        val pairs = detail.songs.sortedBy { it.position }.zipWithNext()
        val automatic = pairs.count { (from, to) ->
            PlaymixMixMode.isSmart(detail.transitions.find {
                it.fromPlaymixSongId == from.playmixSongId && it.toPlaymixSongId == to.playmixSongId
            })
        }
        val advanced = pairs.size - automatic
        binding.playmixBlendSummary.isVisible = pairs.isNotEmpty()
        binding.playmixBlendSummary.text = when {
            advanced == 0 -> "Blend automático"
            automatic == 0 -> if (advanced == 1) "1 transición avanzada" else "$advanced transiciones avanzadas"
            else -> "$advanced avanzadas · $automatic auto"
        }
    }

    private fun showSettingsMenu() {
        val detail = viewModel.screenState.value?.detail ?: return
        val download = collectionDownloads.states.value["${OfflineCollectionStore.TYPE_PLAYMIX}:$playmixId"]
        val ownerActions = !isReadOnly
        PlaymixOptionsBottomSheet(
            detail = detail,
            onPlayClick = { playPlaymix() },
            onPlayNextClick = {
                CollectionQueueActions.playNext(this, QueueCommands.COLLECTION_PLAYMIX, playmixId)
            },
            onAddToQueueClick = {
                CollectionQueueActions.addToQueue(this, QueueCommands.COLLECTION_PLAYMIX, playmixId)
            },
            isDownloaded = download?.isDownloaded == true,
            onDownloadClick = {
                collectionDownloads.download(OfflineCollectionStore.TYPE_PLAYMIX, playmixId, detail.name)
            },
            onRemoveDownloadClick = {
                ResonantDialog(requireContext())
                    .setSection("PlayMix")
                    .setTitle("Quitar descarga")
                    .setMessage("Se borrarán sus canciones y mezclas del dispositivo, salvo las que use otra descarga.")
                    .setDestructive()
                    .setPositiveButton("Quitar") {
                        collectionDownloads.remove(OfflineCollectionStore.TYPE_PLAYMIX, playmixId, detail.name)
                    }
                    .setNegativeButton("Cancelar")
                    .show()
            },
            onEditNameClick = if (ownerActions) ({ showRenameDialog(detail.name) }) else null,
            onDeleteClick = if (!ownerActions) null else ({
                ResonantDialog(requireContext())
                    .setSection("PlayMix")
                    .setTitle("Eliminar PlayMix")
                    .setMessage("¿Estás seguro de que quieres eliminar \"${detail.name}\"? Esta acción no se puede deshacer.")
                    .setDestructive()
                    .setPositiveButton("Eliminar") {
                        viewModel.deletePlaymix(
                            onSuccess = {
                                showResonantSnackbar(detail.name, SnackType.SUCCESS, title = "PlayMix eliminada")
                                findNavController().popBackStack()
                            },
                            onError = { msg ->
                                showResonantSnackbar(msg, SnackType.ERROR, title = "No se pudo eliminar la PlayMix")
                            }
                        )
                    }
                    .setNegativeButton("Cancelar")
                    .show()
            }),
            sharing = sharingUi.sheetSharing(detail, isOwner = !isReadOnly)
        ).show(childFragmentManager, "PlaymixOptions")
    }

    private fun showRenameDialog(currentName: String) {
        PlaymixDialog(requireContext())
            .setIcon(R.drawable.ic_edit)
            .setEyebrow("PlayMix")
            .setTitle("Editar nombre")
            // Mismo límite que al crearla (CreatePlaymixFragment.MAX_NAME).
            .setInput(currentName, hint = "Nombre de la PlayMix", maxLength = PLAYMIX_NAME_MAX) { name ->
                if (name.isBlank()) "El nombre no puede estar vacío" else null
            }
            .setPrimary("Guardar") { newName ->
                viewModel.renamePlaymix(
                    newName = newName,
                    onSuccess = {
                        // La respuesta puede llegar con la vista ya destruida.
                        _binding?.let {
                            it.playmixName.text = newName
                            it.playmixTitle.text = newName
                        }
                        showResonantSnackbar(newName, SnackType.SUCCESS, title = "Nombre actualizado")
                    },
                    onError = { msg ->
                        showResonantSnackbar(msg, SnackType.ERROR, title = "No se pudo cambiar el nombre")
                    }
                )
            }
            .setTertiary("Cancelar")
            .show()
    }

    private fun navigateToCrossfadeEditor(transition: PlaymixTransitionDTO) {
        // Playmix ajena: solo la vista previa, nunca el editor (playmix-sharing-discovery).
        if (isReadOnly) {
            WaveformPreviewBottomSheet(playmixId, transition).show(childFragmentManager, "WaveformPreview")
            return
        }
        val bundle = Bundle().apply {
            putString("playmixId", playmixId)
            putString("transitionId", transition.id)
        }
        findNavController().navigate(R.id.action_playmixDetailFragment_to_crossfadeEditorFragment, bundle)
    }

    private fun showCopyTransitionDialog(transition: PlaymixTransitionDTO) {
        val songs = viewModel.screenState.value?.detail?.songs
        val songA = songs?.find { it.playmixSongId == transition.fromPlaymixSongId }?.title ?: "Canción A"
        val songB = songs?.find { it.playmixSongId == transition.toPlaymixSongId }?.title ?: "Canción B"
        CopyTransitionBottomSheet(transition, playmixId, songA, songB)
            .show(childFragmentManager, "CopyTransition")
    }

    // ─── Compartir y descubrir (playmix-sharing-discovery) ───

    private fun setupSharing() {
        sharingViewModel = ViewModelProvider(
            this,
            com.example.resonant.ui.viewmodels.PlaymixSharingViewModelFactory(
                com.example.resonant.managers.PlaymixSharingManager(requireContext())
            )
        ).get(com.example.resonant.ui.viewmodels.PlaymixSharingViewModel::class.java)
        sharingUi = PlaymixDetailSharing(
            fragment = this,
            viewModel = sharingViewModel,
            currentDetail = { viewModel.screenState.value?.detail },
            reload = { viewModel.loadPlaymixDetail(playmixId, forceReload = true) }
        )
        sharingViewModel.like.observe(viewLifecycleOwner) { like ->
            _binding?.let { sharingUi.bindLike(like, it.playmixLikeButton) }
        }
        sharingViewModel.message.observe(viewLifecycleOwner) { message ->
            message ?: return@observe
            showResonantSnackbar(message, SnackType.INFO)
            sharingViewModel.consumeMessage()
        }
    }

    /**
     * Abierta desde un enlace con la app cerrada, el detalle puede llegar antes que las capacidades: se piden
     * (con caché) y, si compartir pasa a estar activo, se muestran la fila social y el compartir de transiciones.
     */
    private fun enableSharingWhenCapabilitiesArrive() {
        if (sharingUi.enabled) return
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { com.example.resonant.managers.CapabilitiesManager.refresh(appContext) }
            if (_binding == null || !sharingUi.enabled) return@launch
            adapter.onShareTransitionClick = { transition -> sharingUi.shareTransition(transition) }
            viewModel.screenState.value?.detail?.let(::applyOwnership)
        }
    }

    /** `isOwner` del servidor decide el modo solo lectura; con un backend anterior, el argumento de navegación. */
    private fun applyOwnership(detail: PlaymixDetailDTO) {
        isReadOnly = detail.isOwner?.let { !it } ?: argReadOnly
        adapter.isReadOnly = isReadOnly
        sharingUi.bindHeader(
            detail = detail,
            isOwner = !isReadOnly,
            ownerText = binding.playmixOwnerText,
            socialRow = binding.playmixSocialRow,
            visibilityChip = binding.playmixVisibilityChip,
            likeButton = binding.playmixLikeButton,
            shareButton = binding.playmixShareButton
        )
    }

    private fun scrollToHighlightedTransition() {
        val transitionId = highlightTransitionId ?: return
        adapter.highlightTransitionId = transitionId
        if (scrolledToHighlight) return
        scrolledToHighlight = true
        binding.songTransitionList.post {
            val b = _binding ?: return@post
            val position = adapter.positionOfTransition(transitionId)
            if (position < 0) return@post
            b.appBarLayout.setExpanded(false, true)
            // La lista recicla: la fila puede no existir aún, así que se pide la posición al LayoutManager.
            val offset = (16 * resources.displayMetrics.density).toInt()
            (b.songTransitionList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, offset)
        }
    }

    private fun formatDuration(seconds: Int): String {
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            String.format("%dh %02dmin", hrs, mins)
        } else {
            String.format("%d:%02d", mins, secs)
        }
    }

    override fun onDestroyView() {
        // Devolver los iconos de la barra de estado como los deja enableEdgeToEdge para el resto de pantallas.
        setLightStatusBarIcons(!isNightMode())
        super.onDestroyView()
        _binding = null
    }
}

/** Largo máximo del nombre de una PlayMix: el mismo que al crearla (`CreatePlaymixFragment.MAX_NAME`). */
private const val PLAYMIX_NAME_MAX = 30
