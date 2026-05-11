package com.openclaw.car

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.openclaw.car.util.FileHelper

class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        // Force light mode — car head unit only
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewPager = findViewById(R.id.view_pager)
        tabLayout = findViewById(R.id.tab_layout)

        val adapter = com.openclaw.car.adapter.ViewPagerAdapter(supportFragmentManager, lifecycle)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_persona)
                1 -> getString(R.string.tab_skill)
                2 -> getString(R.string.tab_memory)
                else -> ""
            }
        }.attach()

        // Initialize storage for current mode
        FileHelper.init(this)

        // Debug / Production mode toggle
        val switchDebug: MaterialSwitch = findViewById(R.id.switch_debug)
        switchDebug.isChecked = FileHelper.DEBUG_MODE
        updateSwitchLabel(switchDebug)
        switchDebug.setOnCheckedChangeListener { _, isChecked ->
            FileHelper.DEBUG_MODE = isChecked
            updateSwitchLabel(switchDebug)
            FileHelper.init(this@MainActivity)
            recreate()
        }
    }

    private fun updateSwitchLabel(switch: MaterialSwitch) {
        if (switch.isChecked) {
            switch.text = "调试"
            switch.setTextColor(ContextCompat.getColor(this, R.color.light_blue))
        } else {
            switch.text = "生产"
            switch.setTextColor(ContextCompat.getColor(this, R.color.text_hint))
        }
    }
}
