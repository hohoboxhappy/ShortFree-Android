// 앱이나 기기 없이 첫 영상 세션의 허용·차단·이탈 전이를 검증합니다.
package io.shortfree.engine

import io.shortfree.engine.SessionState.Arming
import io.shortfree.engine.SessionState.Blocked
import io.shortfree.engine.SessionState.Outside
import io.shortfree.engine.SessionState.VerifyingNext
import io.shortfree.engine.SessionState.WatchingFirst
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReducerTest {
    @Test
    fun ignoresInitialPagerMovementAndStoresTheStablePage() {
        val opened = observe(Outside, 0, token = "loading", index = 0)
        val initialMovement = observe(opened, 100, token = "first", index = 1, scroll = PagerScroll(1))

        assertTrue(initialMovement is Arming)
        assertEquals(WatchingFirst(PageIdentity("first", 1)), observe(initialMovement, 380, token = "first", index = 1))
    }

    @Test
    fun firstSwipeDuringInitialStabilizationKeepsTheFirstVideoAsBaseline() {
        val opened = observe(Outside, 0, token = "first", index = 0)
        val started = observe(opened, 110, token = "first", scroll = PagerScroll(travelInPages = 0.22))
        val arrived = observe(started, 200, token = "next", index = 1, scroll = PagerScroll(1, 0.78))

        assertTrue(started is VerifyingNext)
        assertEquals(Blocked, observe(arrived, 300, token = "next", index = 1))
    }

    @Test
    fun settledFirstSwipeIsHandledEvenIfTheArmingTimerDidNotRun() {
        val opened = observe(Outside, 0, token = "first", index = 0)
        val firstSwipe = observe(opened, 500, token = "next", index = 1, scroll = PagerScroll(1))

        assertTrue(firstSwipe is VerifyingNext)
        assertEquals(Blocked, observe(firstSwipe, 601, token = "next", index = 1))
    }

    @Test
    fun shiftingTheVisiblePagerRangeConfirmsTheFirstSwipeWithoutMetadataOrDelta() {
        val first = WatchingFirst(PageIdentity())
        val before = observe(first, 2_000, scroll = PagerScroll(firstVisiblePages = 0..1))
        val after = observe(before, 2_150, scroll = PagerScroll(firstVisiblePages = 1..2))

        assertTrue(observe(after, 2_249) is VerifyingNext)
        assertEquals(Blocked, observe(after, 2_250))
    }

    @Test
    fun returningToTheOriginalPagerRangeDoesNotCountAsANewVideo() {
        val first = WatchingFirst(PageIdentity())
        val before = observe(first, 2_000, scroll = PagerScroll(firstVisiblePages = 0..1))
        val after = observe(before, 2_150, scroll = PagerScroll(firstVisiblePages = 1..2))
        val cancelled = observe(after, 2_190, scroll = PagerScroll(firstVisiblePages = 0..1))

        assertEquals(first, observe(cancelled, 2_540))
    }

    @Test
    fun temporaryMissingNodesDoNotDiscardAnOtherwiseConfirmedPagerTransition() {
        val first = watching()
        val moved = observe(first, 400, token = "next", scroll = PagerScroll())
        val noNodes = observe(moved, 450, surface = Surface.UNKNOWN)
        val sameNextVideo = observe(noNodes, 520, token = "next")

        assertTrue(noNodes is VerifyingNext)
        assertEquals(Blocked, observe(sameNextVideo, 620, token = "next"))
    }

    @Test
    fun aShortsSwipeDuringLoadingIsKeptUntilThePlayerAppears() {
        val origin = PagerScroll(travelInPages = 0.30, firstVisiblePages = 0..1)
        val settling = PagerScroll(travelInPages = 0.64, firstVisiblePages = 1..1)
        val loading = observe(Outside, 20, surface = Surface.UNKNOWN, scroll = origin, trackLoadingPager = true)
        val movedWhileLoading = observe(loading, 140, surface = Surface.UNKNOWN, scroll = settling, trackLoadingPager = true)
        val stillLoading = observe(movedWhileLoading, 1_500, surface = Surface.UNKNOWN, trackLoadingPager = true)
        val visible = observe(stillLoading, 2_200, trackLoadingPager = true)

        assertTrue(loading is VerifyingNext)
        assertTrue(movedWhileLoading is VerifyingNext)
        assertTrue(stillLoading is VerifyingNext)
        assertEquals(Blocked, observe(visible, 2_300, trackLoadingPager = true))
    }

    @Test
    fun firstVideosInitialIndexDoesNotCountAsMovingDuringLoading() {
        val initial = PagerScroll(settledIndex = 0, firstVisiblePages = 0..0)

        assertEquals(Outside, observe(Outside, 20, surface = Surface.UNKNOWN, scroll = initial, trackLoadingPager = true))
        assertTrue(observe(Outside, 40, index = 0, scroll = initial, trackLoadingPager = true) is Arming)
    }

    @Test
    fun movingWithoutAnyPlayerOrWithCommentsOpenCannotBlock() {
        val moved = PagerScroll(travelInPages = 1.0, firstVisiblePages = 0..1)
        val loading = observe(Outside, 20, surface = Surface.UNKNOWN, scroll = moved, trackLoadingPager = true)

        assertTrue(observe(loading, 160, surface = Surface.UNKNOWN, trackLoadingPager = true) is VerifyingNext)
        assertTrue(observe(loading, 160, surface = Surface.TRANSIENT, trackLoadingPager = true) is WatchingFirst)
    }

    @Test
    fun blocksAfterStableChangeOfContentWithPagerEvidence() {
        val candidate = observe(watching(), 400, token = "next", scroll = PagerScroll())

        assertTrue(candidate is VerifyingNext)
        assertTrue(observe(candidate, 499, token = "next") is VerifyingNext)
        assertEquals(Blocked, observe(candidate, 500, token = "next"))
    }

    @Test
    fun blocksWhenExplicitPageIndexChangesWithIdenticalContent() {
        val first = watching(index = 3)
        val candidate = observe(first, 400, token = "first", index = 4)

        assertEquals(Blocked, observe(candidate, 510, token = "first", index = 4))
    }

    @Test
    fun captionChangeCanDistinguishVideosByTheSameAuthorAfterPagerMotion() {
        val first = WatchingFirst(PageIdentity(token = "same author", captionToken = "caption one"))
        val nextPage = PlayerSnapshot(
            Surface.PLAYER,
            PageIdentity(token = "same author", captionToken = "caption two"),
        )
        val candidate = SessionReducer.reduce(first, Observation(nextPage, 400, PagerScroll()))

        assertEquals(first, SessionReducer.reduce(first, Observation(nextPage, 400)))
        assertEquals(Blocked, SessionReducer.reduce(candidate, Observation(nextPage, 520)))
    }

    @Test
    fun laterUnsettledScrollClearsStaleIndexEvidence() {
        val first = watching(index = 3)
        val candidate = observe(first, 400, token = "first", scroll = PagerScroll(4))
        val moving = observe(candidate, 460, token = "first", scroll = PagerScroll())

        assertEquals(first, observe(moving, 750, token = "first"))
    }

    @Test
    fun usesSettledEventIndexWhenSnapshotHasNoIndex() {
        val first = watching(index = 3)
        val candidate = observe(first, 400, token = "first", scroll = PagerScroll(4))

        assertEquals(Blocked, observe(candidate, 510, token = "first"))
    }

    @Test
    fun ignoresPagerMotionThatSettlesBackToTheSameVideo() {
        val first = watching(index = 0)
        val candidate = observe(first, 400, token = "next", scroll = PagerScroll())
        val recentred = observe(candidate, 470, token = "first", index = 0)

        assertEquals(first, observe(recentred, 750, token = "first", index = 0))
    }

    @Test
    fun ignoresMetadataChangesAndPlaybackRepeatsWithoutPagerMovement() {
        val first = watching()
        val metadataChange = observe(first, 400, token = "changed metadata")
        val repeat = observe(metadataChange, 800, token = "first")

        assertEquals(first, metadataChange)
        assertEquals(first, repeat)
    }

    @Test
    fun unknownBaselineAndScrollAloneNeverConfirmAnotherVideo() {
        val first = WatchingFirst(PageIdentity())
        val candidate = observe(first, 400, scroll = PagerScroll())

        assertEquals(first, observe(candidate, 750, token = "only observation"))
    }

    @Test
    fun fullPageMotionConfirmsANewVideoEvenWhenIdentityIsUnavailable() {
        val first = WatchingFirst(PageIdentity())
        val started = observe(first, 400, scroll = PagerScroll(travelInPages = 0.33))
        val moved = observe(started, 570, scroll = PagerScroll(travelInPages = 0.36))
        val settled = observe(moved, 820, scroll = PagerScroll(travelInPages = 0.26))

        assertTrue(observe(settled, 919) is VerifyingNext)
        assertEquals(Blocked, observe(settled, 920))
    }

    @Test
    fun reverseMotionCancelsTheTemporaryCoverCandidate() {
        val first = WatchingFirst(PageIdentity())
        val almostNext = observe(first, 400, scroll = PagerScroll(travelInPages = 0.94))
        val slidBack = observe(almostNext, 450, scroll = PagerScroll(travelInPages = -0.90))

        assertTrue(observe(slidBack, 515) is VerifyingNext)
        assertEquals(first, observe(slidBack, 800))
    }

    @Test
    fun oppositePageTravelCanConfirmAChange() {
        val first = WatchingFirst(PageIdentity())
        val moved = observe(first, 400, scroll = PagerScroll(travelInPages = -0.95))

        assertEquals(Blocked, observe(moved, 500))
    }

    @Test
    fun fullMotionInACommentPanelDoesNotBlock() {
        val first = WatchingFirst(PageIdentity())
        val moving = PagerScroll(travelInPages = 1.0)

        assertEquals(first, observe(first, 400, surface = Surface.TRANSIENT, scroll = moving))
    }

    @Test
    fun commentsCancelCandidateAndDoNotEndTheSession() {
        val first = watching()
        val candidate = observe(first, 400, token = "next", scroll = PagerScroll())
        val comments = observe(candidate, 450, surface = Surface.TRANSIENT, scroll = PagerScroll())
        val longComments = observe(comments, 1_400, surface = Surface.TRANSIENT)

        assertEquals(first, longComments)
        assertEquals(first, observe(longComments, 1_600, token = "first"))
    }

    @Test
    fun returningFromAnUnrelatedAppKeepsTheFirstVideo() {
        val first = watching()
        val candidate = observe(first, 400, token = "next", scroll = PagerScroll())
        val returned = SessionReducer.leaveForeground(candidate)

        assertEquals(first, returned)
        assertEquals(first, observe(returned, 2_000, token = "first"))
    }

    @Test
    fun aStableExitAllowsAnotherFirstVideo() {
        val first = watching()
        val leaving = observe(first, 400, surface = Surface.OUTSIDE)

        assertTrue(observe(leaving, 1_019, surface = Surface.OUTSIDE) is WatchingFirst)
        val exited = observe(leaving, 1_020, surface = Surface.OUTSIDE)
        assertEquals(Outside, exited)
        assertTrue(observe(exited, 1_040, token = "another first") is Arming)
    }

    @Test
    fun uncertainScreenDoesNotCountAsVerifiedExit() {
        val first = watching()
        val possibleExit = observe(first, 400, surface = Surface.OUTSIDE)
        val uncertain = observe(possibleExit, 710, surface = Surface.UNKNOWN)
        val secondPossibleExit = observe(uncertain, 1_500, surface = Surface.OUTSIDE)

        assertEquals(first, uncertain)
        assertTrue(secondPossibleExit is WatchingFirst)
    }

    @Test
    fun backgroundBackKeepsTheOverlayUntilLeavingTheTargetApp() {
        val appAfterBackgroundBack = observe(Blocked, 800, surface = Surface.OUTSIDE)

        assertEquals(Blocked, appAfterBackgroundBack)
        assertEquals(Outside, SessionReducer.leaveForeground(appAfterBackgroundBack))
    }

    private fun watching(index: Int? = null) = WatchingFirst(PageIdentity("first", index))

    private fun observe(
        state: SessionState,
        time: Long,
        token: String? = null,
        index: Int? = null,
        surface: Surface = Surface.PLAYER,
        scroll: PagerScroll? = null,
        trackLoadingPager: Boolean = false,
    ): SessionState = SessionReducer.reduce(
        state,
        Observation(PlayerSnapshot(surface, PageIdentity(token, index)), time, scroll, trackLoadingPager),
    )
}
