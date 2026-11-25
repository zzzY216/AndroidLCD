package com.loostone.olcd.data.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 协议层单元测试。
 *
 * 核心目的：确保 Android 端生成的帧与 ESP32 / STM32 端**字节级一致**。
 * 只要这里全绿，就能确定协议层不会成为通讯失败的原因。
 */
class ProtocolTest {

    /** CRC16 标准基准：crc16("123456789") == 0x29B1 */
    @Test
    fun crc16_knownValue() {
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x29B1, Protocol.crc16(data))
    }

    /**
     * PING 帧必须与 ESP32 / STM32 端文档给出的值完全一致。
     *
     * 这个值已在两端实测验证过：
     * ```
     * A5 5A 01 00 00 00 74 F2
     * ```
     */
    @Test
    fun buildFrame_ping_matchesDocumentedBytes() {
        val expected = byteArrayOf(
            0xA5.toByte(), 0x5A.toByte(),
            0x01, 0x00,
            0x00, 0x00,
            0x74.toByte(), 0xF2.toByte()
        )
        val actual = Protocol.buildFrame(Protocol.Cmd.PING, 0)
        assertArrayEquals(expected, actual)
    }

    /** IMAGE_BEGIN 的负载布局：w(2) h(2) x(2) y(2) fmt(1)，小端 */
    @Test
    fun buildImageBegin_payloadLayout() {
        val frame = Protocol.buildImageBegin(seq = 1, width = 120, height = 120)
        // 帧头 6 + 负载 9 + CRC 2 = 17
        assertEquals(17, frame.size)
        assertEquals(Protocol.Cmd.IMAGE_BEGIN.toByte(), frame[2])

        // 宽 120 = 0x0078 -> 小端 78 00
        assertEquals(0x78.toByte(), frame[6])
        assertEquals(0x00.toByte(), frame[7])
        // 高 120 同上
        assertEquals(0x78.toByte(), frame[8])
        assertEquals(0x00.toByte(), frame[9])
        // x=0, y=0
        assertEquals(0x00.toByte(), frame[10])
        assertEquals(0x00.toByte(), frame[12])
        // format = RGB565
        assertEquals(0x00.toByte(), frame[14])
    }

    /** 帧的 CRC 必须覆盖 CMD..PAYLOAD 整段 */
    @Test
    fun crcCoversCommandToPayload() {
        val payload = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        val frame = Protocol.buildFrame(Protocol.Cmd.IMAGE_DATA, 0x42, payload)

        val expected = Protocol.crc16(frame, offset = 2, length = 4 + payload.size)
        assertEquals(
            (expected and 0xFF).toByte(),
            frame[frame.size - 2]
        )
        assertEquals(
            ((expected shr 8) and 0xFF).toByte(),
            frame[frame.size - 1]
        )
    }

    /** 零负载帧长度 = 8 */
    @Test
    fun emptyPayloadFrame() {
        val frame = Protocol.buildFrame(Protocol.Cmd.PLAY, 0)
        assertEquals(8, frame.size)
    }

    /** 单字节参数便捷函数 */
    @Test
    fun buildU8() {
        val frame = Protocol.buildU8(Protocol.Cmd.SET_SCALE, 5, 2)
        assertEquals(9, frame.size)
        assertEquals(2.toByte(), frame[6])
    }

    /** 双字节参数小端序 */
    @Test
    fun buildU16_littleEndian() {
        // 0xF800 = 红色，小端应为 00 F8
        val frame = Protocol.buildU16(Protocol.Cmd.CLEAR, 6, 0xF800)
        assertEquals(0x00.toByte(), frame[6])
        assertEquals(0xF8.toByte(), frame[7])
    }

    /** SET_POS 小端序 */
    @Test
    fun buildSetPos() {
        val frame = Protocol.buildSetPos(seq = 7, x = 0x1234, y = 0x5678)
        assertEquals(0x34.toByte(), frame[6])
        assertEquals(0x12.toByte(), frame[7])
        assertEquals(0x78.toByte(), frame[8])
        assertEquals(0x56.toByte(), frame[9])
    }

    /** 超长负载应抛异常 */
    @Test(expected = IllegalArgumentException::class)
    fun payloadTooLong_throws() {
        Protocol.buildFrame(
            Protocol.Cmd.IMAGE_DATA, 0,
            ByteArray(Protocol.MAX_PAYLOAD + 1)
        )
    }

    /** 序号越界应抛异常 */
    @Test(expected = IllegalArgumentException::class)
    fun seqOutOfRange_throws() {
        Protocol.buildFrame(Protocol.Cmd.PING, 256)
    }

    /** 帧头必须固定 */
    @Test
    fun syncBytesAreFixed() {
        val frame = Protocol.buildFrame(Protocol.Cmd.PING, 0)
        assertEquals(Protocol.SYNC0, frame[0])
        assertEquals(Protocol.SYNC1, frame[1])
    }

    /** 最大负载帧 */
    @Test
    fun maxPayloadFrame() {
        val frame = Protocol.buildFrame(
            Protocol.Cmd.IMAGE_DATA, 0,
            ByteArray(Protocol.MAX_PAYLOAD)
        )
        assertEquals(
            Protocol.HEADER_SIZE + Protocol.MAX_PAYLOAD + Protocol.TAIL_SIZE,
            frame.size
        )
    }
}
