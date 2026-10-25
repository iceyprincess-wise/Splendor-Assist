package com.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.assistant.overlay.bridge.InterceptionControlRoomBridge
import com.assistant.overlay.interceptor.InterceptionRuntimeRegistry
import com.assistant.overlay.repository.GoalkeeperRepository
import com.assistant.overlay.repository.GoalkeeperRuntimeState
import com.assistant.overlay.repository.InterceptionRepository

class ModMenuController(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var menuView: View? = null
    private var isShown = false

    private val smartRepo by lazy { SmartAssistRepository(context) }
    private val goalkeeperRepo by lazy { GoalkeeperRepository(context) }
    private val interceptionRepo by lazy { InterceptionRepository(context) }

    fun show() {
        if (isShown) return
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager == null) return

        val layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 120
        }

        menuView = createView()
        try {
            windowManager?.addView(menuView, layoutParams)
            isShown = true
        } catch (_: Throwable) {
            isShown = false
        }
    }

    fun hide() {
        if (!isShown || menuView == null) return
        try {
            windowManager?.removeView(menuView)
        } catch (_: Throwable) {}
        menuView = null
        isShown = false
    }

    private fun createView(): View {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE0A0E17"))
            setPadding(24, 20, 24, 20)
        }

        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(context).apply {
            text = "⚡ SPLENDOR MOD MENU"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val toggleBtn = Button(context).apply {
            text = "―"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 14f
        }

        headerLayout.addView(title)
        headerLayout.addView(toggleBtn)
        mainLayout.addView(headerLayout)

        val contentScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(480, 600)
        }

        val contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val swSmart = Switch(context).apply {
            text = "Smart Assist Engine"
            setTextColor(Color.WHITE)
            isChecked = SmartAssistRepository.enabled()
            setOnCheckedChangeListener { _, isChecked ->
                smartRepo.updateEnabled(isChecked)
            }
        }
        contentContainer.addView(swSmart)

        val swPanic = Switch(context).apply {
            text = "Panic Emergency Mode"
            setTextColor(Color.parseColor("#FF5252"))
            isChecked = SmartAssistRepository.panicActive()
            setOnCheckedChangeListener { _, isChecked ->
                smartRepo.updatePanicMode(isChecked)
            }
        }
        contentContainer.addView(swPanic)

        val swKeeper = Switch(context).apply {
            text = "Omnipotent Goalkeeper"
            setTextColor(Color.WHITE)
            isChecked = GoalkeeperRuntimeState.enabled
            setOnCheckedChangeListener { _, isChecked ->
                goalkeeperRepo.updateEnabled(isChecked)
            }
        }
        contentContainer.addView(swKeeper)

        val swIntercept = Switch(context).apply {
            text = "Auto Interception"
            setTextColor(Color.WHITE)
            isChecked = InterceptionRuntimeRegistry.enabled
            setOnCheckedChangeListener { _, isChecked ->
                interceptionRepo.updateEnabled(isChecked)
            }
        }
        contentContainer.addView(swIntercept)

        val tvAuthority = TextView(context).apply {
            text = "Engine Authority Level: ${SmartAssistRepository.configuration().authority}"
            setTextColor(Color.parseColor("#B8CCE6"))
            textSize = 12f
            setPadding(0, 16, 0, 4)
        }
        contentContainer.addView(tvAuthority)

        val seekAuthority = SeekBar(context).apply {
            max = 100
            progress = SmartAssistRepository.configuration().authority
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    tvAuthority.text = "Engine Authority Level: $progress"
                    smartRepo.updateAuthority(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        contentContainer.addView(seekAuthority)

        val tvPass = TextView(context).apply {
            text = "Pass Force Threshold: ${SmartAssistRepository.configuration().passThreshold}"
            setTextColor(Color.parseColor("#B8CCE6"))
            textSize = 12f
            setPadding(0, 12, 0, 4)
        }
        contentContainer.addView(tvPass)

        val seekPass = SeekBar(context).apply {
            max = 100
            progress = SmartAssistRepository.configuration().passThreshold
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    tvPass.text = "Pass Force Threshold: $progress"
                    val cfg = SmartAssistRepository.configuration()
                    smartRepo.updateThresholds(progress, cfg.shotThreshold, cfg.crossThreshold)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        contentContainer.addView(seekPass)

        val tvStatus = TextView(context).apply {
            text = "LIVE ENGINE STATUS: ACTIVE"
            setTextColor(Color.parseColor("#40E36A"))
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 0)
        }
        contentContainer.addView(tvStatus)

        contentScroll.addView(contentContainer)
        mainLayout.addView(contentScroll)

        toggleBtn.setOnClickListener {
            if (contentScroll.visibility == View.VISIBLE) {
                contentScroll.visibility = View.GONE
                toggleBtn.text = "＋"
            } else {
                contentScroll.visibility = View.VISIBLE
                toggleBtn.text = "―"
            }
        }

        headerLayout.setOnTouchListener { _, event ->
            val lp = mainLayout.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = initialX + (event.rawX - initialTouchX).toInt()
                    lp.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(mainLayout, lp)
                    true
                }
                else -> false
            }
        }

        return mainLayout
    }
}
