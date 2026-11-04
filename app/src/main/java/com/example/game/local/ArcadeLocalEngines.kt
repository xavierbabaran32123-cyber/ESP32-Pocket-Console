package com.example.game.local

import com.example.game.data.ArcadeAssetsData
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

enum class ConsoleButton {
    UP, RIGHT, DOWN, LEFT, A, B, PAUSE
}

internal object PacketByteWriter {
    fun putU16LE(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v shr 8) and 0xFF).toByte()
    }

    fun putU24LE(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((v shr 16) and 0xFF).toByte()
    }

    fun putU32LE(buf: ByteArray, offset: Int, v: Long) {
        buf[offset] = (v and 0xFFL).toByte()
        buf[offset + 1] = ((v shr 8) and 0xFFL).toByte()
        buf[offset + 2] = ((v shr 16) and 0xFFL).toByte()
        buf[offset + 3] = ((v shr 24) and 0xFFL).toByte()
    }
}

class LocalJetFighterEngine {
    private data class Entity(
        var type: Int,
        var x: Float,
        var y: Float,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var hp: Int = 1,
        var fireCd: Float = 0f
    )

    private val entities = ArrayList<Entity>()
    private var px = 60f
    private var py = 138f
    var score = 0
        private set
    var best = 0
    private var lives = 3
    private var dead = false
    private var paused = false
    private var tripleMs = 0f
    private var invulnMs = 0f
    private var fireTimerMs = 0f
    private var spawnTimerMs = 600f

    fun reset(highScore: Int) {
        entities.clear()
        px = 60f
        py = 138f
        score = 0
        best = maxOf(best, highScore)
        lives = 3
        dead = false
        paused = false
        tripleMs = 0f
        invulnMs = 1000f
        fireTimerMs = 0f
        spawnTimerMs = 500f
    }

    fun onButtonTap(btn: ConsoleButton): Boolean {
        // Returns true if user wants to exit to Arcade menu
        if (btn == ConsoleButton.PAUSE) {
            if (!dead) paused = !paused
            return false
        }
        if (paused) {
            if (btn == ConsoleButton.A) paused = false
            if (btn == ConsoleButton.B) return true
            return false
        }
        if (dead) {
            if (btn == ConsoleButton.A) reset(best)
            if (btn == ConsoleButton.B) return true
            return false
        }
        if (btn == ConsoleButton.B) return true
        return false
    }

    fun step(dtMs: Float, held: Set<ConsoleButton>, emit: (ByteArray) -> Unit) {
        if (!paused && !dead) {
            val speed = 78f * (dtMs / 1000f)
            if (ConsoleButton.LEFT in held) px -= speed
            if (ConsoleButton.RIGHT in held) px += speed
            if (ConsoleButton.UP in held) py -= speed
            if (ConsoleButton.DOWN in held) py += speed
            px = px.coerceIn(10f, 110f)
            py = py.coerceIn(20f, 150f)

            if (tripleMs > 0f) tripleMs = (tripleMs - dtMs).coerceAtLeast(0f)
            if (invulnMs > 0f) invulnMs = (invulnMs - dtMs).coerceAtLeast(0f)

            fireTimerMs -= dtMs
            if (fireTimerMs <= 0f) {
                fireTimerMs = if (ConsoleButton.A in held) 135f else 220f
                entities.add(Entity(1, px, py - 8f, 0f, -155f))
                if (tripleMs > 0f) {
                    entities.add(Entity(1, px - 5f, py - 5f, -32f, -150f))
                    entities.add(Entity(1, px + 5f, py - 5f, 32f, -150f))
                }
            }

            spawnTimerMs -= dtMs
            if (spawnTimerMs <= 0f) {
                val difficulty = (score / 400).coerceAtMost(8)
                spawnTimerMs = (820f - difficulty * 55f).coerceAtLeast(320f)
                val roll = Random.nextFloat()
                val sx = 14f + Random.nextFloat() * 92f
                when {
                    roll < 0.55f -> entities.add(Entity(4, sx, -6f, (Random.nextFloat() - 0.5f) * 28f, 44f + difficulty * 4f, hp = 1, fireCd = 900f))
                    roll < 0.85f -> entities.add(Entity(5, sx, -8f, if (sx < 60f) 24f else -24f, 56f + difficulty * 4f, hp = 2, fireCd = 750f))
                    else -> entities.add(Entity(9, sx.coerceIn(20f, 100f), -10f, 0f, 26f + difficulty * 2f, hp = 4, fireCd = 650f))
                }
            }

            val dtSec = dtMs / 1000f
            val spawned = ArrayList<Entity>()
            val it = entities.iterator()
            while (it.hasNext()) {
                val e = it.next()
                e.x += e.vx * dtSec
                e.y += e.vy * dtSec

                if (e.type in 4..9) {
                    if (e.x < 10f || e.x > 110f) e.vx = -e.vx
                    e.fireCd -= dtMs
                    if (e.fireCd <= 0f && e.y in 12f..110f) {
                        e.fireCd = if (e.type >= 6) 850f else 1350f
                        val dx = px - e.x
                        val dy = (py - e.y).coerceAtLeast(24f)
                        val len = hypot(dx, dy).coerceAtLeast(1f)
                        spawned.add(Entity(2, e.x, e.y + 6f, dx / len * 76f, dy / len * 76f))
                    }
                } else if (e.type >= 10) {
                    e.fireCd += dtMs
                    if (e.fireCd > 75f) {
                        e.fireCd = 0f
                        e.type++
                    }
                    if (e.type > 13) {
                        it.remove()
                        continue
                    }
                }

                if (e.x < -14f || e.x > 134f || e.y < -15f || e.y > 174f) {
                    it.remove()
                }
            }
            entities.addAll(spawned)

            // Player bullets vs enemies
            val bullets = entities.filter { it.type == 1 }
            val enemies = entities.filter { it.type in 4..9 }
            for (b in bullets) {
                for (en in enemies) {
                    if (en.hp <= 0) continue
                    val hitR = if (en.type >= 6) 9f else 6.5f
                    if (abs(b.x - en.x) <= hitR && abs(b.y - en.y) <= hitR) {
                        b.y = -999f
                        en.hp--
                        if (en.type >= 6 && en.hp > 0) {
                            en.type = (5 + en.hp).coerceIn(6, 9)
                        }
                        if (en.hp <= 0) {
                            val pts = when (en.type) {
                                4 -> 25
                                5 -> 50
                                else -> 120
                            }
                            score += pts
                            if (score > best) best = score
                            entities.add(Entity(10, en.x, en.y))
                            if (Random.nextFloat() < 0.14f) {
                                entities.add(Entity(3, en.x, en.y, 0f, 34f))
                            }
                            en.y = 999f
                        }
                        break
                    }
                }
            }

            // Pickups & hazards vs player
            for (e in entities) {
                if (e.type == 3 && abs(e.x - px) < 9f && abs(e.y - py) < 9f) {
                    tripleMs = 8000f
                    score += 30
                    if (score > best) best = score
                    e.y = 999f
                } else if ((e.type == 2 || e.type in 4..9) && invulnMs <= 0f) {
                    val r = if (e.type == 2) 4.5f else 7.5f
                    if (abs(e.x - px) < r && abs(e.y - py) < r) {
                        e.y = 999f
                        lives--
                        entities.add(Entity(10, px, py))
                        if (lives <= 0) {
                            lives = 0
                            dead = true
                        } else {
                            invulnMs = 1600f
                        }
                    }
                }
            }
            entities.removeAll { it.y < -100f || it.y > 200f }
        }

        // Emit 'J' packet (16B)
        val jPkt = ByteArray(16)
        jPkt[0] = 74
        jPkt[1] = 0
        var fl = 0
        if (dead) fl = fl or 1
        if (tripleMs > 0f) fl = fl or 2
        if (invulnMs > 0f) fl = fl or 4
        if (paused) fl = fl or 8
        jPkt[2] = fl.toByte()
        jPkt[3] = px.toInt().coerceIn(0, 120).toByte()
        jPkt[4] = py.toInt().coerceIn(0, 160).toByte()
        jPkt[5] = lives.toByte()
        jPkt[6] = 120.toByte()
        jPkt[7] = 160.toByte()
        PacketByteWriter.putU32LE(jPkt, 8, score.toLong())
        PacketByteWriter.putU32LE(jPkt, 12, best.toLong())
        emit(jPkt)

        // Emit 'S' sprite packet
        val count = entities.size.coerceAtMost(60)
        val sPkt = ByteArray(2 + count * 3)
        sPkt[0] = 83
        sPkt[1] = 3 // start + end
        for (i in 0 until count) {
            val o = entities[i]
            sPkt[2 + i * 3] = o.type.coerceIn(1, 13).toByte()
            sPkt[3 + i * 3] = (o.x.toInt() + 16).coerceIn(0, 255).toByte()
            sPkt[4 + i * 3] = (o.y.toInt() + 16).coerceIn(0, 255).toByte()
        }
        emit(sPkt)
    }
}

class LocalDotMazeEngine {
    private class GhostActor(var tx: Int, var ty: Int, var dir: Int, var mode: Int = 1, var p: Float = 0f, var respawnMs: Float = 0f)

    private var dots = ArcadeAssetsData.freshDots()
    private var pwMask = 15
    private var tx = 7
    private var ty = 14
    private var dir = 0
    private var wantDir = 0
    private var prog = 0f
    private var lives = 3
    private var level = 1
    var score = 0
        private set
    var best = 0
    private var frightMs = 0f
    private var phase = 0 // 0=play, 1=dying, 2=clear, 3=over
    private var phaseTimer = 0f
    private var paused = false
    private var mask = 0
    private var needFullBitmapSync = true

    private val ghosts = arrayOf(
        GhostActor(7, 7, 2, 1),
        GhostActor(6, 7, 4, 1),
        GhostActor(8, 7, 1, 1),
        GhostActor(7, 8, 3, 1)
    )

    private fun isOpen(x: Int, y: Int): Boolean {
        return x in 0 until ArcadeAssetsData.DM_W &&
            y in 0 until ArcadeAssetsData.DM_H &&
            ArcadeAssetsData.DM_MAP[y][x] != '#'
    }

    fun reset(highScore: Int, equippedMask: Int) {
        dots = ArcadeAssetsData.freshDots()
        pwMask = 15
        tx = 7
        ty = 14
        dir = 0
        wantDir = 0
        prog = 0f
        lives = 3
        level = 1
        score = 0
        best = maxOf(best, highScore)
        frightMs = 0f
        phase = 0
        phaseTimer = 0f
        paused = false
        mask = equippedMask
        needFullBitmapSync = true
        resetPositions()
    }

    private fun resetPositions() {
        tx = 7; ty = 14; dir = 0; prog = 0f
        ghosts[0].apply { tx = 7; ty = 7; dir = 2; mode = 1; p = 0f }
        ghosts[1].apply { tx = 6; ty = 7; dir = 4; mode = 1; p = 0f }
        ghosts[2].apply { tx = 8; ty = 7; dir = 1; mode = 1; p = 0f }
        ghosts[3].apply { tx = 7; ty = 9; dir = 2; mode = 1; p = 0f }
    }

    fun onButtonTap(btn: ConsoleButton): Boolean {
        if (btn == ConsoleButton.PAUSE) {
            if (phase != 3) paused = !paused
            return false
        }
        if (paused) {
            if (btn == ConsoleButton.A) paused = false
            if (btn == ConsoleButton.B) return true
            return false
        }
        if (phase == 3) {
            if (btn == ConsoleButton.A) reset(best, mask)
            if (btn == ConsoleButton.B) return true
            return false
        }
        when (btn) {
            ConsoleButton.UP -> wantDir = 1
            ConsoleButton.RIGHT -> wantDir = 2
            ConsoleButton.DOWN -> wantDir = 3
            ConsoleButton.LEFT -> wantDir = 4
            ConsoleButton.B -> return true
            else -> {}
        }
        return false
    }

    fun step(dtMs: Float, held: Set<ConsoleButton>, emit: (ByteArray) -> Unit) {
        when {
            ConsoleButton.UP in held -> wantDir = 1
            ConsoleButton.RIGHT in held -> wantDir = 2
            ConsoleButton.DOWN in held -> wantDir = 3
            ConsoleButton.LEFT in held -> wantDir = 4
        }

        if (!paused) {
            if (phase == 1) {
                phaseTimer -= dtMs
                if (phaseTimer <= 0f) {
                    if (lives <= 0) {
                        phase = 3
                    } else {
                        phase = 0
                        resetPositions()
                    }
                }
            } else if (phase == 2) {
                phaseTimer -= dtMs
                if (phaseTimer <= 0f) {
                    level++
                    dots = ArcadeAssetsData.freshDots()
                    pwMask = 15
                    needFullBitmapSync = true
                    phase = 0
                    resetPositions()
                }
            } else if (phase == 0) {
                if (frightMs > 0f) {
                    frightMs = (frightMs - dtMs).coerceAtLeast(0f)
                    if (frightMs <= 0f) {
                        for (g in ghosts) if (g.mode == 2) g.mode = 1
                    }
                }

                // Player step
                val pSpeed = (5.4f + level * 0.25f) * (dtMs / 1000f)
                if (dir == 0 && wantDir in 1..4) {
                    val wd = ArcadeAssetsData.DM_D[wantDir]
                    if (isOpen(tx + wd[0], ty + wd[1])) dir = wantDir
                }
                if (dir != 0) {
                    prog += pSpeed
                    while (prog >= 1f && dir != 0) {
                        val cd = ArcadeAssetsData.DM_D[dir]
                        tx += cd[0]
                        ty += cd[1]
                        prog -= 1f
                        collectAtPlayer()
                        if (wantDir in 1..4) {
                            val wd = ArcadeAssetsData.DM_D[wantDir]
                            if (isOpen(tx + wd[0], ty + wd[1])) dir = wantDir
                        }
                        val nd = ArcadeAssetsData.DM_D[dir]
                        if (!isOpen(tx + nd[0], ty + nd[1])) {
                            dir = 0
                            prog = 0f
                        }
                    }
                } else {
                    collectAtPlayer()
                }

                // Ghosts step
                for ((idx, g) in ghosts.withIndex()) {
                    if (g.mode == 3) {
                        g.respawnMs -= dtMs
                        if (g.respawnMs <= 0f) {
                            g.tx = 7; g.ty = 7; g.mode = 1; g.p = 0f
                        }
                        continue
                    }
                    val gSpeed = (if (g.mode == 2) 3.1f else (4.2f + level * 0.22f)) * (dtMs / 1000f)
                    g.p += gSpeed
                    if (g.p >= 1f) {
                        val gd = ArcadeAssetsData.DM_D[g.dir.coerceIn(0, 4)]
                        if (isOpen(g.tx + gd[0], g.ty + gd[1])) {
                            g.tx += gd[0]
                            g.ty += gd[1]
                        }
                        g.p = 0f
                        val rev = when (g.dir) { 1 -> 3; 2 -> 4; 3 -> 1; 4 -> 2; else -> 0 }
                        val candidates = (1..4).filter { d ->
                            val dv = ArcadeAssetsData.DM_D[d]
                            isOpen(g.tx + dv[0], g.ty + dv[1])
                        }
                        val nonRev = candidates.filter { it != rev }.ifEmpty { candidates }
                        g.dir = if (nonRev.isEmpty()) 0 else if (g.mode == 2 || Random.nextFloat() < 0.3f) {
                            nonRev.random()
                        } else {
                            nonRev.minByOrNull { d ->
                                val dv = ArcadeAssetsData.DM_D[d]
                                val nx = g.tx + dv[0]
                                val ny = g.ty + dv[1]
                                val targetX = if (idx % 2 == 0) tx else (tx + ArcadeAssetsData.DM_D[dir][0] * 2)
                                val targetY = if (idx % 2 == 0) ty else (ty + ArcadeAssetsData.DM_D[dir][1] * 2)
                                abs(nx - targetX) + abs(ny - targetY)
                            } ?: nonRev.first()
                        }
                    }

                    // Collision check
                    val gx = g.tx + ArcadeAssetsData.DM_D[g.dir.coerceIn(0, 4)][0] * g.p
                    val gy = g.ty + ArcadeAssetsData.DM_D[g.dir.coerceIn(0, 4)][1] * g.p
                    val px = tx + ArcadeAssetsData.DM_D[dir.coerceIn(0, 4)][0] * prog
                    val py = ty + ArcadeAssetsData.DM_D[dir.coerceIn(0, 4)][1] * prog
                    if (abs(gx - px) < 0.65f && abs(gy - py) < 0.65f) {
                        if (g.mode == 2) {
                            g.mode = 3
                            g.respawnMs = 2400f
                            score += 200
                            if (score > best) best = score
                        } else {
                            lives--
                            phase = 1
                            phaseTimer = 950f
                            break
                        }
                    }
                }
            }
        }

        if (needFullBitmapSync) {
            needFullBitmapSync = false
            for (half in 0..1) {
                val bPkt = ByteArray(18)
                bPkt[0] = 98
                bPkt[1] = half.toByte()
                for (k in 0 until 128) {
                    val idx = half * 128 + k
                    if (idx < dots.size && dots[idx].toInt() != 0) {
                        val bIdx = 2 + (k shr 3)
                        bPkt[bIdx] = (bPkt[bIdx].toInt() or (1 shl (k and 7))).toByte()
                    }
                }
                emit(bPkt)
            }
        }

        val dPkt = ByteArray(16)
        dPkt[0] = 68
        dPkt[1] = ((phase and 15) or (if (paused) 16 else 0)).toByte()
        dPkt[2] = 0
        dPkt[3] = lives.toByte()
        dPkt[4] = level.toByte()
        dPkt[5] = (frightMs / 32f).toInt().coerceIn(0, 255).toByte()
        PacketByteWriter.putU32LE(dPkt, 6, score.toLong())
        dPkt[10] = pwMask.toByte()
        dPkt[11] = tx.toByte()
        dPkt[12] = ty.toByte()
        dPkt[13] = dir.toByte()
        dPkt[14] = (prog * 255f).toInt().coerceIn(0, 255).toByte()
        dPkt[15] = mask.toByte()
        emit(dPkt)

        val gPkt = ByteArray(17)
        gPkt[0] = 100
        for (i in 0 until 4) {
            val g = ghosts[i]
            gPkt[1 + i * 4] = g.tx.toByte()
            gPkt[2 + i * 4] = g.ty.toByte()
            gPkt[3 + i * 4] = ((g.dir and 7) or (g.mode shl 3)).toByte()
            gPkt[4 + i * 4] = (g.p * 255f).toInt().coerceIn(0, 255).toByte()
        }
        emit(gPkt)
    }

    private fun collectAtPlayer() {
        val idx = ty * ArcadeAssetsData.DM_W + tx
        if (idx in dots.indices && dots[idx].toInt() != 0) {
            dots[idx] = 0
            score += 10
            if (score > best) best = score
        }
        ArcadeAssetsData.DM_POWER_COORDS.forEachIndexed { i, xy ->
            if (((pwMask shr i) and 1) != 0 && xy[0] == tx && xy[1] == ty) {
                pwMask = pwMask and (1 shl i).inv()
                score += 50
                if (score > best) best = score
                frightMs = 6500f
                for (g in ghosts) if (g.mode != 3) g.mode = 2
            }
        }
        if (dots.all { it.toInt() == 0 } && pwMask == 0) {
            phase = 2
            phaseTimer = 1300f
            score += 250
            if (score > best) best = score
        }
    }
}

class LocalBlocksEngine {
    private val field = IntArray(200)
    private var curType = 0
    private var nextType = 1
    private var rot = 0
    private var px = 3
    private var py = 0
    private var lines = 0
    var score = 0
        private set
    var best = 0
    private var dead = false
    private var paused = false
    private var clearMask = 0
    private var clearTimerMs = 0f
    private var fallTimerMs = 0f
    private var softDropTimerMs = 0f
    private var fieldDirty = true

    fun reset(highScore: Int) {
        field.fill(0)
        curType = Random.nextInt(7)
        nextType = Random.nextInt(7)
        rot = 0
        px = 3
        py = 0
        lines = 0
        score = 0
        best = maxOf(best, highScore)
        dead = false
        paused = false
        clearMask = 0
        clearTimerMs = 0f
        fallTimerMs = 0f
        fieldDirty = true
    }

    private fun fits(type: Int, r: Int, tx: Int, ty: Int): Boolean {
        for (c in ArcadeAssetsData.tbCells(type, r)) {
            val x = tx + c[0]
            val y = ty + c[1]
            if (x !in 0 until 10 || y >= 20) return false
            if (y >= 0 && field[y * 10 + x] != 0) return false
        }
        return true
    }

    fun onButtonTap(btn: ConsoleButton): Boolean {
        if (btn == ConsoleButton.PAUSE) {
            if (!dead) paused = !paused
            return false
        }
        if (paused) {
            if (btn == ConsoleButton.A) paused = false
            if (btn == ConsoleButton.B) return true
            return false
        }
        if (dead) {
            if (btn == ConsoleButton.A) reset(best)
            if (btn == ConsoleButton.B) return true
            return false
        }
        if (clearMask != 0) return false

        when (btn) {
            ConsoleButton.LEFT -> if (fits(curType, rot, px - 1, py)) px--
            ConsoleButton.RIGHT -> if (fits(curType, rot, px + 1, py)) px++
            ConsoleButton.A, ConsoleButton.UP -> {
                val nr = (rot + 1) and 3
                val kicks = intArrayOf(0, -1, 1, -2, 2)
                for (dx in kicks) {
                    if (fits(curType, nr, px + dx, py)) {
                        px += dx
                        rot = nr
                        break
                    }
                }
            }
            ConsoleButton.DOWN -> {
                if (fits(curType, rot, px, py + 1)) {
                    py++
                    score += 1
                    if (score > best) best = score
                }
            }
            ConsoleButton.B -> {
                // Hard drop!
                var dropped = 0
                while (fits(curType, rot, px, py + 1)) {
                    py++
                    dropped++
                }
                score += dropped * 2
                if (score > best) best = score
                lockPiece()
            }
            else -> {}
        }
        return false
    }

    private fun lockPiece() {
        val colorIdx = (curType + 1).coerceIn(1, 7)
        for (c in ArcadeAssetsData.tbCells(curType, rot)) {
            val x = px + c[0]
            val y = py + c[1]
            if (x in 0 until 10 && y in 0 until 20) {
                field[y * 10 + x] = colorIdx
            }
        }
        fieldDirty = true

        var mask = 0
        for (y in 0 until 20) {
            if ((0 until 10).all { x -> field[y * 10 + x] != 0 }) {
                mask = mask or (1 shl y)
            }
        }
        if (mask != 0) {
            clearMask = mask
            clearTimerMs = 260f
        } else {
            spawnNext()
        }
    }

    private fun spawnNext() {
        curType = nextType
        nextType = Random.nextInt(7)
        rot = 0
        px = 3
        py = 0
        if (!fits(curType, rot, px, py)) {
            dead = true
        }
    }

    fun step(dtMs: Float, held: Set<ConsoleButton>, emit: (ByteArray) -> Unit) {
        if (!paused && !dead) {
            if (clearMask != 0) {
                clearTimerMs -= dtMs
                if (clearTimerMs <= 0f) {
                    var cleared = 0
                    var writeY = 19
                    for (readY in 19 downTo 0) {
                        if (((clearMask shr readY) and 1) != 0) {
                            cleared++
                        } else {
                            if (writeY != readY) {
                                for (x in 0 until 10) field[writeY * 10 + x] = field[readY * 10 + x]
                            }
                            writeY--
                        }
                    }
                    while (writeY >= 0) {
                        for (x in 0 until 10) field[writeY * 10 + x] = 0
                        writeY--
                    }
                    lines += cleared
                    val bonus = when (cleared) { 1 -> 100; 2 -> 300; 3 -> 500; else -> 800 }
                    score += bonus * (1 + lines / 10)
                    if (score > best) best = score
                    clearMask = 0
                    fieldDirty = true
                    spawnNext()
                }
            } else {
                if (ConsoleButton.DOWN in held) {
                    softDropTimerMs += dtMs
                    if (softDropTimerMs >= 55f) {
                        softDropTimerMs = 0f
                        if (fits(curType, rot, px, py + 1)) {
                            py++
                            score++
                            if (score > best) best = score
                        } else {
                            lockPiece()
                        }
                    }
                } else {
                    softDropTimerMs = 0f
                }

                val gravityMs = (680f - (lines / 10) * 52f).coerceAtLeast(110f)
                fallTimerMs += dtMs
                if (fallTimerMs >= gravityMs) {
                    fallTimerMs = 0f
                    if (fits(curType, rot, px, py + 1)) {
                        py++
                    } else {
                        lockPiece()
                    }
                }
            }
        }

        if (fieldDirty) {
            fieldDirty = false
            for (part in 0..6) {
                val fPkt = ByteArray(18)
                fPkt[0] = 70
                fPkt[1] = part.toByte()
                for (i in 0 until 32) {
                    val idx = part * 32 + i
                    val cellVal = if (idx < 200) (field[idx] and 15) else 0
                    val bytePos = 2 + (i shr 1)
                    val shift = if ((i and 1) != 0) 0 else 4
                    fPkt[bytePos] = (fPkt[bytePos].toInt() or (cellVal shl shift)).toByte()
                }
                emit(fPkt)
            }
        }

        val tPkt = ByteArray(20)
        tPkt[0] = 84
        tPkt[1] = 0
        var fl = 0
        if (dead) fl = fl or 1
        if (clearMask != 0) fl = fl or 2
        if (paused) fl = fl or 4
        tPkt[2] = fl.toByte()
        tPkt[3] = curType.toByte()
        tPkt[4] = rot.toByte()
        tPkt[5] = (px + 8).coerceIn(0, 255).toByte()
        tPkt[6] = (py + 8).coerceIn(0, 255).toByte()
        tPkt[7] = nextType.toByte()
        PacketByteWriter.putU16LE(tPkt, 8, lines)
        PacketByteWriter.putU32LE(tPkt, 10, score.toLong())
        PacketByteWriter.putU24LE(tPkt, 14, clearMask)
        PacketByteWriter.putU24LE(tPkt, 17, best)
        emit(tPkt)
    }
}
