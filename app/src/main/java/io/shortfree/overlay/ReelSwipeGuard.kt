// 확인된 릴스 플레이어 위의 터치를 가로채고, 위쪽 스와이프를 빼고 인스타그램으로 전달합니다.
package io.shortfree.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import io.shortfree.engine.ScreenBounds

internal class ReelSwipeGuard(
    private val service: AccessibilityService,
    private val callbacks: Handler,
    private val canReplayInWindow: (Int, ScreenBounds) -> Boolean,
    private val recheckPlayer: () -> Unit,
) {
    private data class Target(val windowId: Int, val bounds: ScreenBounds)

    private val windows = requireNotNull(service.getSystemService(WindowManager::class.java))
    private val accessibility = service.getSystemService(AccessibilityManager::class.java)
    private val swipeDistance = ViewConfiguration.get(service).scaledTouchSlop * SWIPE_SLOP_MULTIPLIER
    private var target: Target? = null
    private var cover: View? = null
    private var touch: ReelTouchGesture? = null
    private var pendingReplay: Runnable? = null
    private var replaying = false
    private var replayGeneration = 0
    private val location = IntArray(2)

    fun update(windowId: Int, bounds: ScreenBounds) {
        if (bounds.left < 0 || bounds.top < 0 || bounds.width <= 0 || bounds.height <= 0 ||
            accessibility?.isTouchExplorationEnabled == true
        ) {
            release()
            return
        }
        val next = Target(windowId, bounds)
        if (target != next) {
            release()
            target = next
        }
        if (cover == null && !replaying) attach(bounds)
    }

    fun release() {
        target = null
        touch = null
        replayGeneration++
        pendingReplay?.let(callbacks::removeCallbacks)
        pendingReplay = null
        replaying = false
        detach()
    }

    // 릴스 입력만 전달하는 투명 창이며 자체 접근성 동작을 제공하지 않습니다.
    @SuppressLint("ClickableViewAccessibility")
    private fun attach(bounds: ScreenBounds) {
        val view = View(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setOnTouchListener(::handleTouch)
        }
        val params = WindowManager.LayoutParams(
            bounds.width,
            bounds.height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.getAbsoluteGravity(Gravity.START, View.LAYOUT_DIRECTION_LTR)
            x = bounds.left
            y = bounds.top
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        try {
            windows.addView(view, params)
            cover = view
        } catch (_: WindowManager.BadTokenException) {
            target = null
        } catch (_: WindowManager.InvalidDisplayException) {
            target = null
        } catch (_: SecurityException) {
            target = null
        }
    }

    private fun detach() {
        val view = cover ?: return
        cover = null
        try {
            windows.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
            // 시스템이 창을 먼저 없앴을 수 있습니다.
        }
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        val current = target ?: return true
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touch = null
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            view.getLocationOnScreen(location)
            touch = ReelTouchGesture(swipeDistance, event.downTime)
        }
        val gesture = touch ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                gesture.start(event.getPointerId(index), point(event, index), event.eventTime)
            }
        }
        for (index in 0 until event.pointerCount) {
            val id = event.getPointerId(index)
            for (sample in 0 until event.historySize) {
                val position = TouchPoint(
                    event.getHistoricalX(index, sample) + location[0],
                    event.getHistoricalY(index, sample) + location[1],
                )
                gesture.sample(id, position, event.getHistoricalEventTime(sample))
            }
            gesture.sample(id, point(event, index), event.eventTime)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            touch = null
            if (!gesture.isNextReelSwipe) relay(current, gesture.strokes())
        }
        return true
    }

    private fun point(event: MotionEvent, index: Int) =
        TouchPoint(event.getX(index) + location[0], event.getY(index) + location[1])

    private fun relay(current: Target, strokes: List<TouchStroke>) {
        val gesture = toGesture(current.bounds, strokes) ?: return
        val generation = ++replayGeneration
        replaying = true
        detach()
        val replay = Runnable {
            pendingReplay = null
            if (generation != replayGeneration || target != current) return@Runnable
            if (!canReplayInWindow(current.windowId, current.bounds)) {
                finishRelay(generation)
                return@Runnable
            }
            val started = try {
                service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription) = finishRelay(generation)
                        override fun onCancelled(gestureDescription: GestureDescription) = finishRelay(generation)
                    },
                    callbacks,
                )
            } catch (_: SecurityException) {
                false
            }
            if (started) {
                val timeout = Runnable { finishRelay(generation) }
                pendingReplay = timeout
                callbacks.postDelayed(timeout, MAX_RELAY_MS + PANEL_SETTLE_MS)
            } else {
                finishRelay(generation)
            }
        }
        pendingReplay = replay
        callbacks.postDelayed(replay, INPUT_WINDOW_SETTLE_MS)
    }

    private fun finishRelay(generation: Int) {
        if (generation != replayGeneration) return
        pendingReplay?.let(callbacks::removeCallbacks)
        val afterRelay = Runnable {
            pendingReplay = null
            if (generation != replayGeneration) return@Runnable
            replaying = false
            recheckPlayer()
        }
        pendingReplay = afterRelay
        callbacks.postDelayed(afterRelay, PANEL_SETTLE_MS)
    }

    private fun toGesture(bounds: ScreenBounds, strokes: List<TouchStroke>): GestureDescription? {
        if (strokes.isEmpty() || strokes.size > GestureDescription.getMaxStrokeCount()) return null
        val builder = GestureDescription.Builder()
        for (stroke in strokes) {
            if (stroke.startedMs >= MAX_RELAY_MS) return null
            val path = Path()
            for ((index, point) in stroke.points.withIndex()) {
                if (point.x < bounds.left || point.x >= bounds.right ||
                    point.y < bounds.top || point.y >= bounds.bottom
                ) {
                    return null
                }
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            if (stroke.points.size == 1) {
                val point = stroke.points.single()
                path.lineTo(point.x, point.y)
            }
            val duration = stroke.durationMs.coerceAtMost(MAX_RELAY_MS - stroke.startedMs)
            builder.addStroke(GestureDescription.StrokeDescription(path, stroke.startedMs, duration))
        }
        return builder.build()
    }

    private companion object {
        const val SWIPE_SLOP_MULTIPLIER = 1.5f
        const val INPUT_WINDOW_SETTLE_MS = 24L
        const val PANEL_SETTLE_MS = 120L
        const val MAX_RELAY_MS = 2_000L
    }
}
