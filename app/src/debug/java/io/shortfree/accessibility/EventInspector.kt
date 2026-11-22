// 디버그에서 사용자가 명시적으로 켠 경우에만 대상 앱의 화면 구조를 기록합니다.
package io.shortfree.accessibility

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.shortfree.engine.PagerScroll
import io.shortfree.engine.PlayerSnapshot
import io.shortfree.engine.TargetApp
import java.io.File

internal class EventInspector(context: Context) {
    private val marker = File(context.filesDir, "inspect-ui")
    private var lastMarkerCheckMs = -OPT_IN_CHECK_MS
    private var lastTreeMs = -TREE_SAMPLE_MS
    private var lastScrollMs = -SCROLL_SAMPLE_MS
    private var enabled = false

    fun reportSnapshot(app: TargetApp, root: AccessibilityNodeInfo, snapshot: PlayerSnapshot) {
        val now = SystemClock.uptimeMillis()
        if (!optedIn(now) || now - lastTreeMs < TREE_SAMPLE_MS) return
        lastTreeMs = now
        Log.d(TAG, "${app.packageName} surface=${snapshot.surface} index=${snapshot.page.index}")
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var queued = 1
        val bounds = Rect()
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            try {
                if (node.packageName != app.packageName) continue
                val id = node.safeId(app)
                if (id != null && node.isVisibleToUser) {
                    node.getBoundsInScreen(bounds)
                    Log.d(TAG, "depth=$depth id=$id bounds=${bounds.flattenToString()} scroll=${node.isScrollable}")
                }
                if (depth >= MAX_DEPTH) continue
                for (index in 0 until minOf(node.childCount, MAX_NODES - queued)) {
                    val child = if (Build.VERSION.SDK_INT >= 33) node.getChild(index, 0) else node.getChild(index)
                    if (child == null) continue
                    queue.add(child to depth + 1)
                    queued++
                }
            } finally {
                if (depth > 0) node.release()
            }
        }
    }

    fun reportScroll(app: TargetApp, event: AccessibilityEvent, scroll: PagerScroll?) {
        val now = SystemClock.uptimeMillis()
        if (!optedIn(now) || now - lastScrollMs < SCROLL_SAMPLE_MS) return
        lastScrollMs = now
        val source = event.source ?: return
        try {
            val id = source.safeId(app) ?: return
            Log.d(TAG, "${app.packageName} scroll=$id from=${event.fromIndex} to=${event.toIndex} pager=${scroll != null}")
        } finally {
            source.release()
        }
    }

    private fun optedIn(now: Long): Boolean {
        if (now - lastMarkerCheckMs >= OPT_IN_CHECK_MS) {
            enabled = marker.isFile
            lastMarkerCheckMs = now
        }
        return enabled
    }

    private fun AccessibilityNodeInfo.safeId(app: TargetApp): String? {
        if (packageName != app.packageName) return null
        val prefix = "${app.packageName}:id/"
        return viewIdResourceName?.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)?.takeIf { RESOURCE_ID.matches(it) }
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.release() {
        if (Build.VERSION.SDK_INT < 33) recycle()
    }

    private companion object {
        const val TAG = "ShortFreeStructure"
        const val OPT_IN_CHECK_MS = 2000L
        const val TREE_SAMPLE_MS = 850L
        const val SCROLL_SAMPLE_MS = 150L
        const val MAX_NODES = 180
        const val MAX_DEPTH = 32
        val RESOURCE_ID = Regex("[A-Za-z][A-Za-z0-9_]{0,70}")
    }
}
