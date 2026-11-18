package com.example.lumasync

import java.nio.ByteBuffer
import kotlin.math.pow

data class SampleRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

object AdalightProtocol {
    const val HEADER_SIZE = 6
    private const val LETTERBOX_THRESHOLD = 16

    /**
     * Builds sampling rectangles in physical strip order:
     * 1. Bottom-left going UP the left edge
     * 2. Left-to-right across the top edge
     * 3. Top-to-bottom down the right edge
     * 4. Right-to-left along the bottom edge
     *
     * Any side with count == 0 is omitted.
     */
    fun buildLedRegions(
        width: Int,
        height: Int,
        dx: Int,
        dy: Int,
        leftCount: Int,
        topCount: Int,
        rightCount: Int,
        bottomCount: Int
    ): List<SampleRect> {
        val regions = ArrayList<SampleRect>(
            (leftCount + topCount + rightCount + bottomCount).coerceAtLeast(0)
        )
        val clampedDx = dx.coerceIn(1, width)
        val clampedDy = dy.coerceIn(1, height)

        // 1. Left side: bottom-left -> top-left
        if (leftCount > 0) {
            for (i in 0 until leftCount) {
                val yBottom = height - (i * height) / leftCount
                val yTop = height - ((i + 1) * height) / leftCount
                regions.add(
                    SampleRect(
                        left = 0,
                        top = yTop.coerceIn(0, height - 1),
                        right = clampedDx,
                        bottom = yBottom.coerceIn(yTop + 1, height)
                    )
                )
            }
        }

        // 2. Top side: top-left -> top-right
        if (topCount > 0) {
            for (i in 0 until topCount) {
                val xLeft = (i * width) / topCount
                val xRight = ((i + 1) * width) / topCount
                regions.add(
                    SampleRect(
                        left = xLeft.coerceIn(0, width - 1),
                        top = 0,
                        right = xRight.coerceIn(xLeft + 1, width),
                        bottom = clampedDy
                    )
                )
            }
        }

        // 3. Right side: top-right -> bottom-right
        if (rightCount > 0) {
            for (i in 0 until rightCount) {
                val yTop = (i * height) / rightCount
                val yBottom = ((i + 1) * height) / rightCount
                regions.add(
                    SampleRect(
                        left = (width - clampedDx).coerceIn(0, width - 1),
                        top = yTop.coerceIn(0, height - 1),
                        right = width,
                        bottom = yBottom.coerceIn(yTop + 1, height)
                    )
                )
            }
        }

        // 4. Bottom side: bottom-right -> bottom-left
        if (bottomCount > 0) {
            for (i in 0 until bottomCount) {
                val xRight = width - (i * width) / bottomCount
                val xLeft = width - ((i + 1) * width) / bottomCount
                regions.add(
                    SampleRect(
                        left = xLeft.coerceIn(0, width - 1),
                        top = (height - clampedDy).coerceIn(0, height - 1),
                        right = xRight.coerceIn(xLeft + 1, width),
                        bottom = height
                    )
                )
            }
        }

        return regions
    }

    fun writeHeader(ledCount: Int, out: ByteArray) {
        val countMinusOne = (ledCount - 1).coerceAtLeast(0)
        val hi = ((countMinusOne shr 8) and 0xFF).toByte()
        val lo = (countMinusOne and 0xFF).toByte()
        val chk = ((hi.toInt() and 0xFF) xor (lo.toInt() and 0xFF) xor 0x55).toByte()
        out[0] = 'A'.code.toByte()
        out[1] = 'd'.code.toByte()
        out[2] = 'a'.code.toByte()
        out[3] = hi
        out[4] = lo
        out[5] = chk
    }

    fun buildBlackPacket(ledCount: Int): ByteArray {
        val count = ledCount.coerceAtLeast(0)
        val packet = ByteArray(HEADER_SIZE + count * 3)
        writeHeader(count, packet)
        return packet
    }

    fun buildGammaTable(gamma: Double = 2.2): IntArray {
        return IntArray(256) { i ->
            ((i / 255.0).pow(gamma) * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }
    }

    internal fun isNearBlack(r: Int, g: Int, b: Int): Boolean {
        return maxOf(r, g, b) < LETTERBOX_THRESHOLD
    }
}

class FrameProcessor(
    private val config: LumaSyncConfig,
    private val frameWidth: Int = LumaSyncConfig.CAPTURE_WIDTH,
    private val frameHeight: Int = LumaSyncConfig.CAPTURE_HEIGHT,
    dx: Int = LumaSyncConfig.SAMPLE_DX,
    dy: Int = LumaSyncConfig.SAMPLE_DY
) {
    val regions: List<SampleRect> = AdalightProtocol.buildLedRegions(
        width = frameWidth,
        height = frameHeight,
        dx = dx,
        dy = dy,
        leftCount = config.leftCount,
        topCount = config.topCount,
        rightCount = config.rightCount,
        bottomCount = config.bottomCount
    )

    private val ledCount = regions.size
    private val prevR = FloatArray(ledCount)
    private val prevG = FloatArray(ledCount)
    private val prevB = FloatArray(ledCount)
    private val gammaTable = AdalightProtocol.buildGammaTable(2.2)
    private val packet = ByteArray(AdalightProtocol.HEADER_SIZE + ledCount * 3).also {
        AdalightProtocol.writeHeader(ledCount, it)
    }

    fun processRgbaFrame(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int
    ): ByteArray {
        val smooth = config.smoothing.coerceIn(0f, 0.95f)
        val oneMinusSmooth = 1f - smooth
        val brightnessScale = config.brightnessCap.coerceIn(1, 255) / 255f
        val satBoost = config.saturationBoost.coerceIn(1.0f, 2.0f)
        val applySat = satBoost > 1.01f
        val applyGamma = config.gammaEnabled
        val skipLetterbox = config.skipLetterbox

        for (i in 0 until ledCount) {
            val rect = regions[i]
            var allR = 0L
            var allG = 0L
            var allB = 0L
            var allCount = 0

            var litR = 0L
            var litG = 0L
            var litB = 0L
            var litCount = 0

            for (y in rect.top until rect.bottom) {
                val rowBase = y * rowStride
                for (x in rect.left until rect.right) {
                    val offset = rowBase + x * pixelStride
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF

                    allR += r
                    allG += g
                    allB += b
                    allCount++

                    if (skipLetterbox && !AdalightProtocol.isNearBlack(r, g, b)) {
                        litR += r
                        litG += g
                        litB += b
                        litCount++
                    }
                }
            }

            var rf: Float
            var gf: Float
            var bf: Float

            if (skipLetterbox && litCount > 0) {
                rf = litR.toFloat() / litCount
                gf = litG.toFloat() / litCount
                bf = litB.toFloat() / litCount
            } else if (allCount > 0) {
                rf = allR.toFloat() / allCount
                gf = allG.toFloat() / allCount
                bf = allB.toFloat() / allCount
            } else {
                rf = 0f
                gf = 0f
                bf = 0f
            }

            if (applySat) {
                val luma = 0.299f * rf + 0.587f * gf + 0.114f * bf
                rf = (luma + (rf - luma) * satBoost).coerceIn(0f, 255f)
                gf = (luma + (gf - luma) * satBoost).coerceIn(0f, 255f)
                bf = (luma + (bf - luma) * satBoost).coerceIn(0f, 255f)
            }

            if (applyGamma) {
                rf = gammaTable[rf.toInt().coerceIn(0, 255)].toFloat()
                gf = gammaTable[gf.toInt().coerceIn(0, 255)].toFloat()
                bf = gammaTable[bf.toInt().coerceIn(0, 255)].toFloat()
            }

            if (config.brightnessCap < 255) {
                rf *= brightnessScale
                gf *= brightnessScale
                bf *= brightnessScale
            }

            val outR = prevR[i] * smooth + rf * oneMinusSmooth
            val outG = prevG[i] * smooth + gf * oneMinusSmooth
            val outB = prevB[i] * smooth + bf * oneMinusSmooth

            prevR[i] = outR
            prevG[i] = outG
            prevB[i] = outB

            val base = AdalightProtocol.HEADER_SIZE + i * 3
            packet[base] = outR.toInt().coerceIn(0, 255).toByte()
            packet[base + 1] = outG.toInt().coerceIn(0, 255).toByte()
            packet[base + 2] = outB.toInt().coerceIn(0, 255).toByte()
        }

        return packet
    }
}
