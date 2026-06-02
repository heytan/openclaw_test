package com.openclaw.car.fragment

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.cardview.widget.CardView
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.openclaw.car.R
import com.openclaw.car.adapter.VoicePresetAdapter
import com.openclaw.car.audio.AudioPreviewPlayer
import com.openclaw.car.network.TtsApiClient
import com.openclaw.car.util.FileHelper
import com.openclaw.car.util.PreferenceHelper
import java.util.concurrent.Executors

class PersonaFragment : Fragment() {

    private val personaCards = mutableListOf<CardView>()
    private var selectedPersonaIndex = 0
    private var selectedVoiceIndex = 0
    private var currentDialect = ""
    private var isCustomVoice = false

    private lateinit var chipGroupDialect: ChipGroup
    private lateinit var etCustomVoice: TextInputEditText
    private lateinit var btnApplyCustomVoice: MaterialButton
    private lateinit var rvVoiceList: RecyclerView
    private lateinit var voiceAdapter: VoicePresetAdapter

    private val voiceExecutor = Executors.newSingleThreadExecutor()

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
        etCustomVoice = view.findViewById(R.id.et_custom_voice)
        btnApplyCustomVoice = view.findViewById(R.id.btn_apply_custom_voice)

        rvVoiceList = view.findViewById(R.id.rv_voice_list)
        voiceAdapter = VoicePresetAdapter(
            onPresetSelected = { index -> selectVoice(index) },
            onPreviewClicked = { index -> AudioPreviewPlayer.playSample(requireContext(), index) }
        )
        rvVoiceList.adapter = voiceAdapter
        rvVoiceList.layoutManager = LinearLayoutManager(requireContext())

        val context = requireContext()
        selectedPersonaIndex = PreferenceHelper.getLastPersona(context)
        selectedVoiceIndex = PreferenceHelper.getLastVoice(context)
        currentDialect = PreferenceHelper.getLastDialect(context)

        personaCards.forEachIndexed { index, card ->
            card.setOnClickListener { selectPersona(index) }
        }

        setupDialectChips()

        val savedCustomText = PreferenceHelper.getCustomVoiceText(context)
        val savedMode = PreferenceHelper.getVoiceMode(context)
        if (savedCustomText.isNotBlank() && savedMode == "custom") {
            isCustomVoice = true
            etCustomVoice.setText(savedCustomText)
            voiceAdapter.setSelectedIndex(-1)
        } else {
            isCustomVoice = false
            voiceAdapter.setSelectedIndex(selectedVoiceIndex)
        }

        updatePersonaCardUI(selectedPersonaIndex)

        btnApplyCustomVoice.setOnClickListener { applyCustomVoice() }

        syncVoiceToAdapter()
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
        isCustomVoice = false

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
        PreferenceHelper.saveCustomVoiceText(context, "")
        PreferenceHelper.saveVoiceMode(context, "preset")
    }

    private fun applyCustomVoice() {
        val text = etCustomVoice.text?.toString()?.trim() ?: ""
        if (text.isBlank()) return

        isCustomVoice = true
        val promptText = "(${text})"

        val fields = mutableMapOf<String, Any?>(
            "ref_audio" to null,
            "prompt_text" to promptText,
            "dialect" to currentDialect,
            "auto_ref" to true
        )
        applyVoiceToAdapter(fields)

        voiceAdapter.setSelectedIndex(-1)
        PreferenceHelper.saveCustomVoiceText(requireContext(), text)
        PreferenceHelper.saveVoiceMode(requireContext(), "custom")
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
            val currentVoice = TtsApiClient.getVoice("default")
            if (currentVoice == null) {
                val context = context ?: return@execute
                if (isCustomVoice) {
                    val text = PreferenceHelper.getCustomVoiceText(context)
                    if (text.isNotBlank()) {
                        TtsApiClient.updateVoice("default", mapOf(
                            "ref_audio" to null,
                            "prompt_text" to "(${text})",
                            "dialect" to currentDialect,
                            "auto_ref" to true
                        ))
                    }
                } else {
                    val preset = FileHelper.getVoicePreset(selectedVoiceIndex)
                    TtsApiClient.updateVoice("default", mapOf(
                        "ref_audio" to preset.refAudio,
                        "prompt_text" to "",
                        "dialect" to currentDialect,
                        "cfg_value" to preset.cfgValue,
                        "temperature" to preset.temperature,
                        "auto_ref" to false
                    ))
                }
                return@execute
            }

            val adapterDialect = currentVoice.optString("dialect", "")
            val adapterRefAudio = currentVoice.optString("ref_audio", "")
            val adapterAutoRef = currentVoice.optBoolean("auto_ref", false)

            val context = context ?: return@execute
            val needsUpdate = if (isCustomVoice) {
                adapterDialect != currentDialect
            } else {
                val preset = FileHelper.getVoicePreset(selectedVoiceIndex)
                adapterDialect != currentDialect || adapterRefAudio != preset.refAudio
            }

            if (needsUpdate) {
                if (isCustomVoice && adapterAutoRef && adapterRefAudio.isNotEmpty()) {
                    if (adapterDialect != currentDialect) {
                        TtsApiClient.updateVoice("default", mapOf("dialect" to currentDialect))
                    }
                } else {
                    if (isCustomVoice) {
                        val text = PreferenceHelper.getCustomVoiceText(context)
                        if (text.isNotBlank()) {
                            TtsApiClient.updateVoice("default", mapOf(
                                "ref_audio" to null,
                                "prompt_text" to "(${text})",
                                "dialect" to currentDialect,
                                "auto_ref" to true
                            ))
                        }
                    } else {
                        val preset = FileHelper.getVoicePreset(selectedVoiceIndex)
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
        AudioPreviewPlayer.stop()
        super.onDestroy()
    }
}
