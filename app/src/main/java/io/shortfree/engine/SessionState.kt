// 대상 앱 하나에서 진행되는 첫 영상 시청 세션과 전환 후보를 표현합니다.
package io.shortfree.engine

sealed interface SessionState {
    data object Outside : SessionState

    data class Arming(
        val page: PageIdentity,
        val stableSinceMs: Long?,
        val outsideSinceMs: Long? = null,
    ) : SessionState

    data class WatchingFirst(
        val firstPage: PageIdentity,
        val outsideSinceMs: Long? = null,
    ) : SessionState

    data class VerifyingNext(
        val firstPage: PageIdentity,
        val startedAtMs: Long,
        val scroll: PagerScroll?,
        val change: PageChange?,
        val changeSinceMs: Long?,
        val lastMotionAtMs: Long? = null,
        val loadingDeadlineMs: Long? = null,
    ) : SessionState

    data object Blocked : SessionState
}

sealed interface PageChange {
    data class Index(val value: Int) : PageChange
    data class Content(val token: String) : PageChange
    data class Motion(val direction: Int) : PageChange
    data class VisiblePages(val range: IntRange) : PageChange
}
