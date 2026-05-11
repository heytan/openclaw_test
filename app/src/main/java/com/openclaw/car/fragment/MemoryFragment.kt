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

class MemoryFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyHint: TextView
    private val adapter = ItemListAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_memory, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.rv_memory_list)
        emptyHint = view.findViewById(R.id.tv_memory_empty)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        loadMemories()
    }

    override fun onResume() {
        super.onResume()
        loadMemories()
    }

    private fun loadMemories() {
        val memories = FileHelper.readLines(FileHelper.memoryFilePath)
        if (memories.isEmpty()) {
            emptyHint.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyHint.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter.submitList(memories)
        }
    }
}
