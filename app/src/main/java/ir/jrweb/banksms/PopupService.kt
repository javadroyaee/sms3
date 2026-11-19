package ir.jrweb.banksms

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class PopupService : Service() {

    private var windowManager: WindowManager? = null
    private var popupView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastHandledKey: String = ""
    private var lastHandledTime: Long = 0L
    private var lastSmsId: Long = -1L
    private var smsObserver: ContentObserver? = null

    private val closeRunnable = Runnable {
        removePopup()
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        registerSmsObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        if (intent?.getBooleanExtra("ACTION_KEEP_ALIVE", false) == true) {
            return START_STICKY
        }

        val type = intent?.getStringExtra("TYPE") ?: return START_STICKY
        val amount = intent.getStringExtra("AMOUNT") ?: return START_STICKY
        val dedupKey = intent.getStringExtra("DEDUP_KEY") ?: "_"

        triggerPopupSafely(type, amount, dedupKey)
        return START_STICKY
    }

    private fun triggerPopupSafely(type: String, amount: String, dedupKey: String) {
        val now = System.currentTimeMillis()
        if (dedupKey == lastHandledKey && (now - lastHandledTime) < 8000L) {
            return
        }
        lastHandledKey = dedupKey
        lastHandledTime = now

        playSoftSound(type)
        showCenterPopup(type, amount)
    }

    private fun registerSmsObserver() {
        try {
            // ثبت آخرین شناسه پیامک موجود هنگام شروع سرویس
            lastSmsId = getLatestInboxSmsId()
            smsObserver = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    checkLatestInboxSms()
                }
            }
            contentResolver.registerContentObserver(
                Uri.parse("content://sms/inbox"),
                true,
                smsObserver!!
            )
        } catch (_: Exception) {
        }
    }

    private fun getLatestInboxSmsId(): Long {
        return try {
            contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id"),
                null,
                null,
                "_id DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun checkLatestInboxSms() {
        try {
            contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date"),
                null,
                null,
                "_id DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    if (id > lastSmsId) {
                        lastSmsId = id
                        val sender = cursor.getString(1) ?: ""
                        val body = cursor.getString(2) ?: ""
                        val date = cursor.getLong(3)

                        if (System.currentTimeMillis() - date < 30_000L) {
                            val parsed = BankSmsParser.parse(sender, body) ?: return
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                                triggerPopupSafely(parsed.type, parsed.formattedAmount, "_")
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun startForegroundNotification() {
        val channelId = "jrweb_bank_sms_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "سرویس مانیتورینگ پیامک بانکی",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("JRweb Bank SMS")
            .setContentText("فعال")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun playSoftSound(type: String) {
        try {
            // صدای بسیار خفیف و ملایم (ولوم ۳۵٪)
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 35)
            if (type == "DEPOSIT") {
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
                handler.postDelayed({
                    toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 130)
                    handler.postDelayed({ toneGen.release() }, 200)
                }, 110)
            } else {
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                handler.postDelayed({ toneGen.release() }, 200)
            }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCenterPopup(type: String, amount: String) {
        removePopup()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        popupView = inflater.inflate(R.layout.popup_overlay, null)

        val container = popupView!!.findViewById<LinearLayout>(R.id.popupContainer)
        val tvAmount = popupView!!.findViewById<TextView>(R.id.tvPopupAmount)

        val bgDrawable = if (type == "DEPOSIT") {
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#1E9E49"), Color.parseColor("#136F32"))
            ).apply {
                cornerRadius = 56f
                setStroke(3, Color.parseColor("#55FFFFFF"))
            }
        } else {
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#F56A00"), Color.parseColor("#C84600"))
            ).apply {
                cornerRadius = 56f
                setStroke(3, Color.parseColor("#55FFFFFF"))
            }
        }
        container.background = bgDrawable
        tvAmount.text = amount

        // انیمیشن نرم ظاهر شدن در وسط صفحه
        container.scaleX = 0.88f
        container.scaleY = 0.88f
        container.alpha = 0f
        container.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(220)
            .start()

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f

        container.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    params.x = initialX + dx
                    params.y = initialY + dy
                    val dist = maxOf(abs(dx), abs(dy))
                    view.alpha = (1f - (dist / 650f)).coerceIn(0.25f, 1f)
                    try {
                        windowManager?.updateViewLayout(popupView, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - touchStartX)
                    val dy = abs(event.rawY - touchStartY)
                    // با لمس ساده یا کشیدن به هر سمت فوراً بسته می‌شود
                    if (dx > 120 || dy > 120 || (dx < 15 && dy < 15)) {
                        removePopup()
                    } else {
                        params.x = 0
                        params.y = 0
                        view.alpha = 1f
                        try {
                            windowManager?.updateViewLayout(popupView, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(popupView, params)
        handler.postDelayed(closeRunnable, 60_000L)
    }

    private fun removePopup() {
        handler.removeCallbacks(closeRunnable)
        popupView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
            popupView = null
        }
    }

    override fun onDestroy() {
        smsObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {
            }
        }
        removePopup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}