package com.openclaw.car.fragment

import android.graphics.Typeface
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import android.os.Bundle
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.openclaw.car.R
import com.openclaw.car.adapter.VoicePresetAdapter
import com.openclaw.car.audio.AudioPreviewPlayer
import com.openclaw.car.network.TtsApiClient
import com.openclaw.car.util.FileHelper
import com.openclaw.car.util.PreferenceHelper
import java.util.concurrent.Executors

class PersonaFragment : Fragment() {

    private val TAG = "PersonaFragment"

    private val personaCards = mutableListOf<CardView>()
    private var selectedPersonaIndex = 0
    private var selectedVoiceIndex = 0
    private var currentDialect = ""

    private lateinit var chipGroupDialect: ChipGroup
    private lateinit var rvVoiceList: RecyclerView
    private lateinit var voiceAdapter: VoicePresetAdapter
    private lateinit var voiceSectionRoot: LinearLayout

    private var cloneHintView: View? = null

    private val voiceExecutor = Executors.newSingleThreadExecutor()
    private val clonePollExecutor = Executors.newSingleThreadExecutor()
    private var clonePolling = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_persona, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        personaCards.add(view.findViewById(R.id.card_shixie))
        personaCards.add(view.findViewById(R.id.card_tiexin))
        personaCards.add(view.findViewById(R.id.card_quanneng))

        chipGroupDialect = view.findViewById(R.id.chip_group_dialect)

        rvVoiceList = view.findViewById(R.id.rv_voice_list)
        voiceSectionRoot = rvVoiceList.parent as LinearLayout

        voiceAdapter = VoicePresetAdapter(
            onPresetSelected = { index -> selectVoice(index) },
            onPreviewClicked = { index -> AudioPreviewPlayer.playSample(requireContext(), index) },
            onCloneClicked = { onCloneClicked() },
            onRerecordClicked = { onRerecordClicked() }
        )
        rvVoiceList.adapter = voiceAdapter
        rvVoiceList.layoutManager = LinearLayoutManager(requireContext())

        val context = requireContext()
        selectedPersonaIndex = PreferenceHelper.getLastPersona(context)
        selectedVoiceIndex = PreferenceHelper.getLastVoice(context)
        currentDialect = PreferenceHelper.getLastDialect(context)

        personaCards.forEachIndexed { index, card ->
            card.setOnClickListener { selectPersona(index) }
            card.setOnLongClickListener {
                val names = listOf(getString(R.string.persona_shixie), getString(R.string.persona_tiexin), getString(R.string.persona_quanneng))
                val prompt = FileHelper.getPersonaPrompt(index)
                val scrollView = android.widget.ScrollView(requireContext())
                val tv = TextView(requireContext()).apply {
                    text = prompt
                    setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                }
                scrollView.addView(tv)
                AlertDialog.Builder(requireContext())
                    .setTitle(names[index])
                    .setView(scrollView)
                    .setPositiveButton("关闭", null)
                    .show()
                true
            }
        }

        setupDialectChips()

        // Stream TTS experimental toggle
        val switchStreamTts = view.findViewById<SwitchCompat>(R.id.switch_stream_tts)
        switchStreamTts.isChecked = PreferenceHelper.getStreamTtsEnabled(context)
        switchStreamTts.setOnCheckedChangeListener { _, checked ->
            PreferenceHelper.saveStreamTtsEnabled(context, checked)
            Log.i(TAG, "Stream TTS ${if (checked) "enabled" else "disabled"}")
        }

        val savedMode = PreferenceHelper.getVoiceMode(context)
        when (savedMode) {
            "clone" -> {
                voiceAdapter.setSelectedIndex(VoicePresetAdapter.CLONE_INDEX)
            }
            else -> {
                voiceAdapter.setSelectedIndex(selectedVoiceIndex)
            }
        }

        updatePersonaCardUI(selectedPersonaIndex)

        syncVoiceToAdapter()

        // Check clone status
        voiceExecutor.execute {
            val status = TtsApiClient.getCloneStatus()
            val hasAudio = status?.optBoolean("has_clone_audio", false) == true
            activity?.runOnUiThread {
                voiceAdapter.hasCloneAudio = hasAudio
                voiceAdapter.notifyItemChanged(voiceAdapter.itemCount - 1)
            }
        }
    }

    private fun setupDialectChips() {
        val dialects = FileHelper.DIALECT_OPTIONS
        val labels = listOf(
            getString(R.string.dialect_default),
            "四川话", "粤语"
        )
        dialects.forEachIndexed { index, dialectValue ->
            val chip = Chip(requireContext()).apply {
                text = labels[index]
                isCheckable = true
                id = View.generateViewId()
            }
            chipGroupDialect.addView(chip)
            if (dialectValue == currentDialect) {
                chip.isChecked = true
            }
        }
        chipGroupDialect.setOnCheckedStateChangeListener { _, _ ->
            val checkedId = chipGroupDialect.checkedChipId
            if (checkedId == View.NO_ID) {
                currentDialect = ""
            } else {
                val chip = chipGroupDialect.findViewById<Chip>(checkedId)
                val idx = chipGroupDialect.indexOfChild(chip)
                currentDialect = FileHelper.DIALECT_OPTIONS.getOrElse(idx) { "" }
            }
            onDialectChanged()
        }
    }

    private fun selectPersona(index: Int) {
        val context = requireContext()
        val prompt = FileHelper.getPersonaPrompt(index)
        val dialectSuffix = FileHelper.buildDialectPromptForLlm(currentDialect)
        FileHelper.writeFile(FileHelper.personaFilePath, prompt + dialectSuffix)
        selectedPersonaIndex = index
        updatePersonaCardUI(index)
        PreferenceHelper.saveLastPersona(context, index)
    }

    private fun selectVoice(index: Int) {
        val context = requireContext()

        AudioPreviewPlayer.playSample(context, index)

        val preset = FileHelper.getVoicePreset(index)
        val fields = mutableMapOf<String, Any?>(
            "ref_audio" to preset.refAudio,
            "prompt_text" to preset.promptText,
            "dialect" to currentDialect,
            "cfg_value" to preset.cfgValue,
            "temperature" to preset.temperature,
            "auto_ref" to false
        )
        applyVoiceToAdapter(fields)

        selectedVoiceIndex = index
        voiceAdapter.setSelectedIndex(index)
        PreferenceHelper.saveLastVoice(context, index)
        PreferenceHelper.saveVoiceMode(context, "preset")
        removeCloneHint()
    }

    private fun onCloneClicked() {
        val context = context ?: return
        voiceAdapter.setSelectedIndex(VoicePresetAdapter.CLONE_INDEX)
        PreferenceHelper.saveVoiceMode(context, "clone")
        removeCloneHint()

        voiceExecutor.execute {
            val status = TtsApiClient.getCloneStatus()
            val hasAudio = status?.optBoolean("has_clone_audio", false) == true
            activity?.runOnUiThread {
                if (hasAudio) {
                    voiceExecutor.execute {
                        val ok = TtsApiClient.activateCloneVoice()
                        // 切换到克隆音色 → 立刻重生成 filler 池（applyVoiceToAdapter 路径有调，
                        // clone 路径之前漏了，导致 filler 还是上一个音色）
                        val fillerOk = if (ok) TtsApiClient.regenerateFillers() else false
                        activity?.runOnUiThread {
                            if (ok) {
                                // filler 重生成需几秒，期间 watchdog 若触发还是旧音色 → 抑制 30s 兜底
                                com.openclaw.car.OpenClawApp.responseWatchdog?.setSuppressed(true, autoResetMs = 30_000L)
                                Log.i(TAG, "onCloneClicked: activated, filler regen=$fillerOk")
                                Toast.makeText(context, "已切换到我的音色", Toast.LENGTH_SHORT).show()
                                AudioPreviewPlayer.playCloneSample(context)
                            } else {
                                Toast.makeText(context, R.string.toast_voice_update_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    enableCloneCapture()
                }
            }
        }
    }

    private fun onRerecordClicked() {
        enableCloneCapture()
    }

    private fun enableCloneCapture() {
        val context = context ?: return
        voiceExecutor.execute {
            TtsApiClient.clearCloneAudio()
            activity?.runOnUiThread {
                voiceAdapter.hasCloneAudio = false
            }
            val ok = TtsApiClient.enableCloneCapture()
            activity?.runOnUiThread {
                if (ok) {
                    showCloneHint()
                    Toast.makeText(context, "请通过飞书发送语音", Toast.LENGTH_SHORT).show()
                    startClonePolling()
                } else {
                    Toast.makeText(context, "开启捕获失败，请检查网络", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showCloneHint() {
        if (cloneHintView != null) return
        val context = context ?: return

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(16)
            setPadding(pad, pad, pad, pad)
        }

        TextView(context).apply {
            text = "请通过飞书发送语音，朗读以下内容："
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 14f
        }.also { container.addView(it) }

        val card = CardView(context).apply {
            val m = dpToPx(8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = m; bottomMargin = m }
            setCardBackgroundColor(0xFFF5F5F5.toInt())
            radius = dpToPx(8f)
            cardElevation = 0f
            useCompatPadding = false
        }

        TextView(context).apply {
            text = "你好，看下今天的天气，然后导航到最近的加油站"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            val p = dpToPx(12)
            setPadding(p, p, p, p)
        }.also { card.addView(it) }
        container.addView(card)

        TextView(context).apply {
            text = "语音需 3 秒以上"
            setTextColor(ContextCompat.getColor(context, R.color.text_hint))
            textSize = 12f
        }.also { container.addView(it) }

        val rvIndex = voiceSectionRoot.indexOfChild(rvVoiceList)
        voiceSectionRoot.addView(container, rvIndex + 1)
        cloneHintView = container
    }

    private fun removeCloneHint() {
        cloneHintView?.let {
            voiceSectionRoot.removeView(it)
        }
        cloneHintView = null
    }

    private fun startClonePolling() {
        if (clonePolling) return
        clonePolling = true
        // 整个 upload/poll/activate 期间都不能让 watchdog 触发 filler——
        // 期间 filler 池还是旧音色（regenerateFillers 在 activate 成功后才跑），
        // 用户听到旧音色的"请稍等"会以为切换失败。polling 结束统一解除。
        com.openclaw.car.OpenClawApp.responseWatchdog?.setSuppressed(true)
        var succeeded = false
        clonePollExecutor.execute {
            try {
                while (clonePolling) {
                    try {
                        Thread.sleep(3000)
                        val status = TtsApiClient.getCloneStatus()
                        val hasAudio = status?.optBoolean("has_clone_audio", false) == true
                        if (hasAudio) {
                            // activate + regen 放在 polling 线程里同步跑，避免切到 voiceExecutor
                            // 让 suppression 时序错乱（finally 已清，activate 还没跑完）
                            val ok = TtsApiClient.activateCloneVoice()
                            val fillerOk = if (ok) TtsApiClient.regenerateFillers() else false
                            if (ok) {
                                succeeded = true
                                activity?.runOnUiThread {
                                    clonePolling = false
                                    removeCloneHint()
                                    voiceAdapter.hasCloneAudio = true
                                    voiceAdapter.notifyItemChanged(voiceAdapter.itemCount - 1)
                                    // filler 重生成需几秒 → 切到 30s 兜底抑制
                                    com.openclaw.car.OpenClawApp.responseWatchdog?.setSuppressed(true, autoResetMs = 30_000L)
                                    voiceAdapter.setSelectedIndex(VoicePresetAdapter.CLONE_INDEX)
                                    PreferenceHelper.saveVoiceMode(requireContext(), "clone")
                                    Toast.makeText(requireContext(), "我的音色已就绪", Toast.LENGTH_SHORT).show()
                                    Log.i(TAG, "startClonePolling: activated, filler regen=$fillerOk")
                                }
                            } else {
                                activity?.runOnUiThread {
                                    Toast.makeText(requireContext(), R.string.toast_voice_update_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                            return@execute
                        }
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            } finally {
                // 中途 cancel 或 activate 失败：解除抑制；成功路径走 30s 兜底，不在这里清
                if (!succeeded) {
                    com.openclaw.car.OpenClawApp.responseWatchdog?.setSuppressed(false)
                }
                clonePolling = false
            }
        }
    }

    private fun onDialectChanged() {
        val context = requireContext()
        val fields = mapOf<String, Any?>("dialect" to currentDialect)
        applyVoiceToAdapter(fields)

        val personaPrompt = FileHelper.getPersonaPrompt(selectedPersonaIndex)
        val dialectSuffix = FileHelper.buildDialectPromptForLlm(currentDialect)
        FileHelper.writeFile(FileHelper.personaFilePath, personaPrompt + dialectSuffix)

        PreferenceHelper.saveLastDialect(context, currentDialect)
        Toast.makeText(context, R.string.toast_dialect_updated, Toast.LENGTH_SHORT).show()
    }

    private fun applyVoiceToAdapter(fields: Map<String, Any?>) {
        voiceExecutor.execute {
            val success = TtsApiClient.updateVoice("default", fields)
            if (success) {
                // Regenerate filler pool so watchdog clips match the new voice.
                // Failure is non-fatal: old pool keeps playing until next switch.
                val fillerOk = TtsApiClient.regenerateFillers()
                Log.i(TAG, "applyVoiceToAdapter: filler regen $fillerOk")
            }
            activity?.runOnUiThread {
                val context = context ?: return@runOnUiThread
                if (success) {
                    Toast.makeText(context, R.string.toast_voice_updated, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, R.string.toast_voice_update_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun syncVoiceToAdapter() {
        voiceExecutor.execute {
            val context = context ?: return@execute
            val mode = PreferenceHelper.getVoiceMode(context)

            if (mode == "clone") {
                val status = TtsApiClient.getCloneStatus()
                val hasAudio = status?.optBoolean("has_clone_audio", false) == true
                if (hasAudio) {
                    TtsApiClient.activateCloneVoice()
                }
                return@execute
            }

            val currentVoice = TtsApiClient.getVoice("default")
            if (currentVoice == null) {
                val preset = FileHelper.getVoicePreset(selectedVoiceIndex)
                TtsApiClient.updateVoice("default", mapOf(
                    "ref_audio" to preset.refAudio,
                    "prompt_text" to "",
                    "dialect" to currentDialect,
                    "cfg_value" to preset.cfgValue,
                    "temperature" to preset.temperature,
                    "auto_ref" to false
                ))
                return@execute
            }

            val adapterDialect = currentVoice.optString("dialect", "")
            val adapterRefAudio = currentVoice.optString("ref_audio", "")

            val preset = FileHelper.getVoicePreset(selectedVoiceIndex)
            val needsUpdate = adapterDialect != currentDialect || adapterRefAudio != preset.refAudio

            if (needsUpdate) {
                TtsApiClient.updateVoice("default", mapOf(
                    "ref_audio" to preset.refAudio,
                    "prompt_text" to "",
                    "dialect" to currentDialect,
                    "cfg_value" to preset.cfgValue,
                    "temperature" to preset.temperature,
                    "auto_ref" to false
                ))
            }
        }
    }

    private fun updatePersonaCardUI(selectedIndex: Int) {
        val ctx = requireContext()
        personaCards.forEachIndexed { index, card ->
            if (index == selectedIndex) {
                card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_selected_background))
                card.cardElevation = 4f
            } else {
                card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_background))
                card.cardElevation = 0f
            }
        }
    }

    override fun onDestroy() {
        clonePolling = false
        AudioPreviewPlayer.stop()
        super.onDestroy()
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp,
            resources.displayMetrics
        )
    }

    private fun dpToPx(dp: Int): Int = dpToPx(dp.toFloat()).toInt()
}
