package com.example

import com.example.audio.SoundPlayer
import com.example.data.ConsoleSavedPrefs
import com.example.game.data.ArcadeAssetsData
import com.example.game.data.HeroSpritesData
import com.example.game.data.LevelsData
import com.example.game.local.ConsoleButton
import com.example.game.local.LocalBlocksEngine
import com.example.game.local.LocalConsoleSimulator
import com.example.game.local.LocalDotMazeEngine
import com.example.game.local.LocalJetFighterEngine
import com.example.game.state.ConsoleEngineState
import com.example.game.state.PacketProcessor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    private class FakeSoundPlayer : SoundPlayer {
        override var mute: Boolean = false
        override var musicOn: Boolean = true
        override var sfxOn: Boolean = true
        override var wantTrack: Int = -1
        val playedSfx = ArrayList<String>()
        var lastRumble: Float = 0f

        override fun ensureStarted() {}
        override fun sfx(name: String, prio: Int, vol: Float) {
            playedSfx.add(name)
        }
        override fun setRumble(v: Float) {
            lastRumble = v
        }
        override fun release() {}
    }

    @Test
    fun all45LevelsLoadWithValidDimensionsAndSpawnGate() {
        assertEquals(45, LevelsData.LV_NAMES.size)
        for (i in 0 until 45) {
            val lv = LevelsData.loadLevel(i)
            assertNotNull("Stage $i should load", lv)
            lv!!
            assertTrue("Stage $i width must be > 4", lv.width > 4)
            assertTrue("Stage $i height must be > 4", lv.height > 4)
            assertTrue("Stage $i gateX within bounds", lv.gateX in 0 until lv.width)
            assertTrue("Stage $i gateY within bounds", lv.gateY in 0 until lv.height)
            assertEquals("Stage $i spawn tile should not be a wall", 0, lv.walls[lv.gateY][lv.gateX])
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    @Test
    fun all15HeroMasksGenerateValidPoses() {
        assertEquals(15, HeroSpritesData.MASK_NAMES.size)
        assertEquals(15, HeroSpritesData.MASK_COST.size)
        for (m in 0 until 15) {
            val smallPose = HeroSpritesData.heroPose(HeroSpritesData.HERO[m], 20, 1, 0, 0, 1)
            val bigPose = HeroSpritesData.heroPose(HeroSpritesData.HERO36[m], 36, 0, 2, 1, 3)
            assertEquals(20, smallPose.size)
            assertEquals(36, bigPose.size)
        }
    }

    @Test
    fun packetProcessorDecodesMainV2AndStageSlide() {
        val state = ConsoleEngineState()
        val sound = FakeSoundPlayer()
        val processor = PacketProcessor(state, sound)

        val pkt = ByteArray(18)
        pkt[0] = 77 // 'M'
        pkt[1] = 1  // st = PLAYING
        pkt[2] = (1 or 2 or (1 shl 2)).toByte() // inLevel=1, mv=1, dir=1 (Right)
        pkt[3] = 2  // stage index 2
        pkt[4] = 7  // run 7
        pkt[5] = 4  // x = 4
        pkt[6] = 5  // y = 5
        pkt[7] = 128.toByte()
        pkt[8] = ((3 shl 4) or 2).toByte() // mask=3, stars=2
        pkt[9] = 19 // coins=19
        pkt[10] = 0x34
        pkt[11] = 0x01 // clk = 0x0134
        pkt[12] = (1 or (1 shl 2) or (2 shl 3)).toByte() // pauseSel=1, clearSel=1, face=2
        pkt[13] = 200.toByte()
        pkt[14] = 0x10
        pkt[15] = 0x27 // best = 10000

        processor.onPacket(pkt, nowMs = 1000L)

        val parsed = state.p
        assertNotNull(parsed)
        assertEquals(1, parsed!!.st)
        assertEquals(2, parsed.lvl)
        assertEquals(3, parsed.mask)
        assertEquals(2, parsed.stars)
        assertEquals(19, parsed.coins)
        assertEquals(1, parsed.dir)
        assertTrue(parsed.mv)
        assertEquals(10000L, parsed.best)
        assertEquals(1, sound.wantTrack)
    }

    @Test
    fun packetProcessorDecodesEndlessAndArcadePackets() {
        val state = ConsoleEngineState()
        val sound = FakeSoundPlayer()
        val processor = PacketProcessor(state, sound)

        // Endless Row 'R' (82)
        val rPkt = ByteArray(19)
        rPkt[0] = 82
        rPkt[1] = 10 // y = 10
        rPkt[2] = 0
        rPkt[3] = 2  // stage 2
        rPkt[4] = 1  // gid 1
        rPkt[5] = 0x07 // walls at x=0,1,2
        rPkt[8] = ((2 shl 4) or 8).toByte() // x=0 item=2, x=1 item=8 (Shield)
        processor.onPacket(rPkt, nowMs = 1200L)

        val row = state.endless.rows[10]
        assertNotNull(row)
        assertEquals(1, row!!.w[0])
        assertEquals(1, row.w[2])
        assertEquals(0, row.w[3])
        assertEquals(2, row.it[0])
        assertEquals(8, row.it[1])

        // Blocks 'F' (70)
        val fPkt = ByteArray(18)
        fPkt[0] = 70
        fPkt[1] = 0
        fPkt[2] = ((4 shl 4) or 6).toByte()
        processor.onPacket(fPkt, nowMs = 1300L)
        assertEquals(4, state.blocks.field[0])
        assertEquals(6, state.blocks.field[1])

        val cells = ArcadeAssetsData.tbCells(2, 1)
        assertEquals(4, cells.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun localConsoleSimulatorNavigatesStartTitleAndStageAndArcade() {
        val dispatcher = StandardTestDispatcher()
        val testScope = TestScope(dispatcher)
        val state = ConsoleEngineState()
        val sound = FakeSoundPlayer()
        val processor = PacketProcessor(state, sound)
        var lastSaved: ConsoleSavedPrefs? = null

        val simulator = LocalConsoleSimulator(
            scope = testScope,
            emitPacket = { bytes -> processor.onPacket(bytes, nowMs = 5000L) },
            onPersistSave = { lastSaved = it }
        )

        simulator.start()
        assertEquals(10, state.p?.st) // Starts at Start Screen (10)

        // Press A -> Title Menu (0)
        simulator.onButtonPress(ConsoleButton.A)
        simulator.onButtonRelease(ConsoleButton.A)
        assertEquals(0, state.p?.st)

        // Press A on NEW GAME -> Stage 0 (st = 1, inLevel = true)
        simulator.onButtonPress(ConsoleButton.A)
        simulator.onButtonRelease(ConsoleButton.A)
        assertEquals(1, state.p?.st)
        assertEquals(0, state.p?.lvl)
        assertTrue((state.p!!.f and 1) != 0)
        assertNotNull(lastSaved)

        testScope.advanceTimeBy(100L)
        simulator.stop()

        // Verify Arcade engines emit valid binary packets into PacketProcessor
        val jet = LocalJetFighterEngine()
        jet.reset(100)
        jet.step(30f, setOf(ConsoleButton.A)) { processor.onPacket(it, 6000L) }
        assertEquals(100L, state.jet.hi)

        val dot = LocalDotMazeEngine()
        dot.reset(200, 1)
        dot.step(30f, setOf(ConsoleButton.LEFT)) { processor.onPacket(it, 6100L) }
        assertEquals(1, state.dotMaze.mask)

        val blocks = LocalBlocksEngine()
        blocks.reset(300)
        blocks.step(30f, emptySet()) { processor.onPacket(it, 6200L) }
        assertEquals(300, state.blocks.hi)
    }
}
