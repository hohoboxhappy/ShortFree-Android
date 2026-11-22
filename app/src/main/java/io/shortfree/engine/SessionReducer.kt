// 시간과 화면 관찰값만으로 첫 영상 허용, 전환 확인, 차단 및 세션 종료를 판정합니다.
package io.shortfree.engine

import io.shortfree.engine.SessionState.Arming
import io.shortfree.engine.SessionState.Blocked
import io.shortfree.engine.SessionState.Outside
import io.shortfree.engine.SessionState.VerifyingNext
import io.shortfree.engine.SessionState.WatchingFirst
import kotlin.math.abs

object SessionReducer {
    // TODO(device-test): 두 앱의 페이지 전환과 플레이어 종료 지연을 측정해 조정합니다.
    private const val ARMING_MS = 280L
    private const val EARLY_SWIPE_TRAVEL = 0.04
    private const val RANGE_DIRECTION_TRAVEL = 0.10
    private const val CONFIRM_MS = 100L
    private const val VERIFY_TIMEOUT_MS = 350L
    private const val MAX_MOTION_WINDOW_MS = 1_500L
    private const val LOADING_PAGER_WINDOW_MS = 3_500L
    private const val FULL_PAGE_TRAVEL = 0.90
    private const val EXIT_MS = 620L

    fun reduce(state: SessionState, observation: Observation): SessionState {
        if (state == Blocked) return Blocked

        return when (observation.snapshot.surface) {
            Surface.PLAYER -> onPlayer(state, observation)
            Surface.OUTSIDE -> onOutside(state, observation.atMs)
            Surface.TRANSIENT -> onUncertain(state)
            Surface.UNKNOWN -> onUnknown(state, observation)
        }
    }

    fun leaveForeground(state: SessionState): SessionState = when (state) {
        Blocked -> Outside
        else -> onUncertain(state)
    }

    fun nextCheckAt(state: SessionState, now: Long): Long? = when (state) {
        is Arming -> state.outsideSinceMs?.plus(EXIT_MS)
            ?: state.stableSinceMs?.plus(ARMING_MS)
        is WatchingFirst -> state.outsideSinceMs?.plus(EXIT_MS)
        is VerifyingNext -> minOf(
            state.changeSinceMs?.plus(CONFIRM_MS) ?: now + CONFIRM_MS,
            verificationDeadline(state),
        )
        Outside, Blocked -> null
    }

    private fun onPlayer(state: SessionState, observation: Observation): SessionState {
        val page = observation.snapshot.page.fillMissing(
            PageIdentity(index = observation.scroll?.settledIndex),
        )
        val now = observation.atMs

        return when (state) {
            Outside -> if (canTrackDuringLoading(observation)) {
                watchFirst(PageIdentity(), page, observation, duringLoading = true)
            } else {
                Arming(page, now)
            }
            is Arming -> {
                val stableSince = state.stableSinceMs ?: now
                val receivedSwipe = abs(observation.scroll?.travelInPages ?: 0.0) >= EARLY_SWIPE_TRAVEL
                val alreadyStable = now - stableSince >= ARMING_MS
                if ((state.stableSinceMs != null && (receivedSwipe || alreadyStable)) ||
                    canTrackDuringLoading(observation)
                ) {
                    // 첫 페이지를 덮어쓰기 전에, 안정화 중 들어온 실제 페이저 이동을 비교합니다.
                    watchFirst(state.page, page, observation, duringLoading = state.stableSinceMs == null)
                } else {
                    val changed = pageChange(state.page, page, allowContent = true) != null
                    if (changed) Arming(page, now) else Arming(page.fillMissing(state.page), stableSince)
                }
            }
            is WatchingFirst -> watchFirst(state.firstPage, page, observation)
            is VerifyingNext -> verify(state, observation, page)
            Blocked -> Blocked
        }
    }

    private fun watchFirst(
        firstPage: PageIdentity,
        page: PageIdentity,
        observation: Observation,
        duringLoading: Boolean = false,
    ): SessionState {
        val scroll = observation.scroll
        val change = transitionChange(firstPage, page, scroll)
        if (change == null && scroll == null) {
            val compatible = changedToken(firstPage, page) == null
            return WatchingFirst(if (compatible) firstPage.fillMissing(page) else firstPage)
        }
        val now = observation.atMs
        return VerifyingNext(
            firstPage = firstPage,
            startedAtMs = now,
            scroll = scroll,
            change = change,
            changeSinceMs = now.takeIf { change != null },
            lastMotionAtMs = now.takeIf { scroll?.hasProgress() == true },
            loadingDeadlineMs = (now + LOADING_PAGER_WINDOW_MS).takeIf { duringLoading },
        )
    }

    private fun verify(
        state: VerifyingNext,
        observation: Observation,
        page: PageIdentity,
    ): SessionState {
        val incomingScroll = observation.scroll
        val scroll = state.scroll.withNext(incomingScroll)
        val currentPage = page.fillMissing(PageIdentity(index = scroll?.settledIndex))
        val change = transitionChange(state.firstPage, currentPage, scroll)
        val now = observation.atMs
        val hasNewMotion = incomingScroll?.hasProgress() == true
        val lastMotionAt = if (hasNewMotion) now else state.lastMotionAtMs
        val deadline = verificationDeadline(state, lastMotionAt)
        val stableSince = when {
            change == null -> null
            (change is PageChange.Motion || change is PageChange.VisiblePages) && hasNewMotion -> now
            change == state.change -> state.changeSinceMs
            else -> now
        }

        return when {
            stableSince != null && now - stableSince >= CONFIRM_MS &&
                stableSince + CONFIRM_MS <= deadline -> Blocked
            now >= deadline -> WatchingFirst(state.firstPage)
            else -> state.copy(
                scroll = scroll,
                change = change,
                changeSinceMs = stableSince,
                lastMotionAtMs = lastMotionAt,
            )
        }
    }

    private fun PagerScroll.hasProgress(): Boolean = travelInPages != 0.0 || visiblePages != null

    private fun PagerScroll?.withNext(next: PagerScroll?): PagerScroll? =
        if (next == null) this else this?.followedBy(next) ?: next

    private fun canTrackDuringLoading(observation: Observation): Boolean {
        if (!observation.trackLoadingPager) return false
        val scroll = observation.scroll ?: return false
        val visible = scroll.visiblePages
        return abs(scroll.travelInPages) >= EARLY_SWIPE_TRAVEL ||
            (visible != null && visible.first != visible.last)
    }

    private fun verificationDeadline(state: VerifyingNext, lastMotion: Long? = state.lastMotionAtMs): Long {
        val started = state.startedAtMs
        val motionDeadline = minOf((lastMotion ?: started) + VERIFY_TIMEOUT_MS, started + MAX_MOTION_WINDOW_MS)
        return maxOf(motionDeadline, state.loadingDeadlineMs ?: motionDeadline)
    }

    private fun transitionChange(
        previous: PageIdentity,
        current: PageIdentity,
        scroll: PagerScroll?,
    ): PageChange? {
        val identity = pageChange(previous, current, allowContent = scroll != null)
        if (identity != null || (previous.index != null && current.index != null)) return identity
        if (scroll == null) return null
        visiblePageChange(scroll)?.let { return it }
        val travel = scroll.travelInPages
        return if (abs(travel) >= FULL_PAGE_TRAVEL) {
            PageChange.Motion(direction = if (travel > 0) 1 else -1)
        } else {
            null
        }
    }

    private fun visiblePageChange(scroll: PagerScroll): PageChange? {
        val previous = scroll.firstVisiblePages ?: return null
        val current = scroll.visiblePages ?: return null
        val firstShift = current.first - previous.first
        val lastShift = current.last - previous.last
        val rangeShiftedTogether = firstShift != 0 && firstShift == lastShift
        val settledOnAdjacentPage = previous.first != previous.last && current.first == current.last &&
            ((current.first == previous.last && scroll.travelInPages >= RANGE_DIRECTION_TRAVEL) ||
                (current.last == previous.first && scroll.travelInPages <= -RANGE_DIRECTION_TRAVEL))
        return if (rangeShiftedTogether || settledOnAdjacentPage) PageChange.VisiblePages(current) else null
    }

    private fun pageChange(
        previous: PageIdentity,
        current: PageIdentity,
        allowContent: Boolean,
    ): PageChange? {
        if (previous.index != null && current.index != null) {
            return current.index.takeIf { it != previous.index }?.let { PageChange.Index(it) }
        }
        return if (allowContent) changedToken(previous, current)?.let { PageChange.Content(it) } else null
    }

    private fun changedToken(previous: PageIdentity, current: PageIdentity): String? {
        if (previous.token != null && current.token != null && previous.token != current.token) {
            return current.token
        }
        return current.captionToken?.takeIf { previous.captionToken != null && previous.captionToken != it }
    }

    private fun onOutside(state: SessionState, now: Long): SessionState = when (state) {
        is Arming -> {
            val outsideSince = state.outsideSinceMs ?: now
            if (now - outsideSince >= EXIT_MS) Outside
            else state.copy(stableSinceMs = null, outsideSinceMs = outsideSince)
        }
        is WatchingFirst -> {
            val outsideSince = state.outsideSinceMs ?: now
            if (now - outsideSince >= EXIT_MS) Outside
            else state.copy(outsideSinceMs = outsideSince)
        }
        is VerifyingNext -> WatchingFirst(state.firstPage, outsideSinceMs = now)
        Outside, Blocked -> state
    }

    private fun onUncertain(state: SessionState): SessionState = when (state) {
        is Arming -> state.copy(stableSinceMs = null, outsideSinceMs = null)
        is WatchingFirst -> state.copy(outsideSinceMs = null)
        is VerifyingNext -> WatchingFirst(state.firstPage)
        Outside, Blocked -> state
    }

    private fun onUnknown(state: SessionState, observation: Observation): SessionState {
        val now = observation.atMs
        if (state is VerifyingNext) {
            val progress = observation.scroll?.hasProgress() == true
            val lastMotionAt = if (progress) now else state.lastMotionAtMs
            val scroll = state.scroll.withNext(observation.scroll)
            val loadingDeadline = state.loadingDeadlineMs ?: (state.startedAtMs + LOADING_PAGER_WINDOW_MS)
                .takeIf { observation.trackLoadingPager && scroll?.hasProgress() == true }
            val waiting = state.copy(
                scroll = scroll,
                change = null,
                changeSinceMs = null,
                lastMotionAtMs = lastMotionAt,
                loadingDeadlineMs = loadingDeadline,
            )
            if (now < verificationDeadline(waiting)) return waiting
        }
        if (canTrackDuringLoading(observation) && (state == Outside || state is Arming)) {
            val firstPage = (state as? Arming)?.page ?: PageIdentity()
            return VerifyingNext(
                firstPage = firstPage,
                startedAtMs = now,
                scroll = observation.scroll,
                change = null,
                changeSinceMs = null,
                lastMotionAtMs = now,
                loadingDeadlineMs = now + LOADING_PAGER_WINDOW_MS,
            )
        }
        return onUncertain(state)
    }
}
