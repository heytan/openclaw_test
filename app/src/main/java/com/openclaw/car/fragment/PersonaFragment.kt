package com.openclaw.car.fragment

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.openclaw.car.R
import com.openclaw.car.util.FileHelper
import com.openclaw.car.util.PreferenceHelper
import java.util.Locale

class PersonaFragment : Fragment() {

    private val personaCards = mutableListOf<CardView>()
    private val voiceButtons = mutableListOf<MaterialButton>()
    private var selectedPersonaIndex = 0
    private var selectedVoiceIndex = 0
    private var tts: TextToSpeech? = null

    // Voice TTS parameters: pitch, rate for each of the 4 voices
    private val voiceParams = arrayOf(
        floatArrayOf(1.0f, 1.0f),    // 标准男声
        floatArrayOf(1.15f, 0.95f),  // 标准女声
        floatArrayOf(1.25f, 0.82f),  // 温柔女声
        floatArrayOf(0.88f, 0.85f)   // 沉稳男声
    )

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

        voiceButtons.add(view.findViewById(R.id.rb_voice_standard_male))
        voiceButtons.add(view.findViewById(R.id.rb_voice_standard_female))
        voiceButtons.add(view.findViewById(R.id.rb_voice_gentle_female))
        voiceButtons.add(view.findViewById(R.id.rb_voice_calm_male))

        val context = requireContext()
        selectedPersonaIndex = PreferenceHelper.getLastPersona(context)
        selectedVoiceIndex = PreferenceHelper.getLastVoice(context)

        personaCards.forEachIndexed { index, card ->
            card.setOnClickListener { selectPersona(index) }
        }

        voiceButtons.forEachIndexed { index, button ->
            button.setOnClickListener { selectVoice(index) }
        }

        updatePersonaCardUI(selectedPersonaIndex)
        updateVoiceButtonUI(selectedVoiceIndex)

        // Initialize TTS for voice preview
        initTts(context)
    }

    private fun initTts(context: Context) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
            }
        }
    }

    private fun previewVoice(index: Int) {
        val params = voiceParams[index]
        tts?.apply {
            setPitch(params[0])
            setSpeechRate(params[1])
            speak("你好，我是你的智能语音助手", TextToSpeech.QUEUE_FLUSH, null, "voice_$index")
        }
    }

    private fun selectPersona(index: Int) {
        val context = requireContext()
        val prompt = FileHelper.getPersonaPrompt(index)
        FileHelper.writeFile(FileHelper.personaFilePath, prompt)
        selectedPersonaIndex = index
        updatePersonaCardUI(index)
        PreferenceHelper.saveLastPersona(context, index)
    }

    private fun selectVoice(index: Int) {
        val context = requireContext()
        previewVoice(index)
        val config = FileHelper.getVoiceConfig(index)
        FileHelper.writeFile(FileHelper.voiceConfigFilePath, config)
        selectedVoiceIndex = index
        updateVoiceButtonUI(index)
        PreferenceHelper.saveLastVoice(context, index)
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

    private fun updateVoiceButtonUI(selectedIndex: Int) {
        val ctx = requireContext()
        voiceButtons.forEachIndexed { index, button ->
            if (index == selectedIndex) {
                button.setBackgroundColor(ContextCompat.getColor(ctx, R.color.light_blue))
                button.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                button.iconTint = ContextCompat.getColorStateList(ctx, R.color.white)
                button.strokeColor = null
                button.strokeWidth = 0
            } else {
                button.setBackgroundColor(ContextCompat.getColor(ctx, R.color.surface))
                button.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                button.iconTint = ContextCompat.getColorStateList(ctx, R.color.text_hint)
                button.strokeColor = ContextCompat.getColorStateList(ctx, R.color.card_border)
                button.strokeWidth = 1
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
