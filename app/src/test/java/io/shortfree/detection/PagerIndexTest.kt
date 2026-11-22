// 접근성 페이저가 단일 항목을 가리킬 때만 인덱스를 확정하는 규칙을 검증한다.
package io.shortfree.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PagerIndexTest {
    @Test
    fun onlyNarrowOrderedVisibleRangesDescribeAFullScreenPager() {
        assertEquals(0..1, visiblePagerRange(0, 1))
        assertEquals(4..4, visiblePagerRange(4, 4))
        assertEquals(null, visiblePagerRange(-1, 0))
        assertEquals(null, visiblePagerRange(5, 4))
        assertEquals(null, visiblePagerRange(0, 9))
    }

    @Test
    fun onlyMeasuredVerticalPagerMotionIsConvertedToPageTravel() {
        assertEquals(0.8, verticalTravelInPages(20, 800, 1_000), 0.0001)
        assertEquals(-0.9, verticalTravelInPages(20, -900, 1_000), 0.0001)
        assertEquals(0.0, verticalTravelInPages(-1, -1, 1_000), 0.0001)
        assertEquals(0.0, verticalTravelInPages(900, 50, 1_000), 0.0001)
        assertEquals(0.0, verticalTravelInPages(0, 2_100, 1_000), 0.0001)
    }

    @Test
    fun singleVisibleItemIsSettled() {
        assertEquals(0, settledPagerIndex(0, 0))
        assertEquals(42, settledPagerIndex(42, 42))
    }

    @Test
    fun overlappingOrMissingItemsAreNotSettled() {
        assertNull(settledPagerIndex(4, 5))
        assertNull(settledPagerIndex(-1, -1))
        assertNull(settledPagerIndex(-1, 0))
    }
}
