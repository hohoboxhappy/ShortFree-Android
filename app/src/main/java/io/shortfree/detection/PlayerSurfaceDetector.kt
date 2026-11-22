// 대상 앱의 접근성 트리에서 숏폼 플레이어, 일시 패널과 페이지 이동 증거를 읽습니다.
package io.shortfree.detection

import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.shortfree.engine.PageIdentity
import io.shortfree.engine.PagerScroll
import io.shortfree.engine.PlayerSnapshot
import io.shortfree.engine.ScreenBounds
import io.shortfree.engine.Surface
import io.shortfree.engine.TargetApp
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.abs

// TODO(device-test): 앱 버전별 실제 페이저와 메타데이터 ID를 대조합니다.
private data class AppViews(
    val pagers: Set<String>,
    val players: Set<String>,
    val authors: Set<String>,
    val captions: Set<String>,
    val panels: Set<String>,
    val outside: Set<String>,
)

private val youtube = AppViews(
    pagers = setOf("reel_recycler"),
    players = setOf("reel_watch_fragment_root", "reel_watch_player", "reel_player", "reel_player_page_container"),
    authors = setOf("reel_channel_name", "reel_channel_handle", "channel_handle"),
    captions = setOf("reel_title", "reel_video_title", "reel_player_title", "shorts_video_title"),
    panels = setOf("comments_bottom_sheet", "comments_header", "share_panel"),
    outside = setOf(
        "watch_metadata", "watch_next_feed", "search_results",
        "related_chip_cloud_reduced_margin", "browse_fragment",
    ),
)

private val instagram = AppViews(
    pagers = setOf("clips_viewer_view_pager", "clips_viewer_pager"),
    // 피드의 개별 릴스에도 영상 컨테이너가 있으므로 전용 뷰어만 확인합니다.
    players = setOf("clips_viewer", "clips_viewer_root"),
    authors = setOf("clips_author_username", "clips_username", "clips_author_name"),
    captions = setOf("clips_caption", "clips_caption_text", "clips_caption_body"),
    panels = setOf(
        "layout_comment_thread", "comments_list", "comments_sheet",
        "comments_bottom_sheet", "clips_comments_bottom_sheet", "comment_thread_list",
        "comment_composer_edittext", "direct_share_sheet",
    ),
    outside = setOf(
        "direct_thread_recycler_view", "direct_inbox",
        "reel_viewer_container", "profile_header", "feed_tab_root",
    ),
)

private fun TargetApp.views() = when (this) {
    TargetApp.YOUTUBE -> youtube
    TargetApp.INSTAGRAM -> instagram
}

private const val MAX_NODES = 280
private const val MAX_DEPTH = 36
private const val MAX_TOKEN_LENGTH = 256
private const val MIN_WIDTH_FRACTION = 0.65
private const val MIN_HEIGHT_FRACTION = 0.58
private const val CENTER_PAGE_FRACTION = 0.82
private const val MAX_PAGER_ANCESTORS = 6
private const val UNKNOWN_DELTA = -1
private val sheetHeaders = setOf("title", "sheet_title", "bottom_sheet_title", "panel_title")
private val sheetLabels = setOf("댓글", "Comments", "공유", "Share")
private val repeatedWhitespace = Regex("\\s+")

internal fun settledPagerIndex(from: Int, to: Int): Int? = from.takeIf { it >= 0 && it == to }

internal fun visiblePagerRange(from: Int, to: Int): IntRange? =
    if (from >= 0 && to >= from && to - from <= 1) from..to else null

internal fun verticalTravelInPages(deltaX: Int, deltaY: Int, height: Int): Double {
    val verticalDistance = abs(deltaY.toLong())
    val horizontalDistance = if (deltaX == UNKNOWN_DELTA) 0L else abs(deltaX.toLong())
    if (height <= 0 || deltaY == UNKNOWN_DELTA || verticalDistance <= horizontalDistance) return 0.0
    val pages = deltaY.toDouble() / height
    return pages.takeIf { abs(it) <= 2.0 } ?: 0.0
}

class PlayerSurfaceDetector {
    private val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun readPagerScroll(app: TargetApp, event: AccessibilityEvent): PagerScroll? {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED || event.packageName != app.packageName) {
            return null
        }
        val source = event.source ?: return null
        try {
            if (source.packageName != app.packageName || !source.isVisibleToUser) return null
            val bounds = Rect().also(source::getBoundsInScreen)
            val views = app.views()
            val namedPager = isPager(source, source.localId(app), views)
            if (!namedPager && (app != TargetApp.YOUTUBE || !isNestedShortsPager(source, bounds, views))) {
                return null
            }
            val travel = if (Build.VERSION.SDK_INT >= 28) {
                verticalTravelInPages(event.scrollDeltaX, event.scrollDeltaY, bounds.height())
            } else {
                0.0
            }
            return PagerScroll(
                settledIndex = settledPagerIndex(event.fromIndex, event.toIndex),
                travelInPages = travel,
                firstVisiblePages = if (app == TargetApp.YOUTUBE) {
                    visiblePagerRange(event.fromIndex, event.toIndex)
                } else {
                    null
                },
            )
        } finally {
            source.release()
        }
    }

    private data class PendingNode(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val inPlayer: Boolean,
        val parentPagerBounds: Rect? = null,
    )

    fun inspect(app: TargetApp, root: AccessibilityNodeInfo): PlayerSnapshot {
        val unknown = PlayerSnapshot(Surface.UNKNOWN)
        if (root.packageName != app.packageName) return unknown
        val window = Rect().also(root::getBoundsInScreen)
        if (window.isEmpty) return unknown
        val views = app.views()
        val queue = ArrayDeque<PendingNode>()
        queue.add(PendingNode(root, depth = 0, inPlayer = false))
        val authors = mutableSetOf<String>()
        val captions = mutableSetOf<String>()
        val pageIndices = mutableSetOf<Int>()
        val bounds = Rect()
        var pagerPresent = false
        var largePlayer = false
        var panel = false
        var outside = false
        var reelViewport: ScreenBounds? = null
        var complete = true
        var nodesLeft = MAX_NODES - 1

        try {
            while (queue.isNotEmpty()) {
                val (node, depth, inPlayer, parentPagerBounds) = queue.removeFirst()
                try {
                    if (node.packageName != app.packageName) continue
                    node.getBoundsInScreen(bounds)
                    val visible = node.isVisibleToUser && bounds.intersect(window)
                    if (!visible && depth != 0) continue

                    val id = node.localId(app)
                    val large = visible && bounds.contains(window.centerX(), window.centerY()) &&
                        bounds.width() >= window.width() * MIN_WIDTH_FRACTION &&
                        bounds.height() >= window.height() * MIN_HEIGHT_FRACTION
                    val pager = large && isPager(node, id, views)
                    val viewer = large && id in views.players
                    val inViewer = inPlayer || pager || viewer
                    pagerPresent = pagerPresent || pager
                    if (app == TargetApp.INSTAGRAM && pager && reelViewport == null) {
                        reelViewport = ScreenBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
                    }
                    largePlayer = largePlayer || viewer
                    if (visible) {
                        panel = panel || isTemporaryPanel(node, id, views)
                        outside = outside || id in views.outside

                        if (parentPagerBounds != null && coversViewport(bounds, parentPagerBounds, CENTER_PAGE_FRACTION)) {
                            node.collectionItemInfo?.rowIndex?.takeIf { it >= 0 }?.let(pageIndices::add)
                        }
                        if (inViewer) {
                            if (id in views.authors) token(node, "author")?.let(authors::add)
                            if (id in views.captions) token(node, "caption")?.let(captions::add)
                        }
                    }

                    val pagerBounds = when {
                        pager && node.collectionInfo?.columnCount == 1 -> Rect(bounds)
                        app == TargetApp.YOUTUBE && inViewer && large && isScrollablePager(node) &&
                            node.collectionInfo?.columnCount == 1 -> Rect(bounds)
                        else -> null
                    }
                    for (index in 0 until node.childCount) {
                        if (nodesLeft <= 0 || depth >= MAX_DEPTH) {
                            complete = false
                            break
                        }
                        nodesLeft--
                        val child = if (Build.VERSION.SDK_INT >= 33) node.getChild(index, 0) else node.getChild(index)
                        if (child != null) queue.add(PendingNode(child, depth + 1, inViewer, pagerBounds))
                    }
                } finally {
                    if (depth != 0) node.release()
                }
            }

            val surface = when {
                panel -> Surface.TRANSIENT
                pagerPresent -> Surface.PLAYER
                // Shorts 탭에서는 전체 쇼츠 플레이어와 탐색 화면의 바깥 컨테이너가 함께 노출될 수 있습니다.
                app == TargetApp.YOUTUBE && largePlayer -> Surface.PLAYER
                complete && largePlayer && !outside -> Surface.PLAYER
                complete && !largePlayer && outside -> Surface.OUTSIDE
                else -> Surface.UNKNOWN
            }
            if (surface != Surface.PLAYER) return PlayerSnapshot(surface)
            val page = PageIdentity(
                token = authors.singleOrNull(),
                index = pageIndices.singleOrNull(),
                captionToken = captions.singleOrNull(),
            )
            return PlayerSnapshot(surface, page, reelViewport)
        } finally {
            queue.forEach { if (it.depth != 0) it.node.release() }
        }
    }

    private fun token(node: AccessibilityNodeInfo, field: String): String? {
        if (node.isPassword || node.isEditable) return null
        val raw = (node.text ?: node.contentDescription) ?: return null
        val normalized = raw.take(MAX_TOKEN_LENGTH).trim().replace(repeatedWhitespace, " ")
        if (normalized.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return Base64.encodeToString(digest.digest("$field:$normalized".toByteArray()), Base64.NO_WRAP)
    }
}

private fun isNestedShortsPager(source: AccessibilityNodeInfo, bounds: Rect, views: AppViews): Boolean {
    if (!isScrollablePager(source) || bounds.isEmpty) return false
    var ancestor = source.parentNode()
    repeat(MAX_PAGER_ANCESTORS) {
        val node = ancestor ?: return false
        try {
            if (node.packageName != TargetApp.YOUTUBE.packageName) return false
            val id = node.localId(TargetApp.YOUTUBE)
            if (isTemporaryPanel(node, id, views)) return false
            if (isPager(node, id, views) || id in views.players) {
                val viewport = Rect().also(node::getBoundsInScreen)
                return !viewport.isEmpty && coversViewport(bounds, viewport, CENTER_PAGE_FRACTION)
            }
            ancestor = node.parentNode()
        } finally {
            node.release()
        }
    }
    ancestor?.release()
    return false
}

private fun coversViewport(candidate: Rect, viewport: Rect, heightFraction: Double): Boolean =
    candidate.contains(viewport.centerX(), viewport.centerY()) &&
        candidate.width() >= viewport.width() * MIN_WIDTH_FRACTION &&
        candidate.height() >= viewport.height() * heightFraction

private fun isScrollablePager(node: AccessibilityNodeInfo): Boolean {
    val type = node.className?.toString() ?: return false
    return node.isScrollable && (type.endsWith("RecyclerView") || type.endsWith("ViewPager") || type.endsWith("ViewPager2"))
}

private fun AccessibilityNodeInfo.parentNode(): AccessibilityNodeInfo? =
    if (Build.VERSION.SDK_INT >= 33) getParent(0) else parent

private fun isTemporaryPanel(node: AccessibilityNodeInfo, id: String?, views: AppViews): Boolean =
    id in views.panels || node.className == "android.app.Dialog" ||
        (id in sheetHeaders && node.text?.toString() in sheetLabels)

private fun AccessibilityNodeInfo.localId(app: TargetApp): String? {
    if (packageName != app.packageName) return null
    val prefix = "${app.packageName}:id/"
    return viewIdResourceName?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
}

private fun isPager(node: AccessibilityNodeInfo, id: String?, views: AppViews): Boolean =
    id in views.pagers || (id == "clips_viewer" && node.className?.toString()?.endsWith("ViewPager") == true)

@Suppress("DEPRECATION")
private fun AccessibilityNodeInfo.release() {
    if (Build.VERSION.SDK_INT < 33) recycle()
}
