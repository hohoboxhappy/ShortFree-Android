// 배포 빌드에서 화면과 접근성 이벤트 진단을 비활성화합니다.
package io.shortfree.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.shortfree.engine.PagerScroll
import io.shortfree.engine.PlayerSnapshot
import io.shortfree.engine.TargetApp

@Suppress("UNUSED_PARAMETER")
internal class EventInspector(context: Context) {
    fun reportSnapshot(app: TargetApp, root: AccessibilityNodeInfo, snapshot: PlayerSnapshot) = Unit
    fun reportScroll(app: TargetApp, event: AccessibilityEvent, scroll: PagerScroll?) = Unit
}
