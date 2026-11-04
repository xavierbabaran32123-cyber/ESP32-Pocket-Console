package com.example.game.state

import com.example.game.data.ArcadeAssetsData
import com.example.game.data.LevelData
import kotlin.random.Random

class SlideTracker {
    var bx: Int = 0
    var by: Int = 0
    var sx: Int = 0
    var sy: Int = 0
    var dir: Int = 0
    var mv: Boolean = false
    var fr: Float = 0f
    var pktAt: Long = 0L
    var len: Float = 0f
    var ldir: Int = 0
    var was: Boolean = false
    var tailLen: Float = 0f
    var tailAt: Long = 0L
    var tailDir: Int = 0

    fun reset(x: Int, y: Int, now: Long) {
        bx = x
        sx = x
        by = y
        sy = y
        dir = 0
        mv = false
        fr = 0f
        pktAt = now
        len = 0f
        was = false
        tailLen = 0f
    }

    fun onPacket(x: Int, y: Int, moving: Boolean, direction: Int, frac: Float, now: Long) {
        if (moving && (!mv || direction != dir)) {
            val vert = direction == 0 || direction == 2
            sx = if (vert) x else bx
            sy = if (vert) by else y
        }
        bx = x
        by = y
        fr = frac
        mv = moving
        dir = direction
        pktAt = now
    }
}

data class MainPacket(
    val st: Int,
    val f: Int,
    val lvl: Int,
    val run: Int,
    val x: Int,
    val y: Int,
    val fr: Float,
    val stars: Int,
    val mask: Int,
    val coins: Int,
    val mv: Boolean,
    val dir: Int,
    val left: Boolean,
    val ext: Boolean = false,
    val pauseSel: Int = 0,
    val clearSel: Int = 1,
    val face: Int = 1,
    val nb: Boolean = false,
    val dots: Float = 0f,
    val best: Long = 0L
)

data class PendingCollect(
    val x: Int,
    val y: Int,
    val v: Int,
    val at: Long
)

class ParticleFx(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float,
    val max: Float,
    val kind: Int
)

class TitleEmber(
    var kind: Int = 0,
    var max: Float = 1000f,
    var life: Float = 500f,
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f
)

class EndlessRow(
    val stage: Int,
    val gid: Int,
    val w: IntArray = IntArray(21),
    val it: IntArray = IntArray(21),
    val clr: LongArray = LongArray(21)
)

class EndlessState {
    val pend = ArrayList<PendingCollect>()
    val spk = HashMap<Int, Int>()
    val track = SlideTracker()
    val rows = HashMap<Int, EndlessRow>()
    var run: Int = -1
    var at: Long = 0L
    var px: Int = 0
    var py: Int = 0
    var vx: Float = 0f
    var vy: Float = 0f
    var cy: Float = 0f
    var tide: Float = 0f
    var stage: Int = 0
    var banner: Long = 0L
    var t: Long = 0L
    var f: Int = 0
    var fr: Float = 0f
    var gap: Int = 255
    var coins: Int = 0
    var fz: Int = 0
    var mg: Int = 0
    var score: Long = 0L
    var clk: Int = 0
    var clkAt: Long = 0L
    var mask: Int = 0

    var pf: Int? = null
    var pst: Int? = null
    var pmv: Boolean = false
    var moved: Boolean = false
    var scored: Boolean = false
    var popText: String = ""
    var popCol: Int = 0xFFFFFFFF.toInt()
    var popUntil: Long = 0L
    var flash: Long = 0L
    var hurtAt: Long = 0L
    var landAt: Long = 0L
    var deadAt: Long = 0L
    var alarmAt: Long = 0L
    var best: Long? = null
    var newBest: Boolean = false
}

class JetSprite(
    val t: Int,
    val x: Float,
    val y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f
)

class JetState {
    var at: Long = 0L
    var sat: Long = 0L
    var objs = ArrayList<JetSprite>()
    var build = ArrayList<JetSprite>()
    val stars = Array(30) {
        floatArrayOf(Random.nextFloat(), Random.nextFloat(), 10f + Random.nextFloat() * 30f)
    }
    var px: Int = 60
    var py: Int = 138
    var sx: Float? = null
    var sy: Float? = null
    var t: Long = 0L
    var gw: Int = 120
    var gh: Int = 160
    var fl: Int = 0
    var lives: Int = 3
    var score: Long = 0L
    var hi: Long = 0L
    var pc1: Int = 0
    var pcb: Int = 0
}

class DotGhost {
    var tx: Int = 7
    var ty: Int = 7
    var dir: Int = 0
    var mode: Int = 0
    var p: Float = 0f
    var sx: Float = 7f
    var sy: Float = 7f
}

class DotMazeState {
    var at: Long = 0L
    var dots: ByteArray = ArcadeAssetsData.freshDots()
    var level: Int = 0
    var score: Long = 0L
    var tx: Int = 7
    var ty: Int = 14
    var dir: Int = 0
    var p: Float = 0f
    val ents = Array(4) { DotGhost() }
    var sx: Float = 7f
    var sy: Float = 14f
    var t: Long = 0L
    var phase: Int = 0
    var paused: Boolean = false
    var lives: Int = 3
    var fright: Int = 0
    var pw: Int = 15
    var mask: Int = 0
    var wk: Boolean = false
}

class BlocksState {
    var at: Long = 0L
    val field = IntArray(200)
    var st: Int = 0
    var fl: Int = 0
    var type: Int = 0
    var rot: Int = 0
    var x: Int = 0
    var y: Int = 0
    var next: Int = 0
    var lines: Int = 0
    var score: Long = 0L
    var cm: Int = 0
    var hi: Int = 0
    var sx: Float? = null
    var sy: Float? = null
    var stype: Int = -1
    var tt: Long = 0L
}

class MenuState {
    var at: Long = 0L
    var st: Int = 0
    var sel: Int = 0
    var mask: Int = 0
    var fl: Int = 6 // music + sfx on
    var unl: Int = 1
    var owned: Int = 1
    var wallet: Long = 0L
    var stars: Int = 0
    val v = LongArray(4)
    val lv = IntArray(45)
    var scroll: Float = 0f
    var cscroll: Int = 0
    var t: Long = 0L
}

class ConsoleEngineState {
    val lock = Any()

    // Core Stage state (G in HTML)
    var level: LevelData? = null
    var lvl: Int = -1
    var run: Int = -1
    var p: MainPacket? = null
    var at: Long = 0L
    var t: Long = 0L
    var px: Int = 0
    var py: Int = 0
    var vx: Float = 0f
    var vy: Float = 0f
    var cx: Float = 0f
    var cy: Float = 0f
    var clk: Int = 0
    var clkAt: Long = 0L

    val track = SlideTracker()
    val pend = ArrayList<PendingCollect>()
    val spk = HashMap<Int, Int>()
    var lastSt: Int = -1
    var stAt: Long = 0L
    var moved: Boolean = false
    var prevMv: Boolean = false
    var landAt: Long = 0L
    var dotsCnt: Int = 0
    var dotsTotal: Int = 0

    // UI menu cursors
    var pauseSel: Int = 0
    var clearSel: Int = 1

    // Extra modes (X in HTML)
    val endless = EndlessState()
    val jet = JetState()
    val dotMaze = DotMazeState()
    val blocks = BlocksState()
    val menu = MenuState()

    // FX particles and Title Embers
    val fx = ArrayList<ParticleFx>(84)
    var fxT: Long = 0L
    val titleEmbers = ArrayList<TitleEmber>(40)

    fun resetSignal() {
        synchronized(lock) {
            p = null
            at = 0L
            lastSt = -1
        }
    }

    fun fxClear() {
        fx.clear()
    }

    fun fxAdd(x: Float, y: Float, vx: Float, vy: Float, life: Float, kind: Int) {
        if (fx.size > 80) fx.removeAt(0)
        fx.add(ParticleFx(x, y, vx, vy, life, life, kind))
    }

    fun fxBurst(tx: Int, ty: Int, n: Int) {
        repeat(n) {
            val a = Random.nextFloat() * 6.2831855f
            val sp = 1.2f + Random.nextFloat() * 3f
            fxAdd(
                tx.toFloat(),
                ty.toFloat(),
                kotlin.math.cos(a) * sp,
                kotlin.math.sin(a) * sp,
                300f + Random.nextFloat() * 300f,
                0
            )
        }
    }

    fun fxDust(tx: Int, ty: Int, d: Int, toward: Int, n: Int) {
        val dirVec = com.example.game.data.LevelsData.DXY[d and 3]
        repeat(n) {
            val sp = 0.8f + Random.nextFloat() * 1.8f
            val vx: Float
            val vy: Float
            if (toward == 0) {
                val a = Random.nextFloat() * 6.2831855f
                vx = kotlin.math.cos(a) * sp
                vy = kotlin.math.sin(a) * sp
            } else {
                val side = (Random.nextFloat() * 2f - 1f) * 1.6f
                val dx = dirVec[0]
                val dy = dirVec[1]
                vx = toward * dx * sp + if (dx == 0) side else 0f
                vy = toward * dy * sp + if (dy == 0) side else 0f
            }
            fxAdd(tx.toFloat(), ty.toFloat(), vx, vy, 180f + Random.nextFloat() * 220f, 0)
        }
    }

    fun fxStep(now: Long) {
        val prev = if (fxT == 0L) now else fxT
        val dt = (now - prev).coerceIn(0L, 50L).toFloat()
        fxT = now
        var i = fx.size - 1
        while (i >= 0) {
            val f = fx[i]
            f.life -= dt
            f.x += f.vx * dt / 1000f
            f.y += f.vy * dt / 1000f
            if (f.life <= 0f) fx.removeAt(i)
            i--
        }
    }
}
