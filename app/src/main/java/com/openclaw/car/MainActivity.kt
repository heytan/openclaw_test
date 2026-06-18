package com.openclaw.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.openclaw.car.network.StatusChecker
import com.openclaw.car.service.NodeProcessService
import com.openclaw.car.util.FileHelper

class MainActivity : AppCompatActivity() {

    lateinit var viewPager: ViewPager2
        private set
    private lateinit var tabLayout: TabLayout
    private lateinit var statusPanel: View
    private lateinit var dotGateway: View
    private lateinit var dotAccessibility: View
    private lateinit var dotTts: View
    private lateinit var tvGatewayStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvTtsStatus: TextView

    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRefresh = object : Runnable {
        override fun run() {
            refreshStatus()
            statusHandler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        instance = this

        viewPager = findViewById(R.id.view_pager)
        tabLayout = findViewById(R.id.tab_layout)

        val adapter = com.openclaw.car.adapter.ViewPagerAdapter(supportFragmentManager, lifecycle)
        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 4

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_persona)
                1 -> getString(R.string.tab_skill)
                2 -> getString(R.string.tab_memory)
                3 -> getString(R.string.tab_proactive)
                4 -> getString(R.string.tab_a2ui)
                else -> ""
            }
        }.attach()

        FileHelper.init(this)

        // Request RECORD_AUDIO permission via system dialog
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        // Debug / Production mode toggle
        val switchDebug: MaterialSwitch = findViewById(R.id.switch_debug)
        switchDebug.isChecked = FileHelper.DEBUG_MODE
        updateSwitchLabel(switchDebug)
        switchDebug.setOnCheckedChangeListener { _, isChecked ->
            FileHelper.DEBUG_MODE = isChecked
            updateSwitchLabel(switchDebug)
            FileHelper.init(this@MainActivity)
            updateDebugUI(isChecked)
            recreate()
        }

        // Debug status panel
        statusPanel = findViewById(R.id.status_panel)
        dotGateway = findViewById(R.id.dot_gateway)
        dotAccessibility = findViewById(R.id.dot_accessibility)
        dotTts = findViewById(R.id.dot_tts)
        tvGatewayStatus = findViewById(R.id.tv_gateway_status)
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)
        tvTtsStatus = findViewById(R.id.tv_tts_status)

        findViewById<MaterialButton>(R.id.btn_start_all).setOnClickListener {
            NodeProcessService.start(this)
        }
        findViewById<MaterialButton>(R.id.btn_stop_all).setOnClickListener {
            NodeProcessService.stop(this)
        }
        findViewById<MaterialButton>(R.id.btn_accessibility_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        updateDebugUI(FileHelper.DEBUG_MODE)

        // Auto-start services in production mode (delayed to avoid ANR)
        if (!FileHelper.DEBUG_MODE) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing) NodeProcessService.start(this)
            }, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        if (FileHelper.DEBUG_MODE) {
            refreshStatus()
            statusHandler.postDelayed(statusRefresh, 5000)
        }
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
        statusHandler.removeCallbacks(statusRefresh)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun refreshStatus() {
        Thread {
            val status = StatusChecker.check(this)
            runOnUiThread {
                updateDot(dotGateway, tvGatewayStatus, status.gatewayRunning)
                updateDot(dotAccessibility, tvAccessibilityStatus, status.accessibilityEnabled)
                updateDot(dotTts, tvTtsStatus, status.ttsReachable)
            }
        }.start()
    }

    private fun updateDot(dot: View, textView: TextView, running: Boolean) {
        if (running) {
            dot.setBackgroundResource(R.drawable.status_dot_green)
            textView.text = getString(R.string.status_running)
            textView.setTextColor(ContextCompat.getColor(this, R.color.light_blue))
        } else {
            dot.setBackgroundResource(R.drawable.status_dot_red)
            textView.text = getString(R.string.status_stopped)
            textView.setTextColor(ContextCompat.getColor(this, R.color.text_hint))
        }
    }

    private fun updateDebugUI(isDebug: Boolean) {
        statusPanel.visibility = if (isDebug) View.VISIBLE else View.GONE
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

    companion object {
        var instance: MainActivity? = null
            private set
        var isForeground: Boolean = false
            private set
    }
}
