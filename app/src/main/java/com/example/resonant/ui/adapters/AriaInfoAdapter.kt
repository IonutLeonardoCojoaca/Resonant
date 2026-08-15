package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.aria.AriaIntent
import com.example.resonant.aria.AriaIntentCategory

class AriaInfoAdapter(private val items: List<Any>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_CATEGORY = 0
        private const val TYPE_INTENT = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is AriaIntentCategory -> TYPE_CATEGORY
            is AriaIntent -> TYPE_INTENT
            else -> throw IllegalArgumentException("Invalid type of data")
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_CATEGORY -> {
                val view = inflater.inflate(R.layout.item_aria_info_category, parent, false)
                CategoryViewHolder(view)
            }
            TYPE_INTENT -> {
                val view = inflater.inflate(R.layout.item_aria_info_intent, parent, false)
                IntentViewHolder(view)
            }
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is CategoryViewHolder -> holder.bind(item as AriaIntentCategory)
            is IntentViewHolder -> holder.bind(item as AriaIntent)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.categoryIcon)
        private val titleView: TextView = itemView.findViewById(R.id.categoryTitle)

        fun bind(category: AriaIntentCategory) {
            iconView.setImageResource(category.iconRes)
            titleView.text = category.title
        }
    }

    inner class IntentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.intentTitle)
        private val descView: TextView = itemView.findViewById(R.id.intentDescription)

        fun bind(intent: AriaIntent) {
            titleView.text = intent.id
            descView.text = intent.description
        }
    }
}
