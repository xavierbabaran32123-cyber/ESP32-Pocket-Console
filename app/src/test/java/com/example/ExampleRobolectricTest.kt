package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.audio.ChiptuneSynthEngine
import com.example.ble.BleConsoleManager
import com.example.game.render.ConsoleCanvasRenderer
import com.example.game.state.ConsoleEngineState
import com.example.game.state.PacketProcessor
import com.example.ui.PocketConsoleScreen
import com.example.ui.theme.MaskMazeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun appNameAndBleNusUuidsMatchSpec() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Mask Maze", context.getString(R.string.app_name))
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", BleConsoleManager.NUS_SERVICE_UUID.toString())
        assertEquals("6e400003-b5a3-f393-e0a9-e50e24dcca9e", BleConsoleManager.NUS_TX_UUID.toString())
    }

    @Test
    fun canvasRendererRendersNoSignalMenuAndStageWithoutErrors() {
        val state = ConsoleEngineState()
        val sound = ChiptuneSynthEngine(enableAudioHardware = false)
        val processor = PacketProcessor(state, sound)
        val renderer = ConsoleCanvasRenderer(state, sound, null)

        val bmp = Bitmap.createBitmap(360, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // 1. Render No Signal screen
        renderer.renderFrame(canvas, 360f, 480f, 1000L)

        // 2. Render Start Menu (st = 10)
        val nPkt = ByteArray(14).apply {
            this[0] = 78 // 'N'
            this[1] = 10
            this[4] = 6
            this[5] = 1
            this[6] = 1
        }
        val mMenuPkt = ByteArray(18).apply {
            this[0] = 77 // 'M'
            this[1] = 10
        }
        processor.onPacket(nPkt, 1500L)
        processor.onPacket(mMenuPkt, 1500L)
        renderer.renderFrame(canvas, 360f, 480f, 1600L)

        // 3. Render Stage 1 (st = 1, inLevel = true)
        val mStagePkt = ByteArray(18).apply {
            this[0] = 77
            this[1] = 1
            this[2] = 1 // inLevel
            this[3] = 0 // Stage 0
            this[4] = 1
            this[5] = 5
            this[6] = 2
        }
        processor.onPacket(mStagePkt, 2000L)
        renderer.renderFrame(canvas, 360f, 480f, 2050L)

        assertTrue(bmp.width == 360 && bmp.height == 480)
        sound.release()
    }

    @Test
    fun pocketConsoleScreenDisplaysBleCompanionControlsWithoutLocalMode() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            MaskMazeTheme {
                PocketConsoleScreen()
            }
        }
        composeTestRule.mainClock.advanceTimeBy(100L)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("console_stage_canvas").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connect_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("qr_download_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sound_toggle_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullscreen_toggle_button").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("local_mode_button").assertCountEquals(0)

        // Verify QR download button opens scannable QR dialog
        composeTestRule.onNodeWithTag("qr_download_button").performClick()
        composeTestRule.mainClock.advanceTimeBy(100L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("qr_code_canvas").assertIsDisplayed()
        composeTestRule.onNodeWithTag("qr_close_button").performClick()
        composeTestRule.mainClock.advanceTimeBy(100L)
        composeTestRule.waitForIdle()

        // Verify fullscreen toggle hides header and shows clean unobstructed canvas without minimize button
        composeTestRule.onNodeWithTag("fullscreen_toggle_button").performClick()
        composeTestRule.mainClock.advanceTimeBy(100L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("console_stage_canvas").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("connect_button").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("fullscreen_exit_fab").assertCountEquals(0)
    }
}
