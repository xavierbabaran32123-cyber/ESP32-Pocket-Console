package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.consoleDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mask_maze_console_prefs"
)

data class ConsoleSavedPrefs(
    val soundMuted: Boolean = false,
    val musicOn: Boolean = true,
    val sfxOn: Boolean = true,
    val showGamepadInLocal: Boolean = true,
    val unlockedStages: Int = 1,
    val ownedMasksBits: Int = 1,
    val equippedMask: Int = 0,
    val walletCoins: Int = 0,
    val stageStars: IntArray = IntArray(45),
    val bestEndless: Int = 0,
    val bestStageScore: Int = 0,
    val bestJet: Int = 0,
    val bestDot: Int = 0,
    val bestBlocks: Int = 0
)

class ConsolePreferencesRepository(private val context: Context) {

    private object Keys {
        val SOUND_MUTED = booleanPreferencesKey("sound_muted")
        val MUSIC_ON = booleanPreferencesKey("music_on")
        val SFX_ON = booleanPreferencesKey("sfx_on")
        val SHOW_GAMEPAD = booleanPreferencesKey("show_gamepad")
        val UNLOCKED_STAGES = intPreferencesKey("unlocked_stages")
        val OWNED_MASKS = intPreferencesKey("owned_masks")
        val EQUIPPED_MASK = intPreferencesKey("equipped_mask")
        val WALLET_COINS = intPreferencesKey("wallet_coins")
        val STAGE_STARS_CSV = stringPreferencesKey("stage_stars_csv")
        val BEST_ENDLESS = intPreferencesKey("best_endless")
        val BEST_STAGE_SCORE = intPreferencesKey("best_stage_score")
        val BEST_JET = intPreferencesKey("best_jet")
        val BEST_DOT = intPreferencesKey("best_dot")
        val BEST_BLOCKS = intPreferencesKey("best_blocks")
    }

    val prefsFlow: Flow<ConsoleSavedPrefs> = context.consoleDataStore.data.map { p ->
        val stars = IntArray(45)
        val csv = p[Keys.STAGE_STARS_CSV].orEmpty()
        if (csv.isNotEmpty()) {
            val parts = csv.split(",")
            for (i in 0 until minOf(45, parts.size)) {
                stars[i] = parts[i].toIntOrNull()?.coerceIn(0, 3) ?: 0
            }
        }
        ConsoleSavedPrefs(
            soundMuted = p[Keys.SOUND_MUTED] ?: false,
            musicOn = p[Keys.MUSIC_ON] ?: true,
            sfxOn = p[Keys.SFX_ON] ?: true,
            showGamepadInLocal = p[Keys.SHOW_GAMEPAD] ?: true,
            unlockedStages = (p[Keys.UNLOCKED_STAGES] ?: 1).coerceIn(1, 45),
            ownedMasksBits = (p[Keys.OWNED_MASKS] ?: 1) or 1,
            equippedMask = (p[Keys.EQUIPPED_MASK] ?: 0).coerceIn(0, 14),
            walletCoins = (p[Keys.WALLET_COINS] ?: 0).coerceAtLeast(0),
            stageStars = stars,
            bestEndless = p[Keys.BEST_ENDLESS] ?: 0,
            bestStageScore = p[Keys.BEST_STAGE_SCORE] ?: 0,
            bestJet = p[Keys.BEST_JET] ?: 0,
            bestDot = p[Keys.BEST_DOT] ?: 0,
            bestBlocks = p[Keys.BEST_BLOCKS] ?: 0
        )
    }

    suspend fun setSoundMuted(muted: Boolean) {
        context.consoleDataStore.edit { it[Keys.SOUND_MUTED] = muted }
    }

    suspend fun setShowGamepad(show: Boolean) {
        context.consoleDataStore.edit { it[Keys.SHOW_GAMEPAD] = show }
    }

    suspend fun saveLocalProgress(save: ConsoleSavedPrefs) {
        context.consoleDataStore.edit { p ->
            p[Keys.MUSIC_ON] = save.musicOn
            p[Keys.SFX_ON] = save.sfxOn
            p[Keys.UNLOCKED_STAGES] = save.unlockedStages.coerceIn(1, 45)
            p[Keys.OWNED_MASKS] = save.ownedMasksBits or 1
            p[Keys.EQUIPPED_MASK] = save.equippedMask.coerceIn(0, 14)
            p[Keys.WALLET_COINS] = save.walletCoins.coerceAtLeast(0)
            p[Keys.STAGE_STARS_CSV] = save.stageStars.joinToString(",")
            p[Keys.BEST_ENDLESS] = save.bestEndless
            p[Keys.BEST_STAGE_SCORE] = save.bestStageScore
            p[Keys.BEST_JET] = save.bestJet
            p[Keys.BEST_DOT] = save.bestDot
            p[Keys.BEST_BLOCKS] = save.bestBlocks
        }
    }
}
