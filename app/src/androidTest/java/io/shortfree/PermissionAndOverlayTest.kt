// 접근성 동의 화면, 앱별 스위치와 차단 창의 터치·중복 표시 동작을 검증합니다.
package io.shortfree

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.shortfree.data.ShortFreeSettings
import io.shortfree.engine.TargetApp
import io.shortfree.overlay.BlockOverlayController
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionAndOverlayTest {
    @get:Rule
    val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun explicitConsentIsRequiredBeforeAppSettingsAreShown() {
        val agreed = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    settings = ShortFreeSettings(),
                    connected = false,
                    savingConsent = false,
                    onSettings = { error("동의 전에는 바로 설정을 열 수 없습니다.") },
                    onAccept = { agreed.incrementAndGet() },
                    onDecline = {},
                    onToggle = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.disclosure_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.youtube)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.agree)).performClick()
        assertEquals(1, agreed.get())
    }

    @Test
    fun connectedStatusIsVisibleAndEachSwitchTargetsItsOwnApp() {
        val changed = AtomicReference<Pair<TargetApp, Boolean>>()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    settings = ShortFreeSettings(consentAccepted = true, instagramEnabled = false),
                    connected = true,
                    savingConsent = false,
                    onSettings = {},
                    onAccept = {},
                    onDecline = {},
                    onToggle = { app, enabled -> changed.set(app to enabled) },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.running)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.youtube)).performClick()
        assertEquals(TargetApp.YOUTUBE to false, changed.get())
        compose.onNodeWithText(context.getString(R.string.instagram)).performClick()
        assertEquals(TargetApp.INSTAGRAM to true, changed.get())
    }

    @Test
    fun overlayIsTouchableWithoutTakingSystemNavigationFocus() {
        val added = mutableListOf<Pair<View, WindowManager.LayoutParams>>()
        val removed = mutableListOf<View>()
        val windows = Proxy.newProxyInstance(
            WindowManager::class.java.classLoader,
            arrayOf(WindowManager::class.java),
        ) { _, method, args ->
            when (method.name) {
                "addView" -> added.add(args!![0] as View to args[1] as WindowManager.LayoutParams)
                "removeViewImmediate" -> removed.add(args!![0] as View)
                else -> error("예상하지 못한 창 동작: ${method.name}")
            }
            null
        } as WindowManager
        val testContext = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.WINDOW_SERVICE) windows else super.getSystemService(name)
        }

        instrumentation.runOnMainSync {
            val overlay = BlockOverlayController(testContext)
            var returned = 0
            assertTrue(overlay.coverPreview())
            assertEquals(null, added.single().first.findViewById<View>(R.id.return_button))
            overlay.hidePreview()
            assertEquals(added.single().first, removed.single())
            added.clear()
            removed.clear()

            assertTrue(overlay.coverPreview())
            assertTrue(overlay.show { returned++ })
            assertTrue(overlay.show { error("차단 창이 중복으로 표시됐습니다.") })
            overlay.hidePreview()
            assertTrue(removed.isEmpty())

            val (view, params) = added.single()
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
            assertTrue(view.findViewById<View>(R.id.return_button).performClick())
            assertEquals(1, returned)

            overlay.hide()
            overlay.hide()
            assertEquals(listOf(view), removed)
        }
    }
}
