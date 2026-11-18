package com.example.lumasync

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout

object TvUiHelper {
    const val BG_CANVAS = 0xFF090D16.toInt()
    const val BG_CARD = 0xFF121826.toInt()
    const val BG_CONTROL = 0xFF1C2538.toInt()
    const val BG_CONTROL_FOCUSED = 0xFF24344E.toInt()
    const val BG_PRIMARY = 0xFF0E7490.toInt()
    const val BG_PRIMARY_FOCUSED = 0xFF06B6D4.toInt()
    const val BG_DANGER = 0xFF7F1D1D.toInt()
    const val BG_DANGER_FOCUSED = 0xFFDC2626.toInt()

    const val ACCENT_CYAN = 0xFF22D3EE.toInt()
    const val ACCENT_AMBER = 0xFFF59E0B.toInt()
    const val SUCCESS_GREEN = 0xFF34D399.toInt()
    const val ERROR_RED = 0xFFF87171.toInt()
    const val BORDER_SUBTLE = 0xFF263147.toInt()

    const val TEXT_PRIMARY = 0xFFF8FAFC.toInt()
    const val TEXT_SECONDARY = 0xFF94A3B8.toInt()

    fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    fun makeCardBackground(
        context: Context,
        fillColor: Int = BG_CARD,
        strokeColor: Int = BORDER_SUBTLE,
        cornerDp: Int = 14,
        strokeDp: Int = 1
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, cornerDp).toFloat()
            setColor(fillColor)
            setStroke(dp(context, strokeDp), strokeColor)
        }
    }

    fun makeFocusableBackground(
        context: Context,
        normalColor: Int,
        focusedColor: Int,
        normalBorderColor: Int = BORDER_SUBTLE,
        focusedBorderColor: Int = Color.WHITE,
        cornerDp: Int = 10
    ): StateListDrawable {
        val radius = dp(context, cornerDp).toFloat()
        val focusedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(focusedColor)
            setStroke(dp(context, 3), focusedBorderColor)
        }
        val pressedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(focusedColor)
            setStroke(dp(context, 3), ACCENT_CYAN)
        }
        val normalDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(normalColor)
            setStroke(dp(context, 1), normalBorderColor)
        }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressedDrawable)
            addState(intArrayOf(android.R.attr.state_focused), focusedDrawable)
            addState(intArrayOf(android.R.attr.state_selected), focusedDrawable)
            addState(intArrayOf(), normalDrawable)
        }
    }

    fun createTvButton(
        context: Context,
        label: String,
        normalColor: Int = BG_CONTROL,
        focusedColor: Int = BG_CONTROL_FOCUSED,
        focusedBorderColor: Int = ACCENT_CYAN,
        horizontalPaddingDp: Int = 22,
        verticalPaddingDp: Int = 12,
        textSizeSp: Float = 16f
    ): Button {
        val minSize = dp(context, 48)
        return Button(context).apply {
            text = label
            isAllCaps = false
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            setTextColor(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_focused),
                        intArrayOf(-android.R.attr.state_enabled),
                        intArrayOf()
                    ),
                    intArrayOf(Color.WHITE, 0xFF64748B.toInt(), TEXT_PRIMARY)
                )
            )
            minHeight = minSize
            minWidth = minSize
            gravity = Gravity.CENTER
            setPadding(
                dp(context, horizontalPaddingDp),
                dp(context, verticalPaddingDp),
                dp(context, horizontalPaddingDp),
                dp(context, verticalPaddingDp)
            )
            isFocusable = true
            isFocusableInTouchMode = true
            background = makeFocusableBackground(
                context = context,
                normalColor = normalColor,
                focusedColor = focusedColor,
                focusedBorderColor = focusedBorderColor
            )
        }
    }

    fun linearParams(
        width: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
        height: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
        weight: Float = 0f,
        topMarginDp: Int = 0,
        bottomMarginDp: Int = 0,
        leftMarginDp: Int = 0,
        rightMarginDp: Int = 0,
        context: Context? = null
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(width, height, weight).apply {
            if (context != null) {
                setMargins(
                    dp(context, leftMarginDp),
                    dp(context, topMarginDp),
                    dp(context, rightMarginDp),
                    dp(context, bottomMarginDp)
                )
            }
        }
    }
}
