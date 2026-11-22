// 접근성 서비스의 창에 불투명한 차단 화면을 한 번만 붙이고 필요할 때 제거한다.
package io.shortfree.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import io.shortfree.R

class BlockOverlayController(private val context: Context) {
    private val windows = requireNotNull(context.getSystemService(WindowManager::class.java))
    private var overlay: FrameLayout? = null
    private var confirmed = false

    fun coverPreview(): Boolean = overlay != null || attach(newCover())

    fun show(onReturn: () -> Unit): Boolean {
        if (confirmed) return true
        val cover = overlay ?: newCover()
        val themedContext = ContextThemeWrapper(context, R.style.Theme_ShortFree_Overlay)
        LayoutInflater.from(themedContext).inflate(R.layout.block_overlay, cover, true)
        cover.findViewById<View>(R.id.return_button).setOnClickListener { onReturn() }
        confirmed = overlay != null || attach(cover)
        return confirmed
    }

    fun hidePreview() {
        if (!confirmed) hide()
    }

    private fun newCover() = FrameLayout(context).apply {
        setBackgroundColor(context.getColor(R.color.canvas))
    }

    private fun attach(view: FrameLayout): Boolean {
        val params = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        ).apply {
            title = context.getString(R.string.blocked)
        }
        return try {
            windows.addView(view, params)
            overlay = view
            true
        } catch (_: WindowManager.BadTokenException) {
            false
        } catch (_: WindowManager.InvalidDisplayException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun hide() {
        val view = overlay ?: return
        overlay = null
        confirmed = false
        try {
            windows.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
            // 서비스 연결이 끝나면서 시스템이 이미 창을 제거했을 수 있다.
        }
    }
}
