package com.loostone.olcd.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RGB565 转换逻辑的单元测试。
 *
 * 验证重点：**字节序必须是「大端」**（高字节在前）。
 * 如果字节序错了，屏幕上所有颜色都会变成乱码（典型现象是红蓝互换或严重偏色）。
 *
 * 注意：这里不依赖 Android 的 Bitmap，纯粹测试 RGB565 打包算法，
 * 所以能在普通 JVM 单元测试里跑。
 */
class Rgb565Test {

    /**
     * 模拟 MediaConverter 里的打包逻辑（保持一致）。
     *
     * 因为真正的 `bitmapToFrame` 依赖 Bitmap（需要 Android 环境），
     * 这里复现同样的算术，验证算法本身正确。
     */
    private fun packRgb565(r: Int, g: Int, b: Int): ByteArray {
        val r5 = r shr 3
        val g6 = g shr 2
        val b5 = b shr 3
        val rgb565 = (r5 shl 11) or (g6 shl 5) or b5
        // 大端：高字节在前
        return byteArrayOf(
            ((rgb565 shr 8) and 0xFF).toByte(),
            (rgb565 and 0xFF).toByte(),
        )
    }

    /** 纯黑：0x0000 */
    @Test
    fun black() {
        val p = packRgb565(0, 0, 0)
        assertEquals(0x00.toByte(), p[0])
        assertEquals(0x00.toByte(), p[1])
    }

    /** 纯白：0xFFFF */
    @Test
    fun white() {
        val p = packRgb565(255, 255, 255)
        assertEquals(0xFF.toByte(), p[0])
        assertEquals(0xFF.toByte(), p[1])
    }

    /** 纯红：R=255, G=0, B=0 → 0xF800，大端应为 F8 00 */
    @Test
    fun red_bigEndian() {
        val p = packRgb565(255, 0, 0)
        assertEquals(0xF8.toByte(), p[0])
        assertEquals(0x00.toByte(), p[1])
    }

    /** 纯绿：R=0, G=255, B=0 → 0x07E0，大端应为 07 E0 */
    @Test
    fun green_bigEndian() {
        val p = packRgb565(0, 255, 0)
        assertEquals(0x07.toByte(), p[0])
        assertEquals(0xE0.toByte(), p[1])
    }

    /** 纯蓝：R=0, G=0, B=255 → 0x001F，大端应为 00 1F */
    @Test
    fun blue_bigEndian() {
        val p = packRgb565(0, 0, 255)
        assertEquals(0x00.toByte(), p[0])
        assertEquals(0x1F.toByte(), p[1])
    }

    /** 中灰：128,128,128 → 验证位宽压缩 */
    @Test
    fun gray_midValue() {
        val p = packRgb565(128, 128, 128)
        // r5 = 16, g6 = 32, b5 = 16
        // rgb565 = (16 << 11) | (32 << 5) | 16 = 0x8410
        assertEquals(0x84.toByte(), p[0])
        assertEquals(0x10.toByte(), p[1])
    }

    /**
     * 关键回归测试：确认「大端」而不是「小端」。
     *
     * 如果实现写成小端（低字节在前），红色会变成 00 F8，
     * STM32 端解析出的颜色就会错成蓝色系。
     */
    @Test
    fun byteOrderIsBigEndianNotLittleEndian() {
        val p = packRgb565(255, 0, 0) // 红色 0xF800
        // 大端：F8 在前
        assertEquals(
            "红色必须是大端序 F8 00（高字节在前）",
            0xF8.toByte(), p[0]
        )
        // 反证：不能是 00 F8
        assert(p[0] != 0x00.toByte()) {
            "字节序错了！实际是 ${p[0]}, ${p[1]}，应该是 F8, 00"
        }
    }

    /** 每个像素占 2 字节 */
    @Test
    fun eachPixelTakesTwoBytes() {
        val pixels = listOf(
            packRgb565(255, 0, 0),
            packRgb565(0, 255, 0),
            packRgb565(0, 0, 255),
        )
        assertEquals(3, pixels.size)
        pixels.forEach { assertEquals(2, it.size) }
        // 3 像素 = 6 字节
        assertEquals(6, pixels.sumOf { it.size })
    }

    /** 120×120 帧的数据量 = 28800 字节 */
    @Test
    fun frameSizeCalculation() {
        val size = MediaConverter.TARGET_SIZE
        val bytes = size * size * 2
        assertEquals("120×120 RGB565 应为 28800 字节", 28800, bytes)
    }
}
