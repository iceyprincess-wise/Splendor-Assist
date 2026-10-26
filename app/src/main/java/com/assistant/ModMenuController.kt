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
    private var launcherView: View? = null
    private var isShown = false
    private var isLauncherShown = false
    private var statusView: TextView? = null

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
        if (!isShown || menuView == null) {
            isShown = false
            return
        }
        try {
            windowManager?.removeView(menuView)
        } catch (_: Throwable) {}
        menuView = null
        isShown = false
    }

    /**
     * Compact always-on-screen affordance. This is the hide tab a real mod
     * menu needs: after collapsing, the user can reopen the panel from inside
     * the game without returning to Splendor-Assist.
     */
    fun showLauncher() {
        if (isLauncherShown) return
        val wm = windowManager ?: return
        val lp = WindowManager.LayoutParams().apply {
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

        var dragX = 0
        var dragY = 0
        var downX = 0f
        var downY = 0f
        var moved = false

        val orb = TextView(context).apply {
            text = "\u2699"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 20f
            setBackgroundColor(Color.parseColor("#CC0A0E17"))
            setPadding(24, 12, 24, 12)
        }
        orb.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragX = lp.x; dragY = lp.y
                    downX = event.rawX; downY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - downX) > 12f ||
                        kotlin.math.abs(event.rawY - downY) > 12f) moved = true
                    lp.x = dragX + (event.rawX - downX).toInt()
                    lp.y = dragY + (event.rawY - downY).toInt()
                    windowManager?.updateViewLayout(orb, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        hideLauncher()
                        show()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(orb, lp)
            launcherView = orb
            isLauncherShown = true
        } catch (_: Throwable) {
            isLauncherShown = false
        }
    }

    fun hideLauncher() {
        if (!isLauncherShown || launcherView == null) return
        try {
            windowManager?.removeView(launcherView)
        } catch (_: Throwable) {}
        launcherView = null
        isLauncherShown = false
    }

    /**
     * Reads the live runtime pipeline, not the toggle values the user just
     * typed. GAME/CAP/TRUST come from the same gates that decide whether a
     * gesture may be dispatched at all, so an UNTRUSTED frame here explains
     * why nothing is reaching the screen.
     */
    private fun liveStatusLine(): String {
        val runtime = RuntimeCoordinator.runtimeState()
        val frame = FrameAssembler.frameRuntimeSnapshot()
        val decision = RuntimeDecisionLoop.decisionRuntimeSnapshot()
        val execution = GestureExecutionAuthority.executionRuntimeSnapshot()
        val trusted = frame["trusted"] as? Boolean ?: false
        val capture = runtime["captureReady"] as? Boolean ?: false
        val accepted = (execution["accepted"] as? Number)?.toLong() ?: 0L
        val rejected = (execution["rejected"] as? Number)?.toLong() ?: 0L
        val decisions = (decision["decisions"] as? Number)?.toLong() ?: 0L
        val routed = (decision["routed"] as? Number)?.toLong() ?: 0L
        val statusLine = StringBuilder()
        statusLine.append("GAME=").append(onOff(VisionTrust.isGameForeground()))
        statusLine.append(" CAP=").append(onOff(capture))
        statusLine.append(" TRUST=").append(onOff(trusted))
        statusLine.append("\n")
        statusLine.append("SA=").append(onOff(SmartAssistRepository.enabled()))
        statusLine.append(" GK=").append(onOff(GoalkeeperRuntimeState.enabled))
        statusLine.append(" INT=").append(onOff(InterceptionRuntimeRegistry.enabled))
        statusLine.append("\n")
        statusLine.append("DEC=").append(decisions)
        statusLine.append(" ROUTED=").append(routed)
        statusLine.append(" EXEC=").append(accepted).append("/").append(rejected)
        statusLine.append("\n")
        statusLine.append("AWR=").append(InterceptionRuntimeRegistry.awareness)
        statusLine.append(" AUTH=").append(SmartAssistRepository.configuration().authority)
        return statusLine.toString()
    }

    private fun onOff(value: Boolean): String = if (value) "ON" else "OFF"

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
                    if (fromUser) smartRepo.updateAuthority(progress)
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
                    if (fromUser) {
                        val cfg = SmartAssistRepository.configuration()
                        smartRepo.updateThresholds(progress, cfg.shotThreshold, cfg.crossThreshold)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        contentContainer.addView(seekPass)

        val tvStatus = TextView(context).apply {
            text = liveStatusLine()
            setTextColor(Color.parseColor("#40E36A"))
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 0)
        }
        statusView = tvStatus
        contentContainer.addView(tvStatus)

        // Goalkeeper controls that reach the engine state it actually reads.
        val swKeeperAggressive = Switch(context).apply {
            text = "Keeper Aggressive Push"
            setTextColor(Color.WHITE)
            isChecked = GoalkeeperRuntimeState.aggressiveMode
            setOnCheckedChangeListener { _, isChecked ->
                goalkeeperRepo.updateAggressiveMode(isChecked)
            }
        }
        contentContainer.addView(swKeeperAggressive)

        val tvKeeperPositioning = TextView(context).apply {
            text = "Keeper Positioning: " + GoalkeeperRuntimeState.positioning
            setTextColor(Color.parseColor("#B8CCE6"))
            textSize = 12f
            setPadding(0, 12, 0, 4)
        }
        contentContainer.addView(tvKeeperPositioning)

        val seekKeeperPositioning = SeekBar(context).apply {
            max = 100
            progress = GoalkeeperRuntimeState.positioning
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    tvKeeperPositioning.text = "Keeper Positioning: " + progress
                    if (fromUser) goalkeeperRepo.updatePositioning(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        contentContainer.addView(seekKeeperPositioning)

        val tvKeeperReactions = TextView(context).apply {
            text = "Keeper Reactions: " + GoalkeeperRuntimeState.reactions
            setTextColor(Color.parseColor("#B8CCE6"))
            textSize = 12f
            setPadding(0, 12, 0, 4)
        }
        contentContainer.addView(tvKeeperReactions)

        val seekKeeperReactions = SeekBar(context).apply {
            max = 100
            progress = GoalkeeperRuntimeState.reactions
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    tvKeeperReactions.text = "Keeper Reactions: " + progress
                    if (fromUser) goalkeeperRepo.updateReactions(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        contentContainer.addView(seekKeeperReactions)

        // Single refresh loop for the whole panel, started only once every
        // widget exists. The sliders are refreshed as well because the control
        // rooms write the same runtime state, so a change made there must show
        // here instead of leaving a stale reading on screen. Only a real user
        // drag persists, so this refresh never writes configuration back.
        val statusTicker = android.os.Handler(android.os.Looper.getMainLooper())
        statusTicker.postDelayed(object : Runnable {
            override fun run() {
                statusView?.text = liveStatusLine()
                seekKeeperPositioning.progress = GoalkeeperRuntimeState.positioning
                seekKeeperReactions.progress = GoalkeeperRuntimeState.reactions
                statusTicker.postDelayed(this, 1000L)
            }
        }, 1000L)

        contentScroll.addView(contentContainer)
        mainLayout.addView(contentScroll)

        // Real hide: collapse the panel and leave a compact launcher orb so the
        // menu stays reachable while the game is in the foreground.
        toggleBtn.setOnClickListener {
            hide()
            showLauncher()
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
