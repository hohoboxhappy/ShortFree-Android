// 릴스에서 위로 미는 단일 터치만 차단 후보로 분류하고 다른 입력 경로와 시간을 보존하는지 확인합니다.
package io.shortfree.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelTouchGestureTest {
    private val start = TouchPoint(200f, 600f)

    @Test
    fun upwardSwipeIsDroppedEvenIfTheFingerReturnsToTheStartingPoint() {
        val gesture = begin()
        gesture.sample(0, TouchPoint(218f, 475f), 1_100)
        gesture.sample(0, start, 1_250)

        assertTrue(gesture.isNextReelSwipe)
    }

    @Test
    fun aTapKeepsItsCoordinatesAndDuration() {
        val gesture = begin()
        gesture.sample(0, start, 1_075)

        assertFalse(gesture.isNextReelSwipe)
        assertEquals(listOf(TouchStroke(listOf(start), 0, 75)), gesture.strokes())
    }

    @Test
    fun horizontalAndDownwardDragsAreForwarded() {
        val horizontal = begin().apply { sample(0, TouchPoint(470f, 575f), 1_350) }
        val downward = begin().apply { sample(0, TouchPoint(202f, 775f), 1_250) }

        assertFalse(horizontal.isNextReelSwipe)
        assertFalse(downward.isNextReelSwipe)
        assertEquals(listOf(start, TouchPoint(470f, 575f)), horizontal.strokes().single().points)
    }

    @Test
    fun moreThanOneFingerIsNotTreatedAsPassingToTheNextReel() {
        val gesture = begin()
        gesture.sample(0, TouchPoint(205f, 500f), 1_070)
        gesture.start(1, TouchPoint(300f, 650f), 1_090)
        gesture.sample(1, TouchPoint(303f, 645f), 1_140)

        assertFalse(gesture.isNextReelSwipe)
        assertEquals(90L, gesture.strokes().last().startedMs)
    }

    @Test
    fun smallUpwardTapMovementIsNotDropped() {
        val gesture = begin()
        gesture.sample(0, TouchPoint(203f, 593f), 1_030)

        assertFalse(gesture.isNextReelSwipe)
    }

    private fun begin() = ReelTouchGesture(swipeDistance = 24f, downTime = 1_000).apply {
        start(0, start, 1_000)
    }
}
