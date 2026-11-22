// 차단 메시지를 전면 화면으로 표시해 뒤에 있는 영상 앱이 백그라운드로 이동하게 합니다.
package io.shortfree.overlay

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import io.shortfree.R
import kotlinx.coroutines.launch

internal class BlockedActivity : ComponentActivity() {
    private var token = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getLongExtra(BlockScreenSession.TOKEN_EXTRA, 0L)
        if (BlockScreenSession.active.value != token) {
            finish()
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.block_overlay)
        val content = findViewById<View>(R.id.block_content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        findViewById<View>(R.id.return_button).setOnClickListener { finish() }
        lifecycleScope.launch {
            BlockScreenSession.active.collect { activeToken ->
                if (activeToken != token) finish()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            BlockScreenSession.dismiss(token)
            if (!isFinishing) finish()
        }
    }
}
