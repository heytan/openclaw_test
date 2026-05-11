package com.openclaw.car.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.openclaw.car.fragment.MemoryFragment
import com.openclaw.car.fragment.PersonaFragment
import com.openclaw.car.fragment.SkillFragment

class ViewPagerAdapter(fragmentManager: FragmentManager, lifecycle: Lifecycle) :
    FragmentStateAdapter(fragmentManager, lifecycle) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> PersonaFragment()
            1 -> SkillFragment()
            2 -> MemoryFragment()
            else -> PersonaFragment()
        }
    }
}
