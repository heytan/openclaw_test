package com.openclaw.car.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.openclaw.car.R
import com.openclaw.car.util.FileHelper

class VoicePresetAdapter(
    private val onPresetSelected: (Int) -> Unit,
    private val onPreviewClicked: (Int) -> Unit
) : RecyclerView.Adapter<VoicePresetAdapter.ViewHolder>() {

    private val presets = FileHelper.VOICE_PRESETS.toSortedMap()
    private var selectedIndex: Int = 0

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLabel: TextView = view.findViewById(R.id.tv_voice_label)
        val btnPreview: ImageButton = view.findViewById(R.id.btn_preview)
        val ivSelected: ImageView = view.findViewById(R.id.iv_selected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_voice_preset, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val index = presets.keys.elementAt(position)
        val preset = presets[index] ?: return

        holder.tvLabel.text = preset.label
        holder.itemView.isActivated = (index == selectedIndex)
        holder.ivSelected.visibility = if (index == selectedIndex) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onPresetSelected(index) }
        holder.btnPreview.setOnClickListener { onPreviewClicked(index) }
    }

    override fun getItemCount() = presets.size

    fun setSelectedIndex(index: Int) {
        val old = selectedIndex
        selectedIndex = index
        if (old != index) {
            notifyItemChanged(presets.keys.indexOf(old))
            notifyItemChanged(presets.keys.indexOf(index))
        }
    }
}
