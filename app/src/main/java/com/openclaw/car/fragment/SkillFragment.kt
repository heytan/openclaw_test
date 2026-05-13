package com.openclaw.car.fragment

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.openclaw.car.R
import com.openclaw.car.adapter.ItemListAdapter
import com.openclaw.car.util.FileHelper

class SkillFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyHint: TextView
    private lateinit var adapter: ItemListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_skill, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.rv_skill_list)
        emptyHint = view.findViewById(R.id.tv_skill_empty)

        adapter = ItemListAdapter { skillName -> showSkillDialog(skillName) }
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
        recyclerView.adapter = adapter

        loadSkills()
    }

    override fun onResume() {
        super.onResume()
        loadSkills()
    }

    private fun loadSkills() {
        val skills = FileHelper.getSkills()
        if (skills.isEmpty()) {
            emptyHint.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyHint.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter.submitList(skills)
        }
    }

    private fun showSkillDialog(skillName: String) {
        val content = FileHelper.getSkillDetail(skillName)
        // Strip YAML frontmatter for cleaner display
        val bodyOnly = content.removeFrontmatter()
        val scrollView = ScrollView(requireContext()).apply {
            val tv = TextView(context).apply {
                text = bodyOnly
                textSize = 14f
                setTextColor(0xFF333333.toInt())
                setPadding(24, 16, 24, 16)
                setLineSpacing(4f, 1.1f)
            }
            addView(tv)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(skillName)
            .setView(scrollView)
            .setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun String.removeFrontmatter(): String {
        val lines = this.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return this
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        return if (end >= 0) lines.drop(end + 2).joinToString("\n") else this
    }
}
