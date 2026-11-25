package com.loostone.olcd.data.protocol

/**
 * 与 ESP32 / STM32 完全一致的串口帧协议。
 *
 * 帧格式（所有多字节字段为小端序）：
 * ```
 * +-------+-------+-------+-------+--------+--------+---------+-------+
 * | SYNC0 | SYNC1 |  CMD  |  SEQ  | LEN_L  | LEN_H  | PAYLOAD | CRC16 |
 * | 0xA5  | 0x5A  | 1字节 | 1字节 |        |        | N 字节  | 2字节 |
 * +-------+-------+-------+-------+--------+--------+---------+-------+
 * ```
 *
 * CRC16 = CRC-16/CCITT-FALSE，覆盖 CMD + SEQ + LEN(2) + PAYLOAD
 *
 * **重要**：本文件必须与以下两处保持同步，任何改动都要三边同时改：
 * - ESP32:  `D:\esp32\MyProject\OLCD\main\proto.c`
 * - STM32:  `D:\stm32\MyProject\Hardware\proto.c`
 *
 * 验证基准（单元测试会检查）：
 * - `crc16("123456789") == 0x29B1`
 * - `buildFrame(PING, 0, empty) == A5 5A 01 00 00 00 74 F2`
 */
object Protocol {

    const val SYNC0: Byte = 0xA5.toByte()
    const val SYNC1: Byte = 0x5A.toByte()

    /** 单帧最大负载（与两端一致） */
    const val MAX_PAYLOAD = 1024

    /** 帧头长度：SYNC0 + SYNC1 + CMD + SEQ + LEN(2) */
    const val HEADER_SIZE = 6

    /** 帧尾长度：CRC16 */
    const val TAIL_SIZE = 2

    /** 命令码，必须与两端一致 */
    object Cmd {
        const val PING = 0x01
        const val PONG = 0x02

        const val IMAGE_BEGIN = 0x10
        const val IMAGE_DATA = 0x11
        const val IMAGE_END = 0x12

        const val PLAY = 0x20
        const val PAUSE = 0x21
        const val STOP = 0x22

        const val SET_SCALE = 0x30
        const val SET_POS = 0x31
        const val SET_BRIGHT = 0x32
        const val CLEAR = 0x33

        const val ACK = 0x7E
        const val NAK = 0x7F
    }

    /** 错误码 */
    object Err {
        const val NONE = 0x00
        const val CRC = 0x01
        const val LEN = 0x02
        const val CMD = 0x03
        const val PARAM = 0x04
        const val STATE = 0x05
        const val TIMEOUT = 0x06
        const val OVERFLOW = 0x07
    }

    /**
     * 计算 CRC-16/CCITT-FALSE。
     *
     * 参数：多项式 0x1021，初值 0xFFFF，输入/输出不反转，结果不异或。
     */
    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /**
     * 构造一帧。
     *
     * @param cmd     命令码
     * @param seq     序号（0~255 循环）
     * @param payload 负载
     * @return 完整帧字节数组
     */
    fun buildFrame(cmd: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= MAX_PAYLOAD) {
            "payload 超长: ${payload.size} > $MAX_PAYLOAD"
        }
        require(seq in 0..255) { "seq 必须在 0~255: $seq" }

        val len = payload.size
        val frame = ByteArray(HEADER_SIZE + len + TAIL_SIZE)

        frame[0] = SYNC0
        frame[1] = SYNC1
        frame[2] = cmd.toByte()
        frame[3] = seq.toByte()
        frame[4] = (len and 0xFF).toByte()
        frame[5] = ((len shr 8) and 0xFF).toByte()

        payload.copyInto(frame, HEADER_SIZE)

        // CRC 覆盖 CMD 到 PAYLOAD 末尾
        val crc = crc16(frame, offset = 2, length = 4 + len)
        frame[HEADER_SIZE + len] = (crc and 0xFF).toByte()
        frame[HEADER_SIZE + len + 1] = ((crc shr 8) and 0xFF).toByte()

        return frame
    }

    /** 构造 IMAGE_BEGIN 帧 */
    fun buildImageBegin(
        seq: Int,
        width: Int,
        height: Int,
        x: Int = 0,
        y: Int = 0,
        format: Int = 0, // 0 = RGB565
    ): ByteArray {
        val p = ByteArray(9)
        p[0] = (width and 0xFF).toByte()
        p[1] = ((width shr 8) and 0xFF).toByte()
        p[2] = (height and 0xFF).toByte()
        p[3] = ((height shr 8) and 0xFF).toByte()
        p[4] = (x and 0xFF).toByte()
        p[5] = ((x shr 8) and 0xFF).toByte()
        p[6] = (y and 0xFF).toByte()
        p[7] = ((y shr 8) and 0xFF).toByte()
        p[8] = format.toByte()
        return buildFrame(Cmd.IMAGE_BEGIN, seq, p)
    }

    /** 构造单字节参数帧（SET_SCALE / SET_BRIGHT） */
    fun buildU8(cmd: Int, seq: Int, value: Int): ByteArray =
        buildFrame(cmd, seq, byteArrayOf(value.toByte()))

    /** 构造双字节参数帧（CLEAR 颜色） */
    fun buildU16(cmd: Int, seq: Int, value: Int): ByteArray =
        buildFrame(
            cmd, seq,
            byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
        )

    /** 构造 SET_POS 帧 */
    fun buildSetPos(seq: Int, x: Int, y: Int): ByteArray {
        val p = ByteArray(4)
        p[0] = (x and 0xFF).toByte()
        p[1] = ((x shr 8) and 0xFF).toByte()
        p[2] = (y and 0xFF).toByte()
        p[3] = ((y shr 8) and 0xFF).toByte()
        return buildFrame(Cmd.SET_POS, seq, p)
    }
}
