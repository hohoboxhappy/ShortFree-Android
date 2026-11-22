// 화면 감지기와 상태 머신 사이에서 사용하는 앱, 페이지, 이벤트 정보를 정의합니다.
package io.shortfree.engine

enum class TargetApp(val packageName: String) {
    YOUTUBE("com.google.android.youtube"),
    INSTAGRAM("com.instagram.android");

    companion object {
        fun fromPackage(packageName: CharSequence?): TargetApp? =
            entries.firstOrNull { it.packageName == packageName?.toString() }
    }
}

enum class Surface {
    PLAYER,
    TRANSIENT,
    OUTSIDE,
    UNKNOWN,
}

data class PageIdentity(
    val token: String? = null,
    val index: Int? = null,
    val captionToken: String? = null,
) {
    fun fillMissing(fallback: PageIdentity) =
        PageIdentity(
            token = token ?: fallback.token,
            index = index ?: fallback.index,
            captionToken = captionToken ?: fallback.captionToken,
        )
}

data class PlayerSnapshot(
    val surface: Surface,
    val page: PageIdentity = PageIdentity(),
    val reelViewport: ScreenBounds? = null,
)

data class ScreenBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

data class PagerScroll(
    val settledIndex: Int? = null,
    val travelInPages: Double = 0.0,
    val firstVisiblePages: IntRange? = null,
    val visiblePages: IntRange? = firstVisiblePages,
) {
    fun followedBy(next: PagerScroll) = next.copy(
        travelInPages = travelInPages + next.travelInPages,
        firstVisiblePages = firstVisiblePages ?: next.firstVisiblePages,
    )
}

data class Observation(
    val snapshot: PlayerSnapshot,
    val atMs: Long,
    val scroll: PagerScroll? = null,
    val trackLoadingPager: Boolean = false,
)
