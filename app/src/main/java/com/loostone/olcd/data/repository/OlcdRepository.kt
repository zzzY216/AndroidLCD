package com.loostone.olcd.data.repository

import android.net.Uri
import android.util.Log
import com.loostone.olcd.data.ble.BleManager
import com.loostone.olcd.data.ble.BleState
import com.loostone.olcd.data.ble.BleUuids
import com.loostone.olcd.data.ble.canSend
import com.loostone.olcd.data.media.MediaConverter
import com.loostone.olcd.data.protocol.Protocol
import com.loostone.olcd.domain.model.FrameSet
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 传输进度。
 */
sealed class TransferState {
    data object Idle : TransferState()

    /** 正在转换媒体 */
    data class Converting(val progress: Int = 0) : TransferState()

    /** 正在传输第 index 帧（从 1 开始） */
    data class Sending(
        val frameIndex: Int,
        val frameTotal: Int,
        val bytesSent: Int,
        val bytesTotal: Int,
    ) : TransferState() {
        /** 整体进度 0.0~1.0 */
        val progress: Float
            get() {
                if (frameTotal <= 0) return 0f
                val framePart = (frameIndex - 1).toFloat() / frameTotal
                val bytePart = if (bytesTotal > 0) {
                    bytesSent.toFloat() / bytesTotal / frameTotal
                } else 0f
                return (framePart + bytePart).coerceIn(0f, 1f)
            }
    }

    data class Done(val frameCount: Int) : TransferState()

    data class Failed(val message: String) : TransferState()
}

/**
 * 核心业务仓库：把「媒体文件」变成「屏幕上看到的动画」。
 *
 * 完整流程：
 * ```
 * 1. convert     用户选的 GIF/视频 → 一组 RGB565 帧（120×120）
 * 2. SET_SCALE   告诉 STM32 放大 2 倍（因为图片是 120×120，屏幕是 240×240）
 * 3. IMAGE_BEGIN 告知尺寸，STM32 设置写入窗口
 * 4. IMAGE_DATA  逐帧分片推送像素
 * 5. IMAGE_END   结束
 * 6. 循环 3~5    处理后续帧
 * ```
 *
 * **关键设计**：每帧都发一次 IMAGE_BEGIN/IMAGE_END，而不是只在开头发一次。
 * 原因：STM32 端没有帧缓冲（只有 20KB RAM），它是在 IMAGE_BEGIN 时
 * 设置窗口、在 IMAGE_DATA 时直接写屏。所以每帧都要重新定位。
 */
@Singleton
class OlcdRepository @Inject constructor(
    private val bleManager: BleManager,
    private val mediaConverter: MediaConverter,
) {
    companion object {
        private const val TAG = "OlcdRepository"

        /** 图片尺寸（与 MediaConverter.TARGET_SIZE 一致） */
        const val IMAGE_SIZE = MediaConverter.TARGET_SIZE

        /** STM32 端放大倍数（120 × 2 = 240，正好铺满屏幕） */
        const val DISPLAY_SCALE = 2

        /**
         * 分片间延时（毫秒）。
         *
         * 这个值很关键：ESP32 收到数据后会转发给 STM32，
         * 而 STM32 是软件 SPI 写屏（每个像素都要翻转 GPIO），
         * 处理速度有限。间隔太短会导致 STM32 的 UART 接收缓冲溢出，
         * 表现为图像传一半卡住。
         */
        private const val CHUNK_GAP_MS = 8L

        /** 帧与帧之间的间隔（毫秒），给 STM32 完成一帧绘制的时间 */
        private const val FRAME_GAP_MS = 50L
    }

    /** BLE 连接状态，直接暴露给 UI */
    val bleState: StateFlow<BleState> = bleManager.state

    /** ESP32 回传的通知 */
    val notifications: StateFlow<String> = bleManager.notifications

    private var seq = 0

    /** 下一个序号（0~255 循环） */
    private fun nextSeq(): Int {
        val s = seq
        seq = (seq + 1) and 0xFF
        return s
    }

    // ==================== 连接管理 ====================

    /**
     * 扫描并连接 ESP32。
     */
    suspend fun connect(): Boolean {
        Log.i(TAG, "connect: 扫描 ${BleUuids.DEVICE_NAME}")
        return bleManager.scanAndConnect(BleUuids.DEVICE_NAME)
    }

    fun disconnect() {
        bleManager.disconnect()
    }

    fun stopScan() {
        bleManager.stopScan()
    }

    /** 当前 MTU */
    fun currentMtu(): Int = bleManager.currentMtu()

    // ==================== 媒体转换 ====================

    /**
     * 把媒体文件转换成帧序列（不发数据，只做本地转换）。
     */
    suspend fun convertMedia(uri: Uri, maxFrames: Int = MediaConverter.DEFAULT_MAX_FRAMES): FrameSet? {
        Log.i(TAG, "convertMedia: uri=$uri maxFrames=$maxFrames")
        return mediaConverter.convert(uri, maxFrames)
    }

    // ==================== 发送 ====================

    /**
     * 发送一组帧到屏幕，形成动画。
     *
     * @param frameSet   要发送的帧
     * @param frameDelay 帧间延时（毫秒）。0 表示用帧自带的 delayMs。
     * @param onProgress 进度回调
     * @return 是否全部成功
     */
    suspend fun sendFrames(
        frameSet: FrameSet,
        frameDelay: Int = 0,
        onProgress: (TransferState) -> Unit = {},
    ): Boolean {
        if (!bleState.value.canSend) {
            onProgress(TransferState.Failed("未连接到 ESP32"))
            return false
        }
        if (frameSet.frames.isEmpty()) {
            onProgress(TransferState.Failed("没有可发送的帧"))
            return false
        }

        onProgress(TransferState.Converting(100))

        // 1. 先告诉 STM32 放大倍数
        //    必须在 IMAGE_BEGIN 之前设置，因为放大倍数在 BEGIN 时生效。
        val scaleOk = bleManager.send(
            Protocol.buildU8(Protocol.Cmd.SET_SCALE, nextSeq(), DISPLAY_SCALE),
            chunkGapMs = 0,
        )
        if (!scaleOk) {
            onProgress(TransferState.Failed("设置缩放失败"))
            return false
        }

        // 2. 逐帧发送
        frameSet.frames.forEachIndexed { index, frame ->
            val frameOk = sendOneFrame(
                frame = frame,
                frameIndex = index + 1,
                frameTotal = frameSet.frameCount,
                onProgress = onProgress,
            )
            if (!frameOk) {
                onProgress(
                    TransferState.Failed("第 ${index + 1} 帧发送失败")
                )
                return false
            }

            // 帧间留时间给 STM32 完成绘制
            val wait = if (frameDelay > 0) frameDelay else frame.delayMs
            kotlinx.coroutines.delay(wait.toLong().coerceAtLeast(FRAME_GAP_MS))
        }

        onProgress(TransferState.Done(frameSet.frameCount))
        return true
    }

    /**
     * 发送单帧：IMAGE_BEGIN → IMAGE_DATA（分片）→ IMAGE_END
     */
    private suspend fun sendOneFrame(
        frame: com.loostone.olcd.domain.model.PixelFrame,
        frameIndex: Int,
        frameTotal: Int,
        onProgress: (TransferState) -> Unit,
    ): Boolean {
        // IMAGE_BEGIN：告知尺寸与位置
        val begin = Protocol.buildImageBegin(
            seq = nextSeq(),
            width = frame.width,
            height = frame.height,
            x = 0,
            y = 0,
        )
        if (!bleManager.send(begin, chunkGapMs = 0)) {
            Log.e(TAG, "IMAGE_BEGIN 发送失败 (frame $frameIndex)")
            return false
        }

        // IMAGE_DATA：像素数据分片。
        // 这里让 BleManager 内部再按 MTU 分片，我们只需把整帧交出去。
        // 但为了能报告准确进度，这里自己按「协议层 512 字节」再切一层，
        // 每片包成一个 IMAGE_DATA 帧。
        val chunkSize = 512
        var offset = 0
        val total = frame.rgb565.size

        while (offset < total) {
            val end = minOf(offset + chunkSize, total)
            val payload = frame.rgb565.copyOfRange(offset, end)

            val dataFrame = Protocol.buildFrame(
                cmd = Protocol.Cmd.IMAGE_DATA,
                seq = nextSeq(),
                payload = payload,
            )

            if (!bleManager.send(dataFrame, chunkGapMs = CHUNK_GAP_MS)) {
                Log.e(TAG, "IMAGE_DATA 发送失败 (frame $frameIndex, offset $offset)")
                return false
            }

            offset = end
            onProgress(
                TransferState.Sending(
                    frameIndex = frameIndex,
                    frameTotal = frameTotal,
                    bytesSent = offset,
                    bytesTotal = total,
                )
            )
        }

        // IMAGE_END
        val endFrame = Protocol.buildFrame(Protocol.Cmd.IMAGE_END, nextSeq())
        if (!bleManager.send(endFrame, chunkGapMs = 0)) {
            Log.e(TAG, "IMAGE_END 发送失败 (frame $frameIndex)")
            return false
        }

        return true
    }

    // ==================== 播放控制 ====================

    suspend fun play(): Boolean = sendSimple(Protocol.Cmd.PLAY)

    suspend fun pause(): Boolean = sendSimple(Protocol.Cmd.PAUSE)

    suspend fun stop(): Boolean = sendSimple(Protocol.Cmd.STOP)

    suspend fun setBrightness(value: Int): Boolean {
        require(value in 0..100) { "亮度必须在 0~100" }
        return bleManager.send(
            Protocol.buildU8(Protocol.Cmd.SET_BRIGHT, nextSeq(), value),
            chunkGapMs = 0,
        )
    }

    suspend fun clearScreen(color: Int = 0x0000): Boolean =
        bleManager.send(
            Protocol.buildU16(Protocol.Cmd.CLEAR, nextSeq(), color),
            chunkGapMs = 0,
        )

    /** 发一个无负载命令 */
    private suspend fun sendSimple(cmd: Int): Boolean =
        bleManager.send(Protocol.buildFrame(cmd, nextSeq()), chunkGapMs = 0)

    /** 发送心跳，验证链路是否通 */
    suspend fun ping(): Boolean =
        bleManager.send(Protocol.buildFrame(Protocol.Cmd.PING, nextSeq()), chunkGapMs = 0)
}
