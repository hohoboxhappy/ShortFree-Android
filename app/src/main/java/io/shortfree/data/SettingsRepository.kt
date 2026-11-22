// 접근성 안내 동의와 앱별 차단 설정을 기기의 DataStore에 저장합니다.
package io.shortfree.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import io.shortfree.engine.TargetApp
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.shortFreeSettings by preferencesDataStore(name = "shortfree")

data class ShortFreeSettings(
    val consentAccepted: Boolean = false,
    val youtubeEnabled: Boolean = true,
    val instagramEnabled: Boolean = true,
    val reelSwipeGuardEnabled: Boolean = false,
) {
    fun isEnabled(app: TargetApp): Boolean = when (app) {
        TargetApp.YOUTUBE -> youtubeEnabled
        TargetApp.INSTAGRAM -> instagramEnabled
    }
}

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.shortFreeSettings

    val settings: Flow<ShortFreeSettings> = store.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            ShortFreeSettings(
                consentAccepted = preferences[CONSENT] ?: false,
                youtubeEnabled = preferences[YOUTUBE] ?: true,
                instagramEnabled = preferences[INSTAGRAM] ?: true,
                reelSwipeGuardEnabled = preferences[REEL_SWIPE_GUARD] ?: false,
            )
        }

    suspend fun acceptDisclosure() {
        store.edit { it[CONSENT] = true }
    }

    suspend fun setEnabled(app: TargetApp, enabled: Boolean) {
        val setting = when (app) {
            TargetApp.YOUTUBE -> YOUTUBE
            TargetApp.INSTAGRAM -> INSTAGRAM
        }
        store.edit { it[setting] = enabled }
    }

    suspend fun setReelSwipeGuardEnabled(enabled: Boolean) {
        store.edit { it[REEL_SWIPE_GUARD] = enabled }
    }

    private companion object {
        val CONSENT = booleanPreferencesKey("consent_accepted")
        val YOUTUBE = booleanPreferencesKey("youtube_enabled")
        val INSTAGRAM = booleanPreferencesKey("instagram_enabled")
        val REEL_SWIPE_GUARD = booleanPreferencesKey("reel_swipe_guard_enabled")
    }
}
