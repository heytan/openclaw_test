package com.openclaw.car.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.openclaw.car.R
import com.openclaw.car.adapter.ItemListAdapter
import com.openclaw.car.util.FileHelper

class SkillFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyHint: TextView
    private val adapter = ItemListAdapter()

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

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        loadSkills()
    }

    override fun onResume() {
        super.onResume()
        loadSkills()
    }

    private fun loadSkills() {
        val skills = FileHelper.readLines(FileHelper.skillFilePath)
        if (skills.isEmpty()) {
            emptyHint.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyHint.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter.submitList(skills)
        }
    }
}
