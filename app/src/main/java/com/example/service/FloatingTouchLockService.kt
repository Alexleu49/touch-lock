package com.example.service

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot

data class TouchLockState(
    val isRunning: Boolean = false,
    val isLocked: Boolean = false,
    val isButtonHidden: Boolean = false,
    val remainingSeconds: Int = 10
)

class FloatingTouchLockService : Service() {

    companion object {
        const val CHANNEL_ID = "touch_lock_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val ACTION_TOGGLE_LOCK = "com.example.service.ACTION_TOGGLE_LOCK"
        const val ACTION_UNLOCK = "com.example.service.ACTION_UNLOCK"

        private val _state = MutableStateFlow(TouchLockState())
        val state: StateFlow<TouchLockState> = _state.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, FloatingTouchLockService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, FloatingTouchLockService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun toggleLock(context: Context) {
            val intent = Intent(context, FloatingTouchLockService::class.java).apply {
                action = ACTION_TOGGLE_LOCK
            }
            context.startService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: TouchLockPreferences

    // Button Overlay
    private var buttonView: FrameLayout? = null
    private var buttonIcon: ImageView? = null
    private var buttonBadge: TextView? = null
    private lateinit var buttonParams: WindowManager.LayoutParams

    // Fullscreen Touch Barrier Overlay
    private var barrierView: FrameLayout? = null
    private var hintTextView: TextView? = null
    private lateinit var barrierParams: WindowManager.LayoutParams

    private var isLocked = false
    private var isButtonHidden = false
    private var countDownTimer: CountDownTimer? = null
    private var touchSlop = 16

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = TouchLockPreferences(this)
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        initBarrierView()
        initButtonView()

        _state.value = TouchLockState(
            isRunning = true,
            isLocked = false,
            isButtonHidden = false,
            remainingSeconds = prefs.idleTimeoutSeconds
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_LOCK -> {
                toggleLockState()
            }
            ACTION_UNLOCK -> {
                if (isLocked) {
                    toggleLockState()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelCountdown()
        removeBarrierView()
        removeButtonView()
        _state.value = TouchLockState(isRunning = false)
        super.onDestroy()
    }

    // ==========================================
    // FLOATING BUTTON INITIALIZATION & DRAGGING
    // ==========================================

    @SuppressLint("ClickableViewAccessibility")
    private fun initButtonView() {
        val sizePx = (64 * resources.displayMetrics.density).toInt()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        buttonParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - sizePx - 48)
            y = (resources.displayMetrics.heightPixels * 0.35f).toInt()
        }

        val frame = FrameLayout(this)
        frame.layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)

        // Inner Icon
        val icon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                (32 * resources.displayMetrics.density).toInt(),
                (32 * resources.displayMetrics.density).toInt(),
                Gravity.CENTER
            )
            setImageResource(R.drawable.ic_lock_open)
            setColorFilter(Color.parseColor("#00E5FF"))
        }

        // Countdown Badge
        val badge = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = (4 * resources.displayMetrics.density).toInt()
            }
            textSize = 10f
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }

        frame.addView(icon)
        frame.addView(badge)
        buttonIcon = icon
        buttonBadge = badge
        buttonView = frame

        updateButtonStyle(isLocked = false)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        frame.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = buttonParams.x
                    initialY = buttonParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false

                    // If locked, reset idle timer on touch
                    if (isLocked) {
                        resetCountdown()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    if (hypot(deltaX.toDouble(), deltaY.toDouble()) > touchSlop) {
                        isDragging = true
                        buttonParams.x = (initialX + deltaX).toInt()
                        buttonParams.y = (initialY + deltaY).toInt()
                        windowManager.updateViewLayout(v, buttonParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Tap on button -> toggle lock!
                        toggleLockState()
                    } else if (isLocked) {
                        resetCountdown()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(buttonView, buttonParams)
    }

    // ==========================================
    // FULLSCREEN BARRIER INITIALIZATION
    // ==========================================

    @SuppressLint("ClickableViewAccessibility")
    private fun initBarrierView() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        barrierParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val barrier = FrameLayout(this).apply {
            // Invisible touch-blocking barrier
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Centered hint banner that flashes when user taps
        val banner = TextView(this).apply {
            val padH = (20 * resources.displayMetrics.density).toInt()
            val padV = (12 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            text = "Сенсор заблокирован\nДвойной тап для вызова кнопки"

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24 * resources.displayMetrics.density
                setColor(Color.parseColor("#CC0D1B2A"))
                setStroke((1.5f * resources.displayMetrics.density).toInt(), Color.parseColor("#00E5FF"))
            }
            background = bg

            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            alpha = 0f
            visibility = View.GONE
        }

        barrier.addView(banner)
        hintTextView = banner
        barrierView = barrier

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked && isButtonHidden) {
                    showDoubleTapHint()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Requirement 3: Return button on double tap anywhere on screen
                if (isLocked) {
                    resurrectButton()
                }
                return true
            }
        })

        barrier.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            // Block all touches from reaching apps underneath
            true
        }
    }

    // ==========================================
    // LOCK / UNLOCK TOGGLE LOGIC
    // ==========================================

    private fun toggleLockState() {
        isLocked = !isLocked
        performHaptic(if (isLocked) 60 else 30)

        if (isLocked) {
            // Lock enabled: disable screen touch everywhere except button
            attachBarrierView()
            ensureButtonOnTop()
            updateButtonStyle(isLocked = true)
            isButtonHidden = false
            buttonView?.apply {
                visibility = View.VISIBLE
                alpha = 1f
                scaleX = 1f
                scaleY = 1f
            }
            showBarrierToast("Сенсор отключен\nКнопка скроется через 10 сек")
            resetCountdown()
        } else {
            // Unlock: screen touch returned to normal state
            cancelCountdown()
            detachBarrierView()
            isButtonHidden = false
            buttonView?.apply {
                visibility = View.VISIBLE
                alpha = 1f
                scaleX = 1f
                scaleY = 1f
            }
            updateButtonStyle(isLocked = false)
        }

        updateNotification()
        _state.value = TouchLockState(
            isRunning = true,
            isLocked = isLocked,
            isButtonHidden = isButtonHidden,
            remainingSeconds = prefs.idleTimeoutSeconds
        )
    }

    private fun updateButtonStyle(isLocked: Boolean) {
        val density = resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (isLocked) {
                // Vibrant red / amber lock state
                setColor(Color.parseColor("#E67F1D1D"))
                setStroke((3 * density).toInt(), Color.parseColor("#EF4444"))
            } else {
                // Sleek cyber cyan unlocked state
                setColor(Color.parseColor("#E60F172A"))
                setStroke((2.5f * density).toInt(), Color.parseColor("#00E5FF"))
            }
        }
        buttonView?.background = bg

        if (isLocked) {
            buttonIcon?.setImageResource(R.drawable.ic_lock_closed)
            buttonIcon?.setColorFilter(Color.parseColor("#FCA5A5"))
            buttonBadge?.visibility = View.VISIBLE
            buttonBadge?.text = "${prefs.idleTimeoutSeconds}s"
        } else {
            buttonIcon?.setImageResource(R.drawable.ic_lock_open)
            buttonIcon?.setColorFilter(Color.parseColor("#00E5FF"))
            buttonBadge?.visibility = View.GONE
        }

        // Quick pop animation
        buttonView?.let { btn ->
            val scaleX = ObjectAnimator.ofFloat(btn, "scaleX", 0.85f, 1.08f, 1f)
            val scaleY = ObjectAnimator.ofFloat(btn, "scaleY", 0.85f, 1.08f, 1f)
            AnimatorSet().apply {
                playTogether(scaleX, scaleY)
                duration = 240
                interpolator = OvershootInterpolator()
                start()
            }
        }
    }

    // ==========================================
    // AUTO-HIDE COUNTDOWN & RESURRECTION
    // ==========================================

    private fun resetCountdown() {
        cancelCountdown()
        val totalSeconds = prefs.idleTimeoutSeconds
        val totalMillis = totalSeconds * 1000L

        countDownTimer = object : CountDownTimer(totalMillis, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000).toInt() + 1
                buttonBadge?.text = "${sec}s"
                _state.value = _state.value.copy(remainingSeconds = sec)
            }

            override fun onFinish() {
                // Requirement 3: 10 seconds without touch on button -> hide button!
                hideButton()
            }
        }.start()
    }

    private fun cancelCountdown() {
        countDownTimer?.cancel()
        countDownTimer = null
    }

    private fun hideButton() {
        if (!isLocked) return
        isButtonHidden = true
        _state.value = _state.value.copy(isButtonHidden = true)

        buttonView?.animate()
            ?.alpha(0f)
            ?.scaleX(0.4f)
            ?.scaleY(0.4f)
            ?.setDuration(350)
            ?.withEndAction {
                buttonView?.visibility = View.GONE
            }
            ?.start()

        // Flash gentle hint so user knows how to wake it up
        showDoubleTapHint()
    }

    private fun resurrectButton() {
        if (!isLocked) return
        performHaptic(40)
        isButtonHidden = false

        buttonView?.apply {
            visibility = View.VISIBLE
            alpha = 0f
            scaleX = 0.5f
            scaleY = 0.5f
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(260)
                .setInterpolator(OvershootInterpolator())
                .start()
        }

        ensureButtonOnTop()
        showBarrierToast("Кнопка активна\nНажмите для разблокировки")
        resetCountdown()

        _state.value = _state.value.copy(
            isButtonHidden = false,
            remainingSeconds = prefs.idleTimeoutSeconds
        )
    }

    private fun showDoubleTapHint() {
        hintTextView?.apply {
            text = "Сенсор заблокирован\nДвойной тап для вызова кнопки"
            visibility = View.VISIBLE
            alpha = 0f
            animate()
                .alpha(1f)
                .setDuration(200)
                .withEndAction {
                    postDelayed({
                        animate()
                            .alpha(0f)
                            .setDuration(300)
                            .withEndAction { visibility = View.GONE }
                            .start()
                    }, 2200)
                }
                .start()
        }
    }

    private fun showBarrierToast(message: String) {
        hintTextView?.apply {
            text = message
            visibility = View.VISIBLE
            alpha = 0f
            animate()
                .alpha(1f)
                .setDuration(180)
                .withEndAction {
                    postDelayed({
                        animate()
                            .alpha(0f)
                            .setDuration(250)
                            .withEndAction { visibility = View.GONE }
                            .start()
                    }, 1800)
                }
                .start()
        }
    }

    // ==========================================
    // WINDOW MANAGER MANAGEMENT
    // ==========================================

    private fun attachBarrierView() {
        barrierView?.let { barrier ->
            if (barrier.parent == null) {
                windowManager.addView(barrier, barrierParams)
            }
        }
    }

    private fun detachBarrierView() {
        barrierView?.let { barrier ->
            if (barrier.parent != null) {
                windowManager.removeView(barrier)
            }
        }
    }

    private fun ensureButtonOnTop() {
        buttonView?.let { btn ->
            if (btn.parent != null) {
                windowManager.removeView(btn)
                windowManager.addView(btn, buttonParams)
            }
        }
    }

    private fun removeBarrierView() {
        detachBarrierView()
        barrierView = null
    }

    private fun removeButtonView() {
        buttonView?.let { btn ->
            if (btn.parent != null) {
                windowManager.removeView(btn)
            }
        }
        buttonView = null
    }

    // ==========================================
    // NOTIFICATION & FEEDBACK
    // ==========================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Touch Lock Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Управление плавающей кнопкой блокировки сенсора"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingTouchLockService::class.java).apply {
                action = ACTION_TOGGLE_LOCK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, FloatingTouchLockService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val lockTitle = if (isLocked) "Сенсор экрана ЗАБЛОКИРОВАН" else "Touch Lock: сенсор активен"
        val lockText = if (isLocked)
            "Нажмите на кнопку или дважды коснитесь экрана"
            else "Нажмите на плавающую кнопку для блокировки сенсора"

        val toggleActionTitle = if (isLocked) "Разблокировать" else "Заблокировать"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (isLocked) R.drawable.ic_lock_closed else R.drawable.ic_lock_open)
            .setContentTitle(lockTitle)
            .setContentText(lockText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                if (isLocked) R.drawable.ic_lock_open else R.drawable.ic_lock_closed,
                toggleActionTitle,
                toggleIntent
            )
            .addAction(R.drawable.ic_lock_closed, "Выключить", stopIntent)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun performHaptic(durationMs: Long) {
        if (!prefs.isVibrationEnabled) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }
}
