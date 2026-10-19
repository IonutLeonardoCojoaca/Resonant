package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.resonant.R
import com.example.resonant.data.network.PlaymixDTO
import com.example.resonant.managers.PlaymixSharingManager
import com.example.resonant.ui.components.ChartHero
import com.example.resonant.ui.components.DefaultCover
import com.example.resonant.ui.components.ResonantAccent
import com.example.resonant.ui.viewmodels.PublicPlaymixesViewModel
import com.example.resonant.ui.viewmodels.PublicPlaymixesViewModelFactory
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.chip.ChipGroup

/**
 * PlayMix públicas (playmix-sharing-discovery), copiado de [PublicPlaylistsFragment]: Recientes / Populares,
 * paginación por cursor y detalle en solo lectura (lo decide `isOwner`). Solo se llega con la capacidad activa.
 */
class PublicPlaymixesFragment : Fragment() {

    private lateinit var viewModel: PublicPlaymixesViewModel
    private lateinit var adapter: PublicPlaymixAdapter

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var list: RecyclerView
    private lateinit var shimmerLoading: ShimmerFrameLayout
    private lateinit var stateContainer: View
    private lateinit var tvStateTitle: TextView
    private lateinit var tvStateSubtitle: TextView
    private lateinit var btnStateAction: View
    private lateinit var hero: View
    private lateinit var chipGroupSort: ChipGroup

    private var userRefreshing = false
    private var heroAnimated = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_public_playmixes, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this, PublicPlaymixesViewModelFactory(PlaymixSharingManager(requireContext())))
            .get(PublicPlaymixesViewModel::class.java)

        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        list = view.findViewById(R.id.rvPublicPlaymixes)
        shimmerLoading = view.findViewById(R.id.shimmerLoading)
        stateContainer = view.findViewById(R.id.stateContainer)
        tvStateTitle = view.findViewById(R.id.tvStateTitle)
        tvStateSubtitle = view.findViewById(R.id.tvStateSubtitle)
        btnStateAction = view.findViewById(R.id.btnStateAction)
        hero = view.findViewById(R.id.chartHero)
        chipGroupSort = view.findViewById(R.id.chipGroupSort)
        val appBarLayout: AppBarLayout = view.findViewById(R.id.appBarLayout)
        view.findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener { findNavController().navigateUp() }

        swipeRefreshLayout.setProgressBackgroundColorSchemeResource(R.color.primaryColorTheme)
        swipeRefreshLayout.setColorSchemeResources(R.color.secondaryColorTheme)
        swipeRefreshLayout.setOnRefreshListener {
            userRefreshing = true
            viewModel.load(force = true)
        }
        btnStateAction.setOnClickListener { viewModel.load(force = true) }

        ChartHero.bind(
            hero,
            ResonantAccent.PLAYMIX,
            eyebrow = "PlayMix públicas",
            title = "Comunidad",
            subtitle = "Sesiones con transiciones, listas para escuchar o copiar."
        )
        ChartHero.attachParallax(appBarLayout, hero)
        if (!heroAnimated) {
            ChartHero.animateIn(hero)
            heroAnimated = true
        }

        adapter = PublicPlaymixAdapter { playmix ->
            findNavController().navigate(
                R.id.action_global_to_playmixDetailFragment,
                Bundle().apply { putString("playmixId", playmix.id) }
            )
        }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.itemAnimator = null
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (dy > 0 && lm.findLastVisibleItemPosition() >= adapter.itemCount - 5) viewModel.loadMore()
            }
        })

        chipGroupSort.check(
            if (viewModel.state.value?.sort == PublicPlaymixesViewModel.POPULAR) R.id.chipSortPopular else R.id.chipSortRecent
        )
        chipGroupSort.setOnCheckedStateChangeListener { _, checkedIds ->
            val sort = if (checkedIds.firstOrNull() == R.id.chipSortPopular) PublicPlaymixesViewModel.POPULAR
            else PublicPlaymixesViewModel.RECENT
            viewModel.load(sort)
        }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            adapter.submitList(state.items.orEmpty())
            val hasRows = !state.items.isNullOrEmpty()
            swipeRefreshLayout.isRefreshing = state.isLoading && userRefreshing
            if (!state.isLoading) userRefreshing = false
            val showSkeleton = state.isLoading && !hasRows && !userRefreshing
            shimmerLoading.isVisible = showSkeleton
            if (showSkeleton) shimmerLoading.startShimmer() else shimmerLoading.stopShimmer()
            val showState = !state.isLoading && !hasRows && (state.items != null || state.error)
            stateContainer.isVisible = showState
            if (showState) {
                btnStateAction.isVisible = state.error
                tvStateTitle.text = if (state.error) "No se pudieron cargar las PlayMix" else "No hay PlayMix públicas"
                tvStateSubtitle.text = if (state.error) "Revisa tu conexión e inténtalo de nuevo"
                else "Haz pública una de las tuyas para que aparezca aquí"
            }
        }

        viewModel.load()
    }

    /** Filas con el diseño de `item_playmix`: dueño, canciones y me gusta. */
    private class PublicPlaymixAdapter(
        private val onClick: (PlaymixDTO) -> Unit
    ) : ListAdapter<PlaymixDTO, PublicPlaymixAdapter.Holder>(Diff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_playmix, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val cover: ImageView = view.findViewById(R.id.playmixCover)
            private val name: TextView = view.findViewById(R.id.playmixName)
            private val info: TextView = view.findViewById(R.id.playmixInfo)

            init {
                view.findViewById<View>(R.id.settingsPlaymix).visibility = View.GONE
                view.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos))
                }
            }

            fun bind(playmix: PlaymixDTO) {
                name.text = playmix.name
                val songs = if (playmix.numberOfTracks == 1) "1 canción" else "${playmix.numberOfTracks} canciones"
                val owner = playmix.ownerName?.takeIf { it.isNotBlank() }?.let { "de $it · " }.orEmpty()
                val likes = if (playmix.likeCount > 0) " · ♥ ${playmix.likeCount}" else ""
                info.text = "$owner$songs$likes"
                DefaultCover.load(cover, playmix.coverUrl, DefaultCover.Kind.PLAYMIX, playmix.name, 240)
            }
        }

        private object Diff : DiffUtil.ItemCallback<PlaymixDTO>() {
            override fun areItemsTheSame(oldItem: PlaymixDTO, newItem: PlaymixDTO) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: PlaymixDTO, newItem: PlaymixDTO) = oldItem == newItem
        }
    }
}
