package com.example.game.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.example.game.data.ArcadeAssetsData
import com.example.game.data.HeroSpritesData
import com.example.game.data.LevelsData
import com.example.game.state.ConsoleEngineState
import com.example.game.state.MainPacket
import com.example.game.state.SlideTracker
import com.example.game.state.TitleEmber
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class HeroDrawSpec(
    var mv: Boolean,
    var dir: Int,
    var face: Int,
    var hf: Int,
    var mask: Int,
    var col: Int,
    var pose: Int = 0,
    var frame: Int = 0,
    var dy: Float = 0f,
    var dist: Float = 0f,
    var tailLen: Float = 0f,
    var tailA: Float = 0f,
    var tailDir: Int = 0
)

class ConsolePrimitives(pixelTypeface: Typeface?) {

    val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
    }

    val smoothFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = false
    }

    val dashPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = false
        pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
    }

    val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(pixelTypeface ?: Typeface.MONOSPACE, Typeface.BOLD)
    }

    val bitmapPaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }

    private val tmpPath = Path()
    private val dstRect = RectF()
    private val srcRect = Rect()

    fun fillRect(c: Canvas, x: Float, y: Float, w: Float, h: Float, col: Int) {
        fillPaint.color = col
        c.drawRect(x, y, x + w, y + h, fillPaint)
    }

    fun strokeRect(c: Canvas, x: Float, y: Float, w: Float, h: Float, col: Int, lineWidth: Float = 1f) {
        strokePaint.color = col
        strokePaint.strokeWidth = lineWidth
        c.drawRect(x, y, x + w, y + h, strokePaint)
    }

    fun dashedRect(c: Canvas, x: Float, y: Float, w: Float, h: Float, col: Int, dashLen: Float = 4f) {
        dashPaint.color = col
        dashPaint.strokeWidth = 1.5f
        dashPaint.pathEffect = DashPathEffect(floatArrayOf(dashLen, dashLen), 0f)
        c.drawRect(x, y, x + w, y + h, dashPaint)
    }

    fun txt(
        c: Canvas,
        s: String,
        x: Float,
        y: Float,
        col: Int,
        size: Float,
        align: Paint.Align = Paint.Align.LEFT
    ) {
        textPaint.color = col
        textPaint.textSize = max(7f, size)
        textPaint.textAlign = align
        c.drawText(s, x, y, textPaint)
    }

    fun txtS(
        c: Canvas,
        cw: Float,
        s: String,
        x: Float,
        y: Float,
        col: Int,
        size: Float,
        align: Paint.Align = Paint.Align.LEFT,
        shadowCol: Int = 0xFF080814.toInt()
    ) {
        val o = max(1f, cw / 120f)
        txt(c, s, x + o, y + o, shadowCol, size, align)
        txt(c, s, x, y, col, size, align)
    }

    fun bmp8(c: Canvas, x: Float, y: Float, s: Float, arr: IntArray, col: Int) {
        fillPaint.color = col
        for (r in 0 until 8) {
            val row = arr[r]
            for (colIdx in 0 until 8) {
                if ((row and (0x80 shr colIdx)) != 0) {
                    c.drawRect(
                        x + colIdx * s,
                        y + r * s,
                        x + (colIdx + 1) * s,
                        y + (r + 1) * s,
                        fillPaint
                    )
                }
            }
        }
    }

    fun star(c: Canvas, x: Float, y: Float, s: Float, col: Int) {
        bmp8(c, x, y, s, ArcadeAssetsData.STAR8, col)
    }

    fun coinIcon(c: Canvas, cx: Float, cy: Float, r: Float, col: Int, hole: Int) {
        smoothFillPaint.color = col
        c.drawCircle(cx, cy, r, smoothFillPaint)
        smoothFillPaint.color = hole
        c.drawCircle(cx, cy, r * 0.6f, smoothFillPaint)
        fillRect(c, cx - max(1f, r * 0.16f), cy - r * 0.5f, max(2f, r * 0.32f), r, col)
    }

    fun powerIcon(c: Canvas, x: Float, y: Float, u: Float, kind: Int) {
        val arr = ArcadeAssetsData.PW8[kind] ?: return
        val col = if (kind == 6) 0xFFFFFFFF.toInt() else (ArcadeAssetsData.PW_COL[kind] ?: 0xFFFFFFFF.toInt())
        bmp8(c, x, y, u, arr, col)
        if (kind == 7) {
            fillRect(c, x, y, 2f * u, 2f * u, 0xFFFFFFFF.toInt())
            fillRect(c, x + 6f * u, y, 2f * u, 2f * u, 0xFFFFFFFF.toInt())
        }
    }

    fun wallTile(
        c: Canvas,
        sx: Float,
        sy: Float,
        ts: Float,
        wallCol: Int,
        glowCol: Int,
        x: Int,
        y: Int,
        isWall: (Int, Int) -> Boolean
    ) {
        val e = max(2f, (ts / 9f).roundToInt().toFloat())
        val g = e * 2f
        val b = max(1f, (ts / 10f).roundToInt().toFloat())
        val n = 8
        val h = ArcadeAssetsData.bumpHash(x, y)

        if (!isWall(x, y + 1)) {
            fillRect(c, sx, sy, ts, g, glowCol)
            fillRect(c, sx, sy, ts, e, wallCol)
            for (i in 0 until n) {
                if (ArcadeAssetsData.bumpAt(h, i, 0)) fillRect(c, sx + i * ts / n, sy + e, b, b, wallCol)
            }
        }
        if (!isWall(x, y - 1)) {
            fillRect(c, sx, sy + ts - g, ts, g, glowCol)
            fillRect(c, sx, sy + ts - e, ts, e, wallCol)
            for (i in 0 until n) {
                if (ArcadeAssetsData.bumpAt(h, i, 1)) fillRect(c, sx + i * ts / n, sy + ts - e - b, b, b, wallCol)
            }
        }
        if (!isWall(x - 1, y)) {
            fillRect(c, sx, sy, g, ts, glowCol)
            fillRect(c, sx, sy, e, ts, wallCol)
            for (i in 0 until n) {
                if (ArcadeAssetsData.bumpAt(h, i, 2)) fillRect(c, sx + e, sy + i * ts / n, b, b, wallCol)
            }
        }
        if (!isWall(x + 1, y)) {
            fillRect(c, sx + ts - g, sy, g, ts, glowCol)
            fillRect(c, sx + ts - e, sy, e, ts, wallCol)
            for (i in 0 until n) {
                if (ArcadeAssetsData.bumpAt(h, i, 3)) fillRect(c, sx + ts - e - b, sy + i * ts / n, b, b, wallCol)
            }
        }
    }

    fun spikeTile(c: Canvas, sx: Float, sy: Float, ts: Float, wd: Int, ph: Int, now: Long) {
        val len = when (ph) {
            2 -> 0.75f
            1 -> 0.33f
            else -> 0.08f
        }
        smoothFillPaint.color = if (ph == 1 && (((now / 90L) and 1L) != 0L)) {
            0xFFFFDC00.toInt()
        } else {
            0xFF28E6DC.toInt()
        }

        fun ptX(a: Float, b: Float): Float = when (wd) {
            0, 2 -> sx + a * ts
            3 -> sx + b * ts
            else -> sx + ts - b * ts
        }

        fun ptY(a: Float, b: Float): Float = when (wd) {
            0 -> sy + b * ts
            2 -> sy + ts - b * ts
            else -> sy + a * ts
        }

        for (i in 0 until 4) {
            val a0 = i / 4f
            val am = (i + 0.5f) / 4f
            val a1 = (i + 1f) / 4f
            tmpPath.reset()
            tmpPath.moveTo(ptX(a0, 0f), ptY(a0, 0f))
            tmpPath.lineTo(ptX(am, len), ptY(am, len))
            tmpPath.lineTo(ptX(a1, 0f), ptY(a1, 0f))
            tmpPath.close()
            c.drawPath(tmpPath, smoothFillPaint)
        }
    }

    fun trkTarget(t: SlideTracker, now: Long, stepMs: Float, active: Boolean): Pair<Float, Float> {
        if (!t.mv || !active) return t.bx.toFloat() to t.by.toFloat()
        val prog = min(1f, t.fr + (now - t.pktAt) / stepMs)
        val d = LevelsData.DXY[t.dir and 3]
        return (t.bx + d[0] * prog) to (t.by + d[1] * prog)
    }

    fun trailState(t: SlideTracker, o: HeroDrawSpec, now: Long, vx: Float, vy: Float, ts: Float) {
        if (o.mv) {
            t.len = abs(vx - t.sx) + abs(vy - t.sy)
            t.ldir = o.dir
            o.dist = t.len
        } else if (t.was) {
            t.tailLen = min(t.len * ts, 4f * ts)
            t.tailAt = now
            t.tailDir = t.ldir
        }
        t.was = o.mv
        if (!o.mv) {
            val a = 1f - (now - t.tailAt) / 180f
            if (a > 0f && t.tailLen > 3f) {
                o.tailLen = t.tailLen * a
                o.tailA = a
                o.tailDir = t.tailDir
            }
        }
    }

    fun drawTrail(c: Canvas, cx: Float, cy: Float, ts: Float, dir: Int, len: Float, col: Int, alpha: Float) {
        if (len < 3f || alpha <= 0.01f) return
        val dv = LevelsData.DXY[dir and 3]
        val dx = dv[0].toFloat()
        val dy = -dv[1].toFloat()
        val r = ts * 0.4f
        val th = max(2f, (ts / 10f).roundToInt().toFloat())
        val ppx = if (dy != 0f) 1f else 0f
        val ppy = if (dx != 0f) 1f else 0f

        val aInt = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        val startCol = (col and 0x00FFFFFF) or (aInt shl 24)
        val endCol = col and 0x00FFFFFF

        trailPaint.strokeWidth = th
        for (i in -1..1) {
            val l = len - (if (i == 0) 0f else ts * 0.5f) - (if (i == 1) ts * 0.25f else 0f)
            if (l < 2f) continue
            val off = i * ts / 4f
            val ax = cx - dx * r * 0.5f + ppx * off
            val ay = cy - dy * r * 0.5f + ppy * off
            val bx = ax - dx * (l + r * 0.5f)
            val by = ay - dy * (l + r * 0.5f)
            trailPaint.shader = LinearGradient(ax, ay, bx, by, startCol, endCol, Shader.TileMode.CLAMP)
            c.drawLine(ax, ay, bx, by, trailPaint)
        }
        trailPaint.shader = null
    }

    fun heroDraw(c: Canvas, sx: Float, sy: Float, ts: Float, o: HeroDrawSpec) {
        val cx = sx + ts / 2f
        val cy = sy + ts / 2f
        if (o.mv) {
            drawTrail(c, cx, cy, ts, o.dir, min(o.dist * ts, 4f * ts), o.col, 1f)
            smoothFillPaint.color = o.col
            c.drawCircle(cx, cy, ts * 0.4f, smoothFillPaint)
            return
        }
        if (o.tailLen > 0f) {
            drawTrail(c, cx, cy, ts, o.tailDir, o.tailLen, o.col, o.tailA * 0.8f)
        }
        val hs = ts * 1.25f
        val bmp = HeroSpritesData.getHeroBitmap(o.mask, false, o.face, o.pose, o.frame, o.hf, o.col)
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(cx - hs / 2f, sy + ts - hs + o.dy, cx + hs / 2f, sy + ts + o.dy)
        c.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
    }

    fun drawHeroBitmapBig(
        c: Canvas,
        mask: Int,
        face: Int,
        pose: Int,
        frame: Int,
        hf: Int,
        col: Int,
        left: Float,
        top: Float,
        size: Float
    ) {
        val bmp = HeroSpritesData.getHeroBitmap(mask, true, face, pose, frame, hf, col)
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(left, top, left + size, top + size)
        c.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
    }

    fun drawSimpleHero(c: Canvas, mask: Int, leftDir: Boolean, col: Int, left: Float, top: Float, size: Float) {
        val bmp = HeroSpritesData.getSimpleHeroBitmap(mask, leftDir, col)
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(left, top, left + size, top + size)
        c.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
    }

    fun fxDraw(c: Canvas, state: ConsoleEngineState, now: Long, ts: Float, sxf: (Float) -> Float, syf: (Float) -> Float) {
        for (f in state.fx) {
            val sx = sxf(f.x) + ts / 2f
            val sy = syf(f.y) + ts / 2f
            if (f.kind == 0) {
                if (f.life < 120f && (((now / 40L) and 1L) != 0L)) continue
                val q = max(2f, ts / 8f)
                fillRect(c, sx, sy, q, q, 0xFFFFF000.toInt())
            } else {
                val age = f.max - f.life
                val rmax = ts * 1.2f
                val r = if (age < 350f) age * rmax / 350f else rmax * f.life / max(1f, f.max - 350f)
                if (r > 0f) {
                    smoothFillPaint.color = 0xFFFFF000.toInt()
                    c.drawCircle(sx, sy, r, smoothFillPaint)
                }
            }
        }
    }

    fun overlay(c: Canvas, cw: Float, ch: Float, title: String, tcol: Int, l1: String, l2: String) {
        val w = cw * 0.8f
        val h = ch * 0.3f
        val x = (cw - w) / 2f
        val y = (ch - h) / 2f
        fillRect(c, 0f, 0f, cw, ch, 0x8C000000.toInt())
        fillRect(c, x, y, w, h, 0xFF080814.toInt())
        strokeRect(c, x, y, w, h, tcol, 2f)
        txt(c, title, cw / 2f, y + h * 0.32f, tcol, cw / 9.5f, Paint.Align.CENTER)
        if (l1.isNotEmpty()) txt(c, l1, cw / 2f, y + h * 0.58f, 0xFFFFFFFF.toInt(), cw / 17f, Paint.Align.CENTER)
        if (l2.isNotEmpty()) txt(c, l2, cw / 2f, y + h * 0.82f, 0xFF8A8AA8.toInt(), cw / 21f, Paint.Align.CENTER)
    }

    fun pauseMenu(c: Canvas, cw: Float, ch: Float, sel: Int) {
        val sc = cw / 120f
        val fs = cw / 15f
        val pw = cw * 0.75f
        val ph = 62f * sc
        val x0 = (cw - pw) / 2f
        val y0 = (ch - ph) / 2f
        fillRect(c, x0 - sc, y0 - sc, pw + 2f * sc, ph + 2f * sc, 0xFFFFFFFF.toInt())
        fillRect(c, x0, y0, pw, ph, 0xFF080814.toInt())
        txt(c, "PAUSED", cw / 2f, y0 + 5f * sc + fs * 0.85f, 0xFFFFD228.toInt(), fs, Paint.Align.CENTER)
        val items = arrayOf("RESUME", "RESTART", "EXIT")
        for (i in items.indices) {
            val y = y0 + (17f + i * 13f) * sc
            if (i == sel) {
                fillRect(c, x0 + 4f * sc, y - 2f * sc, pw - 8f * sc, 11f * sc, 0xFF50376E.toInt())
            }
            txt(
                c,
                items[i],
                cw / 2f,
                y + fs * 0.85f,
                if (i == sel) 0xFFFFFFFF.toInt() else 0xFF4664C8.toInt(),
                fs,
                Paint.Align.CENTER
            )
        }
    }

    fun overPanel(c: Canvas, cw: Float, ch: Float, score: Long, best: Long, newBest: Boolean) {
        val sc = cw / 120f
        val fs = cw / 15f
        val bh = 52f * sc
        val by = ch / 2f - bh / 2f
        val bx = 8f * sc
        val bw = cw - 16f * sc
        fillRect(c, bx, by, bw, bh, 0xFF080814.toInt())
        strokeRect(c, bx, by, bw, bh, 0xFFFF3228.toInt(), max(1.5f, sc * 0.6f))
        txt(c, "GAME OVER", cw / 2f, by + 5f * sc + fs * 1.55f, 0xFFFF3228.toInt(), fs * 1.75f, Paint.Align.CENTER)
        txt(c, "SCORE $score", cw / 2f, by + 24f * sc + fs * 0.85f, 0xFFFFFFFF.toInt(), fs, Paint.Align.CENTER)
        if (newBest) {
            txt(c, "NEW BEST!", cw / 2f, by + 34f * sc + fs * 0.85f, 0xFFFFD228.toInt(), fs, Paint.Align.CENTER)
        } else {
            txt(c, "BEST $best", cw / 2f, by + 34f * sc + fs * 0.85f, 0xFFC8DCFF.toInt(), fs, Paint.Align.CENTER)
        }
        txt(c, "A:RETRY  B:MENU", cw / 2f, by + 44f * sc + fs * 0.85f, 0xFFFFFFFF.toInt(), fs * 0.9f, Paint.Align.CENTER)
    }

    fun drawClear(c: Canvas, cw: Float, ch: Float, state: ConsoleEngineState, now: Long, p: MainPacket) {
        val sc = cw / 120f
        val fs = cw / 15f
        val kCol = 0xFFFFF000.toInt()
        val blk = 0xFF000000.toInt()
        val t = (now - state.stAt).toFloat()

        coinIcon(c, 7f * sc, 7f * sc, 5f * sc, kCol, blk)
        val pw = cw * 0.75f
        val ph = ch * 0.6f
        val x0 = (cw - pw) / 2f
        val y0 = (ch - ph) / 2f
        fillRect(c, x0, y0, pw, ph, kCol)

        val ty = y0 + ph * 0.08f
        txt(c, "STAGE CLEAR!", cw / 2f, ty + fs * 0.85f, blk, fs * 0.95f, Paint.Align.CENTER)
        var dx = x0 + pw / 8f
        while (dx < x0 + pw - pw / 8f) {
            fillRect(c, dx, ty + 9f * sc, 2f * sc, sc, blk)
            dx += 3f * sc
        }

        val su = 2f * sc
        for (i in 0 until 3) {
            if (t < 350f + i * 300f) continue
            star(
                c,
                cw / 2f + (i - 1) * (pw / 3f) - 4f * su,
                y0 + ph * 0.22f,
                su,
                if (i < p.stars) blk else 0xFFBEA500.toInt()
            )
        }

        val bx = x0 + pw / 8f
        val bw = pw - pw / 4f - 6f * sc
        val by = y0 + ph * 0.51f
        strokeRect(c, bx, by, bw, 4f * sc, blk, max(1.5f, sc * 0.5f))
        val frac = if (p.ext) p.dots else if (state.dotsTotal > 0) min(1f, state.dotsCnt.toFloat() / state.dotsTotal) else 1f
        val grow = max(0f, t - 700f)
        val shown = if (grow >= 700f) frac else frac * grow / 700f
        fillRect(c, bx, by, bw * shown, 4f * sc, blk)
        coinIcon(c, bx + bw + 3f * sc, by + 2f * sc, 5f * sc, blk, kCol)

        if (p.stars >= 3 && t > 1500f) {
            val tw = 32f * sc
            val tx = (cw - tw) / 2f
            val ty2 = y0 + ph * 0.62f
            txt(c, "+50", tx, ty2 + fs * 0.85f, blk, fs)
            coinIcon(c, tx + 24f * sc, ty2 + 4f * sc, 4f * sc, blk, kCol)
        }

        if (t > 1700f) {
            val bwid = pw * 0.3f
            val bhei = ph * 0.19f
            val byy = y0 + ph * 0.76f
            val rx = cw / 2f - bwid - 4f * sc
            val nx = cw / 2f + 4f * sc
            val rbg = if (state.clearSel == 0) 0xFF28E6DC.toInt() else kCol
            val nbg = if (state.clearSel == 1) 0xFF28E6DC.toInt() else kCol
            fillRect(c, rx, byy, bwid, bhei, rbg)
            fillRect(c, nx, byy, bwid, bhei, nbg)
            strokeRect(c, rx, byy, bwid, bhei, blk, max(1.5f, sc * 0.5f))
            strokeRect(c, nx, byy, bwid, bhei, blk, max(1.5f, sc * 0.5f))
            replayIcon(c, rx + bwid / 2f, byy + bhei / 2f, 5f * sc, rbg, sc)
            nextIcon(c, nx + bwid / 2f - 1f, byy + bhei / 2f, 5f * sc, sc)
        }
    }

    private fun replayIcon(c: Canvas, cx: Float, cy: Float, r: Float, bg: Int, sc: Float) {
        val blk = 0xFF000000.toInt()
        smoothFillPaint.color = blk
        c.drawCircle(cx, cy, r, smoothFillPaint)
        smoothFillPaint.color = bg
        c.drawCircle(cx, cy, max(1f, r - 2f * sc), smoothFillPaint)
        tmpPath.reset()
        tmpPath.moveTo(cx, cy)
        tmpPath.lineTo(cx + r + 1f, cy - r - 1f)
        tmpPath.lineTo(cx + r + 1f, cy - 1f)
        tmpPath.close()
        c.drawPath(tmpPath, smoothFillPaint)

        smoothFillPaint.color = blk
        tmpPath.reset()
        tmpPath.moveTo(cx + r - 3f * sc, cy - 1f)
        tmpPath.lineTo(cx + r + 4f * sc, cy - 1f)
        tmpPath.lineTo(cx + r, cy - 5f * sc)
        tmpPath.close()
        c.drawPath(tmpPath, smoothFillPaint)
    }

    private fun nextIcon(c: Canvas, cx: Float, cy: Float, r: Float, sc: Float) {
        val blk = 0xFF000000.toInt()
        fillRect(c, cx - r, cy - sc, r + 1f, 2f * sc + 1f, blk)
        smoothFillPaint.color = blk
        tmpPath.reset()
        tmpPath.moveTo(cx + r + 1f, cy)
        tmpPath.lineTo(cx + 1f, cy - r + 1f)
        tmpPath.lineTo(cx + 1f, cy + r - 1f)
        tmpPath.close()
        c.drawPath(tmpPath, smoothFillPaint)
    }

    private fun ptSpawn(e: TitleEmber, cw: Float, u: Float, scatter: Boolean, lavaY: Float) {
        val sc = u / 120f
        e.kind = if (Random.nextFloat() < 0.7f) 0 else 1
        e.max = 900f + Random.nextFloat() * 1500f
        e.life = if (scatter) 100f + Random.nextFloat() * (e.max - 100f) else e.max
        e.x = Random.nextFloat() * cw
        if (e.kind == 0) {
            e.y = if (scatter) Random.nextFloat() * lavaY else lavaY + Random.nextFloat() * 8f * sc
            e.vx = (Random.nextFloat() * 17f - 8f) * sc
            e.vy = -(18f + Random.nextFloat() * 40f) * sc
        } else {
            e.y = Random.nextFloat() * lavaY
            e.vx = (Random.nextFloat() * 11f - 5f) * sc
            e.vy = -(3f + Random.nextFloat() * 8f) * sc
        }
    }

    fun ptFrame(c: Canvas, state: ConsoleEngineState, cw: Float, u: Float, now: Long, dt: Float, lavaY: Float) {
        val sc = max(1f, u / 120f)
        val s = dt / 1000f
        if (state.titleEmbers.isEmpty()) {
            repeat(40) {
                val ember = TitleEmber()
                ptSpawn(ember, cw, u, true, lavaY)
                state.titleEmbers.add(ember)
            }
        }
        state.titleEmbers.forEachIndexed { i, q ->
            q.life -= dt
            if (q.life <= 0f || q.y < -2f || q.x < -20f || q.x > cw + 20f) {
                ptSpawn(q, cw, u, false, lavaY)
                return@forEachIndexed
            }
            q.x += (q.vx + sin(now * 0.003f + (q.y / sc) * 0.05f + i) * 6f * sc) * s
            q.y += q.vy * s
            val fr = q.life / q.max
            if (q.kind == 0) {
                val col = when {
                    fr > 0.6f -> 0xFFFFAA1E.toInt()
                    fr > 0.3f -> 0xFFE6460A.toInt()
                    else -> 0xFF781E0A.toInt()
                }
                val z = if (fr > 0.5f) 2f * sc else sc
                fillRect(c, q.x, q.y, z, z, col)
            } else {
                if ((((now / 140L).toInt() + i) and 3) != 0) {
                    fillRect(c, q.x, q.y, sc, sc, 0xFFC8DCFF.toInt())
                    if (fr > 0.5f) {
                        val dim = 0xFF5A5A6E.toInt()
                        fillRect(c, q.x - sc, q.y, sc, sc, dim)
                        fillRect(c, q.x + sc, q.y, sc, sc, dim)
                        fillRect(c, q.x, q.y - sc, sc, sc, dim)
                        fillRect(c, q.x, q.y + sc, sc, sc, dim)
                    }
                }
            }
        }
    }
}
