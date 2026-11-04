package com.example

import com.example.audio.SoundPlayer
import com.example.game.data.ArcadeAssetsData
import com.example.game.data.HeroSpritesData
import com.example.game.data.LevelsData
import com.example.game.state.ConsoleEngineState
import com.example.game.state.PacketProcessor
import com.example.qr.QrCodeEncoder
import kotlin.math.abs
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
    fun packetProcessorDecodesEndlessMenuAndArcadePackets() {
        val state = ConsoleEngineState()
        val sound = FakeSoundPlayer()
        val processor = PacketProcessor(state, sound)

        // Menu 'N' (78)
        val nPkt = ByteArray(14)
        nPkt[0] = 78
        nPkt[1] = 0 // Title menu
        nPkt[2] = 2 // cursor on MASKS
        nPkt[3] = 5 // mask 5
        nPkt[4] = 6 // musicOn + sfxOn
        nPkt[5] = 12 // unlocked = 12
        processor.onPacket(nPkt, nowMs = 1100L)
        assertEquals(2, state.menu.sel)
        assertEquals(5, state.menu.mask)
        assertEquals(12, state.menu.unl)

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

    @Test
    fun qrCodeEncoderGeneratesValidIso18004MatrixAndDecodesBackToDownloadUrl() {
        val qr = QrCodeEncoder.encodeByteMode(QrCodeEncoder.APK_DOWNLOAD_URL)
        // 102-byte URL fits in Version 5-L (37 x 37)
        assertEquals(37, qr.size)

        // Verify dark module at (8, 4*5 + 9) = (8, 29)
        assertTrue(qr.get(8, 29))

        // Read back 15-bit format info from both copies and verify identical & valid BCH
        var fmt1 = 0
        for (i in 0..5) if (qr.get(8, i)) fmt1 = fmt1 or (1 shl i)
        if (qr.get(8, 7)) fmt1 = fmt1 or (1 shl 6)
        if (qr.get(8, 8)) fmt1 = fmt1 or (1 shl 7)
        if (qr.get(7, 8)) fmt1 = fmt1 or (1 shl 8)
        for (i in 9..14) if (qr.get(14 - i, 8)) fmt1 = fmt1 or (1 shl i)

        var fmt2 = 0
        for (i in 0..7) if (qr.get(36 - i, 8)) fmt2 = fmt2 or (1 shl i)
        for (i in 8..14) if (qr.get(8, 37 - 15 + i)) fmt2 = fmt2 or (1 shl i)
        assertEquals(fmt1, fmt2)

        val unmaskedFmt = fmt1 xor 0x5412
        val ecLevelBits = (unmaskedFmt ushr 13) and 0b11
        val maskIndex = (unmaskedFmt ushr 10) and 0b111
        assertEquals(0b01, ecLevelBits) // Level L = 01

        // Reconstruct function-module mask independently to read raw codewords
        val isFunc = Array(37) { BooleanArray(37) }
        for (i in 0 until 37) {
            isFunc[i][6] = true
            isFunc[6][i] = true
        }
        for (y in 0..8) for (x in 0..8) isFunc[y][x] = true
        for (y in 0..8) for (x in 29..36) isFunc[y][x] = true
        for (y in 29..36) for (x in 0..8) isFunc[y][x] = true
        for (dy in -2..2) for (dx in -2..2) {
            isFunc[30 + dy][30 + dx] = true
            val dist = maxOf(abs(dx), abs(dy))
            assertEquals(dist != 1, qr.get(30 + dx, 30 + dy))
        }

        val bits = ArrayList<Int>(1079)
        var right = 36
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until 37) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) 36 - vert else vert
                    if (!isFunc[y][x]) {
                        val rawBit = qr.get(x, y) xor QrCodeEncoder.maskInvert(maskIndex, x, y)
                        bits.add(if (rawBit) 1 else 0)
                    }
                }
            }
            right -= 2
        }
        assertEquals(1079, bits.size)

        val codewords = IntArray(134)
        for (c in 0 until 134) {
            var v = 0
            for (b in 0..7) v = (v shl 1) or bits[c * 8 + b]
            codewords[c] = v
        }

        // Verify ALL 26 Reed-Solomon syndromes over GF(2^8) equal 0!
        var alphaI = 1
        for (s in 0 until 26) {
            var syn = 0
            for (cw in codewords) {
                syn = QrCodeEncoder.gfMultiply(syn, alphaI) xor cw
            }
            assertEquals("RS syndrome $s must be 0", 0, syn)
            alphaI = QrCodeEncoder.gfMultiply(alphaI, 0x02)
        }

        // Decode Byte-mode indicator (0100), length (102), and UTF-8 bytes
        val mode = (bits[0] shl 3) or (bits[1] shl 2) or (bits[2] shl 1) or bits[3]
        assertEquals(0b0100, mode)
        var len = 0
        for (b in 4 until 12) len = (len shl 1) or bits[b]
        assertEquals(102, len)

        val decodedBytes = ByteArray(len)
        for (idx in 0 until len) {
            var ch = 0
            for (b in 0..7) ch = (ch shl 1) or bits[12 + idx * 8 + b]
            decodedBytes[idx] = ch.toByte()
        }
        assertEquals(QrCodeEncoder.APK_DOWNLOAD_URL, decodedBytes.decodeToString())

        val svg = qr.toSvg(quietZone = 4, modulePx = 6)
        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.contains("viewBox=\"0 0 45 45\""))
    }
}
