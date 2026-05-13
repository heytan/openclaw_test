package com.openclaw.car.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.openclaw.car.R

class ItemListAdapter(
    private val onItemClick: (String) -> Unit = {}
) : ListAdapter<String, ItemListAdapter.ViewHolder>(DiffCallback) {

    companion object DiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
    }

    private val nameZh = mapOf(
        "car-control" to "车载控制",
        "browser-automation" to "浏览器自动化",
        "feishu-doc" to "飞书文档",
        "feishu-drive" to "飞书云盘",
        "feishu-perm" to "飞书权限",
        "feishu-wiki" to "飞书知识库",
        "qqbot-channel" to "QQ频道管理",
        "qqbot-media" to "QQ富媒体",
        "qqbot-remind" to "QQ定时提醒"
    )

    private val descZh = mapOf(
        "car-control" to "控制比亚迪仰望车机应用：音乐、地图、导航",
        "browser-automation" to "网页多步骤操作、登录检测、标签管理",
        "feishu-doc" to "飞书文档读写操作",
        "feishu-drive" to "飞书云空间文件管理",
        "feishu-perm" to "飞书文档与文件权限管理",
        "feishu-wiki" to "飞书知识库导航与查询",
        "qqbot-channel" to "QQ频道管理：成员、发帖、公告、日程",
        "qqbot-media" to "QQBot图片、语音、视频、文件收发",
        "qqbot-remind" to "QQBot一次性与周期性定时提醒"
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_skill, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = getItem(position)
        val parts = line.split("：", ":", limit = 2)
        val key = if (parts.size == 2) parts[0].trim() else line.trim()
        val desc = if (parts.size == 2) parts[1].trim() else ""

        holder.title.text = nameZh[key] ?: key
        holder.desc.text = descZh[key] ?: desc

        val iconRes = when {
            key.startsWith("car-") -> R.drawable.ic_skill_car
            key.startsWith("feishu-") -> R.drawable.ic_skill_feishu
            key.startsWith("qqbot-") -> R.drawable.ic_skill_qq
            else -> R.drawable.ic_skill_tool
        }
        holder.icon.setImageResource(iconRes)

        holder.itemView.setOnClickListener { onItemClick(key) }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_skill_icon)
        val title: TextView = view.findViewById(R.id.tv_item_title)
        val desc: TextView = view.findViewById(R.id.tv_item_desc)
    }
}
