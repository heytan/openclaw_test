package com.openclaw.car.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.openclaw.car.R
import android.widget.TextView

/**
 * Generic read-only list adapter used by both SkillFragment and MemoryFragment.
 * Each item shows a title and description derived from the file line.
 */
class ItemListAdapter : ListAdapter<String, ItemListAdapter.ViewHolder>(DiffCallback) {

    companion object DiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_skill, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = getItem(position)
        // Split on first colon or separator to extract title + description
        val parts = line.split("：", ":", limit = 2)
        when (parts.size) {
            2 -> {
                holder.title.text = parts[0].trim()
                holder.desc.text = parts[1].trim()
            }
            else -> {
                holder.title.text = line.trim()
                holder.desc.text = ""
            }
        }
    }

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tv_item_title)
        val desc: TextView = view.findViewById(R.id.tv_item_desc)
    }
}
