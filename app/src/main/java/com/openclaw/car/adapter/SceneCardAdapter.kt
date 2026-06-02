package com.openclaw.car.adapter

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.openclaw.car.R

data class SceneItem(
    val imageRes: Int,
    val description: String,
    var selected: Boolean = false,
    var expanded: Boolean = false
)

class SceneCardAdapter(
    private val items: List<SceneItem>,
    private val onSelected: (SceneItem, Boolean) -> Unit
) : RecyclerView.Adapter<SceneCardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivScene: ImageView = view.findViewById(R.id.iv_scene)
        val overlaySelected: View = view.findViewById(R.id.overlay_selected)
        val ivCheck: ImageView = view.findViewById(R.id.iv_check)
        val llTextSection: LinearLayout = view.findViewById(R.id.ll_text_section)
        val tvDesc: TextView = view.findViewById(R.id.tv_scene_desc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_proactive_card, parent, false)
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.ivScene.setImageResource(item.imageRes)
        holder.tvDesc.text = item.description

        // Selection state
        holder.overlaySelected.visibility = if (item.selected) View.VISIBLE else View.GONE
        holder.ivCheck.visibility = if (item.selected) View.VISIBLE else View.GONE

        // Expanded state
        holder.llTextSection.visibility = if (item.expanded) View.VISIBLE else View.GONE

        // Click to select/deselect
        holder.itemView.setOnClickListener {
            item.selected = !item.selected
            notifyItemChanged(holder.bindingAdapterPosition)
            onSelected(item, item.selected)
        }

        // Swipe up on image to expand/collapse text
        var touchStartY = 0f
        holder.ivScene.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> touchStartY = event.y
                MotionEvent.ACTION_UP -> {
                    val deltaY = touchStartY - event.y
                    if (deltaY > 60) {
                        item.expanded = !item.expanded
                        holder.llTextSection.visibility = if (item.expanded) View.VISIBLE else View.GONE
                    }
                }
            }
            false
        }
    }

    override fun getItemCount() = items.size
}
