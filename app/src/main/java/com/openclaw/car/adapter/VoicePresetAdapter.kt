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
    private val onPreviewClicked: (Int) -> Unit,
    private val onCloneClicked: () -> Unit = {},
    private val onRerecordClicked: () -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val presets = FileHelper.VOICE_PRESETS.toSortedMap()
    private var selectedIndex: Int = 0
    var hasCloneAudio: Boolean = false

    private val TYPE_PRESET = 0
    private val TYPE_CLONE = 1

    inner class PresetViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLabel: TextView = view.findViewById(R.id.tv_voice_label)
        val btnPreview: ImageButton = view.findViewById(R.id.btn_preview)
        val ivSelected: ImageView = view.findViewById(R.id.iv_selected)
    }

    inner class CloneViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLabel: TextView = view.findViewById(R.id.tv_voice_label)
        val btnRerecord: ImageButton = view.findViewById(R.id.btn_rerecord)
        val ivSelected: ImageView = view.findViewById(R.id.iv_selected)
    }

    override fun getItemCount() = presets.size + 1

    override fun getItemViewType(position: Int): Int {
        return if (position == presets.size) TYPE_CLONE else TYPE_PRESET
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_voice_preset, parent, false)
        return if (viewType == TYPE_CLONE) CloneViewHolder(view) else PresetViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is PresetViewHolder) {
            val index = presets.keys.elementAt(position)
            val preset = presets[index] ?: return

            holder.tvLabel.text = preset.label
            holder.itemView.isActivated = (index == selectedIndex)
            holder.ivSelected.visibility = if (index == selectedIndex) View.VISIBLE else View.GONE

            holder.itemView.setOnClickListener { onPresetSelected(index) }
            holder.btnPreview.setOnClickListener { onPreviewClicked(index) }
        } else if (holder is CloneViewHolder) {
            holder.tvLabel.text = "我的音色"
            holder.itemView.isActivated = (selectedIndex == CLONE_INDEX)
            holder.ivSelected.visibility = if (selectedIndex == CLONE_INDEX) View.VISIBLE else View.GONE
            holder.btnRerecord.visibility = if (hasCloneAudio) View.VISIBLE else View.GONE
            holder.btnRerecord.setOnClickListener { onRerecordClicked() }

            holder.itemView.setOnClickListener { onCloneClicked() }
        }
    }

    fun setSelectedIndex(index: Int) {
        val old = selectedIndex
        selectedIndex = index
        notifyItemChanged(if (old == CLONE_INDEX) presets.size else presets.keys.indexOf(old))
        notifyItemChanged(if (index == CLONE_INDEX) presets.size else presets.keys.indexOf(index))
    }

    companion object {
        const val CLONE_INDEX = 99
    }
}
