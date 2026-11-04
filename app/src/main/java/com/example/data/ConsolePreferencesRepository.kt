package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.consoleDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mask_maze_console_prefs"
)

data class ConsoleSavedPrefs(
    val soundMuted: Boolean = false,
    val musicOn: Boolean = true,
    val sfxOn: Boolean = true
)

class ConsolePreferencesRepository(private val context: Context) {

    private object Keys {
        val SOUND_MUTED = booleanPreferencesKey("sound_muted")
        val MUSIC_ON = booleanPreferencesKey("music_on")
        val SFX_ON = booleanPreferencesKey("sfx_on")
    }

    val prefsFlow: Flow<ConsoleSavedPrefs> = context.consoleDataStore.data.map { p ->
        ConsoleSavedPrefs(
            soundMuted = p[Keys.SOUND_MUTED] ?: false,
            musicOn = p[Keys.MUSIC_ON] ?: true,
            sfxOn = p[Keys.SFX_ON] ?: true
        )
    }

    suspend fun setSoundMuted(muted: Boolean) {
        context.consoleDataStore.edit { it[Keys.SOUND_MUTED] = muted }
    }
}
