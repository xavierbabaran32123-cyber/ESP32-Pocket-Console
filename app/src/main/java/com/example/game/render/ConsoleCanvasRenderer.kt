package com.example.game.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.example.audio.SoundPlayer
import com.example.game.data.ArcadeAssetsData
import com.example.game.data.HeroSpritesData
import com.example.game.data.LevelsData
import com.example.game.state.ConsoleEngineState
import com.example.game.state.MainPacket
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class ConsoleCanvasRenderer(
    private val state: ConsoleEngineState,
    private val sound: SoundPlayer,
    pixelTypeface: Typeface? = null
) {
    private val prim = ConsolePrimitives(pixelTypeface)
    private val triPath = Path()

    private var cw = 0f
    private var ch = 0f
    private var u = 0f
    private var ls = false

    private inline fun portrait(c: Canvas, block: () -> Unit) {
        val rw = cw
        val rh = ch
        val w = min(rw, rh * 0.75f)
        val h = w / 0.75f
        val ox = (rw - w) / 2f
        val oy = (rh - h) / 2f
        val saveCount = c.save()
        c.clipRect(ox, oy, ox + w, oy + h)
        c.translate(ox, oy)
        cw = w
        ch = h
        try {
            block()
        } finally {
            cw = rw
            ch = rh
            c.restoreToCount(saveCount)
        }
    }

    private fun fresh(at: Long, now: Long): Boolean = at > 0L && (now - at < 1500L)

    fun renderFrame(c: Canvas, widthPx: Float, heightPx: Float, nowMs: Long) {
        if (widthPx <= 4f || heightPx <= 4f) return
        synchronized(state.lock) {
            cw = widthPx
            ch = heightPx
            u = min(cw, ch * 0.75f)
            ls = cw > ch * 1.15f

            prim.fillRect(c, 0f, 0f, cw, ch, 0xFF080814.toInt())

            val p = state.p
            if (p == null || nowMs - state.at > 3000L) {
                sound.setRumble(0f)
                sound.wantTrack = -1
                drawNoSignal(c, nowMs)
                return
            }

            val inLvl = ((p.f and 1) != 0) && state.level != null && LevelsData.LVL_STATES.contains(p.st)
            if (!inLvl) {
                if ((p.f and 1) == 0 && (p.st in 1..4) && fresh(state.endless.at, nowMs)) {
                    drawEndless(c, nowMs, p)
                    return
                }
                if ((p.st == 0 || p.st == 5 || p.st == 8 || p.st == 9 || p.st == 10 || p.st == 11) && fresh(state.menu.at, nowMs)) {
                    sound.setRumble(0f)
                    menuFrame(c, nowMs, p)
                    return
                }
                if (p.st == 12 && fresh(state.jet.at, nowMs)) {
                    sound.setRumble(0f)
                    portrait(c) { drawJet(c, nowMs) }
                    if (ls) panels(c, "J")
                    return
                }
                if (p.st == 13 && fresh(state.dotMaze.at, nowMs)) {
                    sound.setRumble(0f)
                    portrait(c) { drawDot(c, nowMs) }
                    if (ls) panels(c, "D")
                    return
                }
                if (p.st == 14 && fresh(state.blocks.at, nowMs)) {
                    sound.setRumble(0f)
                    drawBlocks(c, nowMs)
                    if (ls) panels(c, "T")
                    return
                }
                sound.setRumble(0f)
                val stName = LevelsData.STN.getOrElse(p.st) { "..." }
                drawNote(c, stName, "playing on the device")
                return
            }

            sound.setRumble(0f)
            drawLevel(c, nowMs, p)
        }
    }

    private fun drawNote(c: Canvas, a: String, b: String) {
        prim.fillRect(c, 0f, 0f, cw, ch, 0xFF0B0B1A.toInt())
        val titleSize = max(14f, u / 11f)
        prim.txt(c, a, cw / 2f, ch / 2f - 4f, 0xFFFFE600.toInt(), titleSize, Paint.Align.CENTER)
        if (b.isNotEmpty()) {
            prim.txt(c, b, cw / 2f, ch / 2f + u / 10f, 0xFFC8B8FF.toInt(), max(10f, u / 20f), Paint.Align.CENTER)
        }
    }

    private fun drawNoSignal(c: Canvas, now: Long) {
        prim.fillRect(c, 0f, 0f, cw, ch, 0xFF080814.toInt())
        // Subtle animated tomb pillars and bobbing classic mask in idle screen
        val pillarW = u * 0.035f
        prim.fillRect(c, 0f, 0f, pillarW, ch, 0xFF13183F.toInt())
        prim.fillRect(c, cw - pillarW, 0f, pillarW, ch, 0xFF13183F.toInt())

        val hs = u * 0.22f
        val bob = sin(now * 0.004f) * (u * 0.012f)
        val maskCol = if (((now / 450L) and 1L) == 0L) 0xFFFFF000.toInt() else 0xFFF0BE3C.toInt()
        prim.drawHeroBitmapBig(
            c,
            mask = state.menu.mask,
            face = 1,
            pose = 0,
            frame = ((now / 210L).toInt()) and 3,
            hf = 1,
            col = maskCol,
            left = cw / 2f - hs / 2f,
            top = ch * 0.27f + bob,
            size = hs
        )

        prim.txtS(c, cw, "NO SIGNAL", cw / 2f, ch * 0.57f, 0xFFFFE600.toInt(), max(16f, u / 10f), Paint.Align.CENTER)
        prim.txt(
            c,
            "Connect to see the game",
            cw / 2f,
            ch * 0.65f,
            0xFFC8B8FF.toInt(),
            max(11f, u / 21f),
            Paint.Align.CENTER
        )
    }

    private fun collectPendingStage(now: Long, p: MainPacket) {
        val lv = state.level ?: run {
            state.pend.clear()
            return
        }
        var i = state.pend.size - 1
        while (i >= 0) {
            val q = state.pend[i]
            if (p.st != 1 || now - q.at > 400L || abs(state.vx - q.x) + abs(state.vy - q.y) < 0.6f) {
                state.pend.removeAt(i)
                if (q.y in 0 until lv.height && q.x in 0 until lv.width && lv.items[q.y][q.x] == q.v) {
                    lv.items[q.y][q.x] = 0
                    when (q.v) {
                        1 -> {
                            state.dotsCnt++
                            sound.sfx("DOT", 0)
                        }
                        2 -> {
                            state.fxBurst(q.x, q.y, 6)
                            sound.sfx("COIN", 1)
                        }
                        else -> {
                            state.fxBurst(q.x, q.y, 10)
                            sound.sfx("STAR", 2)
                        }
                    }
                }
            }
            i--
        }
    }

    private fun collectPendingEndless(now: Long, p: MainPacket) {
        val e = state.endless
        var i = e.pend.size - 1
        while (i >= 0) {
            val q = e.pend[i]
            if (p.st != 1 || now - q.at > 400L || abs(e.vx - q.x) + abs(e.vy - q.y) < 0.6f) {
                e.pend.removeAt(i)
                val row = e.rows[q.y]
                if (row != null && q.x in 0 until 21 && row.it[q.x] == q.v) {
                    row.it[q.x] = 0
                    row.clr[q.x] = now
                    if (q.v == 1) {
                        sound.sfx("DOT", 0)
                    } else if (q.v == 2) {
                        sound.sfx("COIN", 1)
                        state.fxBurst(q.x, q.y, 4)
                    }
                }
            }
            i--
        }
    }

    private fun drawLevel(c: Canvas, now: Long, p: MainPacket) {
        val lv = state.level ?: return
        val wCol = lv.theme.wallHex
        val glCol = lv.theme.glowHex
        val dCol = lv.theme.dotHex

        val prevT = if (state.t == 0L) now else state.t
        val dt = (now - prevT).coerceIn(0L, 50L).toFloat()
        state.t = now

        val k = 1f - exp(-dt / 45f)
        val kc = 1f - exp(-dt / 120f)
        val (tx, ty) = prim.trkTarget(state.track, now, 55f, p.st == 1)
        val km = if (state.track.mv) (1f - exp(-dt / 14f)) else k
        state.vx += (tx - state.vx) * km
        state.vy += (ty - state.vy) * km
        collectPendingStage(now, p)
        state.fxStep(now)

        val tsRaw = if (ls) {
            min(cw / min(lv.width, 15).toFloat(), ch / 9f)
        } else {
            u / min(lv.width, 11).toFloat()
        }
        val ts = max(4f, floor(tsRaw))
        val vw = cw / ts
        val vh = ch / ts

        val tcx = if (lv.width <= vw) (lv.width - 1) / 2f else state.vx.coerceIn(vw / 2f - 0.5f, lv.width - 0.5f - vw / 2f)
        val tcy = if (lv.height <= vh) (lv.height - 1) / 2f else (state.vy + vh * 0.1f).coerceIn(vh / 2f - 0.5f, lv.height - 0.5f - vh / 2f)
        state.cx += (tcx - state.cx) * kc
        state.cy += (tcy - state.cy) * kc

        val sxf: (Float) -> Float = { x -> cw / 2f + (x - state.cx) * ts - ts / 2f }
        val syf: (Float) -> Float = { y -> ch / 2f - (y - state.cy) * ts - ts / 2f }
        val isWall: (Int, Int) -> Boolean = { x, y ->
            x < 0 || x >= lv.width || y < 0 || y >= lv.height || lv.walls[y][x] == 1
        }
        val clock = ((state.clk + (now - state.clkAt)) % 1700L).toInt()

        prim.fillRect(c, 0f, 0f, cw, ch, 0xFF000000.toInt())
        val x0 = max(0, floor(state.cx - vw / 2f).toInt() - 1)
        val x1 = min(lv.width - 1, ceil(state.cx + vw / 2f).toInt() + 1)
        val y0 = max(0, floor(state.cy - vh / 2f).toInt() - 1)
        val y1 = min(lv.height - 1, ceil(state.cy + vh / 2f).toInt() + 1)

        for (y in y0..y1) {
            for (x in x0..x1) {
                val sx = sxf(x.toFloat())
                val sy = syf(y.toFloat())
                if (lv.walls[y][x] != 0) {
                    prim.wallTile(c, sx, sy, ts, wCol, glCol, x, y, isWall)
                    continue
                }
                when (lv.items[y][x]) {
                    1 -> {
                        val d = max(2f, ts / 5f)
                        prim.fillRect(c, sx + (ts - d) / 2f, sy + (ts - d) / 2f, d, d, dCol)
                    }
                    2 -> {
                        prim.strokePaint.color = dCol
                        prim.strokePaint.strokeWidth = max(2f, ts / 8f)
                        c.drawCircle(sx + ts / 2f, sy + ts / 2f, ts * 0.3f, prim.strokePaint)
                        prim.fillRect(c, sx + ts / 2f - 1f, sy + ts * 0.32f, 2f, ts * 0.36f, dCol)
                    }
                    4 -> {
                        val sCol = if (((now / 450L) % 5L) == 0L) 0xFFFFEE00.toInt() else wCol
                        prim.star(c, sx, sy, ts / 8f, sCol)
                    }
                    5 -> {
                        val u8 = ts / 8f
                        val glow = ((now / 350L) and 1L) != 0L
                        prim.fillRect(c, sx + u8, sy + u8, 6f * u8, 6f * u8, wCol)
                        prim.fillRect(c, sx + 2f * u8, sy + 2f * u8, 4f * u8, 4f * u8, 0xFF000000.toInt())
                        prim.fillRect(c, sx + 3f * u8, sy + 3f * u8, 2f * u8, 2f * u8, if (glow) 0xFFFFFFFF.toInt() else wCol)
                    }
                    3 -> {
                        val wd = when {
                            isWall(x, y + 1) -> 0
                            isWall(x + 1, y) -> 1
                            isWall(x, y - 1) -> 2
                            else -> 3
                        }
                        val gid = max(0, lv.spikeGroups[y][x])
                        val tm = (clock + gid * 430) % 1700
                        val ph = when {
                            tm < 600 -> 2
                            tm >= 1450 -> 1
                            else -> 0
                        }
                        prim.spikeTile(c, sx, sy, ts, wd, ph, now)
                        val dd = abs(y - state.vy) + abs(x - state.vx)
                        if (dd < 7f) {
                            val prevPh = state.spk[gid]
                            if (prevPh != null && prevPh != ph && ph == 2) {
                                sound.sfx("SPIKE", 0, 1f - dd / 7f)
                            }
                            state.spk[gid] = ph
                        }
                    }
                }
            }
        }

        // Start gate: skull altar flanked by two flickering torches
        run {
            val sx = sxf(lv.gateX.toFloat())
            val sy = syf(lv.gateY.toFloat())
            val u8 = ts / 8f
            val cx = sx + ts / 2f
            val by = sy + ts
            val ga = 0xFF4E2670.toInt()
            val gb = 0xFF7D4EA5.toInt()
            prim.fillRect(c, cx - 5f * u8, by - 12f * u8, 10f * u8, 12f * u8, ga)
            prim.fillRect(c, cx - 4f * u8, by - 14f * u8, 8f * u8, 2f * u8, ga)
            prim.fillRect(c, cx - 5f * u8, by - 12f * u8, u8, 12f * u8, gb)
            prim.fillRect(c, cx + 4f * u8, by - 12f * u8, u8, 12f * u8, gb)
            prim.fillRect(c, cx - 3f * u8, by - 12f * u8, 6f * u8, 5f * u8, gb)
            prim.fillRect(c, cx - 2f * u8, by - 10f * u8, u8, u8, ga)
            prim.fillRect(c, cx + u8, by - 10f * u8, u8, u8, ga)
            prim.fillRect(c, cx - u8, by - 8f * u8, 2f * u8, u8, ga)
            for (side in intArrayOf(-1, 1)) {
                val tx2 = cx + side * 9f * u8 - u8
                prim.fillRect(c, tx2, by - 7f * u8, 2f * u8, 7f * u8, ga)
                prim.fillRect(c, tx2 - u8, by - 8f * u8, 4f * u8, u8, gb)
                val flameCol = if (((now / 120L) and 1L) != 0L) 0xFFD78CFF.toInt() else gb
                prim.fillRect(c, tx2, by - 11f * u8, 2f * u8, 3f * u8, flameCol)
            }
        }

        val dead = p.st == 3
        val dms = now - state.stAt
        if (p.st != 6 && !(dead && dms > 400L)) {
            val col = if (dead && (((dms / 100L) and 1L) != 0L)) 0xFFFF3228.toInt() else 0xFFFFF000.toInt()
            val spec = HeroDrawSpec(
                mv = p.mv && p.st == 1,
                dir = p.dir,
                face = if (p.ext) p.face else if (state.moved) p.dir else 1,
                hf = if (p.left) 3 else 1,
                mask = p.mask,
                col = col,
                pose = 0,
                frame = ((now / 190L).toInt()) and 3
            )
            prim.trailState(state.track, spec, now, state.vx, state.vy, ts)
            if (dead) {
                spec.pose = 2
                spec.frame = if (dms < 220L) 0 else 1
            } else if (!spec.mv && p.st == 1 && now - state.landAt < 110L) {
                spec.dy = ts / 8f
            }
            prim.heroDraw(c, sxf(state.vx), syf(state.vy), ts, spec)
        }

        prim.fxDraw(c, state, now, ts, sxf, syf)

        // HUD: 3 stars on top, coin counter on top-left
        val sc = u / 120f
        val su = max(2f, (ts / 9f).roundToInt().toFloat())
        for (i in 0 until 3) {
            prim.star(
                c,
                cw / 2f + (i - 1) * 10f * su - 4f * su,
                6f,
                su,
                if (i < p.stars) 0xFFFFEE00.toInt() else wCol
            )
        }
        prim.coinIcon(c, 9f * sc, 9f * sc, 5f * sc, 0xFFFFF000.toInt(), 0xFF000000.toInt())
        prim.txt(c, p.coins.toString(), 17f * sc, 5f * sc + (u / 15f) * 0.85f, 0xFFFFF000.toInt(), u / 15f)

        if (p.st == 2) portrait(c) { prim.pauseMenu(c, cw, ch, state.pauseSel) }
        if (p.st == 7) portrait(c) { prim.drawClear(c, cw, ch, state, now, p) }
    }

    private fun drawEndless(c: Canvas, now: Long, p: MainPacket) {
        val e = state.endless
        val prevT = if (e.t == 0L) now else e.t
        val dt = (now - prevT).coerceIn(0L, 50L).toFloat()
        e.t = now

        val sc = u / 120f
        val fs = u / 15f
        val k = 1f - exp(-dt / 45f)
        val kc = 1f - exp(-dt / 120f)

        val f = e.f
        val moving = (f and 1) != 0
        val dir = (f shr 1) and 3
        val shield = (f and 16) != 0
        val frozen = (f and 32) != 0
        val magnet = (f and 64) != 0
        val inv = (f and 128) != 0

        val pf = e.pf ?: f
        e.pf = f
        fun pop(txt: String, col: Int) {
            e.popText = txt
            e.popCol = col
            e.popUntil = now + 1100L
        }
        if ((f and 16) != 0 && (pf and 16) == 0) {
            pop("SHIELD", 0xFF3CE6FF.toInt()); state.fxBurst(e.px, e.py, 8); sound.sfx("SHIELD", 2)
        }
        if ((f and 32) != 0 && (pf and 32) == 0) {
            pop("FREEZE", 0xFF3CE6FF.toInt()); state.fxBurst(e.px, e.py, 8); sound.sfx("CHARGE", 2)
        }
        if ((f and 64) != 0 && (pf and 64) == 0) {
            pop("MAGNET", 0xFFFF3228.toInt()); state.fxBurst(e.px, e.py, 8); sound.sfx("CHARGE", 2)
        }
        if ((f and 16) == 0 && (pf and 16) != 0) {
            pop("SAVED!", 0xFF3CE6FF.toInt()); state.fxBurst(e.px, e.py, 12); sound.sfx("BOOM", 2)
        }
        if ((f and 128) != 0 && (pf and 128) == 0) e.hurtAt = now
        if (moving && !e.pmv) { e.moved = true; state.fxDust(e.px, e.py, dir, -1, 4) }
        if (!moving && e.pmv) { e.landAt = now; state.fxDust(e.px, e.py, dir, 0, 6) }
        e.pmv = moving
        if (p.st == 3 && e.pst != 3) { e.deadAt = now; state.fxBurst(e.px, e.py, 10) }
        e.pst = p.st

        if (p.ext) {
            e.best = p.best
            e.newBest = p.nb
        } else if ((p.st == 3 || p.st == 4) && !e.scored) {
            val b0 = e.best ?: state.menu.v[0]
            e.newBest = e.score > b0
            e.best = max(b0, e.score)
            e.scored = true
        }

        val rowNow = e.rows[e.py]
        val stepMs = if (rowNow != null && (rowNow.stage and 128) != 0) 28f else 55f
        val (tx, ty) = prim.trkTarget(e.track, now, stepMs, p.st == 1)
        val km = if (e.track.mv) (1f - exp(-dt / 14f)) else k
        e.vx += (tx - e.vx) * km
        e.vy += (ty - e.vy) * km
        e.cy += (e.vy + 2f - e.cy) * kc
        collectPendingEndless(now, p)
        if (e.spk.size > 300) e.spk.clear()
        e.tide += ((e.py - e.gap / 8f) - e.tide) * kc

        if (magnet) {
            for (dy in -3..3) {
                for (dx in -3..3) {
                    if (dx * dx + dy * dy > 10) continue
                    val row = e.rows[e.py + dy]
                    val x = e.px + dx
                    if (row != null && x in 0 until 21 && (row.it[x] == 1 || row.it[x] == 2)) {
                        row.it[x] = 0
                        row.clr[x] = now
                        state.fxAdd(x.toFloat(), (e.py + dy).toFloat(), -dx * 7f, -dy * 7f, 140f, 0)
                    }
                }
            }
        }
        state.fxStep(now)

        val ts = if (ls) min(cw / 21f, ch / 15f) else (u / 21f)
        val vh = ch / ts
        val ox = (cw - 21f * ts) / 2f
        val sxf: (Float) -> Float = { x -> ox + x * ts }
        val syf: (Float) -> Float = { y -> ch / 2f - (y - e.cy) * ts - ts / 2f }
        val isWall: (Int, Int) -> Boolean = { x, y ->
            if (x < 0 || x >= 21) true else {
                val r = e.rows[y]
                r == null || r.w[x] == 1
            }
        }
        val clock = ((e.clk + (now - e.clkAt)) % 1700L).toInt()

        prim.fillRect(c, 0f, 0f, cw, ch, 0xFF000000.toInt())
        val yMin = max(0, floor(e.cy - vh / 2f).toInt() - 1)
        val yMax = ceil(e.cy + vh / 2f).toInt() + 1

        for (y in yMin..yMax) {
            val r = e.rows[y] ?: continue
            val thIdx = ((((r.stage and 127) - 1) % 10) + 10) % 10
            val th = LevelsData.THEMES[thIdx]
            val wCol = th.wallHex
            val glCol = th.glowHex
            val dCol = th.dotHex
            val sy = syf(y.toFloat())
            for (x in 0 until 21) {
                val sx = sxf(x.toFloat())
                if (r.w[x] != 0) {
                    prim.wallTile(c, sx, sy, ts, wCol, glCol, x, y, isWall)
                    continue
                }
                if ((r.stage and 128) != 0) {
                    val ph = (y * 2 - (now / 70L).toInt()) and 7
                    prim.strokePaint.color = if (ph < 2) wCol else dCol
                    prim.strokePaint.strokeWidth = 2f
                    val mx = sx + ts / 2f
                    val h = ts / 4f + 1f
                    val t0 = sy + ts / 4f
                    c.drawLine(mx - h, t0 + h, mx, t0, prim.strokePaint)
                    c.drawLine(mx, t0, mx + h, t0 + h, prim.strokePaint)
                    continue
                }
                when (val it = r.it[x]) {
                    1 -> {
                        val d = max(2f, ts / 5f)
                        prim.fillRect(c, sx + (ts - d) / 2f, sy + (ts - d) / 2f, d, d, dCol)
                    }
                    2 -> {
                        prim.strokePaint.color = dCol
                        prim.strokePaint.strokeWidth = max(2f, ts / 8f)
                        c.drawCircle(sx + ts / 2f, sy + ts / 2f, ts * 0.3f, prim.strokePaint)
                        prim.fillRect(c, sx + ts / 2f - 1f, sy + ts * 0.32f, 2f, ts * 0.36f, dCol)
                    }
                    3 -> {
                        val wd = when {
                            isWall(x, y + 1) -> 0
                            isWall(x + 1, y) -> 1
                            isWall(x, y - 1) -> 2
                            else -> 3
                        }
                        val tm = (clock + r.gid * 430) % 1700
                        val ph = if (frozen) 0 else if (tm < 600) 2 else if (tm >= 1450) 1 else 0
                        prim.spikeTile(c, sx, sy, ts, wd, ph, now)
                        val dd = abs(y - e.vy) + abs(x - e.vx)
                        if (dd < 7f) {
                            val key = y * 64 + r.gid
                            val pp = e.spk[key]
                            if (pp != null && pp != ph && ph == 2) {
                                sound.sfx("SPIKE", 0, 1f - dd / 7f)
                            }
                            e.spk[key] = ph
                        }
                    }
                    6, 7, 8 -> {
                        if (((now / 260L) and 3L) == 0L) {
                            prim.fillRect(c, sx, sy, ts, ts, glCol)
                        }
                        prim.powerIcon(c, sx, sy, ts / 8f, it)
                    }
                }
            }
        }

        val curTheme = LevelsData.THEMES[(((e.stage - 1) % 10) + 10) % 10]
        val surf = ch / 2f - (e.tide + 0.5f - e.cy) * ts
        if (surf < ch) {
            val bodyCol = if (frozen) 0xFF0A2D4B.toInt() else curTheme.glowHex
            val topCol = if (frozen) 0xFF3CE6FF.toInt() else curTheme.wallHex
            var x = 0f
            while (x < cw) {
                val wave = if (frozen) 0f else sin((x + now / 70f) * 0.2f) * ts * 0.18f
                val yy = max(0f, surf + wave)
                prim.fillRect(c, x, yy, 3f, ch - yy, bodyCol)
                prim.fillRect(c, x, yy, 3f, 2f, topCol)
                val ix = (x / 3f).toInt()
                if (!frozen && ((ix * 7 + (now / 160L).toInt()) % 23) == 0 && yy + 6f < ch) {
                    prim.fillRect(c, x, yy + 5f + (ix % 7), 3f, 3f, curTheme.dotHex)
                }
                x += 3f
            }
        }

        // Hero
        run {
            val dead = p.st == 3
            val dms = now - e.deadAt
            val col = if (dead && (((dms / 100L) and 1L) != 0L)) 0xFFFF3228.toInt() else 0xFFFFF000.toInt()
            if (!(dead && dms > 400L)) {
                val sx = sxf(e.vx)
                val sy = syf(e.vy)
                val spec = HeroDrawSpec(
                    mv = moving && p.st == 1,
                    dir = dir,
                    face = if (p.ext) p.face else if (e.moved) dir else 1,
                    hf = if ((f and 8) != 0) 3 else 1,
                    mask = e.mask,
                    col = col,
                    pose = 0,
                    frame = ((now / 190L).toInt()) and 3
                )
                prim.trailState(e.track, spec, now, e.vx, e.vy, ts)
                if (dead) {
                    spec.pose = 2
                    spec.frame = if (dms < 220L) 0 else 1
                } else if (inv && p.st == 1 && now - e.hurtAt < 350L) {
                    spec.pose = 2
                    spec.frame = 0
                } else if (!spec.mv && p.st == 1 && now - e.landAt < 110L) {
                    spec.dy = ts / 8f
                }
                prim.heroDraw(c, sx, sy, ts, spec)
                if (shield) {
                    prim.strokePaint.color = 0xFF3CE6FF.toInt()
                    prim.strokePaint.strokeWidth = 2f
                    c.drawCircle(sx + ts / 2f, sy + ts / 2f, ts * 0.75f, prim.strokePaint)
                }
            }
        }

        prim.fxDraw(c, state, now, ts, sxf, syf)

        if (p.st == 1 && !frozen) {
            sound.setRumble(max(0f, 1f - e.gap / 64f))
            if (e.gap < 32 && now - e.alarmAt > 520L) {
                e.alarmAt = now
                sound.sfx("ALARM", 0, 1f - e.gap / 32f)
            }
        } else {
            sound.setRumble(0f)
        }

        // HUD
        prim.txtS(c, cw, e.score.toString(), 4f * sc, fs * 1.05f, 0xFFFFFFFF.toInt(), fs)
        val cs = e.coins.toString()
        val cWidth = cs.length * fs * 0.65f
        prim.txt(c, cs, cw - 4f * sc, fs * 1.05f, 0xFFFFF000.toInt(), fs, Paint.Align.RIGHT)
        prim.coinIcon(c, cw - 4f * sc - cWidth - 6f * sc, fs * 0.65f, 3.5f * sc, 0xFFFFF000.toInt(), 0xFF000000.toInt())
        prim.txt(c, "STAGE ${e.stage}", 4f * sc, fs * 2.05f, curTheme.wallHex, fs * 0.8f)

        var iy = fs * 2.5f
        val pUnit = max(2f, sc * 1.8f)
        if (shield) {
            prim.powerIcon(c, 4f * sc, iy, pUnit, 8)
            iy += pUnit * 10f
        }
        if (e.fz > 0) {
            prim.powerIcon(c, 4f * sc, iy, pUnit, 6)
            prim.fillRect(c, 4f * sc + pUnit * 10f, iy + pUnit * 2f, (e.fz / 60f) * 36f * sc, pUnit * 3f, 0xFF3CE6FF.toInt())
            iy += pUnit * 10f
        }
        if (e.mg > 0) {
            prim.powerIcon(c, 4f * sc, iy, pUnit, 7)
            prim.fillRect(c, 4f * sc + pUnit * 10f, iy + pUnit * 2f, (e.mg / 80f) * 36f * sc, pUnit * 3f, 0xFFFF3228.toInt())
        }

        if (p.st == 1 && !frozen && e.gap < 32 && (((now / 120L) and 1L) != 0L)) {
            prim.strokeRect(c, 1.5f, 1.5f, cw - 3f, ch - 3f, 0xFFFF3228.toInt(), 3f)
        }
        if (now < e.popUntil) {
            prim.txtS(c, cw, e.popText, cw / 2f, ch / 3f + 8f * sc + fs * 1.7f, e.popCol, fs * 1.8f, Paint.Align.CENTER, 0xFF000000.toInt())
        }
        if (now < e.flash) {
            val alpha = ((((e.flash - now) * 22f / 700f) / 32f).coerceIn(0f, 0.8f) * 255f).toInt()
            prim.fillRect(c, 0f, 0f, cw, ch, (alpha shl 24) or 0xFFF000)
        }
        if (now < e.banner) {
            prim.txtS(c, cw, "STAGE ${e.stage}", cw / 2f, ch / 4f + fs * 1.7f, 0xFFFFEE00.toInt(), fs * 1.8f, Paint.Align.CENTER, 0xFF000000.toInt())
            prim.txtS(c, cw, "+25", cw / 2f, ch / 4f + 17f * sc + fs * 0.85f, 0xFFFFFFFF.toInt(), fs, Paint.Align.CENTER, 0xFF000000.toInt())
        }
        if (p.st == 2) portrait(c) { prim.pauseMenu(c, cw, ch, state.pauseSel) }
        if (p.st == 4) portrait(c) { prim.overPanel(c, cw, ch, e.score, e.best ?: state.menu.v[0], e.newBest) }
    }

    private fun menuFrame(c: Canvas, now: Long, p: MainPacket) {
        val n = state.menu
        val st = p.st
        val prevT = if (n.t == 0L) now else n.t
        val dt = (now - prevT).coerceIn(0L, 50L).toFloat()
        n.t = now

        val fs = max(10f, u / 17f)
        val kCol = 0xFFFFF000.toInt()
        val cx = cw / 2f
        prim.fillRect(c, 0f, 0f, cw, ch, if (st == 5 || st == 8) 0xFF000000.toInt() else 0xFF080814.toInt())

        fun pillars() {
            prim.fillRect(c, 0f, 0f, u * 0.04f, ch, 0xFF19235A.toInt())
            prim.fillRect(c, cw - u * 0.04f, 0f, u * 0.04f, ch, 0xFF19235A.toInt())
        }

        val mw = min(cw * 0.8f, u * 0.9f)
        val mxBox = (cw - mw) / 2f

        if (st == 10 || st == 0) {
            pillars()
            val sc = u / 120f
            val lava = ch * 0.86f
            val sh = 0xFF080814.toInt()
            var lx = 0f
            while (lx < cw) {
                val y = lava + sin(lx * 0.15f + now * 0.005f) * 3f
                prim.fillRect(c, lx, y, 3f, ch - y, 0xFFE6460A.toInt())
                prim.fillRect(c, lx, y, 3f, 2f, 0xFFFFAA1E.toInt())
                lx += 3f
            }
            prim.ptFrame(c, state, cw, u, now, dt, lava)

            val tx = if (ls) cw * 0.27f else cx
            val mx = if (ls) cw * 0.73f else cx
            prim.txtS(c, cw, "TOMB OF", tx, ch * (if (ls) 0.2f else 0.09f), 0xFF4664C8.toInt(), u / 9.5f, Paint.Align.CENTER, 0xFF19235A.toInt())
            val maskTitleCol = if (((now / 90L) % 14L) == 0L) 0xFFFFFFFF.toInt() else 0xFFF0BE3C.toInt()
            prim.txtS(c, cw, "MASK", tx, ch * (if (ls) 0.4f else 0.19f), maskTitleCol, u / 4.5f, Paint.Align.CENTER, 0xFF961E3C.toInt())
            prim.txtS(c, cw, "POCKET CONSOLE", tx, ch * (if (ls) 0.48f else 0.24f), 0xFFFFD228.toInt(), fs * 0.95f, Paint.Align.CENTER, sh)

            val bob = sin(now * 0.006f) * 4f
            val hs = u * (if (st == 10) 0.3f else 0.26f)
            prim.drawHeroBitmapBig(
                c,
                mask = n.mask,
                face = 1,
                pose = 0,
                frame = ((now / 190L).toInt()) and 3,
                hf = 1,
                col = kCol,
                left = tx - hs / 2f,
                top = ch * (if (ls) 0.52f else 0.26f) + bob,
                size = hs
            )

            if (st == 10) {
                val bw = u * 0.5f
                val bh = ch * 0.1f
                val bx = mx - bw / 2f
                val by = ch * (if (ls) 0.4f else 0.55f)
                val pulse = ((now / 350L) and 1L) != 0L
                prim.fillRect(c, bx + 3f, by + 3f, bw, bh, 0xFF961E3C.toInt())
                prim.fillRect(c, bx, by, bw, bh, 0xFF50376E.toInt())
                prim.strokeRect(c, bx, by, bw, bh, if (pulse) 0xFFFFD228.toInt() else 0xFFF0BE3C.toInt(), 2f)
                prim.strokeRect(c, bx + 4f, by + 4f, bw - 8f, bh - 8f, if (pulse) 0xFFF0BE3C.toInt() else 0xFFFFD228.toInt(), 1f)
                prim.txtS(c, cw, "PLAY", mx, by + bh * 0.68f, 0xFFFFFFFF.toInt(), u / 9.5f, Paint.Align.CENTER, sh)

                val cyBtn = by + bh / 2f
                val ax = bx - 7f * sc + (if (pulse) 0f else -2f * sc)
                val bx2 = bx + bw + 7f * sc + (if (pulse) 0f else 2f * sc)
                prim.smoothFillPaint.color = 0xFFFFD228.toInt()
                triPath.reset()
                triPath.moveTo(ax, cyBtn - 3f * sc); triPath.lineTo(ax, cyBtn + 3f * sc); triPath.lineTo(ax + 4f * sc, cyBtn); triPath.close()
                c.drawPath(triPath, prim.smoothFillPaint)
                triPath.reset()
                triPath.moveTo(bx2, cyBtn - 3f * sc); triPath.lineTo(bx2, cyBtn + 3f * sc); triPath.lineTo(bx2 - 4f * sc, cyBtn); triPath.close()
                c.drawPath(triPath, prim.smoothFillPaint)

                if (((now / 500L) and 1L) == 0L) {
                    prim.txtS(c, cw, "PRESS A", cx, lava + fs * 1.45f, 0xFFFFFFFF.toInt(), fs, Paint.Align.CENTER, 0xFFE6460A.toInt())
                }
                prim.txtS(c, cw, "B: ARCADE", cx, ch - fs * 0.6f, 0xFFFFFFFF.toInt(), fs, Paint.Align.CENTER, sh)
            } else {
                val names = arrayOf("NEW GAME", "CONTINUE", "ENDLESS", "CHARACTER", "SETTINGS")
                for (i in names.indices) {
                    val s = names[i]
                    val y = if (ls) ch * 0.24f + i * ch * 0.12f else ch * 0.55f + i * ch * 0.07f
                    val dim = i == 1 && (n.fl and 1) == 0
                    val col = when {
                        dim -> 0xFF19235A.toInt()
                        i == n.sel -> 0xFFFFFFFF.toInt()
                        else -> 0xFF4664C8.toInt()
                    }
                    prim.txtS(c, cw, s, mx, y, col, fs * 1.15f, Paint.Align.CENTER, sh)
                    if (i == n.sel) {
                        val lx2 = mx - s.length * fs * 0.72f / 2f - fs * 1.2f
                        prim.smoothFillPaint.color = 0xFFFFD228.toInt()
                        triPath.reset()
                        triPath.moveTo(lx2, y - fs); triPath.lineTo(lx2, y); triPath.lineTo(lx2 + fs * 0.6f, y - fs * 0.5f); triPath.close()
                        c.drawPath(triPath, prim.smoothFillPaint)
                    }
                }
                val info = when {
                    (n.fl and 8) != 0 -> "A AGAIN: ERASE SAVE"
                    n.sel == 4 -> "MUSIC ${if ((n.fl and 2) != 0) "ON" else "OFF"}  SFX ${if ((n.fl and 4) != 0) "ON" else "OFF"}"
                    n.sel == 3 -> "COINS ${n.wallet}"
                    n.sel == 2 -> "BEST ${n.v[0]}"
                    else -> "STARS ${n.stars}  SCORE ${n.v[1]}"
                }
                prim.txtS(c, cw, info, cx, lava + fs * 1.45f, 0xFFFFFFFF.toInt(), fs * 0.9f, Paint.Align.CENTER, 0xFFE6460A.toInt())
            }
        } else if (st == 5) { // LEVEL MAP
            val sp = u / 4f
            val nw = sp * 0.7f
            val nh = nw * 0.8f
            val cyR = floatArrayOf(ch * 0.3875f, ch * 0.6125f)
            fun mc(i: Int) = i shr 1
            fun mr(i: Int) = if (((i shr 1) and 1) != 0) 1 - (i and 1) else (i and 1)

            n.scroll += (mc(n.sel) - n.scroll) * (1f - exp(-dt / 80f))
            fun nx(colIdx: Int) = cx + (colIdx - n.scroll) * sp

            prim.txt(c, "MAP", cx, ch * 0.09f, kCol, u / 8.5f, Paint.Align.CENTER)
            prim.txt(c, "STAGE BASED MODE", cx, ch * 0.14f, 0xFFC878FF.toInt(), fs * 0.85f, Paint.Align.CENTER)
            prim.txt(c, "${n.stars}/135", 8f, fs * 1.15f, kCol, fs)

            for (i in 0 until 44) {
                val x0 = nx(mc(i))
                val x1 = nx(mc(i + 1))
                val y0 = cyR[mr(i)]
                val y1 = cyR[mr(i + 1)]
                val pathCol = if (i + 1 < n.unl) kCol else 0xFF50376E.toInt()
                if (x0 == x1) {
                    prim.fillRect(c, x0 - 2f, min(y0, y1), 4f, abs(y1 - y0), pathCol)
                } else {
                    prim.fillRect(c, min(x0, x1), y0 - 2f, abs(x1 - x0), 4f, pathCol)
                }
            }
            for (i in 0 until 45) {
                val ncx = nx(mc(i))
                val ncy = cyR[mr(i)]
                if (ncx < -nw || ncx > cw + nw) continue
                val open = i < n.unl
                val x0 = ncx - nw / 2f
                val y0 = ncy - nh / 2f
                prim.fillRect(c, x0, y0, nw, nh, if (open) kCol else 0xFFC878FF.toInt())
                prim.fillRect(c, x0, y0 + nh - 4f, nw, 4f, if (open) 0xFFBEA500.toInt() else 0xFF7D5FA0.toInt())
                if (open) {
                    prim.txt(c, (i + 1).toString(), ncx, ncy + fs * 0.35f, 0xFF000000.toInt(), fs * 1.05f, Paint.Align.CENTER)
                    for (j in 0 until 3) {
                        prim.star(c, ncx + (j - 1) * fs * 0.9f - fs * 0.4f, y0 + nh + 3f, fs / 8f, if (j < n.lv[i]) kCol else 0xFF5A5A6E.toInt())
                    }
                } else {
                    prim.fillRect(c, ncx - 6f, ncy - 2f, 12f, 9f, 0xFF50376E.toInt())
                    prim.strokeRect(c, ncx - 4f, ncy - 8f, 8f, 7f, 0xFF50376E.toInt(), 2f)
                }
                if (i == n.sel) {
                    val selCol = if (((now / 250L) and 1L) != 0L) 0xFF28E6DC.toInt() else 0xFFFFFFFF.toInt()
                    prim.strokeRect(c, x0 - 3f, y0 - 3f, nw + 6f, nh + 6f, selCol, 3f)
                }
            }
            prim.fillRect(c, 0f, ch - fs * 4.2f, cw, fs * 0.6f, 0xFF28E6DC.toInt())
            prim.fillRect(c, 0f, ch - fs * 3.6f, cw, fs * 1.7f, kCol)
            val stageName = LevelsData.LV_NAMES.getOrElse(n.sel) { "" }
            prim.txt(c, "${n.sel + 1}  $stageName", cx, ch - fs * 2.4f, 0xFF000000.toInt(), fs * 0.95f, Paint.Align.CENTER)
            prim.txt(c, "A:PLAY  B:BACK", cx, ch - fs * 0.8f, 0xFFFFFFFF.toInt(), fs * 0.9f, Paint.Align.CENTER)
        } else if (st == 8) { // MASKS
            val bar = fs * 1.6f
            prim.fillRect(c, 0f, 0f, cw, bar, 0xFF7D20A0.toInt())
            prim.txt(c, "MASKS", cx, bar * 0.72f, 0xFFFFFFFF.toInt(), fs, Paint.Align.CENTER)
            prim.txt(c, n.wallet.toString(), cw - 8f, bar * 0.72f, kCol, fs, Paint.Align.RIGHT)

            fun maskAction(): Pair<String, Int> {
                val idx = n.sel.coerceIn(0, 14)
                val own = ((n.owned shr idx) and 1) != 0
                val cost = HeroSpritesData.MASK_COST[idx]
                val need = !own && n.wallet < cost
                val label = when {
                    own -> if (idx == n.mask) "EQUIPPED" else "A:EQUIP"
                    need -> "NEED $cost"
                    else -> "A:BUY $cost"
                }
                return label to (if (need) 0xFFFF3228.toInt() else 0xFFFFFFFF.toInt())
            }

            fun drawCell(i: Int, cellCx: Float, y0: Float, box: Float, small: Boolean) {
                val own = ((n.owned shr i) and 1) != 0
                val x0 = cellCx - box / 2f
                prim.dashedRect(c, x0, y0, box, box, if (own) kCol else 0xFFBEA500.toInt(), 4f)
                val hs = box * 0.8f
                prim.drawHeroBitmapBig(
                    c, i, 1, 0, (((now / 190L).toInt()) + i) and 3, 1,
                    if (own) kCol else 0xFF5A5A6E.toInt(),
                    cellCx - hs / 2f, y0 + box / 2f - hs / 2f, hs
                )
                if (!own) {
                    prim.fillRect(c, cellCx - 7f, y0 + box / 2f - 1f, 14f, 10f, 0xFF7D5FA0.toInt())
                    prim.strokeRect(c, cellCx - 4f, y0 + box / 2f - 8f, 8f, 8f, 0xFF7D5FA0.toInt(), 2f)
                }
                if (i == n.sel) {
                    val selCol = if (((now / 250L) and 1L) != 0L) 0xFF28E6DC.toInt() else 0xFFFFFFFF.toInt()
                    prim.strokeRect(c, x0 - 3f, y0 - 3f, box + 6f, box + 6f, selCol, 2f)
                }
                val label = if (own) HeroSpritesData.MASK_NAMES[i] else HeroSpritesData.MASK_COST[i].toString()
                val lCol = when {
                    i == n.sel || i == n.mask -> 0xFFFFFFFF.toInt()
                    own -> 0xFFD78C5A.toInt()
                    else -> 0xFFFFD228.toInt()
                }
                prim.txt(c, label, cellCx, y0 + box + fs * (if (small) 0.95f else 1.1f), lCol, fs * (if (small) 0.68f else 0.82f), Paint.Align.CENTER)
                if (i == n.mask) {
                    prim.fillRect(c, x0 + box - 7f, y0 + 3f, 5f, 5f, 0xFF28E6DC.toInt())
                }
            }

            if (ls) {
                val gx0 = cw * 0.42f
                val cellW = cw * 0.56f / 3f
                val top = bar + fs * 0.6f
                val rowH = (ch - top - fs * 0.8f) / 5f
                val box = min(cellW * 0.62f, rowH * 0.62f)
                for (i in 0 until 15) {
                    drawCell(i, gx0 + (i % 3) * cellW + cellW / 2f, top + (i / 3) * rowH, box, true)
                }
                val s = n.sel.coerceIn(0, 14)
                val own = ((n.owned shr s) and 1) != 0
                val big = min(cw * 0.32f, ch * 0.5f)
                val pcx = cw * 0.2f
                val pcy = ch * 0.46f
                prim.dashedRect(c, pcx - big / 2f, pcy - big / 2f, big, big, if (own) kCol else 0xFFBEA500.toInt(), 6f)
                val hs = big * 0.85f
                prim.drawHeroBitmapBig(c, s, 1, 0, ((now / 190L).toInt()) and 3, 1, if (own) kCol else 0xFF5A5A6E.toInt(), pcx - hs / 2f, pcy - hs / 2f, hs)
                prim.txt(c, if (own) HeroSpritesData.MASK_NAMES[s] else "LOCKED", pcx, pcy + big / 2f + fs * 1.6f, 0xFFFFFFFF.toInt(), fs * 1.2f, Paint.Align.CENTER)
                val (at, ac) = maskAction()
                prim.txt(c, at, pcx, pcy + big / 2f + fs * 3.1f, ac, fs * 1.05f, Paint.Align.CENTER)
            } else {
                val cellW = cw / 3f
                val box = cellW * 0.6f
                val top = bar + 8f
                val rowH = (ch - top - fs * 2f) / 3f
                val selRow = n.sel / 3
                n.cscroll = min(max(n.cscroll, selRow - 2), selRow)
                for (i in 0 until 15) {
                    val row = (i / 3) - n.cscroll
                    if (row !in 0..2) continue
                    drawCell(i, (i % 3) * cellW + cellW / 2f, top + row * rowH, box, false)
                }
                val (at, ac) = maskAction()
                prim.txt(c, at, cx, ch - fs * 0.6f, ac, fs, Paint.Align.CENTER)
            }
        } else if (st == 9) { // SETTINGS
            pillars()
            prim.txt(c, "SETTINGS", cx, ch * 0.1f, 0xFFF0BE3C.toInt(), u / 8.5f, Paint.Align.CENTER)
            val labs = arrayOf("MUSIC", "SOUND FX", "RESET GAME", "BACK")
            val top = ch * 0.2f
            val gap = (ch - top - fs * 3f) / 4f
            for (i in labs.indices) {
                val y = top + i * gap
                val sel = i == n.sel
                val bh = gap - 8f
                prim.fillRect(c, mxBox, y, mw, bh, if (sel) 0xFF50376E.toInt() else 0xFF19235A.toInt())
                prim.strokeRect(c, mxBox, y, mw, bh, if (sel) 0xFFFFFFFF.toInt() else 0xFF4664C8.toInt(), 1.5f)
                val col = if (sel) 0xFFFFFFFF.toInt() else 0xFF4664C8.toInt()
                val ty = y + bh / 2f + fs * 0.35f
                if (i < 2) {
                    val on = if (i == 0) (n.fl and 2) != 0 else (n.fl and 4) != 0
                    prim.txt(c, labs[i], mxBox + 12f, ty, col, fs)
                    prim.txt(c, if (on) "ON" else "OFF", mxBox + mw - 12f, ty, if (on) 0xFF50FF78.toInt() else 0xFFFF3228.toInt(), fs, Paint.Align.RIGHT)
                } else {
                    var t = labs[i]
                    var tc = col
                    if (i == 2 && (n.fl and 16) != 0) { t = "SURE? PRESS A"; tc = 0xFFFF3228.toInt() }
                    else if (i == 2 && (n.fl and 32) != 0) { t = "ALL ERASED!"; tc = 0xFF50FF78.toInt() }
                    prim.txt(c, t, cx, ty, tc, fs, Paint.Align.CENTER)
                }
            }
            prim.txt(c, "A:SELECT  B:BACK", cx, ch - fs * 0.6f, 0xFFFFFFFF.toInt(), fs * 0.9f, Paint.Align.CENTER)
        } else if (st == 11) { // ARCADE MENU
            pillars()
            prim.txt(c, "ARCADE", cx, ch * 0.1f, 0xFFF0BE3C.toInt(), u / 8.5f, Paint.Align.CENTER)
            val labs = arrayOf("JET FIGHTER", "DOT MAZE", "BLOCKS", "BACK")
            val top = ch * 0.2f
            val gap = min(ch * 0.14f, (ch - top - fs * 5f) / 4f)
            for (i in labs.indices) {
                val y = top + i * gap
                val sel = i == n.sel
                val bh = gap - 6f
                prim.fillRect(c, mxBox, y, mw, bh, if (sel) 0xFF50376E.toInt() else 0xFF19235A.toInt())
                prim.strokeRect(c, mxBox, y, mw, bh, if (sel) 0xFFFFFFFF.toInt() else 0xFF4664C8.toInt(), 1.5f)
                prim.txt(c, labs[i], cx, y + bh / 2f + fs * 0.35f, if (sel) 0xFFFFFFFF.toInt() else 0xFF4664C8.toInt(), fs * 1.05f, Paint.Align.CENTER)
            }
            val bestLabel = if (n.sel in 0..2) "BEST ${n.v[n.sel]}" else "BACK TO TITLE"
            prim.txt(c, bestLabel, cx, ch - fs * 3f, 0xFFFFD228.toInt(), fs, Paint.Align.CENTER)
            prim.txt(c, "A:PLAY  B:BACK", cx, ch - fs * 0.8f, 0xFFFFFFFF.toInt(), fs * 0.9f, Paint.Align.CENTER)
        }
    }

    private fun drawJet(c: Canvas, now: Long) {
        val j = state.jet
        val prevT = if (j.t == 0L) now else j.t
        val dt = (now - prevT).coerceIn(0L, 50L).toFloat()
        j.t = now

        val s = cw / j.gw.toFloat()
        fun jr(x: Float, y: Float, w: Float, h: Float, col: Int) {
            prim.fillRect(c, x * s, y * s, max(1f, w * s), max(1f, h * s), col)
        }
        fun tri(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, col: Int) {
            prim.smoothFillPaint.color = col
            triPath.reset()
            triPath.moveTo(x0 * s, y0 * s); triPath.lineTo(x1 * s, y1 * s); triPath.lineTo(x2 * s, y2 * s); triPath.close()
            c.drawPath(triPath, prim.smoothFillPaint)
        }

        prim.fillRect(c, 0f, 0f, cw, ch, 0xFF080814.toInt())
        for (st in j.stars) {
            st[1] += st[2] * dt / 1000f / j.gh.toFloat()
            if (st[1] > 1f) { st[1] = 0f; st[0] = kotlin.random.Random.nextFloat() }
            jr(st[0] * j.gw, st[1] * j.gh, 1f, if (st[2] > 28f) 2f else 1f, if (st[2] > 28f) 0xFFC8DCFF.toInt() else 0xFF4664C8.toInt())
        }

        val satBase = if (j.sat != 0L) j.sat else now
        val el = min(0.1f, (now - satBase) / 1000f)
        for (o in j.objs) {
            val x = o.x + o.vx * el
            val y = o.y + o.vy * el
            when (val t = o.t) {
                1 -> jr(x - 0.5f, y - 3f, 1.5f, 5f, 0xFFFFF000.toInt())
                2 -> { jr(x - 1.5f, y - 1.5f, 3f, 3f, 0xFFFF3228.toInt()); jr(x - 0.5f, y - 0.5f, 1f, 1f, 0xFFFFFFFF.toInt()) }
                3 -> {
                    jr(x - 4f, y - 4f, 8f, 8f, 0xFFFFD228.toInt())
                    prim.strokeRect(c, (x - 4f) * s, (y - 4f) * s, 8f * s, 8f * s, 0xFFFFFFFF.toInt(), 1f)
                    prim.txt(c, "P", x * s, (y + 2.5f) * s, 0xFF000000.toInt(), 8f * s, Paint.Align.CENTER)
                }
                4 -> { tri(x - 5f, y - 4f, x + 5f, y - 4f, x, y + 5f, 0xFFFF3228.toInt()); jr(x - 1f, y - 2f, 2f, 2f, 0xFFFFF000.toInt()) }
                5 -> { tri(x, y - 6f, x - 4f, y + 2f, x + 4f, y + 2f, 0xFFFFAA1E.toInt()); tri(x - 4f, y + 2f, x + 4f, y + 2f, x, y + 6f, 0xFFE6460A.toInt()) }
                in 6..9 -> {
                    jr(x - 8f, y - 3f, 16f, 5f, 0xFF7D5FA0.toInt())
                    jr(x - 4f, y - 6f, 8f, 12f, 0xFF50376E.toInt())
                    jr(x - 2f, y - 1f, 4f, 3f, if (t - 5 > 2) 0xFFFF3228.toInt() else 0xFFFFF000.toInt())
                    tri(x - 3f, y + 6f, x + 3f, y + 6f, x, y + 9f, 0xFF7D5FA0.toInt())
                }
                else -> if (t >= 10) {
                    val r = (t - 10) / 3f
                    val col = when {
                        r < 0.4f -> 0xFFFFFFFF.toInt()
                        r < 0.7f -> 0xFFFFAA1E.toInt()
                        else -> 0xFFE6460A.toInt()
                    }
                    val rad = 2f + r * 9f
                    jr(x - rad, y - 1f, rad * 2f, 2f, col)
                    jr(x - 1f, y - rad, 2f, rad * 2f, col)
                    jr(x - rad * 0.6f, y - rad * 0.6f, rad * 1.2f, rad * 1.2f, if (r < 0.7f) col else 0xFF781E0A.toInt())
                }
            }
        }

        val dead = (j.fl and 1) != 0
        val inv = (j.fl and 4) != 0
        val k = 1f - exp(-dt / 50f)
        j.sx = j.sx?.let { it + (j.px - it) * k } ?: j.px.toFloat()
        j.sy = j.sy?.let { it + (j.py - it) * k } ?: j.py.toFloat()
        val shipX = j.sx ?: j.px.toFloat()
        val shipY = j.sy ?: j.py.toFloat()

        if (!dead && !(inv && (((now / 80L) and 1L) != 0L))) {
            tri(shipX - 9f, shipY + 6f, shipX - 1f, shipY - 3f, shipX - 1f, shipY + 6f, 0xFF5A5A6E.toInt())
            tri(shipX + 9f, shipY + 6f, shipX + 1f, shipY - 3f, shipX + 1f, shipY + 6f, 0xFF5A5A6E.toInt())
            tri(shipX, shipY - 8f, shipX - 3f, shipY + 6f, shipX + 3f, shipY + 6f, 0xFF3CE6FF.toInt())
            jr(shipX - 1f, shipY - 2f, 2f, 4f, 0xFF080814.toInt())
            jr(shipX - 1f, shipY + 6f, 2f, 2f + (((now / 60L) and 1L) * 2L).toFloat(), 0xFFFFAA1E.toInt())
        }

        if (!ls) {
            prim.txt(c, j.score.toString(), 2f * s, 9f * s, 0xFFFFFFFF.toInt(), 8f * s)
            for (i in 0 until j.lives) {
                val lx = j.gw - 8f - i * 9f
                tri(lx, 2f, lx - 3f, 9f, lx + 3f, 9f, 0xFF3CE6FF.toInt())
            }
            if ((j.fl and 2) != 0) prim.txt(c, "x3", 2f * s, 19f * s, 0xFFFFD228.toInt(), 8f * s)
        }
        if ((j.fl and 8) != 0) prim.overlay(c, cw, ch, "PAUSED", 0xFFFFE600.toInt(), "A:RESUME  B:QUIT", "")
        if (dead) prim.overlay(c, cw, ch, "GAME OVER", 0xFFFF3228.toInt(), "SCORE ${j.score}", "BEST ${j.hi}")
    }

    private fun drawDot(c: Canvas, now: Long) {
        val d = state.dotMaze
        val prevT = if (d.t == 0L) now else d.t
        val dt = (now - prevT).coerceIn(0L, 50L).toFloat()
        d.t = now

        val k = 1f - exp(-dt / 45f)
        val sc = cw / 120f
        val top = 12f * sc
        val tile = floor(min(cw / ArcadeAssetsData.DM_W, (ch - top) / ArcadeAssetsData.DM_H))
        val ox = (cw - ArcadeAssetsData.DM_W * tile) / 2f
        val oy = top

        prim.fillRect(c, 0f, 0f, cw, ch, 0xFF080814.toInt())
        fun open(x: Int, y: Int): Boolean =
            x in 0 until ArcadeAssetsData.DM_W && y in 0 until ArcadeAssetsData.DM_H && ArcadeAssetsData.DM_MAP[y][x] != '#'

        val flash = d.phase == 2 && (((now / 160L) and 1L) != 0L)
        val wc = if (flash) 0xFFFFFFFF.toInt() else 0xFF4664C8.toInt()
        val ln = max(1f, (tile / 8f).roundToInt().toFloat())

        for (y in 0 until ArcadeAssetsData.DM_H) {
            for (x in 0 until ArcadeAssetsData.DM_W) {
                val sx = ox + x * tile
                val sy = oy + y * tile
                if (ArcadeAssetsData.DM_MAP[y][x] == '#') {
                    prim.fillRect(c, sx, sy, tile, tile, 0xFF19235A.toInt())
                    if (open(x, y - 1)) prim.fillRect(c, sx, sy, tile, ln, wc)
                    if (open(x, y + 1)) prim.fillRect(c, sx, sy + tile - ln, tile, ln, wc)
                    if (open(x - 1, y)) prim.fillRect(c, sx, sy, ln, tile, wc)
                    if (open(x + 1, y)) prim.fillRect(c, sx + tile - ln, sy, ln, tile, wc)
                } else if (d.dots[y * ArcadeAssetsData.DM_W + x].toInt() != 0) {
                    val q = max(2f, tile / 6f)
                    prim.fillRect(c, sx + (tile - q) / 2f, sy + (tile - q) / 2f, q, q, 0xFFC8DCFF.toInt())
                }
            }
        }

        ArcadeAssetsData.DM_POWER_COORDS.forEachIndexed { i, xy ->
            if (((d.pw shr i) and 1) != 0 && (((now / 220L) and 1L) == 0L)) {
                prim.smoothFillPaint.color = 0xFFFFD228.toInt()
                c.drawCircle(ox + (xy[0] + 0.5f) * tile, oy + (xy[1] + 0.5f) * tile, tile * 0.33f, prim.smoothFillPaint)
            }
        }

        d.ents.forEachIndexed { i, en ->
            val dv = ArcadeAssetsData.DM_D[en.dir.coerceIn(0, 4)]
            val tx = en.tx + dv[0] * en.p
            val ty = en.ty + dv[1] * en.p
            if (abs(en.sx - tx) > 2f || abs(en.sy - ty) > 2f) { en.sx = tx; en.sy = ty }
            else { en.sx += (tx - en.sx) * k; en.sy += (ty - en.sy) * k }

            if (en.mode == 3 || d.phase == 1) return@forEachIndexed
            val scared = en.mode == 2
            val fl = scared && d.fright < 1800 && (((now / 150L) and 1L) != 0L)
            val x = ox + en.sx * tile
            val y = oy + en.sy * tile + (if (en.mode == 0) sin(now * 0.008f + i) * tile * 0.1f else 0f)
            val hc = tile / 2f
            val body = if (scared) (if (fl) 0xFFFFFFFF.toInt() else 0xFF2846E6.toInt()) else ArcadeAssetsData.DM_COL[i]
            prim.smoothFillPaint.color = body
            c.drawCircle(x + hc, y + hc, hc - tile * 0.18f, prim.smoothFillPaint)
            prim.fillRect(c, x + tile * 0.06f, y + hc - tile * 0.08f, tile * 0.16f, tile * 0.16f, body)
            prim.fillRect(c, x + tile * 0.78f, y + hc - tile * 0.08f, tile * 0.16f, tile * 0.16f, body)
            prim.fillRect(c, x + hc - tile * 0.08f, y + tile * 0.06f, tile * 0.16f, tile * 0.16f, body)
            prim.fillRect(c, x + hc - tile * 0.08f, y + tile * 0.78f, tile * 0.16f, tile * 0.16f, body)
            if (scared) {
                prim.fillRect(c, x + hc - tile * 0.25f, y + hc - tile * 0.12f, tile * 0.12f, tile * 0.12f, 0xFFFFFFFF.toInt())
                prim.fillRect(c, x + hc + tile * 0.13f, y + hc - tile * 0.12f, tile * 0.12f, tile * 0.12f, 0xFFFFFFFF.toInt())
                prim.fillRect(c, x + hc - tile * 0.25f, y + hc + tile * 0.2f, tile * 0.5f, tile * 0.1f, 0xFFFFFFFF.toInt())
            } else {
                prim.fillRect(c, x + hc - tile * 0.3f, y + hc - tile * 0.25f, tile * 0.24f, tile * 0.24f, 0xFFFFFFFF.toInt())
                prim.fillRect(c, x + hc + tile * 0.06f, y + hc - tile * 0.25f, tile * 0.24f, tile * 0.24f, 0xFFFFFFFF.toInt())
                prim.fillRect(c, x + hc - tile * 0.2f + dv[0] * tile * 0.08f, y + hc - tile * 0.18f + dv[1] * tile * 0.08f, tile * 0.1f, tile * 0.1f, 0xFF000000.toInt())
                prim.fillRect(c, x + hc + tile * 0.16f + dv[0] * tile * 0.08f, y + hc - tile * 0.18f + dv[1] * tile * 0.08f, tile * 0.1f, tile * 0.1f, 0xFF000000.toInt())
            }
        }

        val pdv = ArcadeAssetsData.DM_D[d.dir.coerceIn(0, 4)]
        val ptx = d.tx + pdv[0] * d.p
        val pty = d.ty + pdv[1] * d.p
        if (abs(d.sx - ptx) > 2f || abs(d.sy - pty) > 2f) { d.sx = ptx; d.sy = pty }
        else { d.sx += (ptx - d.sx) * k; d.sy += (pty - d.sy) * k }

        if (!(d.phase == 1 && (((now / 120L) and 1L) != 0L))) {
            val hs = tile * 1.3f
            prim.drawSimpleHero(c, d.mask, d.dir == 4, 0xFFFFF000.toInt(), ox + d.sx * tile + tile / 2f - hs / 2f, oy + d.sy * tile + tile - hs, hs)
        }

        val fs = 8f * sc
        if (!ls) {
            prim.txt(c, d.score.toString(), 2f * sc, 9f * sc, 0xFFFFFFFF.toInt(), fs)
            prim.txt(c, "L${d.level}", cw / 2f, 9f * sc, 0xFF4664C8.toInt(), fs, Paint.Align.CENTER)
            for (i in 0 until d.lives) {
                prim.fillRect(c, cw - (6f + i * 6f) * sc, 3f * sc, 4f * sc, 4f * sc, 0xFFFFF000.toInt())
            }
        }
        if (d.paused) prim.overlay(c, cw, ch, "PAUSED", 0xFFFFE600.toInt(), "A:RESUME  B:QUIT", "")
        if (d.phase == 3) prim.overlay(c, cw, ch, "GAME OVER", 0xFFFF3228.toInt(), "SCORE ${d.score}", "A:RETRY  B:BACK")
    }

    private fun drawBlocks(c: Canvas, now: Long) {
        val t = state.blocks
        val tw = 10
        val th = 20
        val cs = floor(if (ls) (ch - 8f) / th else min((ch - 8f) / th, cw * 0.62f / tw))
        val fx = if (ls) floor((cw - tw * cs) / 2f) else 6f
        val fy = floor((ch - cs * th) / 2f)

        prim.fillRect(c, 0f, 0f, cw, ch, 0xFF080814.toInt())
        prim.fillRect(c, fx - 2f, fy - 2f, tw * cs + 4f, th * cs + 4f, 0xFF4664C8.toInt())
        prim.fillRect(c, fx, fy, tw * cs, th * cs, 0xFF000000.toInt())

        fun cell(x: Float, y: Float, col: Int) {
            prim.fillRect(c, x, y, cs, cs, col)
            prim.fillRect(c, x, y, cs, 1f, 0xFFFFFFFF.toInt())
            prim.fillRect(c, x, y + cs - 1f, cs, 1f, 0xFF080814.toInt())
        }

        val clearing = (t.fl and 2) != 0
        for (y in 0 until th) {
            for (x in 0 until tw) {
                val fv = t.field[y * tw + x]
                if (fv != 0) {
                    val fl = clearing && (((t.cm shr y) and 1) != 0)
                    val col = if (fl) {
                        if (((now / 80L) and 1L) != 0L) 0xFFFFFFFF.toInt() else 0xFF5A5A6E.toInt()
                    } else {
                        ArcadeAssetsData.TB_COL[fv.coerceIn(0, 7)]
                    }
                    cell(fx + x * cs, fy + y * cs, col)
                }
            }
        }

        val dead = (t.fl and 1) != 0
        if (!dead && !clearing) {
            val cells = ArcadeAssetsData.tbCells(t.type, t.rot)
            fun fits(px: Int, py: Int): Boolean = cells.all { cc ->
                val a = px + cc[0]
                val b = py + cc[1]
                a in 0 until tw && b < th && (b < 0 || t.field[b * tw + a] == 0)
            }
            var gy = t.y
            while (fits(t.x, gy + 1)) gy++

            val prevTt = if (t.tt == 0L) now else t.tt
            val dtb = (now - prevTt).coerceIn(0L, 50L).toFloat()
            t.tt = now
            val kb = 1f - exp(-dtb / 40f)
            val curSx = t.sx
            val curSy = t.sy
            if (curSx == null || curSy == null || t.stype != t.type || abs(curSx - t.x) > 3f || abs(curSy - t.y) > 4f) {
                t.sx = t.x.toFloat(); t.sy = t.y.toFloat(); t.stype = t.type
            }
            t.sx = (t.sx ?: t.x.toFloat()) + (t.x - (t.sx ?: t.x.toFloat())) * kb
            t.sy = (t.sy ?: t.y.toFloat()) + (t.y - (t.sy ?: t.y.toFloat())) * kb

            val pCol = ArcadeAssetsData.TB_COL[(t.type + 1).coerceIn(1, 7)]
            for (cc in cells) {
                if (gy + cc[1] >= 0) {
                    prim.strokeRect(c, fx + (t.x + cc[0]) * cs + 0.5f, fy + (gy + cc[1]) * cs + 0.5f, cs - 1f, cs - 1f, pCol, 1f)
                }
            }
            val drawSx = t.sx ?: t.x.toFloat()
            val drawSy = t.sy ?: t.y.toFloat()
            for (cc in cells) {
                if (t.y + cc[1] >= 0) {
                    cell(fx + (drawSx + cc[0]) * cs, fy + (drawSy + cc[1]) * cs, pCol)
                }
            }
        }

        if (ls) {
            if ((t.fl and 4) != 0) portrait(c) { prim.overlay(c, cw, ch, "PAUSED", 0xFFFFE600.toInt(), "A:RESUME  B:QUIT", "") }
            if (dead) portrait(c) { prim.overlay(c, cw, ch, "GAME OVER", 0xFFFF3228.toInt(), "SCORE ${t.score}", "BEST ${t.hi}") }
            return
        }

        val px = fx + tw * cs + 10f
        val fs = max(10f, cw / 17f)
        val pcs = max(4f, floor(cs * 0.6f))
        prim.txt(c, "NEXT", px, fy + fs, 0xFF4664C8.toInt(), fs)
        for (cc in ArcadeAssetsData.tbCells(t.next, 0)) {
            prim.fillRect(c, px + 2f + cc[0] * pcs, fy + fs + 6f + cc[1] * pcs, pcs - 1f, pcs - 1f, ArcadeAssetsData.TB_COL[(t.next + 1).coerceIn(1, 7)])
        }
        var syStat = fy + fs * 2f + pcs * 4f + 14f
        val stats = arrayOf(
            Triple("SCORE", t.score.toString(), 0xFFFFFFFF.toInt()),
            Triple("LINES", t.lines.toString(), 0xFFFFFFFF.toInt()),
            Triple("LEVEL", (1 + t.lines / 10).toString(), 0xFFFFFFFF.toInt()),
            Triple("BEST", t.hi.toString(), 0xFFFFD228.toInt())
        )
        for ((lab, vStr, col) in stats) {
            prim.txt(c, lab, px, syStat, 0xFF4664C8.toInt(), fs)
            prim.txt(c, vStr, px, syStat + fs * 1.15f, col, fs)
            syStat += fs * 2.4f
        }
        prim.txt(c, "A:TURN", px, syStat, 0xFF5A5A6E.toInt(), fs * 0.9f)
        if ((t.fl and 4) != 0) prim.overlay(c, cw, ch, "PAUSED", 0xFFFFE600.toInt(), "A:RESUME  B:QUIT", "")
        if (dead) prim.overlay(c, cw, ch, "GAME OVER", 0xFFFF3228.toInt(), "SCORE ${t.score}", "BEST ${t.hi}")
    }

    private fun panels(c: Canvas, kind: String) {
        val pw = ch * 0.75f
        val aw = (cw - pw) / 2f
        if (aw < 50f) return
        val fs = max(9f, min(ch / 16f, aw / 8f))
        val lx = aw / 2f
        val rx = cw - aw / 2f
        val gap = fs * 3.4f
        val y0 = ch * 0.16f

        prim.fillRect(c, (cw - pw) / 2f - 2f, 0f, 2f, ch, 0xFF19235A.toInt())
        prim.fillRect(c, (cw + pw) / 2f, 0f, 2f, ch, 0xFF19235A.toInt())

        fun stat(x: Float, y: Float, lab: String, vStr: String, col: Int = 0xFFFFFFFF.toInt()) {
            prim.txt(c, lab, x, y, 0xFF4664C8.toInt(), fs * 0.8f, Paint.Align.CENTER)
            prim.txt(c, vStr, x, y + fs * 1.3f, col, fs * 1.25f, Paint.Align.CENTER)
        }

        when (kind) {
            "J" -> {
                val j = state.jet
                stat(lx, y0, "SCORE", j.score.toString())
                stat(lx, y0 + gap, "BEST", j.hi.toString(), 0xFFFFD228.toInt())
                stat(rx, y0, "LIVES", j.lives.toString(), 0xFF3CE6FF.toInt())
                if ((j.fl and 2) != 0) prim.txt(c, "TRIPLE SHOT", rx, y0 + gap, 0xFFFFD228.toInt(), fs * 0.8f, Paint.Align.CENTER)
            }
            "D" -> {
                val d = state.dotMaze
                stat(lx, y0, "SCORE", d.score.toString())
                stat(lx, y0 + gap, "LEVEL", d.level.toString(), 0xFF4664C8.toInt())
                stat(rx, y0, "LIVES", d.lives.toString(), 0xFFFFF000.toInt())
            }
            "T" -> {
                val t = state.blocks
                prim.txt(c, "NEXT", lx, y0, 0xFF4664C8.toInt(), fs * 0.8f, Paint.Align.CENTER)
                val pcs = max(4f, fs * 0.9f)
                val cells = ArcadeAssetsData.tbCells(t.next, 0)
                val bw = ArcadeAssetsData.TB_BOX[t.next.coerceIn(0, 6)] * pcs
                for (cc in cells) {
                    prim.fillRect(c, lx - bw / 2f + cc[0] * pcs, y0 + fs * 0.6f + cc[1] * pcs, pcs - 1f, pcs - 1f, ArcadeAssetsData.TB_COL[(t.next + 1).coerceIn(1, 7)])
                }
                stat(lx, y0 + gap * 1.7f, "SCORE", t.score.toString())
                stat(rx, y0, "LINES", t.lines.toString())
                stat(rx, y0 + gap, "LEVEL", (1 + t.lines / 10).toString())
                stat(rx, y0 + gap * 2f, "BEST", t.hi.toString(), 0xFFFFD228.toInt())
                prim.txt(c, "A:TURN", rx, y0 + gap * 3f, 0xFF5A5A6E.toInt(), fs * 0.8f, Paint.Align.CENTER)
            }
        }
    }
}
