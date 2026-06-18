package com.openclaw.car.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.openclaw.car.R
import com.openclaw.car.util.FileHelper
import java.io.File
import kotlin.math.abs

class ProactiveFragment : Fragment() {

    companion object {
        private val expandedCardIndices = mutableSetOf<Int>()
    }

    private data class CardState(
        val cardId: Int,
        val overlayId: Int,
        val checkId: Int,
        val descId: Int,
        val descText: String,
        var selected: Boolean = false,
        var expanded: Boolean = false
    )

    private val cards = listOf(
        CardState(
            R.id.card_1, R.id.overlay_1, R.id.iv_check_1, R.id.tv_desc_1,
            "上午9点天气晴，位于保利小区停车场，车辆刚启动。主驾：男主人，灰色上衣，神情悠闲放松。后排右二：女主人，黄色上衣，表情轻松，怀中抱着宝宝，宝宝处于熟睡状态。"
        ),
        CardState(
            R.id.card_2, R.id.overlay_2, R.id.iv_check_2, R.id.tv_desc_2,
            "上午10点天气晴，位于社康中心停车场，车辆刚启动。主驾：男主人，绿色上衣，神情轻松。副驾：女主人，黄色上衣，神情放松，看向男主人。女主人怀中抱着宝宝，宝宝眉头略紧，情绪略显局促，应该是刚才打疫苗被惊醒了，刚从熟睡中醒来，有点不舒服。"
        ),
        CardState(
            R.id.card_3, R.id.overlay_3, R.id.iv_check_3, R.id.tv_desc_3,
            "上午10点10分天气晴，车辆仍在返程路上行驶，车速30km/h。主驾：男主人，绿色上衣，神情略显紧张；副驾：女主人，黄色上衣，神情略显无奈，低头看着怀中宝宝，宝宝开始大声哭闹，躁动不安。"
        )
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_proactive, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        for (card in cards) {
            val cardView: FrameLayout = view.findViewById(card.cardId)
            val overlay: View = view.findViewById(card.overlayId)
            val check: ImageView = view.findViewById(card.checkId)
            val desc: TextView = view.findViewById(card.descId)

            // Overlay should not intercept touches
            overlay.isClickable = false
            overlay.isFocusable = false

            desc.text = card.descText

            var touchStartX = 0f
            var touchStartY = 0f
            var isSwipe = false

            cardView.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartX = event.x
                        touchStartY = event.y
                        isSwipe = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = touchStartX - event.x
                        val dy = abs(touchStartY - event.y)
                        if (abs(dx) > 30 && abs(dx) > dy) {
                            isSwipe = true
                            v.parent.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        v.parent.requestDisallowInterceptTouchEvent(false)
                        val deltaX = touchStartX - event.x
                        val deltaY = abs(touchStartY - event.y)
                        if (isSwipe && abs(deltaX) > 80) {
                            if (deltaX > 0 && !card.expanded) {
                                // Swipe left -> expand
                                card.expanded = true
                                expandedCardIndices.add(cards.indexOf(card))
                                updateDescLayout(desc, true)
                            } else if (deltaX < 0 && card.expanded) {
                                // Swipe right -> collapse
                                card.expanded = false
                                expandedCardIndices.remove(cards.indexOf(card))
                                updateDescLayout(desc, false)
                            }
                        } else if (!isSwipe && deltaY < 30) {
                            // Tap -> toggle selection
                            selectCard(view, card, overlay, check)
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
                true
            }
        }

        // Restore selection from scene file
        restoreSelection(view)
        for (idx in expandedCardIndices) {
            if (idx in cards.indices) {
                cards[idx].expanded = true
                val desc: TextView = view.findViewById(cards[idx].descId)
                updateDescLayout(desc, true)
            }
        }
    }

    private fun selectCard(root: View, card: CardState, overlay: View, check: ImageView) {
        val wasSelected = card.selected
        for (c in cards) {
            c.selected = false
            root.findViewById<View>(c.overlayId).visibility = View.GONE
            root.findViewById<ImageView>(c.checkId).visibility = View.GONE
        }
        if (!wasSelected) {
            card.selected = true
            overlay.visibility = View.VISIBLE
            check.visibility = View.VISIBLE
            writeScene(card.descText)
        } else {
            clearScene()
        }
    }

    private fun restoreSelection(root: View) {
        try {
            val file = File(FileHelper.AGENT_SCENE_PATH)
            if (!file.exists()) return
            val content = file.readText(Charsets.UTF_8).trim()
            if (content.isEmpty()) return
            val matchedCard = cards.find { it.descText == content } ?: return
            matchedCard.selected = true
            root.findViewById<View>(matchedCard.overlayId).visibility = View.VISIBLE
            root.findViewById<ImageView>(matchedCard.checkId).visibility = View.VISIBLE
        } catch (_: Exception) {}
    }

    private fun writeScene(desc: String) {
        try {
            val file = File(FileHelper.AGENT_SCENE_PATH)
            file.writeText(desc, Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    private fun clearScene() {
        try {
            val file = File(FileHelper.AGENT_SCENE_PATH)
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
    }

    private fun updateDescLayout(desc: TextView, expanded: Boolean) {
        if (expanded) {
            desc.visibility = View.VISIBLE
            val params = desc.layoutParams as LinearLayout.LayoutParams
            params.weight = 1f
            desc.layoutParams = params
        } else {
            val params = desc.layoutParams as LinearLayout.LayoutParams
            params.weight = 0f
            desc.layoutParams = params
            desc.visibility = View.GONE
        }
    }
}
