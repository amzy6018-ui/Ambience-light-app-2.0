package com.example.lumasync

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

class SettingsActivity : Activity() {

    private var workingConfig: LumaSyncConfig = LumaSyncConfig()

    private lateinit var baudValueText: TextView
    private lateinit var smoothValueText: TextView
    private lateinit var brightnessValueText: TextView
    private lateinit var leftValueText: TextView
    private lateinit var topValueText: TextView
    private lateinit var rightValueText: TextView
    private lateinit var bottomValueText: TextView
    private lateinit var totalLedsText: TextView
    private lateinit var gammaButton: Button
    private lateinit var saturationValueText: TextView
    private lateinit var letterboxButton: Button
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workingConfig = LumaSyncPrefs.load(this)
        buildUi()
        refreshAllLabels()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(TvUiHelper.BG_CANVAS)
            isFillViewport = true
        }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                TvUiHelper.dp(this@SettingsActivity, 32),
                TvUiHelper.dp(this@SettingsActivity, 24),
                TvUiHelper.dp(this@SettingsActivity, 32),
                TvUiHelper.dp(this@SettingsActivity, 28)
            )
        }

        val maxWidthPx = TvUiHelper.dp(this, 760)
        val availableWidth = resources.displayMetrics.widthPixels - TvUiHelper.dp(this, 64)
        val cardWidth = minOf(maxWidthPx, availableWidth).coerceAtLeast(TvUiHelper.dp(this, 280))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = TvUiHelper.makeCardBackground(this@SettingsActivity)
            setPadding(
                TvUiHelper.dp(this@SettingsActivity, 24),
                TvUiHelper.dp(this@SettingsActivity, 22),
                TvUiHelper.dp(this@SettingsActivity, 24),
                TvUiHelper.dp(this@SettingsActivity, 22)
            )
            layoutParams = LinearLayout.LayoutParams(
                cardWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(this).apply {
            text = getString(R.string.settings_title)
            setTextColor(TvUiHelper.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            layoutParams = TvUiHelper.linearParams(bottomMarginDp = 10, context = this@SettingsActivity)
        }

        val warningBanner = TextView(this).apply {
            text = getString(R.string.settings_sync_warning)
            setTextColor(TvUiHelper.ACCENT_AMBER)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = TvUiHelper.makeCardBackground(
                context = this@SettingsActivity,
                fillColor = 0xFF231A0B.toInt(),
                strokeColor = TvUiHelper.ACCENT_AMBER,
                cornerDp = 8
            )
            setPadding(
                TvUiHelper.dp(this@SettingsActivity, 14),
                TvUiHelper.dp(this@SettingsActivity, 10),
                TvUiHelper.dp(this@SettingsActivity, 14),
                TvUiHelper.dp(this@SettingsActivity, 10)
            )
            layoutParams = TvUiHelper.linearParams(
                width = LinearLayout.LayoutParams.MATCH_PARENT,
                bottomMarginDp = 16,
                context = this@SettingsActivity
            )
        }

        card.addView(title)
        card.addView(warningBanner)

        // Baud Rate row
        baudValueText = addStepperRow(
            parent = card,
            label = "USB Serial Baud Rate",
            onMinus = { cycleBaud(-1) },
            onPlus = { cycleBaud(1) },
            firstFocusTarget = true
        )

        // Smoothing row
        smoothValueText = addStepperRow(
            parent = card,
            label = "Exponential Smoothing",
            onMinus = {
                val next = ((workingConfig.smoothing - 0.05f) * 20f).roundToInt() / 20f
                workingConfig = workingConfig.copy(smoothing = next.coerceIn(0.0f, 0.90f))
                refreshAllLabels()
            },
            onPlus = {
                val next = ((workingConfig.smoothing + 0.05f) * 20f).roundToInt() / 20f
                workingConfig = workingConfig.copy(smoothing = next.coerceIn(0.0f, 0.90f))
                refreshAllLabels()
            }
        )

        // Brightness cap row
        brightnessValueText = addStepperRow(
            parent = card,
            label = "Brightness Cap (1–255)",
            onMinus = {
                workingConfig = workingConfig.copy(
                    brightnessCap = (workingConfig.brightnessCap - 15).coerceIn(15, 255)
                )
                refreshAllLabels()
            },
            onPlus = {
                workingConfig = workingConfig.copy(
                    brightnessCap = (workingConfig.brightnessCap + 15).coerceIn(15, 255)
                )
                refreshAllLabels()
            }
        )

        // Per-side LED counts
        leftValueText = addLedSideRow(card, "Left Side LEDs (bottom → top)") { delta ->
            workingConfig = workingConfig.copy(
                leftCount = (workingConfig.leftCount + delta).coerceIn(0, 200)
            )
            refreshAllLabels()
        }
        topValueText = addLedSideRow(card, "Top Side LEDs (left → right)") { delta ->
            workingConfig = workingConfig.copy(
                topCount = (workingConfig.topCount + delta).coerceIn(0, 200)
            )
            refreshAllLabels()
        }
        rightValueText = addLedSideRow(card, "Right Side LEDs (top → bottom)") { delta ->
            workingConfig = workingConfig.copy(
                rightCount = (workingConfig.rightCount + delta).coerceIn(0, 200)
            )
            refreshAllLabels()
        }
        bottomValueText = addLedSideRow(card, "Bottom Side LEDs (right → left)") { delta ->
            workingConfig = workingConfig.copy(
                bottomCount = (workingConfig.bottomCount + delta).coerceIn(0, 200)
            )
            refreshAllLabels()
        }

        totalLedsText = TextView(this).apply {
            setTextColor(TvUiHelper.ACCENT_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            layoutParams = TvUiHelper.linearParams(
                topMarginDp = 4,
                bottomMarginDp = 14,
                context = this@SettingsActivity
            )
        }
        card.addView(totalLedsText)

        // Gamma correction toggle
        gammaButton = addToggleRow(card, "Gamma Correction (2.2)") {
            workingConfig = workingConfig.copy(gammaEnabled = !workingConfig.gammaEnabled)
            refreshAllLabels()
        }

        // Saturation boost stepper
        saturationValueText = addStepperRow(
            parent = card,
            label = "Saturation Boost",
            onMinus = {
                val next = ((workingConfig.saturationBoost - 0.1f) * 10f).roundToInt() / 10f
                workingConfig = workingConfig.copy(saturationBoost = next.coerceIn(1.0f, 2.0f))
                refreshAllLabels()
            },
            onPlus = {
                val next = ((workingConfig.saturationBoost + 0.1f) * 10f).roundToInt() / 10f
                workingConfig = workingConfig.copy(saturationBoost = next.coerceIn(1.0f, 2.0f))
                refreshAllLabels()
            }
        )

        // Skip letterbox bars toggle
        letterboxButton = addToggleRow(card, "Skip Near-Black Letterbox Bars") {
            workingConfig = workingConfig.copy(skipLetterbox = !workingConfig.skipLetterbox)
            refreshAllLabels()
        }

        // Action footer
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = TvUiHelper.linearParams(
                width = LinearLayout.LayoutParams.MATCH_PARENT,
                topMarginDp = 18,
                context = this@SettingsActivity
            )
        }

        val resetBtn = TvUiHelper.createTvButton(
            context = this,
            label = "Reset Defaults",
            horizontalPaddingDp = 18,
            verticalPaddingDp = 10,
            textSizeSp = 15f
        ).apply {
            layoutParams = TvUiHelper.linearParams(
                rightMarginDp = 12,
                context = this@SettingsActivity
            )
            setOnClickListener {
                workingConfig = LumaSyncConfig()
                refreshAllLabels()
            }
        }

        val cancelBtn = TvUiHelper.createTvButton(
            context = this,
            label = "Cancel",
            horizontalPaddingDp = 18,
            verticalPaddingDp = 10,
            textSizeSp = 15f
        ).apply {
            layoutParams = TvUiHelper.linearParams(
                rightMarginDp = 12,
                context = this@SettingsActivity
            )
            setOnClickListener { finish() }
        }

        saveButton = TvUiHelper.createTvButton(
            context = this,
            label = "Save",
            normalColor = TvUiHelper.BG_PRIMARY,
            focusedColor = TvUiHelper.BG_PRIMARY_FOCUSED,
            focusedBorderColor = TvUiHelper.TEXT_PRIMARY,
            horizontalPaddingDp = 22,
            verticalPaddingDp = 10,
            textSizeSp = 15f
        ).apply {
            setOnClickListener {
                if (workingConfig.totalLeds > 0) {
                    LumaSyncPrefs.save(this@SettingsActivity, workingConfig)
                    finish()
                }
            }
        }

        footer.addView(resetBtn)
        footer.addView(cancelBtn)
        footer.addView(saveButton)
        card.addView(footer)

        outer.addView(card)
        scroll.addView(outer)
        setContentView(scroll)
    }

    private fun addStepperRow(
        parent: LinearLayout,
        label: String,
        onMinus: () -> Unit,
        onPlus: () -> Unit,
        firstFocusTarget: Boolean = false
    ): TextView {
        val row = createRowContainer()
        val labelView = TextView(this).apply {
            text = label
            setTextColor(TvUiHelper.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val minusBtn = TvUiHelper.createTvButton(
            context = this,
            label = "−",
            horizontalPaddingDp = 14,
            verticalPaddingDp = 6,
            textSizeSp = 16f
        ).apply {
            contentDescription = "$label decrease"
            setOnClickListener { onMinus() }
            if (firstFocusTarget) requestFocus()
        }

        val valueView = TextView(this).apply {
            setTextColor(TvUiHelper.ACCENT_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            minWidth = TvUiHelper.dp(this@SettingsActivity, 100)
            setPadding(
                TvUiHelper.dp(this@SettingsActivity, 10),
                0,
                TvUiHelper.dp(this@SettingsActivity, 10),
                0
            )
        }

        val plusBtn = TvUiHelper.createTvButton(
            context = this,
            label = "+",
            horizontalPaddingDp = 14,
            verticalPaddingDp = 6,
            textSizeSp = 16f
        ).apply {
            contentDescription = "$label increase"
            setOnClickListener { onPlus() }
        }

        row.addView(labelView)
        row.addView(minusBtn)
        row.addView(valueView)
        row.addView(plusBtn)
        parent.addView(row)
        return valueView
    }

    private fun addLedSideRow(
        parent: LinearLayout,
        label: String,
        onDelta: (Int) -> Unit
    ): TextView {
        val row = createRowContainer()
        val labelView = TextView(this).apply {
            text = label
            setTextColor(TvUiHelper.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val m5 = TvUiHelper.createTvButton(this, "−5", horizontalPaddingDp = 10, verticalPaddingDp = 6, textSizeSp = 14f).apply {
            contentDescription = "$label minus 5"
            layoutParams = TvUiHelper.linearParams(rightMarginDp = 6, context = this@SettingsActivity)
            setOnClickListener { onDelta(-5) }
        }
        val m1 = TvUiHelper.createTvButton(this, "−1", horizontalPaddingDp = 10, verticalPaddingDp = 6, textSizeSp = 14f).apply {
            contentDescription = "$label minus 1"
            setOnClickListener { onDelta(-1) }
        }

        val valueView = TextView(this).apply {
            setTextColor(TvUiHelper.ACCENT_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            minWidth = TvUiHelper.dp(this@SettingsActivity, 52)
        }

        val p1 = TvUiHelper.createTvButton(this, "+1", horizontalPaddingDp = 10, verticalPaddingDp = 6, textSizeSp = 14f).apply {
            contentDescription = "$label plus 1"
            layoutParams = TvUiHelper.linearParams(rightMarginDp = 6, context = this@SettingsActivity)
            setOnClickListener { onDelta(1) }
        }
        val p5 = TvUiHelper.createTvButton(this, "+5", horizontalPaddingDp = 10, verticalPaddingDp = 6, textSizeSp = 14f).apply {
            contentDescription = "$label plus 5"
            setOnClickListener { onDelta(5) }
        }

        row.addView(labelView)
        row.addView(m5)
        row.addView(m1)
        row.addView(valueView)
        row.addView(p1)
        row.addView(p5)
        parent.addView(row)
        return valueView
    }

    private fun addToggleRow(
        parent: LinearLayout,
        label: String,
        onToggle: () -> Unit
    ): Button {
        val row = createRowContainer()
        val labelView = TextView(this).apply {
            text = label
            setTextColor(TvUiHelper.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val toggleBtn = TvUiHelper.createTvButton(
            context = this,
            label = "ON",
            horizontalPaddingDp = 20,
            verticalPaddingDp = 6,
            textSizeSp = 14f
        ).apply {
            contentDescription = label
            setOnClickListener { onToggle() }
        }

        row.addView(labelView)
        row.addView(toggleBtn)
        parent.addView(row)
        return toggleBtn
    }

    private fun createRowContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = TvUiHelper.makeCardBackground(
                context = this@SettingsActivity,
                fillColor = TvUiHelper.BG_CANVAS,
                strokeColor = TvUiHelper.BORDER_SUBTLE,
                cornerDp = 10
            )
            setPadding(
                TvUiHelper.dp(this@SettingsActivity, 14),
                TvUiHelper.dp(this@SettingsActivity, 8),
                TvUiHelper.dp(this@SettingsActivity, 14),
                TvUiHelper.dp(this@SettingsActivity, 8)
            )
            layoutParams = TvUiHelper.linearParams(
                width = LinearLayout.LayoutParams.MATCH_PARENT,
                bottomMarginDp = 8,
                context = this@SettingsActivity
            )
        }
    }

    private fun cycleBaud(direction: Int) {
        val rates = LumaSyncConfig.SUPPORTED_BAUD_RATES
        val currentIdx = rates.indexOf(workingConfig.baudRate).let { if (it < 0) 0 else it }
        val nextIdx = (currentIdx + direction + rates.size) % rates.size
        workingConfig = workingConfig.copy(baudRate = rates[nextIdx])
        refreshAllLabels()
    }

    private fun refreshAllLabels() {
        baudValueText.text = "${workingConfig.baudRate}"
        smoothValueText.text = "%.2f".format(workingConfig.smoothing)
        val pct = (workingConfig.brightnessCap * 100f / 255f).roundToInt()
        brightnessValueText.text = "${workingConfig.brightnessCap} ($pct%)"
        leftValueText.text = "${workingConfig.leftCount}"
        topValueText.text = "${workingConfig.topCount}"
        rightValueText.text = "${workingConfig.rightCount}"
        bottomValueText.text = "${workingConfig.bottomCount}"

        val total = workingConfig.totalLeds
        if (total > 0) {
            totalLedsText.text = "Total LEDs: $total (matches NUM_LEDS in ambient_uno.ino)"
            totalLedsText.setTextColor(TvUiHelper.ACCENT_CYAN)
            saveButton.isEnabled = true
        } else {
            totalLedsText.text = "Total LEDs: 0 (configure at least 1 LED to save)"
            totalLedsText.setTextColor(TvUiHelper.ERROR_RED)
            saveButton.isEnabled = false
        }

        gammaButton.text = if (workingConfig.gammaEnabled) "ON" else "OFF"
        saturationValueText.text = "%.1fx".format(workingConfig.saturationBoost)
        letterboxButton.text = if (workingConfig.skipLetterbox) "ON" else "OFF"
    }
}
