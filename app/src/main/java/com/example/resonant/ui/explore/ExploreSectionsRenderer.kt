package com.example.resonant.ui.explore

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Genre
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.Song
import com.example.resonant.explore.AriaPick
import com.example.resonant.explore.ExploreSectionUi
import com.example.resonant.ui.adapters.AriaPickAdapter
import com.example.resonant.ui.adapters.ExploreAlbumAdapter
import com.example.resonant.ui.adapters.ExploreArtistAdapter
import com.example.resonant.ui.adapters.ExplorePlaylistAdapter
import com.example.resonant.ui.adapters.ExploreSongAdapter
import com.example.resonant.ui.adapters.GenreAdapter
import com.facebook.shimmer.ShimmerFrameLayout

/**
 * Paints the Explore v2 sections inside a plain vertical container (not a RecyclerView, so the
 * NestedScrollView viewport check keeps working for deferred sections). Views are kept per section
 * id: a new layout reuses, adds, removes and reorders them instead of inflating everything again.
 */
class ExploreSectionsRenderer(
    private val container: LinearLayout,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onSongClick(sectionId: String, songs: List<Song>, index: Int)
        fun onAriaPickClick(sectionId: String, picks: List<AriaPick>, index: Int)
        fun onAriaPickLongClick(pick: AriaPick, anchor: View)
        fun onAlbumClick(album: Album)
        fun onArtistClick(artist: Artist)
        fun onPlaylistClick(playlist: Playlist)
        fun onGenreClick(genre: Genre)
        fun onSeeAllGenres()
        fun onSeeAllPlaylists()
        fun onOpenCollabFinder()
    }

    private val holders = mutableMapOf<String, SectionHolder>()
    private val inflater = LayoutInflater.from(container.context)

    fun render(sections: List<ExploreSectionUi>) {
        val ids = sections.mapTo(mutableSetOf()) { it.id }
        holders.keys.filter { it !in ids }.forEach { id -> holders.remove(id)?.let { container.removeView(it.view) } }

        sections.forEachIndexed { index, section ->
            val holder = holders[section.id]?.takeIf { it.accepts(section) } ?: createHolder(section).also { created ->
                holders.put(section.id, created)?.let { container.removeView(it.view) }
            }
            holder.bind(section)
            if (container.getChildAt(index) !== holder.view) {
                (holder.view.parent as? ViewGroup)?.removeView(holder.view)
                container.addView(holder.view, index)
            }
        }
    }

    /** Ids of pending sections whose view satisfies [isNear] (close to the viewport). */
    fun pendingIdsNear(isNear: (View) -> Boolean): List<String> =
        holders.filter { (_, holder) -> holder.isPending && isNear(holder.view) }.keys.toList()

    fun clear() {
        holders.clear()
        container.removeAllViews()
    }

    private fun createHolder(section: ExploreSectionUi): SectionHolder = when (section) {
        is ExploreSectionUi.CollabFinder -> CollabHolder()
        else -> ListHolder(section)
    }

    private abstract class SectionHolder(val view: View) {
        var isPending: Boolean = false
            protected set

        abstract fun accepts(section: ExploreSectionUi): Boolean
        abstract fun bind(section: ExploreSectionUi)
    }

    private inner class CollabHolder : SectionHolder(
        inflater.inflate(R.layout.view_explore_collab_card, container, false)
    ) {
        init {
            ExploreCollabCard.setOnOpenListener(view, callbacks::onOpenCollabFinder)
        }

        override fun accepts(section: ExploreSectionUi) = section is ExploreSectionUi.CollabFinder

        override fun bind(section: ExploreSectionUi) {
            isPending = section.isPending
            ExploreCollabCard.bind(view, (section as ExploreSectionUi.CollabFinder).items.orEmpty())
        }
    }

    private inner class ListHolder(first: ExploreSectionUi) : SectionHolder(
        inflater.inflate(R.layout.view_explore_dynamic_section, container, false)
    ) {
        private val kind = first::class
        private val header: View = view.findViewById(R.id.dynamicSectionHeader)
        private val title: TextView = header.findViewById(R.id.sectionHeaderTitle)
        private val action: TextView = header.findViewById(R.id.sectionHeaderAction)
        private val subtitle: TextView = view.findViewById(R.id.dynamicSectionSubtitle)
        private val list: RecyclerView = view.findViewById(R.id.dynamicSectionList)
        private val shimmer: ShimmerFrameLayout = view.findViewById(R.id.dynamicSectionShimmer)
        private var sectionId: String = first.id
        private var currentSongs: List<Song> = emptyList()
        private var currentPicks: List<AriaPick> = emptyList()

        private val songAdapter by lazy {
            ExploreSongAdapter { index -> callbacks.onSongClick(sectionId, currentSongs, index) }
        }
        private val ariaAdapter by lazy {
            AriaPickAdapter(
                onPickClick = { index -> callbacks.onAriaPickClick(sectionId, currentPicks, index) },
                onPickLongClick = callbacks::onAriaPickLongClick
            )
        }
        private val albumAdapter by lazy { ExploreAlbumAdapter(callbacks::onAlbumClick) }
        private val artistAdapter by lazy { ExploreArtistAdapter(callbacks::onArtistClick) }
        private val playlistAdapter by lazy { ExplorePlaylistAdapter(callbacks::onPlaylistClick) }
        private val genreAdapter by lazy { GenreAdapter(emptyList(), onGenreClick = callbacks::onGenreClick) }

        init {
            val context = view.context
            list.itemAnimator = null
            if (first is ExploreSectionUi.Genres) {
                list.layoutManager = GridLayoutManager(context, 2)
                list.adapter = genreAdapter
            } else {
                list.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                list.adapter = when (first) {
                    is ExploreSectionUi.Songs -> songAdapter
                    is ExploreSectionUi.AriaPicks -> ariaAdapter
                    is ExploreSectionUi.Albums -> albumAdapter
                    is ExploreSectionUi.Artists -> artistAdapter
                    else -> playlistAdapter
                }
            }
            val seeAll: (() -> Unit)? = when (first) {
                is ExploreSectionUi.Genres -> callbacks::onSeeAllGenres
                is ExploreSectionUi.Playlists -> callbacks::onSeeAllPlaylists
                else -> null
            }
            action.isVisible = seeAll != null
            seeAll?.let { open -> action.setOnClickListener { open() } }
        }

        override fun accepts(section: ExploreSectionUi) = section::class == kind

        override fun bind(section: ExploreSectionUi) {
            sectionId = section.id
            title.text = section.title
            subtitle.text = section.subtitle
            subtitle.isVisible = !section.subtitle.isNullOrBlank()
            isPending = section.isPending
            renderLoading(isPending)
            when (section) {
                is ExploreSectionUi.Songs -> section.items?.let { currentSongs = it; songAdapter.submitList(it) }
                is ExploreSectionUi.AriaPicks -> section.items?.let { currentPicks = it; ariaAdapter.submitList(it) }
                is ExploreSectionUi.Albums -> section.items?.let(albumAdapter::submitList)
                is ExploreSectionUi.Artists -> section.items?.let(artistAdapter::submitList)
                is ExploreSectionUi.Playlists -> section.items?.let(playlistAdapter::submitList)
                is ExploreSectionUi.Genres -> section.items?.let { genreAdapter.updateList(it.take(GENRES_SHOWN)) }
                is ExploreSectionUi.CollabFinder -> Unit
            }
        }

        private fun renderLoading(loading: Boolean) {
            list.isVisible = !loading
            shimmer.isVisible = loading
            if (loading) shimmer.startShimmer() else shimmer.stopShimmer()
        }
    }

    private companion object {
        /** Same compact 2x3 grid as the legacy genre blocks; "Ver todo" opens the full list. */
        const val GENRES_SHOWN = 6
    }
}
