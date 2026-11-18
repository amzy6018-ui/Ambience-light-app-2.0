package com.example.lumasync

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hoho.android.usbserial.driver.UsbSerialProber

class MainActivity : Activity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var configSummaryText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var settingsButton: Button

    private var usbReceiverRegistered = false
    private var stateReceiverRegistered = false
    private var notificationPromptedThisSession = false

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            unregisterUsbReceiverSafely()

            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                requestScreenCapture()
            } else {
                updateStatus(
                    CaptureService.StatusKind.ERROR,
                    getString(R.string.status_usb_denied)
                )
            }
        }
    }

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CaptureService.ACTION_STATE_CHANGED) return
            val kindName = intent.getStringExtra(CaptureService.EXTRA_STATUS_KIND)
            val msg = intent.getStringExtra(CaptureService.EXTRA_STATUS_MESSAGE) ?: return
            val kind = runCatching {
                CaptureService.StatusKind.valueOf(kindName ?: "")
            }.getOrDefault(CaptureService.StatusKind.IDLE)
            updateStatus(kind, msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        startButton.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        registerStateReceiver()
        refreshConfigSummary()
        syncStatusFromService()
    }

    override fun onStop() {
        super.onStop()
        unregisterStateReceiverSafely()
        unregisterUsbReceiverSafely()
    }

    private fun buildUi() {
        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(TvUiHelper.BG_CANVAS)
            isFillViewport = true
        }

        val outerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                TvUiHelper.dp(this@MainActivity, 32),
                TvUiHelper.dp(this@MainActivity, 28),
                TvUiHelper.dp(this@MainActivity, 32),
                TvUiHelper.dp(this@MainActivity, 28)
            )
        }

        val mainCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = TvUiHelper.makeCardBackground(this@MainActivity)
            setPadding(
                TvUiHelper.dp(this@MainActivity, 28),
                TvUiHelper.dp(this@MainActivity, 24),
                TvUiHelper.dp(this@MainActivity, 28),
                TvUiHelper.dp(this@MainActivity, 24)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // Constrain comfortably for wide TV screens while adapting to smaller screens
                val maxWidthPx = TvUiHelper.dp(this@MainActivity, 720)
                val screenWidth = resources.displayMetrics.widthPixels -
                    TvUiHelper.dp(this@MainActivity, 64)
                width = minOf(maxWidthPx, screenWidth).coerceAtLeast(
                    TvUiHelper.dp(this@MainActivity, 280)
                )
            }
        }

        // Header row
        val titleView = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(TvUiHelper.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subtitleView = TextView(this).apply {
            text = getString(R.string.app_subtitle)
            setTextColor(TvUiHelper.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = TvUiHelper.linearParams(
                topMarginDp = 4,
                bottomMarginDp = 16,
                context = this@MainActivity
            )
        }

        configSummaryText = TextView(this).apply {
            setTextColor(TvUiHelper.ACCENT_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            background = TvUiHelper.makeCardBackground(
                context = this@MainActivity,
                fillColor = TvUiHelper.BG_CANVAS,
                strokeColor = TvUiHelper.BORDER_SUBTLE,
                cornerDp = 8
            )
            setPadding(
                TvUiHelper.dp(this@MainActivity, 14),
                TvUiHelper.dp(this@MainActivity, 10),
                TvUiHelper.dp(this@MainActivity, 14),
                TvUiHelper.dp(this@MainActivity, 10)
            )
            layoutParams = TvUiHelper.linearParams(
                width = LinearLayout.LayoutParams.MATCH_PARENT,
                bottomMarginDp = 16,
                context = this@MainActivity
            )
        }

        // Status panel
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = TvUiHelper.makeCardBackground(
                context = this@MainActivity,
                fillColor = TvUiHelper.BG_CANVAS,
                strokeColor = TvUiHelper.BORDER_SUBTLE,
                cornerDp = 10
            )
            setPadding(
                TvUiHelper.dp(this@MainActivity, 16),
                TvUiHelper.dp(this@MainActivity, 14),
                TvUiHelper.dp(this@MainActivity, 16),
                TvUiHelper.dp(this@MainActivity, 14)
            )
            layoutParams = TvUiHelper.linearParams(
                width = LinearLayout.LayoutParams.MATCH_PARENT,
                bottomMarginDp = 22,
                context = this@MainActivity
            )
        }

        statusDot = View(this).apply {
            val size = TvUiHelper.dp(this@MainActivity, 12)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = TvUiHelper.dp(this@MainActivity, 12)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(TvUiHelper.TEXT_SECONDARY)
            }
        }

        statusText = TextView(this).apply {
            text = getString(R.string.status_idle)
            setTextColor(TvUiHelper.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        statusRow.addView(statusDot)
        statusRow.addView(statusText)

        // Primary D-pad action buttons row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = TvUiHelper.linearParams(
                width = LinearLayout.LayoutParams.MATCH_PARENT,
                bottomMarginDp = 18,
                context = this@MainActivity
            )
        }

        startButton = TvUiHelper.createTvButton(
            context = this,
            label = getString(R.string.btn_start),
            normalColor = TvUiHelper.BG_PRIMARY,
            focusedColor = TvUiHelper.BG_PRIMARY_FOCUSED,
            focusedBorderColor = TvUiHelper.TEXT_PRIMARY
        ).apply {
            id = View.generateViewId()
            contentDescription = getString(R.string.btn_start)
            layoutParams = TvUiHelper.linearParams(
                width = 0,
                height = LinearLayout.LayoutParams.WRAP_CONTENT,
                weight = 1.2f,
                rightMarginDp = 10,
                context = this@MainActivity
            )
            setOnClickListener { beginStartFlow() }
        }

        stopButton = TvUiHelper.createTvButton(
            context = this,
            label = getString(R.string.btn_stop),
            normalColor = TvUiHelper.BG_DANGER,
            focusedColor = TvUiHelper.BG_DANGER_FOCUSED,
            focusedBorderColor = TvUiHelper.TEXT_PRIMARY
        ).apply {
            id = View.generateViewId()
            contentDescription = getString(R.string.btn_stop)
            layoutParams = TvUiHelper.linearParams(
                width = 0,
                height = LinearLayout.LayoutParams.WRAP_CONTENT,
                weight = 1f,
                rightMarginDp = 10,
                context = this@MainActivity
            )
            setOnClickListener { stopCaptureService() }
        }

        settingsButton = TvUiHelper.createTvButton(
            context = this,
            label = getString(R.string.btn_settings),
            normalColor = TvUiHelper.BG_CONTROL,
            focusedColor = TvUiHelper.BG_CONTROL_FOCUSED,
            focusedBorderColor = TvUiHelper.ACCENT_CYAN
        ).apply {
            id = View.generateViewId()
            contentDescription = getString(R.string.btn_settings)
            layoutParams = TvUiHelper.linearParams(
                width = 0,
                height = LinearLayout.LayoutParams.WRAP_CONTENT,
                weight = 1f,
                context = this@MainActivity
            )
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }

        startButton.nextFocusRightId = stopButton.id
        stopButton.nextFocusLeftId = startButton.id
        stopButton.nextFocusRightId = settingsButton.id
        settingsButton.nextFocusLeftId = stopButton.id

        buttonRow.addView(startButton)
        buttonRow.addView(stopButton)
        buttonRow.addView(settingsButton)

        val footerHint = TextView(this).apply {
            text = "Adalight USB Serial · WS2812B GRB (Pin 6) · Frame 128×72 @ ~30 fps"
            setTextColor(TvUiHelper.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = TvUiHelper.linearParams(
                width = LinearLayout.LayoutParams.MATCH_PARENT,
                context = this@MainActivity
            )
        }

        mainCard.addView(titleView)
        mainCard.addView(subtitleView)
        mainCard.addView(configSummaryText)
        mainCard.addView(statusRow)
        mainCard.addView(buttonRow)
        mainCard.addView(footerHint)

        outerContainer.addView(mainCard)
        rootScroll.addView(outerContainer)
        setContentView(rootScroll)
    }

    private fun refreshConfigSummary() {
        val cfg = LumaSyncPrefs.load(this)
        val gammaText = if (cfg.gammaEnabled) "Gamma 2.2" else "Linear"
        val letterboxText = if (cfg.skipLetterbox) "Skip Black Bars" else "Full Edge"
        configSummaryText.text =
            "${cfg.totalLeds} LEDs (${cfg.leftCount}L · ${cfg.topCount}T · ${cfg.rightCount}R · ${cfg.bottomCount}B)  |  " +
                "${cfg.baudRate} baud  |  Smooth ${"%.2f".format(cfg.smoothing)}  |  $gammaText  |  $letterboxText"
    }

    private fun syncStatusFromService() {
        if (CaptureService.isServiceRunning) {
            val msg = CaptureService.lastStatusMessage ?: getString(R.string.status_running)
            val kind = CaptureService.lastStatusKind
            updateStatus(kind, msg)
        } else {
            val lastKind = CaptureService.lastStatusKind
            val lastMsg = CaptureService.lastStatusMessage
            if (lastMsg != null && (lastKind == CaptureService.StatusKind.ERROR ||
                    lastKind == CaptureService.StatusKind.STOPPED)
            ) {
                updateStatus(lastKind, lastMsg)
            } else {
                updateStatus(CaptureService.StatusKind.IDLE, getString(R.string.status_idle))
            }
        }
    }

    private fun beginStartFlow() {
        // Request POST_NOTIFICATIONS runtime permission on API 33+ before starting foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationPromptedThisSession &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPromptedThisSession = true
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_POST_NOTIFICATIONS
            )
            return
        }
        continueUsbCheckAndStart()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            continueUsbCheckAndStart()
        }
    }

    private fun continueUsbCheckAndStart() {
        val cfg = LumaSyncPrefs.load(this)
        if (cfg.totalLeds <= 0) {
            updateStatus(
                CaptureService.StatusKind.ERROR,
                "Total LED count is 0. Open Settings and configure at least 1 LED."
            )
            return
        }

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
        if (driver == null) {
            updateStatus(CaptureService.StatusKind.ERROR, getString(R.string.status_no_arduino))
            return
        }

        val device: UsbDevice = driver.device
        if (usbManager.hasPermission(device)) {
            requestScreenCapture()
        } else {
            requestUsbPermission(usbManager, device)
        }
    }

    private fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice) {
        updateStatus(
            CaptureService.StatusKind.CONNECTING,
            getString(R.string.status_requesting_usb)
        )
        registerUsbReceiver()

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun requestScreenCapture() {
        updateStatus(
            CaptureService.StatusKind.CONNECTING,
            getString(R.string.status_requesting_capture)
        )
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = projectionManager.createScreenCaptureIntent()
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(captureIntent, REQ_MEDIA_PROJECTION)
        } catch (_: ActivityNotFoundException) {
            updateStatus(
                CaptureService.StatusKind.ERROR,
                getString(R.string.status_capture_unavailable)
            )
        }
    }

    @Deprecated("Required for plain android.app.Activity without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_MEDIA_PROJECTION) return

        if (resultCode != RESULT_OK || data == null) {
            updateStatus(
                CaptureService.StatusKind.ERROR,
                getString(R.string.status_capture_denied)
            )
            return
        }

        updateStatus(CaptureService.StatusKind.CONNECTING, getString(R.string.status_starting))
        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(serviceIntent)
    }

    private fun stopCaptureService() {
        stopService(Intent(this, CaptureService::class.java))
        updateStatus(CaptureService.StatusKind.STOPPED, getString(R.string.status_stopped))
    }

    private fun updateStatus(kind: CaptureService.StatusKind, message: String) {
        statusText.text = message
        val dotColor = when (kind) {
            CaptureService.StatusKind.RUNNING -> TvUiHelper.SUCCESS_GREEN
            CaptureService.StatusKind.CONNECTING -> TvUiHelper.ACCENT_AMBER
            CaptureService.StatusKind.ERROR -> TvUiHelper.ERROR_RED
            CaptureService.StatusKind.IDLE,
            CaptureService.StatusKind.STOPPED -> TvUiHelper.TEXT_SECONDARY
        }
        (statusDot.background as? GradientDrawable)?.setColor(dotColor)
    }

    private fun registerUsbReceiver() {
        if (usbReceiverRegistered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, filter)
        }
        usbReceiverRegistered = true
    }

    private fun unregisterUsbReceiverSafely() {
        if (!usbReceiverRegistered) return
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (_: IllegalArgumentException) {
        }
        usbReceiverRegistered = false
    }

    private fun registerStateReceiver() {
        if (stateReceiverRegistered) return
        val filter = IntentFilter(CaptureService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(serviceStateReceiver, filter)
        }
        stateReceiverRegistered = true
    }

    private fun unregisterStateReceiverSafely() {
        if (!stateReceiverRegistered) return
        try {
            unregisterReceiver(serviceStateReceiver)
        } catch (_: IllegalArgumentException) {
        }
        stateReceiverRegistered = false
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.lumasync.USB_PERMISSION"
        private const val REQ_MEDIA_PROJECTION = 2001
        private const val REQ_POST_NOTIFICATIONS = 2002
    }
}
