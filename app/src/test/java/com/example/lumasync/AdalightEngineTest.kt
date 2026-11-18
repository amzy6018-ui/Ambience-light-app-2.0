package com.example.lumasync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class AdalightEngineTest {

    @Test
    fun adalightHeader_matchesProtocolFor108Leds() {
        val packet = AdalightProtocol.buildBlackPacket(108)
        assertEquals(6 + 108 * 3, packet.size)
        assertEquals('A'.code.toByte(), packet[0])
        assertEquals('d'.code.toByte(), packet[1])
        assertEquals('a'.code.toByte(), packet[2])
        // 108 - 1 = 107 -> hi = 0, lo = 107, chk = 0 xor 107 xor 0x55
        assertEquals(0.toByte(), packet[3])
        assertEquals(107.toByte(), packet[4])
        assertEquals((0 xor 107 xor 0x55).toByte(), packet[5])
        for (i in 6 until packet.size) {
            assertEquals(0.toByte(), packet[i])
        }
    }

    @Test
    fun adalightHeader_matchesProtocolAbove256Leds() {
        val out = ByteArray(6)
        AdalightProtocol.writeHeader(300, out)
        // 300 - 1 = 299 -> hi = 1, lo = 43
        assertEquals(1.toByte(), out[3])
        assertEquals(43.toByte(), out[4])
        assertEquals((1 xor 43 xor 0x55).toByte(), out[5])
    }

    @Test
    fun buildLedRegions_followsClockwiseFromBottomLeftOrder() {
        val regions = AdalightProtocol.buildLedRegions(
            width = 128,
            height = 72,
            dx = 12,
            dy = 8,
            leftCount = 27,
            topCount = 27,
            rightCount = 27,
            bottomCount = 27
        )
        assertEquals(108, regions.size)

        // First LED: bottom-left on left edge
        val firstLeft = regions[0]
        assertEquals(0, firstLeft.left)
        assertEquals(12, firstLeft.right)
        assertEquals(72, firstLeft.bottom)

        // Last left LED (index 26): top-left on left edge
        val lastLeft = regions[26]
        assertEquals(0, lastLeft.top)

        // First top LED (index 27): top-left on top edge
        val firstTop = regions[27]
        assertEquals(0, firstTop.left)
        assertEquals(0, firstTop.top)
        assertEquals(8, firstTop.bottom)

        // First right LED (index 54): top-right on right edge
        val firstRight = regions[54]
        assertEquals(116, firstRight.left)
        assertEquals(128, firstRight.right)
        assertEquals(0, firstRight.top)

        // First bottom LED (index 81): bottom-right on bottom edge
        val firstBottom = regions[81]
        assertEquals(128, firstBottom.right)
        assertEquals(64, firstBottom.top)
        assertEquals(72, firstBottom.bottom)

        // Last bottom LED (index 107): bottom-left on bottom edge
        val lastBottom = regions[107]
        assertEquals(0, lastBottom.left)
    }

    @Test
    fun buildLedRegions_supportsZeroCountSides() {
        val regions = AdalightProtocol.buildLedRegions(
            width = 128,
            height = 72,
            dx = 12,
            dy = 8,
            leftCount = 10,
            topCount = 20,
            rightCount = 10,
            bottomCount = 0
        )
        assertEquals(40, regions.size)
    }

    @Test
    fun frameProcessor_respectsStridesSmoothingAndLetterboxSkip() {
        val width = 8
        val height = 8
        val pixelStride = 4
        val rowPadding = 4
        val rowStride = width * pixelStride + rowPadding
        val buffer = ByteBuffer.allocateDirect(height * rowStride)

        // Fill top 2 rows as black letterbox (0,0,0), rest of top edge (rows 2..3) as red (200, 20, 40)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val off = y * rowStride + x * pixelStride
                if (y < 2) {
                    buffer.put(off, 0.toByte())
                    buffer.put(off + 1, 0.toByte())
                    buffer.put(off + 2, 0.toByte())
                    buffer.put(off + 3, 255.toByte())
                } else {
                    buffer.put(off, 200.toByte())
                    buffer.put(off + 1, 20.toByte())
                    buffer.put(off + 2, 40.toByte())
                    buffer.put(off + 3, 255.toByte())
                }
            }
        }

        val config = LumaSyncConfig(
            leftCount = 0,
            topCount = 1,
            rightCount = 0,
            bottomCount = 0,
            smoothing = 0.5f,
            brightnessCap = 255,
            gammaEnabled = false,
            saturationBoost = 1.0f,
            skipLetterbox = true
        )
        val processor = FrameProcessor(
            config = config,
            frameWidth = width,
            frameHeight = height,
            dx = 2,
            dy = 4
        )

        val packet = processor.processRgbaFrame(buffer, rowStride, pixelStride)
        val r = packet[6].toInt() and 0xFF
        val g = packet[7].toInt() and 0xFF
        val b = packet[8].toInt() and 0xFF

        // With prev = 0 and smoothing = 0.5, skipping the black letterbox rows gives ~200 * 0.5 = 100
        assertEquals(100, r)
        assertEquals(10, g)
        assertEquals(20, b)

        // Second identical frame converges toward 150
        val packet2 = processor.processRgbaFrame(buffer, rowStride, pixelStride)
        val r2 = packet2[6].toInt() and 0xFF
        assertTrue(r2 in 149..151)
    }
}
