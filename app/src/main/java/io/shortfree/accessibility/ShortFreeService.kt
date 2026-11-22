// 대상 앱 접근성 관찰을 시청 세션에 연결하고 차단 화면과 배경 재생을 제어합니다.
package io.shortfree.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.shortfree.data.SettingsRepository
import io.shortfree.data.ShortFreeSettings
import io.shortfree.detection.PlayerSurfaceDetector
import io.shortfree.engine.Observation
import io.shortfree.engine.PagerScroll
import io.shortfree.engine.ScreenBounds
import io.shortfree.engine.SessionReducer
import io.shortfree.engine.SessionState
import io.shortfree.engine.Surface
import io.shortfree.engine.TargetApp
import io.shortfree.overlay.BlockOverlayController
import io.shortfree.overlay.BlockScreenSession
import io.shortfree.overlay.BlockedActivity
import io.shortfree.overlay.ReelSwipeGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ShortFreeService : AccessibilityService() {
    private val callbacks = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val detector = PlayerSurfaceDetector()
    private val overlay by lazy { BlockOverlayController(this) }
    private val reelSwipeGuard by lazy {
        ReelSwipeGuard(this, callbacks, ::canReplayReelTouch) { requestProbe() }
    }
    private val inspector by lazy { EventInspector(this) }
    private val sessions = mutableMapOf<TargetApp, SessionState>()
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private val power by lazy { getSystemService(PowerManager::class.java) }

    private var settings = ShortFreeSettings()
    private var settingsJob: Job? = null
    private var receiverRegistered = false
    private var foregroundApp: TargetApp? = null
    private var lastSurface = Surface.UNKNOWN
    private var pendingScroll: PagerScroll? = null
    private var pendingScrollApp: TargetApp? = null
    private var pendingScrollWindow: Int? = null
    private var pendingScrollAtMs = 0L
    private var nextProbeAtMs: Long? = null
    private var lastProbeAtMs = 0L
    private var unavailableRootCount = 0
    private var audioCheck: Runnable? = null

    private val probe = Runnable {
        nextProbeAtMs = null
        observeForeground()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                BlockScreenSession.cancel()
                suspendObservation()
            } else {
                requestProbe()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connection.value = true
        registerScreenReceiver()
        settingsJob?.cancel()
        settingsJob = scope.launch {
            SettingsRepository(this@ShortFreeService).settings.collect { newSettings ->
                settings = newSettings
                val activeApp = foregroundApp
                if (activeApp != null && !monitoringEnabled(activeApp)) changeForeground(null)
                if (!settings.reelSwipeGuardEnabled) reelSwipeGuard.release()
                sessions.keys.retainAll(TargetApp.entries.filter(::monitoringEnabled).toSet())
                if (settings.consentAccepted) requestProbe() else resetSessions()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!connection.value || !settings.consentAccepted || !screenAvailable()) return

        val app = TargetApp.fromPackage(event.packageName)
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> requestProbe()

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (app == null || !monitoringEnabled(app)) return
                if (foregroundApp != null && foregroundApp != app) return
                val current = sessions[app]
                if (current == SessionState.Blocked) return
                val scroll = detector.readPagerScroll(app, event)
                if (foregroundApp == app) inspector.reportScroll(app, event, scroll)
                if (scroll == null) return
                val accumulated = pendingScroll?.takeIf {
                    pendingScrollApp == app && pendingScrollWindow == event.windowId
                }
                pendingScroll = accumulated?.followedBy(scroll) ?: scroll
                pendingScrollApp = app
                pendingScrollWindow = event.windowId
                pendingScrollAtMs = SystemClock.uptimeMillis()
                val reelInProgress = app == TargetApp.INSTAGRAM && foregroundApp == app &&
                    lastSurface == Surface.PLAYER &&
                    (current is SessionState.WatchingFirst || current is SessionState.VerifyingNext)
                if (reelInProgress) {
                    overlay.coverPreview()
                    requestProbe()
                } else {
                    requestProbe(SCROLL_SAMPLE_MS)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (app == null || !monitoringEnabled(app)) return
                if (foregroundApp != null && foregroundApp != app) return
                if (sessions[app] == SessionState.Blocked) return
                val sinceLastProbe = SystemClock.uptimeMillis() - lastProbeAtMs
                requestProbe((CONTENT_SAMPLE_MS - sinceLastProbe).coerceAtLeast(0))
            }
            else -> Unit
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        suspendObservation()
        requestProbe()
    }

    override fun onInterrupt() {
        resetSessions()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private fun observeForeground() {
        if (!connection.value || !settings.consentAccepted) return
        if (!screenAvailable()) {
            suspendObservation()
            return
        }

        val now = SystemClock.uptimeMillis()
        lastProbeAtMs = now
        val root = foregroundRoot()
        if (root == null) {
            reelSwipeGuard.release()
            if (unavailableRootCount < ROOT_RETRY_LIMIT) {
                unavailableRootCount++
                requestProbe(CONTENT_SAMPLE_MS)
            } else {
                changeForeground(null)
            }
            return
        }

        unavailableRootCount = 0
        try {
            val app = TargetApp.fromPackage(root.packageName)?.takeIf(::monitoringEnabled)
            if (app != foregroundApp && root.packageName?.toString() != packageName) {
                BlockScreenSession.cancel()
            }
            val initialScroll = pendingScroll.takeIf {
                foregroundApp == null && pendingScrollApp == app && pendingScrollWindow == root.windowId &&
                    now - pendingScrollAtMs <= SCROLL_SAMPLE_VALID_MS
            }
            changeForeground(app)
            if (app == null) return

            val previous = sessions[app] ?: SessionState.Outside
            if (previous == SessionState.Blocked) {
                reelSwipeGuard.release()
                overlay.show { returnFromBlock(app) }
                if (app == TargetApp.INSTAGRAM && audioCheck == null && BlockScreenSession.active.value == null) {
                    scheduleAfterBack(app)
                }
                return
            }

            val scroll = initialScroll ?: pendingScroll.takeIf {
                pendingScrollApp == app && pendingScrollWindow == root.windowId &&
                    now - pendingScrollAtMs <= SCROLL_SAMPLE_VALID_MS
            }
            clearPendingScroll()

            val snapshot = detector.inspect(app, root)
            lastSurface = snapshot.surface
            inspector.reportSnapshot(app, root, snapshot)
            val state = SessionReducer.reduce(
                previous,
                Observation(snapshot, now, scroll, trackLoadingPager = app == TargetApp.YOUTUBE),
            )
            sessions[app] = state

            val reelViewport = snapshot.reelViewport
            if (app == TargetApp.INSTAGRAM && settings.reelSwipeGuardEnabled &&
                state != SessionState.Blocked && reelViewport != null && canSendGestures()
            ) {
                reelSwipeGuard.update(root.windowId, reelViewport)
            } else {
                reelSwipeGuard.release()
            }

            if (state == SessionState.Blocked) {
                // 플레이어가 확인된 창에 먼저 뒤로 가기를 보내고 터치 차단 창을 붙입니다.
                if (closePlayerInForeground(app, verifiedWindowId = root.windowId)) {
                    overlay.show { returnFromBlock(app) }
                    scheduleAfterBack(app)
                } else {
                    sessions[app] = previous
                    overlay.hidePreview()
                    requestProbe(CONTENT_SAMPLE_MS)
                }
            } else {
                if (state !is SessionState.VerifyingNext) overlay.hidePreview()
                SessionReducer.nextCheckAt(state, now)?.let { requestProbe((it - now).coerceAtLeast(0)) }
            }
        } finally {
            root.release()
        }
    }

    private fun scheduleAfterBack(app: TargetApp, mayRetryBack: Boolean = true) {
        cancelAudioCheck()
        val check = Runnable {
            audioCheck = null
            if (foregroundApp != app || sessions[app] != SessionState.Blocked) return@Runnable

            val attemptedAgain = mayRetryBack && closePlayerInForeground(app)
            if (app == TargetApp.INSTAGRAM) {
                if (attemptedAgain) {
                    scheduleAfterBack(app, mayRetryBack = false)
                } else {
                    showForegroundBlock(app)
                }
            }
        }
        audioCheck = check
        callbacks.postDelayed(check, BACK_SETTLE_MS)
    }

    private fun showForegroundBlock(app: TargetApp) {
        if (!screenAvailable() || !monitoringEnabled(app)) return
        val root = foregroundRoot() ?: return
        val targetHasFocus = try {
            TargetApp.fromPackage(root.packageName) == app
        } finally {
            root.release()
        }
        if (!targetHasFocus) return

        // TODO(device-test): 릴스에서 나간 뒤 인스타 피드의 소리가 전면 차단 화면에서 멈추는지 확인합니다.
        val token = BlockScreenSession.begin {
            sessions.remove(app)
            overlay.hide()
            requestProbe()
        }
        val intent = Intent(this, BlockedActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(BlockScreenSession.TOKEN_EXTRA, token)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            BlockScreenSession.cancel()
        } catch (_: SecurityException) {
            BlockScreenSession.cancel()
        }
    }

    private fun returnFromBlock(app: TargetApp) {
        cancelAudioCheck()
        BlockScreenSession.cancel()
        if (foregroundApp == app && sessions[app] == SessionState.Blocked) {
            closePlayerInForeground(app)
            sessions.remove(app)
        }
        overlay.hide()
        requestProbe(CONTENT_SAMPLE_MS)
    }

    private fun closePlayerInForeground(
        app: TargetApp,
        verifiedWindowId: Int? = null,
    ): Boolean {
        if (!screenAvailable() || !monitoringEnabled(app)) return false
        val root = foregroundRoot() ?: return false
        try {
            if (TargetApp.fromPackage(root.packageName) != app) return false
            val verifiedPlayer = if (verifiedWindowId != null) {
                root.windowId == verifiedWindowId
            } else {
                detector.inspect(app, root).surface == Surface.PLAYER
            }
            if (!verifiedPlayer) return false
            performGlobalAction(GLOBAL_ACTION_BACK)
            return true
        } finally {
            root.release()
        }
    }

    private fun foregroundRoot(): AccessibilityNodeInfo? {
        val currentWindows = windows
        try {
            val active = currentWindows.firstOrNull { it.isActive }
            if (active?.type == AccessibilityWindowInfo.TYPE_SYSTEM && active.isFocused) {
                return windowRoot(active)
            }
            val appWindows = currentWindows.filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !it.isInPictureInPictureMode
            }
            val appWindow = appWindows.firstOrNull { it.isActive }
                ?: appWindows.firstOrNull { it.isFocused }
            if (appWindow != null) return windowRoot(appWindow)
            // 창 전환 중에는 배경의 앱 창과 PIP에 동작을 전달하지 않습니다.
            return if (currentWindows.isEmpty()) {
                if (Build.VERSION.SDK_INT >= 33) getRootInActiveWindow(0) else rootInActiveWindow
            } else {
                null
            }
        } finally {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                currentWindows.forEach { it.recycle() }
            }
        }
    }

    private fun windowRoot(window: AccessibilityWindowInfo): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= 33) window.getRoot(0) else window.root

    private fun changeForeground(app: TargetApp?) {
        if (foregroundApp == app) return

        foregroundApp?.let { oldApp ->
            sessions[oldApp]?.let { sessions[oldApp] = SessionReducer.leaveForeground(it) }
        }
        foregroundApp = app
        lastSurface = Surface.UNKNOWN
        reelSwipeGuard.release()
        cancelAudioCheck()
        clearPendingScroll()
        overlay.hide()
    }

    private fun requestProbe(delayMs: Long = 0) {
        if (!connection.value || !settings.consentAccepted) return
        val dueAt = SystemClock.uptimeMillis() + delayMs
        if (nextProbeAtMs?.let { it <= dueAt } == true) return
        callbacks.removeCallbacks(probe)
        nextProbeAtMs = dueAt
        callbacks.postAtTime(probe, dueAt)
    }

    private fun cancelProbe() {
        callbacks.removeCallbacks(probe)
        nextProbeAtMs = null
        unavailableRootCount = 0
        clearPendingScroll()
    }

    private fun cancelAudioCheck() {
        audioCheck?.let(callbacks::removeCallbacks)
        audioCheck = null
    }

    private fun suspendObservation() {
        cancelProbe()
        cancelAudioCheck()
        lastSurface = Surface.UNKNOWN
        reelSwipeGuard.release()
        foregroundApp?.let { app ->
            val state = sessions[app]
            if (state != null && state != SessionState.Blocked) {
                sessions[app] = SessionReducer.leaveForeground(state)
            }
        }
        overlay.hide()
    }

    private fun clearPendingScroll() {
        pendingScroll = null
        pendingScrollApp = null
        pendingScrollWindow = null
    }

    private fun resetSessions() {
        cancelProbe()
        cancelAudioCheck()
        BlockScreenSession.cancel()
        reelSwipeGuard.release()
        sessions.clear()
        foregroundApp = null
        lastSurface = Surface.UNKNOWN
        overlay.hide()
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val actions = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, actions, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, actions)
        }
        receiverRegistered = true
    }

    private fun disconnect() {
        connection.value = false
        settingsJob?.cancel()
        settingsJob = null
        resetSessions()
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
    }

    private fun screenAvailable(): Boolean = power.isInteractive && !keyguard.isKeyguardLocked

    private fun monitoringEnabled(app: TargetApp): Boolean =
        settings.consentAccepted && settings.isEnabled(app)

    private fun canSendGestures(): Boolean =
        ((serviceInfo?.capabilities ?: 0) and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0

    private fun canReplayReelTouch(windowId: Int, bounds: ScreenBounds): Boolean {
        if (!settings.reelSwipeGuardEnabled || !monitoringEnabled(TargetApp.INSTAGRAM) || !screenAvailable()) {
            return false
        }
        val root = foregroundRoot() ?: return false
        try {
            if (root.windowId != windowId || TargetApp.fromPackage(root.packageName) != TargetApp.INSTAGRAM) {
                return false
            }
            return detector.inspect(TargetApp.INSTAGRAM, root).reelViewport == bounds
        } finally {
            root.release()
        }
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.release() {
        if (Build.VERSION.SDK_INT < 33) recycle()
    }

    companion object {
        private const val CONTENT_SAMPLE_MS = 100L
        private const val SCROLL_SAMPLE_MS = 24L
        private const val SCROLL_SAMPLE_VALID_MS = 220L
        private const val BACK_SETTLE_MS = 220L
        private const val ROOT_RETRY_LIMIT = 2
        private val connection = MutableStateFlow(false)
        val connected = connection.asStateFlow()
    }
}
