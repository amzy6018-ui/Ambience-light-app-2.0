package com.example.lumasync

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CaptureService : Service() {

    enum class StatusKind {
        IDLE,
        CONNECTING,
        RUNNING,
        STOPPED,
        ERROR
    }

    private var handlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var usbConnection: UsbDeviceConnection? = null
    private var usbPort: UsbSerialPort? = null

    private var config: LumaSyncConfig = LumaSyncConfig()
    private var frameProcessor: FrameProcessor? = null

    @Volatile
    private var isStopping = false

    @Volatile
    private var serialReady = false

    private var lastFrameMs = 0L
    private var stopReasonMessage: String? = null
    private var stopWasError = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopReasonMessage = getString(R.string.status_stopped)
            stopWasError = false
            stopSelf()
            return START_NOT_STICKY
        }

        config = LumaSyncPrefs.load(this)
        ensureNotificationChannel()
        startAsForeground(config)

        if (config.totalLeds <= 0) {
            failAndStop("Total LED count must be greater than 0.")
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (intent != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
        } else {
            null
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            failAndStop(getString(R.string.status_capture_denied))
            return START_NOT_STICKY
        }

        // Clean up any prior session if restarted while running
        cleanupResourcesSync(sendBlackFrame = false)
        isStopping = false
        serialReady = false
        stopReasonMessage = null
        stopWasError = false
        lastFrameMs = 0L

        val thread = HandlerThread("LumaSyncCapture").apply { start() }
        val handler = Handler(thread.looper)
        handlerThread = thread
        captureHandler = handler

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            projectionManager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain MediaProjection", e)
            null
        }

        if (projection == null) {
            failAndStop("Failed to obtain screen capture session.")
            return START_NOT_STICKY
        }
        mediaProjection = projection

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (!isStopping) {
                    stopReasonMessage = getString(R.string.status_stopped)
                    stopWasError = false
                    stopSelf()
                }
            }
        }
        projectionCallback = callback
        // Android 14 requirement: register callback before creating VirtualDisplay
        projection.registerCallback(callback, handler)

        val reader = ImageReader.newInstance(
            LumaSyncConfig.CAPTURE_WIDTH,
            LumaSyncConfig.CAPTURE_HEIGHT,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader = reader

        val densityDpi = resources.displayMetrics.densityDpi
        virtualDisplay = projection.createVirtualDisplay(
            "LumaSyncCapture",
            LumaSyncConfig.CAPTURE_WIDTH,
            LumaSyncConfig.CAPTURE_HEIGHT,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

        frameProcessor = FrameProcessor(config)
        isServiceRunning = true
        broadcastState(StatusKind.CONNECTING, getString(R.string.status_starting))

        handler.post {
            initSerialAndBeginCapture(reader, handler)
        }

        return START_NOT_STICKY
    }

    private fun initSerialAndBeginCapture(reader: ImageReader, handler: Handler) {
        if (isStopping) return

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
        if (driver == null) {
            failAndStop(getString(R.string.status_no_arduino))
            return
        }
        if (!usbManager.hasPermission(driver.device)) {
            failAndStop(getString(R.string.status_usb_denied))
            return
        }

        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            failAndStop("Unable to open USB connection to Arduino Uno.")
            return
        }

        val port = driver.ports.firstOrNull()
        if (port == null) {
            connection.close()
            failAndStop("No serial port found on USB device.")
            return
        }

        try {
            port.open(connection)
            port.setParameters(
                config.baudRate,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            usbConnection = connection
            usbPort = port
        } catch (e: Exception) {
            Log.e(TAG, "Error opening serial port", e)
            try {
                port.close()
            } catch (_: Exception) {
            }
            connection.close()
            failAndStop("Serial port error: ${e.message ?: "unknown error"}")
            return
        }

        // Arduino Uno resets when the serial port opens; wait 2 seconds in small slices
        // so Stop remains responsive.
        val waitEnd = SystemClock.elapsedRealtime() + LumaSyncConfig.UNO_RESET_DELAY_MS
        while (!isStopping && SystemClock.elapsedRealtime() < waitEnd) {
            try {
                Thread.sleep(100L)
            } catch (_: InterruptedException) {
                break
            }
        }
        if (isStopping) return

        serialReady = true
        reader.setOnImageAvailableListener({ imageReaderInstance ->
            onFrameAvailable(imageReaderInstance)
        }, handler)

        broadcastState(StatusKind.RUNNING, getString(R.string.status_running))
    }

    private fun onFrameAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquireLatestImage failed", e)
            null
        } ?: return

        try {
            if (isStopping || !serialReady) return

            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameMs < LumaSyncConfig.FRAME_INTERVAL_MS) {
                return
            }
            lastFrameMs = now

            val processor = frameProcessor ?: return
            val port = usbPort ?: return
            val planes = image.planes
            if (planes.isEmpty()) return

            val plane = planes[0]
            val packet = processor.processRgbaFrame(
                buffer = plane.buffer,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride
            )

            port.write(packet, SERIAL_WRITE_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Serial write failed", e)
            if (!isStopping) {
                failAndStop("Serial write error: ${e.message ?: "Arduino disconnected"}")
            }
        } finally {
            image.close()
        }
    }

    private fun failAndStop(message: String) {
        stopReasonMessage = message
        stopWasError = true
        stopSelf()
    }

    override fun onDestroy() {
        isStopping = true
        serialReady = false
        cleanupResourcesSync(sendBlackFrame = true)
        isServiceRunning = false

        val finalMessage = stopReasonMessage ?: getString(R.string.status_stopped)
        val kind = if (stopWasError) StatusKind.ERROR else StatusKind.STOPPED
        broadcastState(kind, finalMessage)

        super.onDestroy()
    }

    private fun cleanupResourcesSync(sendBlackFrame: Boolean) {
        val handler = captureHandler
        val thread = handlerThread

        if (handler != null && thread != null && thread.isAlive) {
            val latch = CountDownLatch(1)
            handler.post {
                try {
                    if (sendBlackFrame) {
                        val port = usbPort
                        if (port != null && config.totalLeds > 0) {
                            try {
                                val blackPacket = AdalightProtocol.buildBlackPacket(config.totalLeds)
                                port.write(blackPacket, SERIAL_WRITE_TIMEOUT_MS)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to send black frame on stop", e)
                            }
                        }
                    }
                    closeSerial()
                } finally {
                    latch.countDown()
                }
            }
            try {
                latch.await(600L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        } else {
            closeSerial()
        }

        try {
            imageReader?.setOnImageAvailableListener(null, null)
        } catch (_: Exception) {
        }
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        val proj = mediaProjection
        val cb = projectionCallback
        if (proj != null && cb != null) {
            try {
                proj.unregisterCallback(cb)
            } catch (_: Exception) {
            }
        }
        projectionCallback = null

        try {
            proj?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null

        thread?.quitSafely()
        handlerThread = null
        captureHandler = null
    }

    private fun closeSerial() {
        try {
            usbPort?.close()
        } catch (_: Exception) {
        }
        usbPort = null
        try {
            usbConnection?.close()
        } catch (_: Exception) {
        }
        usbConnection = null
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    private fun startAsForeground(cfg: LumaSyncConfig) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, CaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.mipmap.ic_launcher),
            getString(R.string.notif_action_stop),
            stopPi
        ).build()

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, cfg.totalLeds, cfg.baudRate))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(stopAction)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun broadcastState(kind: StatusKind, message: String) {
        lastStatusKind = kind
        lastStatusMessage = message
        val intent = Intent(ACTION_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS_KIND, kind.name)
            putExtra(EXTRA_STATUS_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "lumasync_capture_channel"
        private const val NOTIFICATION_ID = 1001
        private const val SERIAL_WRITE_TIMEOUT_MS = 250

        const val ACTION_STOP = "com.example.lumasync.ACTION_STOP"
        const val ACTION_STATE_CHANGED = "com.example.lumasync.ACTION_STATE_CHANGED"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_STATUS_KIND = "status_kind"
        const val EXTRA_STATUS_MESSAGE = "status_message"

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        @Volatile
        var lastStatusKind: StatusKind = StatusKind.IDLE
            private set

        @Volatile
        var lastStatusMessage: String? = null
            private set
    }
}
