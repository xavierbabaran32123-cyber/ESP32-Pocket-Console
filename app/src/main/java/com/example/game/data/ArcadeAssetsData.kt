package com.example.game.data

object ArcadeAssetsData {
    // 8x8 Star icon bitmap
    val STAR8 = intArrayOf(0x18, 0x18, 0xFF, 0x7E, 0x3C, 0x7E, 0x66, 0x00)

    // Power-up 8x8 bitmaps: 6=Freeze, 7=Magnet, 8=Shield
    val PW8 = mapOf(
        8 to intArrayOf(0x7E, 0xFF, 0xDB, 0xDB, 0xFF, 0x7E, 0x3C, 0x18),
        6 to intArrayOf(0x18, 0x5A, 0x3C, 0xFF, 0xFF, 0x3C, 0x5A, 0x18),
        7 to intArrayOf(0xC3, 0xC3, 0xC3, 0xC3, 0xC3, 0x66, 0x3C, 0x00)
    )

    val PW_COL = mapOf(
        8 to 0xFF3CE6FF.toInt(),
        6 to 0xFFFFFFFF.toInt(),
        7 to 0xFFFF3228.toInt()
    )

    // Dot Maze (15x17)
    const val DM_W = 15
    const val DM_H = 17
    val DM_MAP = arrayOf(
        "###############",
        "#o...........o#",
        "#.###.###.###.#",
        "#.............#",
        "#.###.#.#.###.#",
        "#.....#.#.....#",
        "#####.#.#.#####",
        "#.............#",
        "#.###.###.###.#",
        "#.............#",
        "#.#.#.#.#.#.#.#",
        "#.#...#.#...#.#",
        "#.#.#.#.#.#.#.#",
        "#.....#.#.....#",
        "#.#.........#.#",
        "#o...#####...o#",
        "###############"
    )

    // Direction code -> dx, dy (y grows downward in Dot Maze)
    val DM_D = arrayOf(
        intArrayOf(0, 0),
        intArrayOf(0, -1),
        intArrayOf(1, 0),
        intArrayOf(0, 1),
        intArrayOf(-1, 0)
    )

    val DM_COL = intArrayOf(
        0xFF78FF5A.toInt(),
        0xFFBE6EFF.toInt(),
        0xFFFF783C.toInt(),
        0xFFFFFFFF.toInt()
    )

    val DM_POWER_COORDS = arrayOf(
        intArrayOf(1, 1),
        intArrayOf(13, 1),
        intArrayOf(1, 15),
        intArrayOf(13, 15)
    )

    fun freshDots(): ByteArray {
        val a = ByteArray(DM_W * DM_H)
        for (y in 0 until DM_H) {
            for (x in 0 until DM_W) {
                a[y * DM_W + x] = if (DM_MAP[y][x] == '.') 1 else 0
            }
        }
        a[14 * DM_W + 7] = 0
        return a
    }

    // Blocks (Tetrominoes)
    val TB_CELLS = arrayOf(
        arrayOf(intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(2, 1), intArrayOf(3, 1)),
        arrayOf(intArrayOf(0, 0), intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1)),
        arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(2, 1)),
        arrayOf(intArrayOf(1, 0), intArrayOf(2, 0), intArrayOf(0, 1), intArrayOf(1, 1)),
        arrayOf(intArrayOf(0, 0), intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(2, 1)),
        arrayOf(intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(2, 1)),
        arrayOf(intArrayOf(2, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(2, 1))
    )

    val TB_BOX = intArrayOf(4, 2, 3, 3, 3, 3, 3)

    val TB_COL = intArrayOf(
        0xFF000000.toInt(),
        0xFF3CE6FF.toInt(),
        0xFFFFE628.toInt(),
        0xFFBE5AFF.toInt(),
        0xFF5AEB5A.toInt(),
        0xFFFF4646.toInt(),
        0xFF466EFF.toInt(),
        0xFFFF9628.toInt()
    )

    fun tbCells(type: Int, rot: Int): Array<IntArray> {
        val safeType = type.coerceIn(0, 6)
        val n = TB_BOX[safeType]
        val rCount = rot and 3
        return Array(4) { idx ->
            var x = TB_CELLS[safeType][idx][0]
            var y = TB_CELLS[safeType][idx][1]
            repeat(rCount) {
                val nx = n - 1 - y
                val ny = x
                x = nx
                y = ny
            }
            intArrayOf(x, y)
        }
    }

    // Deterministic procedural pixel bump hash for glowing dungeon walls (matches JS bumpHash/bumpAt)
    fun bumpHash(x: Int, y: Int): UInt {
        var h = ((x * 73856093) xor (y * 19349663)).toUInt()
        h = h xor (h shr 13)
        h *= 0x5bd1e995u
        h = h xor (h shr 15)
        return h
    }

    fun bumpAt(h: UInt, i: Int, side: Int): Boolean {
        val sum = h + (i * 40503).toUInt() + (side * 977).toUInt()
        val v = sum * 2654435761u
        return ((v shr 13) and 3u) == 0u
    }
}
