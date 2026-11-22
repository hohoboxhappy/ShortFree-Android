// 접근성 서비스가 요청한 차단 화면만 표시하고 닫힐 때 해당 세션을 정리합니다.
package io.shortfree.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object BlockScreenSession {
    const val TOKEN_EXTRA = "io.shortfree.block_screen_token"
    private val request = MutableStateFlow<Long?>(null)
    val active = request.asStateFlow()
    private var lastToken = 0L
    private var onDismiss: (() -> Unit)? = null

    fun begin(onDismiss: () -> Unit): Long {
        val token = ++lastToken
        this.onDismiss = onDismiss
        request.value = token
        return token
    }

    fun dismiss(token: Long) {
        if (request.value != token) return
        val callback = onDismiss
        cancel()
        callback?.invoke()
    }

    fun cancel() {
        onDismiss = null
        request.value = null
    }
}
