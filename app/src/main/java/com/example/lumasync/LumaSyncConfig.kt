package com.example.lumasync

import android.content.Context

data class LumaSyncConfig(
    val leftCount: Int = DEFAULT_LEFT,
    val topCount: Int = DEFAULT_TOP,
    val rightCount: Int = DEFAULT_RIGHT,
    val bottomCount: Int = DEFAULT_BOTTOM,
    val baudRate: Int = DEFAULT_BAUD_RATE,
    val smoothing: Float = DEFAULT_SMOOTHING,
    val brightnessCap: Int = DEFAULT_BRIGHTNESS_CAP,
    val gammaEnabled: Boolean = DEFAULT_GAMMA_ENABLED,
    val saturationBoost: Float = DEFAULT_SATURATION_BOOST,
    val skipLetterbox: Boolean = DEFAULT_SKIP_LETTERBOX
) {
    val totalLeds: Int
        get() = leftCount + topCount + rightCount + bottomCount

    companion object {
        const val DEFAULT_LEFT = 27
        const val DEFAULT_TOP = 27
        const val DEFAULT_RIGHT = 27
        const val DEFAULT_BOTTOM = 27

        const val DEFAULT_BAUD_RATE = 115200
        const val DEFAULT_SMOOTHING = 0.50f
        const val DEFAULT_BRIGHTNESS_CAP = 255
        const val DEFAULT_GAMMA_ENABLED = true
        const val DEFAULT_SATURATION_BOOST = 1.25f
        const val DEFAULT_SKIP_LETTERBOX = true

        const val CAPTURE_WIDTH = 128
        const val CAPTURE_HEIGHT = 72
        const val SAMPLE_DX = 12
        const val SAMPLE_DY = 8
        const val FRAME_INTERVAL_MS = 33L
        const val UNO_RESET_DELAY_MS = 2000L

        val SUPPORTED_BAUD_RATES = intArrayOf(115200, 230400, 460800, 500000, 921600)
    }
}

object LumaSyncPrefs {
    private const val PREFS_NAME = "lumasync_prefs"
    private const val KEY_LEFT = "left_count"
    private const val KEY_TOP = "top_count"
    private const val KEY_RIGHT = "right_count"
    private const val KEY_BOTTOM = "bottom_count"
    private const val KEY_BAUD_RATE = "baud_rate"
    private const val KEY_SMOOTHING = "smoothing"
    private const val KEY_BRIGHTNESS_CAP = "brightness_cap"
    private const val KEY_GAMMA_ENABLED = "gamma_enabled"
    private const val KEY_SATURATION_BOOST = "saturation_boost"
    private const val KEY_SKIP_LETTERBOX = "skip_letterbox"

    fun load(context: Context): LumaSyncConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return LumaSyncConfig(
            leftCount = prefs.getInt(KEY_LEFT, LumaSyncConfig.DEFAULT_LEFT).coerceIn(0, 300),
            topCount = prefs.getInt(KEY_TOP, LumaSyncConfig.DEFAULT_TOP).coerceIn(0, 300),
            rightCount = prefs.getInt(KEY_RIGHT, LumaSyncConfig.DEFAULT_RIGHT).coerceIn(0, 300),
            bottomCount = prefs.getInt(KEY_BOTTOM, LumaSyncConfig.DEFAULT_BOTTOM).coerceIn(0, 300),
            baudRate = prefs.getInt(KEY_BAUD_RATE, LumaSyncConfig.DEFAULT_BAUD_RATE),
            smoothing = prefs.getFloat(KEY_SMOOTHING, LumaSyncConfig.DEFAULT_SMOOTHING).coerceIn(0f, 0.95f),
            brightnessCap = prefs.getInt(KEY_BRIGHTNESS_CAP, LumaSyncConfig.DEFAULT_BRIGHTNESS_CAP).coerceIn(1, 255),
            gammaEnabled = prefs.getBoolean(KEY_GAMMA_ENABLED, LumaSyncConfig.DEFAULT_GAMMA_ENABLED),
            saturationBoost = prefs.getFloat(KEY_SATURATION_BOOST, LumaSyncConfig.DEFAULT_SATURATION_BOOST).coerceIn(1.0f, 2.0f),
            skipLetterbox = prefs.getBoolean(KEY_SKIP_LETTERBOX, LumaSyncConfig.DEFAULT_SKIP_LETTERBOX)
        )
    }

    fun save(context: Context, config: LumaSyncConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_LEFT, config.leftCount)
            .putInt(KEY_TOP, config.topCount)
            .putInt(KEY_RIGHT, config.rightCount)
            .putInt(KEY_BOTTOM, config.bottomCount)
            .putInt(KEY_BAUD_RATE, config.baudRate)
            .putFloat(KEY_SMOOTHING, config.smoothing)
            .putInt(KEY_BRIGHTNESS_CAP, config.brightnessCap)
            .putBoolean(KEY_GAMMA_ENABLED, config.gammaEnabled)
            .putFloat(KEY_SATURATION_BOOST, config.saturationBoost)
            .putBoolean(KEY_SKIP_LETTERBOX, config.skipLetterbox)
            .apply()
    }
}
