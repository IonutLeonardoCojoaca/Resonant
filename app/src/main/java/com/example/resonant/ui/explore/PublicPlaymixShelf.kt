package com.example.resonant.ui.explore

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.network.PlaymixDTO
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.managers.PlaymixSharingManager
import com.example.resonant.ui.components.DefaultCover
import com.example.resonant.ui.viewmodels.PublicPlaymixesViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * "PlayMix de la comunidad" en Explorar (playmix-sharing-discovery): las PlayMix públicas más populares en una
 * estantería horizontal, con "Ver todas" hacia su pantalla. Oculta con la capacidad apagada, sin PlayMix o si falla
 * la carga. El listado se guarda [CACHE_MS] para que volver a Explorar no lo pida otra vez.
 */
object PublicPlaymixShelf {

    private const val TAG = "PublicPlaymixShelf"
    private const val CACHE_MS = 5 * 60 * 1000L
    private const val LIMIT = 12

    private var cached: List<PlaymixDTO>? = null
    private var cachedAtMs = 0L

    fun bind(
        shelf: View,
        scope: CoroutineScope,
        onOpen: (PlaymixDTO) -> Unit,
        onSeeAll: () -> Unit
    ) {
        val header = shelf.findViewById<View>(R.id.publicPlaymixesHeader)
        header.findViewById<TextView>(R.id.sectionHeaderTitle).text = "PlayMix de la comunidad"
        header.findViewById<TextView>(R.id.sectionHeaderAction).apply {
            text = "Ver todas"
            setOnClickListener { onSeeAll() }
        }
        val adapter = Adapter(onOpen)
        shelf.findViewById<RecyclerView>(R.id.publicPlaymixesList).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
        }

        // Lo que haya en caché se pinta ya; la carga solo cuando ha caducado.
        val fresh = cached?.takeIf { System.currentTimeMillis() - cachedAtMs < CACHE_MS }
        fresh?.let { show(shelf, adapter, it) }
        if (fresh != null) return

        val appContext = shelf.context.applicationContext
        // [scope] es el de la vista de Explorar: si se destruye, la carga se cancela.
        scope.launch {
            val items = load(appContext) ?: return@launch
            show(shelf, adapter, items)
        }
    }

    private suspend fun load(context: Context): List<PlaymixDTO>? {
        if (!CapabilitiesManager.refresh(context).playmixSharing) return null
        return try {
            PlaymixSharingManager(context)
                .getPublicPlaymixes(PublicPlaymixesViewModel.POPULAR, cursor = null, limit = LIMIT)
                .items.orEmpty()
                .also {
                    cached = it
                    cachedAtMs = System.currentTimeMillis()
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "public playmixes unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun show(shelf: View, adapter: Adapter, items: List<PlaymixDTO>) {
        adapter.submitList(items)
        shelf.isVisible = items.isNotEmpty()
    }

    private class Adapter(
        private val onOpen: (PlaymixDTO) -> Unit
    ) : ListAdapter<PlaymixDTO, Adapter.Holder>(Diff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_public_playmix_card, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val cover: ImageView = view.findViewById(R.id.publicPlaymixCover)
            private val name: TextView = view.findViewById(R.id.publicPlaymixName)
            private val meta: TextView = view.findViewById(R.id.publicPlaymixMeta)
            private val likes: TextView = view.findViewById(R.id.publicPlaymixLikes)

            init {
                view.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) onOpen(getItem(position))
                }
            }

            fun bind(playmix: PlaymixDTO) {
                name.text = playmix.name
                val songs = if (playmix.numberOfTracks == 1) "1 canción" else "${playmix.numberOfTracks} canciones"
                val owner = playmix.ownerName?.takeIf { it.isNotBlank() }
                meta.text = if (owner != null) "de $owner · $songs" else songs
                likes.isVisible = playmix.likeCount > 0
                likes.text = playmix.likeCount.toString()
                DefaultCover.load(cover, playmix.coverUrl, DefaultCover.Kind.PLAYMIX, playmix.name, 312)
                val likesText = if (playmix.likeCount > 0) ", ${playmix.likeCount} me gusta" else ""
                itemView.contentDescription = "PlayMix ${playmix.name}, ${meta.text}$likesText"
            }
        }

        private object Diff : DiffUtil.ItemCallback<PlaymixDTO>() {
            override fun areItemsTheSame(oldItem: PlaymixDTO, newItem: PlaymixDTO) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: PlaymixDTO, newItem: PlaymixDTO) = oldItem == newItem
        }
    }
}
