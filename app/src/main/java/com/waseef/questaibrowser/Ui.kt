package com.waseef.questaibrowser

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

object Ui {
    const val BG = 0xFF101318.toInt()
    const val BAR = 0xFF1A1F27.toInt()
    const val TILE = 0xFF252B35.toInt()
    const val ACC = 0xFF3D7BFF.toInt()
    const val ON = 0xFF2E7D32.toInt()

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun round(color: Int, r: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = r; if (strokeW > 0) setStroke(strokeW, stroke)
    }

    fun btn(c: Context, t: String, tip: String? = null, color: Int = TILE, onClick: (View) -> Unit) = Button(c).apply {
        text = t; setTextColor(Color.WHITE); textSize = 18f; isAllCaps = false; gravity = Gravity.CENTER
        minWidth = dp(c, 52); minimumWidth = dp(c, 52); minHeight = dp(c, 52); minimumHeight = dp(c, 52)
        setPadding(dp(c, 10), 0, dp(c, 10), 0)
        background = round(color, dp(c, 12).toFloat())
        if (tip != null) { tooltipText = tip; contentDescription = tip }
        setOnClickListener { onClick(it) }
        hover(this)
    }

    fun label(c: Context, t: String, size: Float = 16f, color: Int = Color.WHITE) = TextView(c).apply {
        text = t; textSize = size; setTextColor(color); setPadding(dp(c, 8), dp(c, 6), dp(c, 8), dp(c, 6))
    }

    fun seek(c: Context, title: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val tv = label(c, "$title: ${fmt(value)}", 17f)
        val sb = SeekBar(c).apply {
            this.max = max - min; progress = value - min
            minimumHeight = dp(c, 48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { tv.text = "$title: ${fmt(p + min)}"; if (fromUser) onChange(p + min) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(tv); box.addView(sb, LinearLayout.LayoutParams(-1, dp(c, 52)))
        return box
    }

    /** Visible hover/pressed feedback for controller rays, hands and Look-and-Pinch. */
    fun hover(v: View) {
        v.foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0x55FFFFFF))
            addState(intArrayOf(android.R.attr.state_hovered), ColorDrawable(0x33FFFFFF))
            addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(0x1FFFFFFF))
        }
    }

    fun hoverTree(v: View) {
        if (v is Button || v is CompoundButton) { if (v.foreground == null) hover(v) }
        if (v is ViewGroup) for (i in 0 until v.childCount) hoverTree(v.getChildAt(i))
    }

    fun vbox(c: Context, padDp: Int = 16) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(c, padDp), dp(c, padDp), dp(c, padDp), dp(c, padDp))
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }
}
