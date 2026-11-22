// 종료된 차단 화면이나 권한 해제 후의 콜백이 현재 세션을 변경하지 않는지 검증합니다.
package io.shortfree.overlay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockScreenSessionTest {
    @After
    fun clearRequest() {
        BlockScreenSession.cancel()
    }

    @Test
    fun staleScreenCannotDismissTheCurrentScreen() {
        var dismissed = 0
        val old = BlockScreenSession.begin { error("종료된 차단 화면입니다.") }
        val current = BlockScreenSession.begin { dismissed++ }

        BlockScreenSession.dismiss(old)
        assertEquals(current, BlockScreenSession.active.value)
        assertEquals(0, dismissed)

        BlockScreenSession.dismiss(current)
        BlockScreenSession.dismiss(current)
        assertNull(BlockScreenSession.active.value)
        assertEquals(1, dismissed)
    }

    @Test
    fun cancelClearsTheScreenWithoutInvokingTheReturnAction() {
        val token = BlockScreenSession.begin { error("취소된 차단 화면입니다.") }

        BlockScreenSession.cancel()
        BlockScreenSession.dismiss(token)

        assertNull(BlockScreenSession.active.value)
    }
}
