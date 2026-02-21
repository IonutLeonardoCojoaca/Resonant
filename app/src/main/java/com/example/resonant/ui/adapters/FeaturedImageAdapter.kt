package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.resonant.R

class FeaturedImageAdapter(
    private var images: List<String> = emptyList()
) : RecyclerView.Adapter<FeaturedImageAdapter.ViewHolder>() {

    class ViewHolder(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_featured_image, parent, false) as ImageView
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val url = images[position]
        Glide.with(holder.imageView.context)
            .load(url)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .centerCrop()
            .placeholder(R.drawable.gradient_bottom_overlay) // Placeholder temporal
            .error(R.drawable.gradient_bottom_overlay)
            .into(holder.imageView)
    }

    override fun getItemCount() = images.size

    fun updateData(newImages: List<String>) {
        images = newImages
        notifyDataSetChanged()
    }
}
