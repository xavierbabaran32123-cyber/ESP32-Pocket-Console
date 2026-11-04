package com.example.qr

import kotlin.math.abs

class QrMatrix(
    val size: Int,
    private val modules: Array<BooleanArray>
) {
    fun get(x: Int, y: Int): Boolean = modules[y][x]

    fun toSvg(quietZone: Int = 4, modulePx: Int = 8): String {
        val totalModules = size + quietZone * 2
        val viewPx = totalModules * modulePx
        val sb = StringBuilder(4096)
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $totalModules $totalModules" width="$viewPx" height="$viewPx" shape-rendering="crispEdges">""")
        sb.append("\n")
        sb.append("""  <rect width="$totalModules" height="$totalModules" rx="2" fill="#FFFFFF"/>""")
        sb.append("\n  <path fill=\"#080814\" d=\"")
        for (y in 0 until size) {
            var x = 0
            while (x < size) {
                if (modules[y][x]) {
                    var runLen = 1
                    while (x + runLen < size && modules[y][x + runLen]) {
                        runLen++
                    }
                    val sx = x + quietZone
                    val sy = y + quietZone
                    sb.append("M${sx},${sy}h${runLen}v1h-${runLen}z")
                    x += runLen
                } else {
                    x++
                }
            }
        }
        sb.append("\"/>\n</svg>\n")
        return sb.toString()
    }
}

object QrCodeEncoder {

    const val APK_DOWNLOAD_URL =
        "https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/releases/latest/download/MaskMaze.apk"

    const val WINDOWS_DOWNLOAD_URL =
        "https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/releases/latest/download/MaskMaze-Windows.exe"

    private data class VersionSpec(
        val version: Int,
        val totalDataCodewords: Int,
        val ecCodewordsPerBlock: Int,
        val numBlocks: Int,
        val alignCenters: IntArray
    )

    // ISO/IEC 18004 Level L specs for Versions 1..6 (supports up to 134 UTF-8 bytes)
    private val VERSION_SPECS = arrayOf(
        VersionSpec(1, 19, 7, 1, intArrayOf()),
        VersionSpec(2, 34, 10, 1, intArrayOf(6, 18)),
        VersionSpec(3, 55, 15, 1, intArrayOf(6, 22)),
        VersionSpec(4, 80, 20, 1, intArrayOf(6, 26)),
        VersionSpec(5, 108, 26, 1, intArrayOf(6, 30)),
        VersionSpec(6, 136, 18, 2, intArrayOf(6, 34))
    )

    fun encodeByteMode(text: String): QrMatrix {
        val utf8 = text.encodeToByteArray()
        val spec = VERSION_SPECS.firstOrNull { utf8.size + 2 <= it.totalDataCodewords }
            ?: throw IllegalArgumentException("Payload too long (${utf8.size} bytes) for QR v1..v6")

        val dataCodewords = buildDataCodewords(utf8, spec.totalDataCodewords)
        val allCodewords = addEccAndInterleave(dataCodewords, spec)
        val size = spec.version * 4 + 17

        val baseModules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        drawFunctionPatterns(baseModules, isFunction, spec)
        drawCodewords(baseModules, isFunction, allCodewords, size)

        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        var bestGrid: Array<BooleanArray>? = null

        for (mask in 0..7) {
            val candidate = Array(size) { y -> baseModules[y].clone() }
            applyMask(candidate, isFunction, mask, size)
            drawFormatBits(candidate, isFunction, mask, size)
            val penalty = computePenaltyScore(candidate, size)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
                bestGrid = candidate
            }
        }

        // Ensure bestMask is recorded on chosen grid
        val finalGrid = bestGrid ?: baseModules
        drawFormatBits(finalGrid, isFunction, bestMask, size)
        return QrMatrix(size, finalGrid)
    }

    private fun buildDataCodewords(payload: ByteArray, capacityBytes: Int): ByteArray {
        val bits = ArrayList<Int>(capacityBytes * 8)
        fun appendBits(value: Int, len: Int) {
            for (i in len - 1 downTo 0) {
                bits.add((value ushr i) and 1)
            }
        }

        // Byte mode indicator = 0100 (4 bits), 8-bit character count for versions 1..9
        appendBits(0b0100, 4)
        appendBits(payload.size, 8)
        for (b in payload) {
            appendBits(b.toInt() and 0xFF, 8)
        }

        val capacityBits = capacityBytes * 8
        val terminatorLen = minOf(4, capacityBits - bits.size)
        appendBits(0, terminatorLen)

        while (bits.size % 8 != 0) {
            bits.add(0)
        }

        val padBytes = intArrayOf(0xEC, 0x11)
        var padIdx = 0
        while (bits.size < capacityBits) {
            appendBits(padBytes[padIdx and 1], 8)
            padIdx++
        }

        val out = ByteArray(capacityBytes)
        for (i in 0 until capacityBytes) {
            var v = 0
            for (b in 0..7) {
                v = (v shl 1) or bits[i * 8 + b]
            }
            out[i] = v.toByte()
        }
        return out
    }

    private fun addEccAndInterleave(data: ByteArray, spec: VersionSpec): ByteArray {
        val blockDataLen = spec.totalDataCodewords / spec.numBlocks
        val divisor = rsComputeDivisor(spec.ecCodewordsPerBlock)
        val dataBlocks = Array(spec.numBlocks) { b ->
            data.copyOfRange(b * blockDataLen, (b + 1) * blockDataLen)
        }
        val ecBlocks = Array(spec.numBlocks) { b ->
            rsComputeRemainder(dataBlocks[b], divisor)
        }

        val out = ByteArray(spec.totalDataCodewords + spec.ecCodewordsPerBlock * spec.numBlocks)
        var pos = 0
        for (i in 0 until blockDataLen) {
            for (b in 0 until spec.numBlocks) {
                out[pos++] = dataBlocks[b][i]
            }
        }
        for (i in 0 until spec.ecCodewordsPerBlock) {
            for (b in 0 until spec.numBlocks) {
                out[pos++] = ecBlocks[b][i]
            }
        }
        return out
    }

    private fun drawFunctionPatterns(
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
        spec: VersionSpec
    ) {
        val size = modules.size
        fun setFunc(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            isFunction[y][x] = true
        }

        // Timing patterns
        for (i in 0 until size) {
            setFunc(6, i, i % 2 == 0)
            setFunc(i, 6, i % 2 == 0)
        }

        // 3 Finder patterns + white separators
        fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until size && y in 0 until size) {
                        val dist = maxOf(abs(dx), abs(dy))
                        setFunc(x, y, dist != 2 && dist != 4)
                    }
                }
            }
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)

        // Alignment patterns
        val centers = spec.alignCenters
        for (cy in centers) {
            for (cx in centers) {
                // Skip if overlapping any of the 3 finder corners
                if ((cx == 6 && cy == 6) ||
                    (cx == 6 && cy == size - 7) ||
                    (cx == size - 7 && cy == 6)
                ) {
                    continue
                }
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val dist = maxOf(abs(dx), abs(dy))
                        setFunc(cx + dx, cy + dy, dist != 1)
                    }
                }
            }
        }

        // Reserve format bits + dark module
        drawFormatBits(modules, isFunction, mask = 0, size = size)
    }

    private fun drawFormatBits(
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
        mask: Int,
        size: Int
    ) {
        // Error Correction Level L format indicator = 0b01 (1)
        val data = (0b01 shl 3) or mask
        var rem = data
        repeat(10) {
            rem = (rem shl 1) xor (((rem ushr 9) and 1) * 0x537)
        }
        val bits = ((data shl 10) or rem) xor 0x5412

        fun bit(i: Int): Boolean = ((bits ushr i) and 1) != 0
        fun setFunc(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            isFunction[y][x] = true
        }

        // First copy around top-left finder
        for (i in 0..5) setFunc(8, i, bit(i))
        setFunc(8, 7, bit(6))
        setFunc(8, 8, bit(7))
        setFunc(7, 8, bit(8))
        for (i in 9..14) setFunc(14 - i, 8, bit(i))

        // Second copy along right and bottom-left edges
        for (i in 0..7) setFunc(size - 1 - i, 8, bit(i))
        for (i in 8..14) setFunc(8, size - 15 + i, bit(i))

        // Always-dark module
        setFunc(8, size - 8, true)
    }

    private fun drawCodewords(
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
        allCodewords: ByteArray,
        size: Int
    ) {
        val totalBits = allCodewords.size * 8
        var bitIdx = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x]) {
                        val dark = if (bitIdx < totalBits) {
                            val b = allCodewords[bitIdx ushr 3].toInt() and 0xFF
                            ((b ushr (7 - (bitIdx and 7))) and 1) != 0
                        } else {
                            false
                        }
                        modules[y][x] = dark
                        bitIdx++
                    }
                }
            }
            right -= 2
        }
    }

    private fun applyMask(
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
        mask: Int,
        size: Int
    ) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (!isFunction[y][x] && maskInvert(mask, x, y)) {
                    modules[y][x] = !modules[y][x]
                }
            }
        }
    }

    internal fun maskInvert(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (y / 2 + x / 3) % 2 == 0
        5 -> (x * y) % 2 + (x * y) % 3 == 0
        6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
        7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
        else -> false
    }

    private fun computePenaltyScore(modules: Array<BooleanArray>, size: Int): Int {
        var score = 0

        // N1: Adjacent runs of 5+ identical modules in rows and columns
        for (y in 0 until size) {
            var runColor = modules[y][0]
            var runLen = 1
            for (x in 1 until size) {
                if (modules[y][x] == runColor) {
                    runLen++
                    if (runLen == 5) score += 3 else if (runLen > 5) score += 1
                } else {
                    runColor = modules[y][x]
                    runLen = 1
                }
            }
        }
        for (x in 0 until size) {
            var runColor = modules[0][x]
            var runLen = 1
            for (y in 1 until size) {
                if (modules[y][x] == runColor) {
                    runLen++
                    if (runLen == 5) score += 3 else if (runLen > 5) score += 1
                } else {
                    runColor = modules[y][x]
                    runLen = 1
                }
            }
        }

        // N2: 2x2 blocks of identical color
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) {
                    score += 3
                }
            }
        }

        // N4: Dark module balance near 50%
        var darkCount = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (modules[y][x]) darkCount++
            }
        }
        val total = size * size
        val k = (abs(darkCount * 20 - total * 10) + total - 1) / total - 1
        if (k > 0) score += k * 10

        return score
    }

    internal fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private fun rsComputeDivisor(degree: Int): ByteArray {
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in 0 until degree) {
                result[j] = gfMultiply(result[j].toInt() and 0xFF, root).toByte()
                if (j + 1 < degree) {
                    result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
                }
            }
            root = gfMultiply(root, 0x02)
        }
        return result
    }

    private fun rsComputeRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
        val result = ByteArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() xor result[0].toInt()) and 0xFF
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in result.indices) {
                result[i] = (result[i].toInt() xor gfMultiply(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
        }
        return result
    }
}
