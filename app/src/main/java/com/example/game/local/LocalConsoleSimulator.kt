package com.example.game.local

import com.example.data.ConsoleSavedPrefs
import com.example.game.data.HeroSpritesData
import com.example.game.data.LevelData
import com.example.game.data.LevelsData
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LocalConsoleSimulator(
    private val scope: CoroutineScope,
    private val emitPacket: (ByteArray) -> Unit,
    private val onPersistSave: (ConsoleSavedPrefs) -> Unit
) {
    private class SimEndlessRow(
        var stageByte: Int,
        var gid: Int,
        val w: IntArray = IntArray(21) { 1 },
        val it: IntArray = IntArray(21) { 0 },
        var dirty: Boolean = true
    )

    private val heldButtons = ConcurrentHashMap.newKeySet<ConsoleButton>()
    private var loopJob: Job? = null

    // Saved progression
    private var save = ConsoleSavedPrefs()

    // Core firmware state
    private var st: Int = 10 // Start on START screen (10)
    private var inLevel: Boolean = false
    private var menuSel: Int = 0
    private var confirmNewErase: Boolean = false
    private var confirmReset: Boolean = false
    private var erasedDone: Boolean = false

    // Stage Mode state
    private var curStageIdx: Int = 0
    private var curLevel: LevelData? = null
    private var runCounter: Int = 1
    private var px: Int = 0
    private var py: Int = 0
    private var moving: Boolean = false
    private var slideDir: Int = 0
    private var faceDir: Int = 1
    private var lookLeft: Boolean = false
    private var slideFrac: Float = 0f
    private var stageStarsEarned: Int = 0
    private var stageCoins: Int = 0
    private var dotsCollected: Int = 0
    private var dotsTotal: Int = 1
    private var pauseSel: Int = 0
    private var clearSel: Int = 1
    private var stateTimerMs: Float = 0f
    private var clkMs: Int = 0
    private var queuedDir: Int = -1

    // Endless Mode state
    private val endlessRows = HashMap<Int, SimEndlessRow>()
    private var endlessRunId: Int = 1
    private var genCursorX: Int = 10
    private var genCursorY: Int = 2
    private var genSegmentCount: Int = 0
    private var endlessPyMax: Int = 2
    private var endlessScore: Int = 0
    private var endlessCoins: Int = 0
    private var endlessStage: Int = 1
    private var tideY: Float = -6f
    private var hasShield: Boolean = false
    private var freezeMs: Float = 0f
    private var magnetMs: Float = 0f
    private var invulnMs: Float = 0f
    private var endlessNewBest: Boolean = false

    // Arcade sub-engines
    private val jetEngine = LocalJetFighterEngine()
    private val dotEngine = LocalDotMazeEngine()
    private val blocksEngine = LocalBlocksEngine()

    val isRunning: Boolean
        get() = loopJob?.isActive == true

    fun syncPrefs(prefs: ConsoleSavedPrefs) {
        val wasInitialized = save.unlockedStages > 1 || save.walletCoins > 0 || save.bestEndless > 0
        if (!isRunning || !wasInitialized) {
            save = prefs.copy(stageStars = prefs.stageStars.clone())
        } else {
            save = save.copy(
                soundMuted = prefs.soundMuted,
                showGamepadInLocal = prefs.showGamepadInLocal
            )
        }
    }

    fun start() {
        if (isRunning) return
        heldButtons.clear()
        st = 10
        inLevel = false
        menuSel = 0
        emitFullMenuSync()

        loopJob = scope.launch {
            var lastTime = System.currentTimeMillis()
            while (isActive) {
                val now = System.currentTimeMillis()
                val dt = (now - lastTime).coerceIn(5L, 50L).toFloat()
                lastTime = now
                tick(dt)
                delay(25L) // ~40 Hz firmware packet cadence
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        heldButtons.clear()
    }

    fun onButtonPress(btn: ConsoleButton) {
        heldButtons.add(btn)
        handleButtonEdge(btn)
    }

    fun onButtonRelease(btn: ConsoleButton) {
        heldButtons.remove(btn)
    }

    fun onSwipeDirection(dir: Int) {
        val btn = when (dir and 3) {
            0 -> ConsoleButton.UP
            1 -> ConsoleButton.RIGHT
            2 -> ConsoleButton.DOWN
            else -> ConsoleButton.LEFT
        }
        handleButtonEdge(btn)
    }

    /**
     * Handles system Back gesture in Local Mode.
     * Returns true if consumed internally (navigated back to parent menu / paused),
     * or false if already at root Start Screen.
     */
    fun onNavigateBack(): Boolean {
        if (!isRunning) return false
        return when {
            st == 10 -> false
            st == 0 || st == 11 -> {
                st = 10
                menuSel = 0
                emitFullMenuSync()
                true
            }
            st == 5 || st == 8 || st == 9 -> {
                st = 0
                menuSel = 0
                confirmReset = false
                erasedDone = false
                emitFullMenuSync()
                true
            }
            st in 12..14 -> {
                handleButtonEdge(ConsoleButton.PAUSE)
                true
            }
            inLevel || (st in 1..4) -> {
                if (st == 1) {
                    st = 2
                    pauseSel = 0
                } else {
                    exitToMenu()
                }
                true
            }
            else -> false
        }
    }

    private fun handleButtonEdge(btn: ConsoleButton) {
        if (!isRunning) return

        // Arcade games delegate input
        if (st == 12) {
            if (jetEngine.onButtonTap(btn)) {
                saveBestScores()
                st = 11
                menuSel = 0
                emitFullMenuSync()
            }
            return
        }
        if (st == 13) {
            if (dotEngine.onButtonTap(btn)) {
                saveBestScores()
                st = 11
                menuSel = 1
                emitFullMenuSync()
            }
            return
        }
        if (st == 14) {
            if (blocksEngine.onButtonTap(btn)) {
                saveBestScores()
                st = 11
                menuSel = 2
                emitFullMenuSync()
            }
            return
        }

        // Stage Mode
        if (inLevel) {
            handleStageInput(btn)
            return
        }

        // Endless Mode
        if (st in 1..4) {
            handleEndlessInput(btn)
            return
        }

        // Menus (10, 0, 5, 8, 9, 11)
        handleMenuInput(btn)
    }

    private fun handleMenuInput(btn: ConsoleButton) {
        val hasContinue = save.unlockedStages > 1 || save.stageStars.any { it > 0 }
        when (st) {
            10 -> { // START SCREEN
                when (btn) {
                    ConsoleButton.A, ConsoleButton.PAUSE -> {
                        st = 0
                        menuSel = if (hasContinue) 1 else 0
                        confirmNewErase = false
                        emitFullMenuSync()
                    }
                    ConsoleButton.B -> {
                        st = 11
                        menuSel = 0
                        emitFullMenuSync()
                    }
                    else -> {}
                }
            }
            0 -> { // TITLE MENU
                when (btn) {
                    ConsoleButton.UP -> {
                        menuSel = (menuSel + 4) % 5
                        confirmNewErase = false
                        emitFullMenuSync()
                    }
                    ConsoleButton.DOWN -> {
                        menuSel = (menuSel + 1) % 5
                        confirmNewErase = false
                        emitFullMenuSync()
                    }
                    ConsoleButton.B -> {
                        confirmNewErase = false
                        st = 10
                        menuSel = 0
                        emitFullMenuSync()
                    }
                    ConsoleButton.A -> {
                        when (menuSel) {
                            0 -> { // NEW GAME
                                if (hasContinue && !confirmNewErase) {
                                    confirmNewErase = true
                                    emitFullMenuSync()
                                } else {
                                    confirmNewErase = false
                                    val clearedStars = IntArray(45)
                                    save = save.copy(
                                        unlockedStages = 1,
                                        stageStars = clearedStars,
                                        bestStageScore = 0
                                    )
                                    onPersistSave(save)
                                    startStage(0)
                                }
                            }
                            1 -> { // CONTINUE
                                if (hasContinue) {
                                    st = 5
                                    menuSel = (save.unlockedStages - 1).coerceIn(0, 44)
                                    emitFullMenuSync()
                                } else {
                                    startStage(0)
                                }
                            }
                            2 -> startEndless()
                            3 -> {
                                st = 8
                                menuSel = save.equippedMask.coerceIn(0, 14)
                                emitFullMenuSync()
                            }
                            4 -> {
                                st = 9
                                menuSel = 0
                                confirmReset = false
                                erasedDone = false
                                emitFullMenuSync()
                            }
                        }
                    }
                    else -> {}
                }
            }
            5 -> { // LEVEL MAP
                when (btn) {
                    ConsoleButton.RIGHT -> {
                        menuSel = (menuSel + 1).coerceAtMost(44)
                        emitFullMenuSync()
                    }
                    ConsoleButton.LEFT -> {
                        menuSel = (menuSel - 1).coerceAtLeast(0)
                        emitFullMenuSync()
                    }
                    ConsoleButton.UP, ConsoleButton.DOWN -> {
                        val toggled = menuSel xor 1
                        if (toggled in 0..44) menuSel = toggled
                        emitFullMenuSync()
                    }
                    ConsoleButton.A -> {
                        if (menuSel < save.unlockedStages) {
                            startStage(menuSel)
                        }
                    }
                    ConsoleButton.B -> {
                        st = 0
                        menuSel = 1
                        emitFullMenuSync()
                    }
                    else -> {}
                }
            }
            8 -> { // MASKS SHOP
                when (btn) {
                    ConsoleButton.LEFT -> {
                        menuSel = (menuSel - 1).coerceAtLeast(0)
                        emitFullMenuSync()
                    }
                    ConsoleButton.RIGHT -> {
                        menuSel = (menuSel + 1).coerceAtMost(14)
                        emitFullMenuSync()
                    }
                    ConsoleButton.UP -> {
                        menuSel = (menuSel - 3).coerceAtLeast(0)
                        emitFullMenuSync()
                    }
                    ConsoleButton.DOWN -> {
                        menuSel = (menuSel + 3).coerceAtMost(14)
                        emitFullMenuSync()
                    }
                    ConsoleButton.A -> {
                        val idx = menuSel.coerceIn(0, 14)
                        val owned = ((save.ownedMasksBits shr idx) and 1) != 0
                        val cost = HeroSpritesData.MASK_COST[idx]
                        if (owned) {
                            save = save.copy(equippedMask = idx)
                            onPersistSave(save)
                            emitFullMenuSync()
                        } else if (save.walletCoins >= cost) {
                            save = save.copy(
                                walletCoins = save.walletCoins - cost,
                                ownedMasksBits = save.ownedMasksBits or (1 shl idx),
                                equippedMask = idx
                            )
                            onPersistSave(save)
                            emitFullMenuSync()
                        }
                    }
                    ConsoleButton.B -> {
                        st = 0
                        menuSel = 3
                        emitFullMenuSync()
                    }
                    else -> {}
                }
            }
            9 -> { // SETTINGS
                when (btn) {
                    ConsoleButton.UP -> {
                        menuSel = (menuSel + 3) % 4
                        confirmReset = false
                        erasedDone = false
                        emitFullMenuSync()
                    }
                    ConsoleButton.DOWN -> {
                        menuSel = (menuSel + 1) % 4
                        confirmReset = false
                        erasedDone = false
                        emitFullMenuSync()
                    }
                    ConsoleButton.B -> {
                        st = 0
                        menuSel = 4
                        confirmReset = false
                        erasedDone = false
                        emitFullMenuSync()
                    }
                    ConsoleButton.A -> {
                        when (menuSel) {
                            0 -> {
                                save = save.copy(musicOn = !save.musicOn)
                                onPersistSave(save)
                                emitFullMenuSync()
                            }
                            1 -> {
                                save = save.copy(sfxOn = !save.sfxOn)
                                onPersistSave(save)
                                emitFullMenuSync()
                            }
                            2 -> {
                                if (!confirmReset) {
                                    confirmReset = true
                                    erasedDone = false
                                } else {
                                    confirmReset = false
                                    erasedDone = true
                                    save = ConsoleSavedPrefs(
                                        soundMuted = save.soundMuted,
                                        musicOn = save.musicOn,
                                        sfxOn = save.sfxOn,
                                        showGamepadInLocal = save.showGamepadInLocal
                                    )
                                    onPersistSave(save)
                                }
                                emitFullMenuSync()
                            }
                            3 -> {
                                st = 0
                                menuSel = 4
                                emitFullMenuSync()
                            }
                        }
                    }
                    else -> {}
                }
            }
            11 -> { // ARCADE MENU
                when (btn) {
                    ConsoleButton.UP -> {
                        menuSel = (menuSel + 3) % 4
                        emitFullMenuSync()
                    }
                    ConsoleButton.DOWN -> {
                        menuSel = (menuSel + 1) % 4
                        emitFullMenuSync()
                    }
                    ConsoleButton.B -> {
                        st = 10
                        menuSel = 0
                        emitFullMenuSync()
                    }
                    ConsoleButton.A -> {
                        when (menuSel) {
                            0 -> {
                                st = 12
                                inLevel = false
                                jetEngine.reset(save.bestJet)
                            }
                            1 -> {
                                st = 13
                                inLevel = false
                                dotEngine.reset(save.bestDot, save.equippedMask)
                            }
                            2 -> {
                                st = 14
                                inLevel = false
                                blocksEngine.reset(save.bestBlocks)
                            }
                            3 -> {
                                st = 10
                                menuSel = 0
                                emitFullMenuSync()
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun startStage(stageIdx: Int) {
        val idx = stageIdx.coerceIn(0, 44)
        val lv = LevelsData.loadLevel(idx)?.copy() ?: return
        curStageIdx = idx
        curLevel = lv
        runCounter = (runCounter + 1) and 0xFF
        inLevel = true
        st = 1
        px = lv.gateX
        py = lv.gateY
        moving = false
        slideDir = 0
        faceDir = 1
        lookLeft = false
        slideFrac = 0f
        stageStarsEarned = 0
        stageCoins = 0
        dotsCollected = 0
        var total = 0
        for (r in lv.items) for (v in r) if (v == 1) total++
        dotsTotal = max(1, total)
        pauseSel = 0
        clearSel = if (idx + 1 < 45) 1 else 0
        stateTimerMs = 0f
        queuedDir = -1
        emitMainPacket()
    }

    private fun handleStageInput(btn: ConsoleButton) {
        when (st) {
            1 -> { // PLAYING
                if (btn == ConsoleButton.PAUSE || btn == ConsoleButton.B) {
                    st = 2
                    pauseSel = 0
                    emitMainPacket()
                    return
                }
                val dir = when (btn) {
                    ConsoleButton.UP -> 0
                    ConsoleButton.RIGHT -> 1
                    ConsoleButton.DOWN -> 2
                    ConsoleButton.LEFT -> 3
                    else -> -1
                }
                if (dir != -1) {
                    if (!moving) {
                        tryStartStageSlide(dir)
                    } else {
                        queuedDir = dir
                    }
                }
            }
            2 -> { // PAUSED
                when (btn) {
                    ConsoleButton.UP -> pauseSel = (pauseSel + 2) % 3
                    ConsoleButton.DOWN -> pauseSel = (pauseSel + 1) % 3
                    ConsoleButton.PAUSE, ConsoleButton.B -> st = 1
                    ConsoleButton.A -> {
                        when (pauseSel) {
                            0 -> st = 1
                            1 -> startStage(curStageIdx)
                            2 -> {
                                inLevel = false
                                st = 5
                                menuSel = curStageIdx
                                emitFullMenuSync()
                                return
                            }
                        }
                    }
                    else -> {}
                }
                emitMainPacket()
            }
            3 -> { // DEAD
                if (btn == ConsoleButton.A) {
                    startStage(curStageIdx)
                }
            }
            7 -> { // STAGE CLEAR
                when (btn) {
                    ConsoleButton.LEFT -> clearSel = 0
                    ConsoleButton.RIGHT -> clearSel = 1
                    ConsoleButton.B -> {
                        inLevel = false
                        st = 5
                        menuSel = curStageIdx
                        emitFullMenuSync()
                        return
                    }
                    ConsoleButton.A -> {
                        if (clearSel == 0) {
                            startStage(curStageIdx)
                        } else if (curStageIdx + 1 < 45) {
                            startStage(curStageIdx + 1)
                        } else {
                            inLevel = false
                            st = 5
                            menuSel = 44
                            emitFullMenuSync()
                            return
                        }
                    }
                    else -> {}
                }
                emitMainPacket()
            }
        }
    }

    private fun tryStartStageSlide(dir: Int): Boolean {
        val lv = curLevel ?: return false
        val dv = LevelsData.DXY[dir and 3]
        val nx = px + dv[0]
        val ny = py + dv[1]
        if (nx !in 0 until lv.width || ny !in 0 until lv.height) return false
        if (lv.walls[ny][nx] != 0) return false
        moving = true
        slideDir = dir and 3
        faceDir = slideDir
        if (slideDir == 3) lookLeft = true else if (slideDir == 1) lookLeft = false
        slideFrac = 0f
        queuedDir = -1
        return true
    }

    private fun startEndless() {
        inLevel = false
        st = 1
        endlessRunId = (endlessRunId + 1) and 0xFF
        if (endlessRunId == 0) endlessRunId = 1
        endlessRows.clear()

        // Build base chamber around y = 0..4
        for (y in 0..4) {
            val row = SimEndlessRow(stageByte = 1, gid = 0)
            if (y in 1..3) {
                for (x in 6..14) row.w[x] = 0
            }
            endlessRows[y] = row
        }
        // Solid floor below y=1, starter shaft at x=10
        endlessRows[0]?.w?.fill(1)
        genCursorX = 10
        genCursorY = 2
        genSegmentCount = 0
        px = 10
        py = 2
        endlessPyMax = 2
        moving = false
        slideDir = 0
        faceDir = 1
        lookLeft = false
        slideFrac = 0f
        queuedDir = -1
        endlessScore = 0
        endlessCoins = 0
        endlessStage = 1
        tideY = -6f
        hasShield = false
        freezeMs = 0f
        magnetMs = 0f
        invulnMs = 0f
        endlessNewBest = false
        pauseSel = 0

        ensureEndlessGeneratedUpTo(42)
        emitDirtyEndlessRows(py)
        emitEndlessStatePacket()
        emitMainPacket()
    }

    private fun ensureEndlessGeneratedUpTo(targetY: Int) {
        while (genCursorY < targetY) {
            val stageNum = (1 + genCursorY / 36).coerceIn(1, 99)
            // Pick horizontal slide target distinct from genCursorX within [3..17]
            val candidates = (3..17).filter { kotlin.math.abs(it - genCursorX) in 3..9 }
            val nextX = candidates.random()
            val dirX = (nextX - genCursorX).sign

            val curRow = getOrCreateEndlessRow(genCursorY, stageNum)
            // Carve horizontal run from genCursorX to nextX
            var x = genCursorX
            while (true) {
                curRow.w[x] = 0
                if (x != genCursorX && curRow.it[x] == 0) {
                    curRow.it[x] = if (Random.nextFloat() < 0.14f) 2 else 1
                }
                if (x == nextX) break
                x += dirX
            }
            // Ensure stopper wall right after nextX so horizontal dash lands at nextX
            val stopX = nextX + dirX
            if (stopX in 0..20) {
                curRow.w[stopX] = 1
                curRow.it[stopX] = 0
            }
            curRow.dirty = true

            // Ensure wall directly below nextX so player cannot accidentally fall without floor
            val belowRow = getOrCreateEndlessRow(genCursorY - 1, stageNum)
            if (belowRow.w[nextX] == 0 && genCursorY > 3) {
                // Keep existing shafts open if already carved, otherwise wall
            }

            // Pick vertical rise
            val isBoost = (genSegmentCount > 0 && genSegmentCount % 6 == 0)
            val rise = if (isBoost) 9 else Random.nextInt(4, 8)
            val nextY = genCursorY + rise

            for (y in (genCursorY + 1)..nextY) {
                val r = getOrCreateEndlessRow(y, stageNum)
                if (isBoost && y < nextY) {
                    r.stageByte = stageNum or 128
                }
                r.w[nextX] = 0
                r.gid = (y / 5) and 31
                if (!isBoost && y < nextY) {
                    val roll = Random.nextFloat()
                    r.it[nextX] = when {
                        y == genCursorY + rise / 2 && roll < 0.08f -> intArrayOf(6, 7, 8).random()
                        roll < 0.18f -> 2
                        else -> 1
                    }
                    // Occasional timed wall spike on vertical shaft walls (after stage 1)
                    if (stageNum >= 2 && y == genCursorY + 2 && Random.nextFloat() < 0.25f) {
                        if (r.w[(nextX - 1).coerceAtLeast(0)] == 1 || r.w[(nextX + 1).coerceAtMost(20)] == 1) {
                            r.it[nextX] = 3
                        }
                    }
                }
                r.dirty = true
            }

            // Ceiling stopper wall above (nextX, nextY) so upward dash lands cleanly at (nextX, nextY)
            val ceilRow = getOrCreateEndlessRow(nextY + 1, stageNum)
            ceilRow.w[nextX] = 1
            ceilRow.it[nextX] = 0
            ceilRow.dirty = true

            genCursorX = nextX
            genCursorY = nextY
            genSegmentCount++
        }
    }

    private fun getOrCreateEndlessRow(y: Int, stageNum: Int): SimEndlessRow {
        return endlessRows.getOrPut(y) {
            SimEndlessRow(stageByte = stageNum.coerceIn(1, 99), gid = (y / 5) and 31)
        }
    }

    private fun handleEndlessInput(btn: ConsoleButton) {
        when (st) {
            1 -> {
                if (btn == ConsoleButton.PAUSE || btn == ConsoleButton.B) {
                    st = 2
                    pauseSel = 0
                    emitMainPacket()
                    return
                }
                val dir = when (btn) {
                    ConsoleButton.UP -> 0
                    ConsoleButton.RIGHT -> 1
                    ConsoleButton.DOWN -> 2
                    ConsoleButton.LEFT -> 3
                    else -> -1
                }
                if (dir != -1) {
                    if (!moving) {
                        tryStartEndlessSlide(dir)
                    } else {
                        queuedDir = dir
                    }
                }
            }
            2 -> {
                when (btn) {
                    ConsoleButton.UP -> pauseSel = (pauseSel + 2) % 3
                    ConsoleButton.DOWN -> pauseSel = (pauseSel + 1) % 3
                    ConsoleButton.PAUSE, ConsoleButton.B -> st = 1
                    ConsoleButton.A -> {
                        when (pauseSel) {
                            0 -> st = 1
                            1 -> startEndless()
                            2 -> {
                                exitToMenu()
                                return
                            }
                        }
                    }
                    else -> {}
                }
                emitMainPacket()
            }
            4 -> { // GAME OVER
                when (btn) {
                    ConsoleButton.A -> startEndless()
                    ConsoleButton.B -> exitToMenu()
                    else -> {}
                }
            }
        }
    }

    private fun tryStartEndlessSlide(dir: Int): Boolean {
        val dv = LevelsData.DXY[dir and 3]
        val nx = px + dv[0]
        val ny = py + dv[1]
        if (nx !in 0..20 || ny < 1) return false
        val r = endlessRows[ny] ?: return false
        if (r.w[nx] != 0) return false
        moving = true
        slideDir = dir and 3
        faceDir = slideDir
        if (slideDir == 3) lookLeft = true else if (slideDir == 1) lookLeft = false
        slideFrac = 0f
        queuedDir = -1
        return true
    }

    private fun exitToMenu() {
        inLevel = false
        st = 0
        menuSel = 0
        emitFullMenuSync()
    }

    private fun tick(dtMs: Float) {
        clkMs = (clkMs + dtMs.toInt()) % 1700

        if (st == 12) {
            emitMainPacket()
            jetEngine.step(dtMs, heldButtons, emitPacket)
            return
        }
        if (st == 13) {
            emitMainPacket()
            dotEngine.step(dtMs, heldButtons, emitPacket)
            return
        }
        if (st == 14) {
            emitMainPacket()
            blocksEngine.step(dtMs, heldButtons, emitPacket)
            return
        }

        if (inLevel) {
            tickStage(dtMs)
            return
        }

        if (st in 1..4) {
            tickEndless(dtMs)
            return
        }

        // Menu heartbeat so renderer never shows NO SIGNAL (3000ms timeout)
        emitMainPacket()
        val mPkt = buildMenuNPacket()
        emitPacket(mPkt)
    }

    private fun tickStage(dtMs: Float) {
        val lv = curLevel ?: return
        when (st) {
            1 -> {
                if (moving) {
                    slideFrac += dtMs / 48f
                    val dv = LevelsData.DXY[slideDir]
                    while (slideFrac >= 1f && moving && st == 1) {
                        slideFrac -= 1f
                        px += dv[0]
                        py += dv[1]
                        collectStageCell(lv, px, py)
                        if (st != 1) break
                        if (isStageSpikeLethal(lv, px, py)) {
                            killStageHero()
                            break
                        }
                        val nx = px + dv[0]
                        val ny = py + dv[1]
                        if (nx !in 0 until lv.width || ny !in 0 until lv.height || lv.walls[ny][nx] != 0) {
                            moving = false
                            slideFrac = 0f
                            if (queuedDir != -1) {
                                val q = queuedDir
                                queuedDir = -1
                                tryStartStageSlide(q)
                            }
                        }
                    }
                } else {
                    if (isStageSpikeLethal(lv, px, py)) {
                        killStageHero()
                    }
                }
            }
            3 -> {
                stateTimerMs -= dtMs
                if (stateTimerMs <= 0f) {
                    startStage(curStageIdx)
                    return
                }
            }
            6 -> {
                stateTimerMs -= dtMs
                if (stateTimerMs <= 0f) {
                    st = 7
                    onStageCompleted()
                }
            }
        }
        emitMainPacket()
    }

    private fun isStageSpikeLethal(lv: LevelData, x: Int, y: Int): Boolean {
        if (lv.items[y][x] != 3) return false
        val gid = max(0, lv.spikeGroups[y][x])
        val tm = (clkMs + gid * 430) % 1700
        return tm < 600
    }

    private fun killStageHero() {
        moving = false
        slideFrac = 0f
        st = 3
        stateTimerMs = 800f
    }

    private fun collectStageCell(lv: LevelData, x: Int, y: Int) {
        when (lv.items[y][x]) {
            1 -> {
                lv.items[y][x] = 0
                dotsCollected++
                save = save.copy(bestStageScore = save.bestStageScore + 5)
            }
            2 -> {
                lv.items[y][x] = 0
                stageCoins = (stageCoins + 1).coerceAtMost(255)
                save = save.copy(
                    walletCoins = save.walletCoins + 1,
                    bestStageScore = save.bestStageScore + 20
                )
            }
            4 -> {
                lv.items[y][x] = 0
                stageStarsEarned = (stageStarsEarned + 1).coerceAtMost(3)
                save = save.copy(bestStageScore = save.bestStageScore + 100)
            }
            5 -> {
                moving = false
                slideFrac = 0f
                st = 6
                stateTimerMs = 700f
            }
        }
    }

    private fun onStageCompleted() {
        val earned = max(1, stageStarsEarned).coerceIn(1, 3)
        val prevStars = save.stageStars[curStageIdx]
        val newStarsArr = save.stageStars.clone()
        newStarsArr[curStageIdx] = max(prevStars, earned)
        val bonusCoins = if (earned == 3 && prevStars < 3) 50 else 0
        val nextUnl = max(save.unlockedStages, (curStageIdx + 2).coerceAtMost(45))
        save = save.copy(
            unlockedStages = nextUnl,
            walletCoins = save.walletCoins + bonusCoins,
            stageStars = newStarsArr
        )
        clearSel = if (curStageIdx + 1 < 45) 1 else 0
        onPersistSave(save)
    }

    private fun tickEndless(dtMs: Float) {
        if (st == 1) {
            if (freezeMs > 0f) freezeMs = (freezeMs - dtMs).coerceAtLeast(0f)
            if (magnetMs > 0f) magnetMs = (magnetMs - dtMs).coerceAtLeast(0f)
            if (invulnMs > 0f) invulnMs = (invulnMs - dtMs).coerceAtLeast(0f)

            val curRow = endlessRows[py]
            val stepMs = if (curRow != null && (curRow.stageByte and 128) != 0) 26f else 50f

            if (moving) {
                slideFrac += dtMs / stepMs
                val dv = LevelsData.DXY[slideDir]
                while (slideFrac >= 1f && moving && st == 1) {
                    slideFrac -= 1f
                    px += dv[0]
                    py += dv[1]
                    if (py > endlessPyMax) {
                        endlessScore += (py - endlessPyMax) * 2
                        endlessPyMax = py
                    }
                    val newStage = 1 + py / 36
                    if (newStage > endlessStage) {
                        endlessStage = newStage
                        endlessScore += 25
                    }
                    ensureEndlessGeneratedUpTo(py + 35)
                    collectEndlessCell(px, py)
                    if (isEndlessSpikeLethal(px, py)) {
                        onEndlessHazardHit()
                        if (st != 1) break
                    }
                    val nx = px + dv[0]
                    val ny = py + dv[1]
                    val nextRow = endlessRows[ny]
                    if (nx !in 0..20 || ny < 1 || nextRow == null || nextRow.w[nx] != 0) {
                        moving = false
                        slideFrac = 0f
                        if (queuedDir != -1) {
                            val q = queuedDir
                            queuedDir = -1
                            tryStartEndlessSlide(q)
                        }
                    }
                }
            } else if (isEndlessSpikeLethal(px, py)) {
                onEndlessHazardHit()
            }

            // Magnet pulls nearby dots/coins
            if (magnetMs > 0f && st == 1) {
                for (dy in -3..3) {
                    for (dx in -3..3) {
                        if (dx * dx + dy * dy > 10) continue
                        val r = endlessRows[py + dy] ?: continue
                        val rx = px + dx
                        if (rx in 0..20 && (r.it[rx] == 1 || r.it[rx] == 2)) {
                            if (r.it[rx] == 2) {
                                endlessCoins = (endlessCoins + 1).coerceAtMost(255)
                                save = save.copy(walletCoins = save.walletCoins + 1)
                                endlessScore += 10
                            } else {
                                endlessScore += 2
                            }
                            r.it[rx] = 0
                            r.dirty = true
                        }
                    }
                }
            }

            // Rising tide
            if (freezeMs <= 0f && st == 1) {
                val tideSpeed = 1.25f + endlessStage * 0.14f
                tideY += tideSpeed * (dtMs / 1000f)
                if (py - tideY < -14f) tideY = py - 14f
                if (tideY >= py - 0.15f) {
                    if (hasShield) {
                        hasShield = false
                        invulnMs = 1200f
                        tideY = py - 5.5f
                    } else {
                        triggerEndlessDeath()
                    }
                }
            }
        } else if (st == 3) {
            stateTimerMs -= dtMs
            if (stateTimerMs <= 0f) {
                st = 4
            }
        }

        emitDirtyEndlessRows(py)
        emitEndlessStatePacket()
        emitMainPacket()
    }

    private fun isEndlessSpikeLethal(x: Int, y: Int): Boolean {
        if (freezeMs > 0f || invulnMs > 0f) return false
        val r = endlessRows[y] ?: return false
        if (r.it[x] != 3) return false
        val tm = (clkMs + r.gid * 430) % 1700
        return tm < 600
    }

    private fun onEndlessHazardHit() {
        if (invulnMs > 0f) return
        if (hasShield) {
            hasShield = false
            invulnMs = 1200f
        } else {
            triggerEndlessDeath()
        }
    }

    private fun triggerEndlessDeath() {
        moving = false
        slideFrac = 0f
        st = 3
        stateTimerMs = 650f
        if (endlessScore > save.bestEndless) {
            endlessNewBest = true
            save = save.copy(bestEndless = endlessScore)
        }
        onPersistSave(save)
    }

    private fun collectEndlessCell(x: Int, y: Int) {
        val r = endlessRows[y] ?: return
        when (r.it[x]) {
            1 -> {
                r.it[x] = 0
                r.dirty = true
                endlessScore += 2
            }
            2 -> {
                r.it[x] = 0
                r.dirty = true
                endlessCoins = (endlessCoins + 1).coerceAtMost(255)
                endlessScore += 10
                save = save.copy(walletCoins = save.walletCoins + 1)
            }
            6 -> { // Freeze
                r.it[x] = 0
                r.dirty = true
                freezeMs = 6000f
                endlessScore += 25
            }
            7 -> { // Magnet
                r.it[x] = 0
                r.dirty = true
                magnetMs = 8000f
                endlessScore += 25
            }
            8 -> { // Shield
                r.it[x] = 0
                r.dirty = true
                hasShield = true
                endlessScore += 25
            }
        }
    }

    private fun emitDirtyEndlessRows(centerY: Int) {
        val lo = max(0, centerY - 18)
        val hi = centerY + 24
        for (y in lo..hi) {
            val r = endlessRows[y] ?: continue
            if (!r.dirty) continue
            r.dirty = false
            val pkt = ByteArray(19)
            pkt[0] = 82 // 'R'
            PacketByteWriter.putU16LE(pkt, 1, y)
            pkt[3] = r.stageByte.toByte()
            pkt[4] = r.gid.toByte()
            var wallMask = 0
            for (x in 0 until 21) {
                if (r.w[x] != 0) wallMask = wallMask or (1 shl x)
                val v = r.it[x] and 15
                val byteIdx = 8 + (x shr 1)
                val shift = if ((x and 1) != 0) 0 else 4
                pkt[byteIdx] = (pkt[byteIdx].toInt() or (v shl shift)).toByte()
            }
            PacketByteWriter.putU24LE(pkt, 5, wallMask)
            emitPacket(pkt)
        }
    }

    private fun emitEndlessStatePacket() {
        val pkt = ByteArray(20)
        pkt[0] = 69 // 'E'
        pkt[1] = 0
        var ef = 0
        if (moving) ef = ef or 1
        ef = ef or ((slideDir and 3) shl 1)
        if (lookLeft) ef = ef or 8
        if (hasShield) ef = ef or 16
        if (freezeMs > 0f) ef = ef or 32
        if (magnetMs > 0f) ef = ef or 64
        if (invulnMs > 0f) ef = ef or 128
        pkt[2] = ef.toByte()
        pkt[3] = endlessRunId.toByte()
        pkt[4] = px.coerceIn(0, 20).toByte()
        PacketByteWriter.putU16LE(pkt, 5, py.coerceAtLeast(0))
        pkt[7] = (slideFrac * 255f).toInt().coerceIn(0, 255).toByte()
        val gap = ((py - tideY) * 8f).toInt().coerceIn(0, 255)
        pkt[8] = gap.toByte()
        pkt[9] = endlessStage.coerceIn(1, 127).toByte()
        pkt[10] = endlessCoins.coerceIn(0, 255).toByte()
        pkt[11] = (freezeMs / 100f).toInt().coerceIn(0, 60).toByte()
        pkt[12] = (magnetMs / 100f).toInt().coerceIn(0, 80).toByte()
        PacketByteWriter.putU32LE(pkt, 13, endlessScore.toLong())
        PacketByteWriter.putU16LE(pkt, 17, clkMs)
        pkt[19] = save.equippedMask.coerceIn(0, 14).toByte()
        emitPacket(pkt)
    }

    private fun saveBestScores() {
        val newJet = max(save.bestJet, jetEngine.best)
        val newDot = max(save.bestDot, dotEngine.best)
        val newBlocks = max(save.bestBlocks, blocksEngine.best)
        if (newJet != save.bestJet || newDot != save.bestDot || newBlocks != save.bestBlocks) {
            save = save.copy(
                bestJet = newJet,
                bestDot = newDot,
                bestBlocks = newBlocks
            )
            onPersistSave(save)
        }
    }

    private fun buildMenuNPacket(): ByteArray {
        val pkt = ByteArray(14)
        pkt[0] = 78 // 'N'
        pkt[1] = st.toByte()
        pkt[2] = menuSel.toByte()
        pkt[3] = save.equippedMask.coerceIn(0, 14).toByte()
        val hasContinue = save.unlockedStages > 1 || save.stageStars.any { it > 0 }
        var fl = 0
        if (hasContinue) fl = fl or 1
        if (save.musicOn) fl = fl or 2
        if (save.sfxOn) fl = fl or 4
        if (confirmNewErase) fl = fl or 8
        if (confirmReset) fl = fl or 16
        if (erasedDone) fl = fl or 32
        pkt[4] = fl.toByte()
        pkt[5] = save.unlockedStages.coerceIn(1, 45).toByte()
        PacketByteWriter.putU16LE(pkt, 6, save.ownedMasksBits or 1)
        PacketByteWriter.putU32LE(pkt, 8, save.walletCoins.toLong())
        PacketByteWriter.putU16LE(pkt, 12, save.stageStars.sum())
        return pkt
    }

    private fun emitFullMenuSync() {
        emitPacket(buildMenuNPacket())

        val nSmall = ByteArray(14)
        nSmall[0] = 110 // 'n'
        nSmall[1] = st.toByte()
        if (st == 11) {
            PacketByteWriter.putU32LE(nSmall, 2, max(save.bestJet, jetEngine.best).toLong())
            PacketByteWriter.putU32LE(nSmall, 6, max(save.bestDot, dotEngine.best).toLong())
            PacketByteWriter.putU32LE(nSmall, 10, max(save.bestBlocks, blocksEngine.best).toLong())
        } else {
            PacketByteWriter.putU32LE(nSmall, 2, save.bestEndless.toLong())
            PacketByteWriter.putU32LE(nSmall, 6, save.bestStageScore.toLong())
            PacketByteWriter.putU32LE(nSmall, 10, 0L)
        }
        emitPacket(nSmall)

        val lPkt = ByteArray(13)
        lPkt[0] = 76 // 'L'
        for (i in 0 until 45) {
            val stars = save.stageStars[i].coerceIn(0, 3)
            val bIdx = 1 + (i shr 2)
            val shift = (i and 3) * 2
            lPkt[bIdx] = (lPkt[bIdx].toInt() or (stars shl shift)).toByte()
        }
        emitPacket(lPkt)

        emitMainPacket()
    }

    private fun emitMainPacket() {
        val pkt = ByteArray(18)
        pkt[0] = 77 // 'M'
        pkt[1] = st.toByte()
        var flags = 0
        if (inLevel) flags = flags or 1
        if (moving) flags = flags or 2
        flags = flags or ((slideDir and 3) shl 2)
        if (lookLeft) flags = flags or 16
        pkt[2] = flags.toByte()
        pkt[3] = curStageIdx.coerceIn(0, 44).toByte()
        pkt[4] = runCounter.toByte()
        pkt[5] = px.coerceIn(0, 255).toByte()
        pkt[6] = min(255, py.coerceAtLeast(0)).toByte()
        pkt[7] = (slideFrac * 255f).toInt().coerceIn(0, 255).toByte()
        pkt[8] = (((save.equippedMask and 15) shl 4) or (stageStarsEarned and 3)).toByte()
        pkt[9] = stageCoins.coerceIn(0, 255).toByte()
        PacketByteWriter.putU16LE(pkt, 10, clkMs)

        var extByte = (pauseSel and 3) or ((clearSel and 1) shl 2) or ((faceDir and 3) shl 3)
        if (!inLevel && endlessNewBest) extByte = extByte or 32
        pkt[12] = extByte.toByte()
        val dotsRatio = if (dotsTotal > 0) (dotsCollected.toFloat() / dotsTotal).coerceIn(0f, 1f) else 1f
        pkt[13] = (dotsRatio * 255f).toInt().coerceIn(0, 255).toByte()
        val bestVal = if (!inLevel) save.bestEndless.toLong() else save.bestStageScore.toLong()
        PacketByteWriter.putU32LE(pkt, 14, bestVal)
        emitPacket(pkt)
    }
}
