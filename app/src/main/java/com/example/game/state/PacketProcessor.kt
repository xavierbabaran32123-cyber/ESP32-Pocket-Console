package com.example.game.state

import com.example.audio.ChiptuneSynthEngine
import com.example.audio.SoundPlayer
import com.example.game.data.ArcadeAssetsData
import com.example.game.data.LevelsData
import kotlin.math.max
import kotlin.math.sign

class PacketProcessor(
    val state: ConsoleEngineState,
    private val sound: SoundPlayer
) {

    private fun u8(bytes: ByteArray, i: Int): Int = bytes[i].toInt() and 0xFF

    private fun u16le(bytes: ByteArray, i: Int): Int {
        return u8(bytes, i) or (u8(bytes, i + 1) shl 8)
    }

    private fun u32le(bytes: ByteArray, i: Int): Long {
        return (u8(bytes, i).toLong()) or
            (u8(bytes, i + 1).toLong() shl 8) or
            (u8(bytes, i + 2).toLong() shl 16) or
            (u8(bytes, i + 3).toLong() shl 24)
    }

    fun onPacket(bytes: ByteArray, nowMs: Long = System.currentTimeMillis()) {
        if (bytes.isEmpty()) return
        synchronized(state.lock) {
            val tag0 = u8(bytes, 0)
            if (tag0 != 77) { // 'M'
                onExtra(tag0, bytes, nowMs)
                return
            }
            if (bytes.size < 12) return

            val f = u8(bytes, 2)
            val sg = u8(bytes, 8)
            val hasExt = bytes.size >= 18
            val u = if (hasExt) u8(bytes, 12) else 0
            val p = MainPacket(
                st = u8(bytes, 1),
                f = f,
                lvl = u8(bytes, 3),
                run = u8(bytes, 4),
                x = u8(bytes, 5),
                y = u8(bytes, 6),
                fr = u8(bytes, 7) / 255f,
                stars = sg and 3,
                mask = sg shr 4,
                coins = u8(bytes, 9),
                mv = (f and 2) != 0,
                dir = (f shr 2) and 3,
                left = (f and 16) != 0,
                ext = hasExt,
                pauseSel = u and 3,
                clearSel = (u shr 2) and 1,
                face = (u shr 3) and 3,
                nb = (u and 32) != 0,
                dots = if (hasExt) u8(bytes, 13) / 255f else 0f,
                best = if (hasExt) u32le(bytes, 14) else 0L
            )

            state.clk = u16le(bytes, 10)
            state.clkAt = nowMs

            if (p.st != state.lastSt) {
                val old = state.lastSt
                state.lastSt = p.st
                state.stAt = nowMs
                onStateEdge(p, old)
            }
            if (p.ext) {
                state.pauseSel = p.pauseSel
                state.clearSel = p.clearSel
            }

            sound.wantTrack = ChiptuneSynthEngine.bgmFor(p.st, (p.f and 1) != 0)

            if ((f and 1) != 0) {
                if (state.level == null || p.lvl != state.lvl || p.run != state.run) {
                    state.lvl = p.lvl
                    state.run = p.run
                    state.level = LevelsData.loadLevel(p.lvl)
                    state.px = p.x
                    state.vx = p.x.toFloat()
                    state.py = p.y
                    state.vy = p.y.toFloat()
                    state.cx = p.x.toFloat()
                    state.cy = p.y.toFloat()
                    state.dotsCnt = 0
                    state.dotsTotal = 0
                    state.moved = false
                    state.prevMv = false
                    state.landAt = 0L
                    state.fxClear()
                    state.track.reset(p.x, p.y, nowMs)
                    state.pend.clear()
                    state.spk.clear()
                    state.level?.let { lv ->
                        for (row in lv.items) {
                            for (v in row) if (v == 1) state.dotsTotal++
                        }
                    }
                    state.pauseSel = 0
                    if (p.st == 3) state.fxBurst(p.x, p.y, 10)
                } else {
                    var x = state.px
                    var y = state.py
                    var guard = 40
                    val lv = state.level
                    while ((x != p.x || y != p.y) && guard-- > 0) {
                        if (x != p.x) {
                            x += (p.x - x).sign
                        } else {
                            y += (p.y - y).sign
                        }
                        if (lv != null && y in 0 until lv.height && x in 0 until lv.width) {
                            val v = lv.items[y][x]
                            if (v == 1 || v == 2 || v == 4) {
                                state.pend.add(PendingCollect(x, y, v, nowMs))
                            }
                        }
                    }
                    val newSlide = p.mv && (!state.track.mv || p.dir != state.track.dir)
                    state.track.onPacket(p.x, p.y, p.mv, p.dir, p.fr, nowMs)
                    if (newSlide) {
                        state.moved = true
                        state.fxDust(state.track.sx, state.track.sy, p.dir, -1, 4)
                        sound.sfx("DASH", 0)
                    }
                    if (!p.mv && state.prevMv && p.st == 1) {
                        state.landAt = nowMs
                        state.fxDust(p.x, p.y, p.dir, 0, 6)
                        sound.sfx("LAND", 0)
                    }
                    state.px = p.x
                    state.py = p.y
                }
                state.prevMv = p.mv
            }

            state.p = p
            state.at = nowMs
        }
    }

    private fun onStateEdge(p: MainPacket, old: Int) {
        state.pauseSel = 0
        if (p.st == 2 || old == 2) sound.sfx("PAUSE", 2)
        if (p.st == 3) sound.sfx("DEAD", 3)
        if (p.st == 6) sound.sfx("BOOM", 3)
        if (p.st == 7) sound.sfx("WIN", 3)
        if ((p.st == 1 && old != 1 && old != 2 && old != -1) || ((p.st == 12 || p.st == 13 || p.st == 14) && old != p.st)) {
            sound.sfx("START", 2)
        }
        if ((p.f and 1) == 0) return
        if (p.st == 3) state.fxBurst(p.x, p.y, 10)
        if (p.st == 6) {
            state.fxAdd(p.x - 0.35f, p.y + 0.3f, 0f, 0f, 900f, 1)
            state.fxAdd(p.x + 0.45f, p.y - 0.1f, 0f, 0f, 900f, 1)
            state.fxBurst(p.x, p.y, 12)
        }
        if (p.st == 7) {
            state.clearSel = if (p.lvl + 1 < 45) 1 else 0
        }
    }

    private fun onMenuPkt(tag: Int, bytes: ByteArray, nowMs: Long) {
        val n = state.menu
        if (tag == 78 && bytes.size >= 14) { // 'N'
            val oldSt = n.st
            val oldSel = n.sel
            val oldMask = n.mask
            val oldOwned = n.owned
            val oldFl = n.fl
            val had = n.at > 0L

            n.st = u8(bytes, 1)
            n.sel = u8(bytes, 2)
            n.mask = u8(bytes, 3)
            n.fl = u8(bytes, 4)
            n.unl = u8(bytes, 5)
            n.owned = u8(bytes, 6) or (u8(bytes, 7) shl 8)
            n.wallet = u32le(bytes, 8)
            n.stars = u8(bytes, 12) or (u8(bytes, 13) shl 8)
            n.at = nowMs

            sound.musicOn = (n.fl and 2) != 0
            sound.sfxOn = (n.fl and 4) != 0

            if (had) {
                if (n.st != oldSt) {
                    sound.sfx("TICK", 0)
                } else if (n.sel != oldSel) {
                    sound.sfx(if (n.st == 9) "TICK" else "DOT", 0)
                }
                if (n.owned != oldOwned) {
                    sound.sfx("STAR", 2)
                } else if (n.mask != oldMask) {
                    sound.sfx("COIN", 1)
                }
                if (((n.fl xor oldFl) and 6) != 0) {
                    sound.sfx("TICK", 0)
                }
            }
        } else if (tag == 110 && bytes.size >= 14) { // 'n'
            n.v[0] = u32le(bytes, 2)
            n.v[1] = u32le(bytes, 6)
            n.v[2] = u32le(bytes, 10)
        } else if (tag == 76 && bytes.size >= 13) { // 'L'
            for (i in 0 until 45) {
                n.lv[i] = (u8(bytes, 1 + (i shr 2)) shr ((i and 3) * 2)) and 3
            }
        }
    }

    private fun onExtra(tag: Int, bytes: ByteArray, nowMs: Long) {
        if (tag == 78 || tag == 110 || tag == 76) {
            onMenuPkt(tag, bytes, nowMs)
            return
        }

        if (tag == 69 && bytes.size >= 20) { // 'E' Endless state
            val e = state.endless
            val run = u8(bytes, 3)
            val px = u8(bytes, 4)
            val py = u8(bytes, 5) or (u8(bytes, 6) shl 8)
            if (run != e.run) {
                e.rows.clear()
                e.run = run
                e.pend.clear()
                e.spk.clear()
                e.track.reset(px, py, nowMs)
                e.pf = null
                e.pst = null
                e.pmv = false
                e.moved = false
                e.scored = false
                e.popUntil = 0L
                e.flash = 0L
                state.fxClear()
                e.px = px
                e.vx = px.toFloat()
                e.py = py
                e.vy = py.toFloat()
                e.cy = py + 2f
                e.tide = py - 9f
                e.stage = u8(bytes, 9)
                e.banner = 0L
            } else {
                var x = e.px
                var y = e.py
                var g = 90
                while ((x != px || y != py) && g-- > 0) {
                    if (x != px) x += (px - x).sign else y += (py - y).sign
                    val row = e.rows[y]
                    if (row != null && x in 0 until 21) {
                        val iv = row.it[x]
                        if (iv == 1 || iv == 2 || iv >= 6) {
                            e.pend.add(PendingCollect(x, y, iv, nowMs))
                        }
                    }
                }
                val newStage = u8(bytes, 9)
                if (newStage != e.stage) {
                    e.stage = newStage
                    e.banner = nowMs + 1800L
                    e.flash = nowMs + 700L
                    sound.sfx("WIN", 2)
                }
            }
            val ef = u8(bytes, 2)
            val efr = u8(bytes, 7) / 255f
            e.track.onPacket(px, py, (ef and 1) != 0, (ef shr 1) and 3, efr, nowMs)
            e.px = px
            e.py = py
            e.f = ef
            e.fr = efr
            e.gap = u8(bytes, 8)
            e.coins = u8(bytes, 10)
            e.fz = u8(bytes, 11)
            e.mg = u8(bytes, 12)
            e.score = u32le(bytes, 13)
            e.clk = u16le(bytes, 17)
            e.clkAt = nowMs
            e.mask = u8(bytes, 19)
            e.at = nowMs

            if (e.rows.size > 120) {
                val it = e.rows.keys.iterator()
                while (it.hasNext()) {
                    if (it.next() < py - 50) it.remove()
                }
            }
        } else if (tag == 82 && bytes.size >= 19) { // 'R' Endless row
            val e = state.endless
            val y = u8(bytes, 1) or (u8(bytes, 2) shl 8)
            val m = u8(bytes, 5) or (u8(bytes, 6) shl 8) or (u8(bytes, 7) shl 16)
            val old = e.rows[y]
            val row = EndlessRow(
                stage = u8(bytes, 3),
                gid = u8(bytes, 4),
                clr = old?.clr ?: LongArray(21)
            )
            for (x in 0 until 21) {
                row.w[x] = (m shr x) and 1
                val shift = if ((x and 1) != 0) 0 else 4
                val v = (u8(bytes, 8 + (x shr 1)) shr shift) and 15
                var nv = v
                if (old != null && v != 0 && old.it[x] == 0 && nowMs - row.clr[x] < 1500L) {
                    nv = 0
                } else if (old != null && v == 0 && old.it[x] != 0 && e.pend.any { it.y == y && it.x == x }) {
                    nv = old.it[x]
                }
                row.it[x] = nv
            }
            e.rows[y] = row
        } else if (tag == 74 && bytes.size >= 16) { // 'J' Jet Fighter
            val j = state.jet
            val pLives = j.lives
            val pFl = j.fl
            j.fl = u8(bytes, 2)
            j.px = u8(bytes, 3)
            j.py = u8(bytes, 4)
            j.lives = u8(bytes, 5)
            val gw = u8(bytes, 6)
            val gh = u8(bytes, 7)
            j.gw = if (gw != 0) gw else 120
            j.gh = if (gh != 0) gh else 160
            j.score = u32le(bytes, 8)
            j.hi = u32le(bytes, 12)
            j.at = nowMs
            if (j.lives < pLives) sound.sfx("BOOM", 1)
            if ((j.fl and 1) != 0 && (pFl and 1) == 0) sound.sfx("DEAD", 3)
            if ((j.fl and 2) != 0 && (pFl and 2) == 0) sound.sfx("CHARGE", 2)
        } else if (tag == 83 && bytes.size >= 2) { // 'S' Jet Fighter sprites
            val j = state.jet
            val fl = u8(bytes, 1)
            if ((fl and 1) != 0) j.build = ArrayList()
            var i = 2
            while (i + 2 < bytes.size) {
                j.build.add(
                    JetSprite(
                        t = u8(bytes, i),
                        x = (u8(bytes, i + 1) - 16).toFloat(),
                        y = (u8(bytes, i + 2) - 16).toFloat()
                    )
                )
                i += 3
            }
            if ((fl and 2) != 0) {
                val prev = j.objs
                val baseSat = if (j.sat != 0L) j.sat else (nowMs - 66L)
                val dtp = max(0.02f, (nowMs - baseSat) / 1000f)
                for (o in j.build) {
                    var best: JetSprite? = null
                    var bd = 144f
                    for (q in prev) {
                        if (q.t == o.t) {
                            val dx = q.x - o.x
                            val dy = q.y - o.y
                            val d = dx * dx + dy * dy
                            if (d < bd) {
                                bd = d
                                best = q
                            }
                        }
                    }
                    o.vx = if (best != null) ((o.x - best.x) / dtp).coerceIn(-220f, 220f) else 0f
                    o.vy = if (best != null) ((o.y - best.y) / dtp).coerceIn(-220f, 220f) else 0f
                }
                j.objs = j.build
                j.at = nowMs
                j.sat = nowMs
                val c1 = j.objs.count { it.t == 1 }
                val cb = j.objs.count { it.t == 10 }
                if (c1 > j.pc1) sound.sfx("PEW", 0)
                if (cb > j.pcb) sound.sfx("SMALLBOOM", 1)
                j.pc1 = c1
                j.pcb = cb
            }
        } else if (tag == 68 && bytes.size >= 16) { // 'D' Dot Maze
            val d = state.dotMaze
            val level = u8(bytes, 4)
            val score = u32le(bytes, 6)
            if (level != d.level || score < d.score) {
                d.dots = ArcadeAssetsData.freshDots()
            }
            val pLv = d.level
            val pSc = d.score
            val pLi = d.lives
            val pFr = d.fright

            d.level = level
            d.score = score
            d.phase = u8(bytes, 1) and 15
            d.paused = (u8(bytes, 1) and 16) != 0
            d.lives = u8(bytes, 3)
            d.fright = u8(bytes, 5) * 32
            d.pw = u8(bytes, 10)
            d.tx = u8(bytes, 11)
            d.ty = u8(bytes, 12)
            d.dir = u8(bytes, 13)
            d.p = u8(bytes, 14) / 255f
            d.mask = u8(bytes, 15)
            d.at = nowMs

            if (d.lives < pLi) sound.sfx("DEAD", 3)
            if (d.level > pLv) sound.sfx("WIN", 2)
            if (d.fright > pFr + 100) sound.sfx("CHARGE", 2)
            if (d.score - pSc >= 200L && d.fright > 0) sound.sfx("STAR", 2)
            if (d.tx < ArcadeAssetsData.DM_W && d.ty < ArcadeAssetsData.DM_H) {
                val ix = d.ty * ArcadeAssetsData.DM_W + d.tx
                if (d.dots[ix].toInt() != 0) {
                    d.dots[ix] = 0
                    d.wk = !d.wk
                    sound.sfx(if (d.wk) "WAKA1" else "WAKA2", 0)
                }
            }
        } else if (tag == 100 && bytes.size >= 17) { // 'd' Dot Maze ghosts
            for (i in 0 until 4) {
                val en = state.dotMaze.ents[i]
                en.tx = u8(bytes, 1 + i * 4)
                en.ty = u8(bytes, 2 + i * 4)
                val dm = u8(bytes, 3 + i * 4)
                en.dir = dm and 7
                en.mode = dm shr 3
                en.p = u8(bytes, 4 + i * 4) / 255f
            }
        } else if (tag == 98 && bytes.size >= 18) { // 'b' Dot Maze dot bitmap half
            val d = state.dotMaze
            val half = u8(bytes, 1)
            for (k in 0 until 128) {
                val idx = half * 128 + k
                if (idx >= ArcadeAssetsData.DM_W * ArcadeAssetsData.DM_H) break
                val isDotCell = ArcadeAssetsData.DM_MAP[idx / ArcadeAssetsData.DM_W][idx % ArcadeAssetsData.DM_W] == '.'
                val bitOn = ((u8(bytes, 2 + (k shr 3)) shr (k and 7)) and 1) != 0
                d.dots[idx] = if (isDotCell && bitOn) 1 else 0
            }
        } else if (tag == 84 && bytes.size >= 20) { // 'T' Blocks
            val t = state.blocks
            val pFl2 = t.fl
            val pLn = t.lines
            val pTy = t.type
            val pRo = t.rot
            val pXx = t.x

            t.st = u8(bytes, 1)
            t.fl = u8(bytes, 2)
            t.type = u8(bytes, 3)
            t.rot = u8(bytes, 4)
            t.x = u8(bytes, 5) - 8
            t.y = u8(bytes, 6) - 8
            t.next = u8(bytes, 7)
            t.lines = u8(bytes, 8) or (u8(bytes, 9) shl 8)
            t.score = u32le(bytes, 10)
            t.cm = u8(bytes, 14) or (u8(bytes, 15) shl 8) or (u8(bytes, 16) shl 16)
            t.hi = u8(bytes, 17) or (u8(bytes, 18) shl 8) or (u8(bytes, 19) shl 16)
            t.at = nowMs

            if (t.lines > pLn) sound.sfx(if (t.lines - pLn >= 4) "WIN" else "STAR", 2)
            if ((t.fl and 1) != 0 && (pFl2 and 1) == 0) sound.sfx("DEAD", 3)
            if (t.type != pTy) {
                sound.sfx("LAND", 1)
            } else {
                if (t.rot != pRo) sound.sfx("TICK", 1)
                if (t.x != pXx) sound.sfx("MOVE", 0)
            }
        } else if (tag == 70 && bytes.size >= 18) { // 'F' Blocks field part
            val t = state.blocks
            val part = u8(bytes, 1)
            for (i in 0 until 32) {
                val idx = part * 32 + i
                if (idx < 200) {
                    val shift = if ((i and 1) != 0) 0 else 4
                    t.field[idx] = (u8(bytes, 2 + (i shr 1)) shr shift) and 15
                }
            }
        }
    }
}
