package com.openclaw.car.widget

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    var columns = 3
    var spacing = 0

    fun getChildWidth(): Int {
        val contentWidth = width - paddingLeft - paddingRight
        return (contentWidth - (columns - 1) * spacing) / columns
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val contentWidth = parentWidth - paddingLeft - paddingRight
        val childW = (contentWidth - (columns - 1) * spacing) / columns

        var totalHeight = paddingTop + paddingBottom
        var rowHeight = 0
        var col = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? MarginLayoutParams
            val vMargin = (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)

            child.measure(
                MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )

            rowHeight = maxOf(rowHeight, child.measuredHeight + vMargin)
            col++

            if (col >= columns || i == childCount - 1) {
                totalHeight += rowHeight
                rowHeight = 0
                col = 0
            }
        }

        setMeasuredDimension(parentWidth, resolveSize(totalHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val contentWidth = r - l - paddingLeft - paddingRight
        val childW = (contentWidth - (columns - 1) * spacing) / columns

        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0
        var col = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? MarginLayoutParams
            val topMargin = lp?.topMargin ?: 0
            val bottomMargin = lp?.bottomMargin ?: 0

            if (col >= columns) {
                y += rowHeight
                x = paddingLeft
                rowHeight = 0
                col = 0
            }

            val cx = x
            val cy = y + topMargin
            child.layout(cx, cy, cx + childW, cy + child.measuredHeight)

            rowHeight = maxOf(rowHeight, child.measuredHeight + topMargin + bottomMargin)
            x += childW + spacing
            col++
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams {
        return MarginLayoutParams(context, attrs)
    }

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams {
        return MarginLayoutParams(p)
    }

    override fun checkLayoutParams(p: LayoutParams): Boolean {
        return p is MarginLayoutParams
    }
}
