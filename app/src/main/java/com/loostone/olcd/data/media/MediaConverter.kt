package com.loostone.olcd.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.loostone.olcd.domain.model.FrameSet
import com.loostone.olcd.domain.model.PixelFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 把用户选择的媒体文件转换成供 ESP32 → STM32 显示的 RGB565 帧序列。
 *
 * 支持的输入：
 * - **GIF**（推荐）：逐帧解码，保留帧延时
 * - **视频**（MP4/MKV/…）：用 MediaMetadataRetriever 抽帧
 * - **静态图片**（JPG/PNG/WebP）：单帧
 *
 * 输出统一为 120×120 RGB565（大端序），由 STM32 端放大 2 倍显示，
 * 正好铺满 240×240 的屏幕。
 *
 * 为什么用 120×120 而不是 240×240：
 * - 传输量降到 1/4（28.8KB vs 115.2KB）
 * - BLE 传输时间从约 3 秒/帧降到约 1 秒/帧
 * - 屏幕本身只有 240×240，放大 2 倍后观感差异不大
 */
class MediaConverter(private val context: Context) {

    companion object {
        private const val TAG = "MediaConverter"

        /** 输出图片尺寸（STM32 端放大 2 倍 → 240×240 铺满屏幕） */
        const val TARGET_SIZE = 120

        /** 默认最多取多少帧（超过会截断，避免传输时间过长） */
        const val DEFAULT_MAX_FRAMES = 10

        /** 视频抽帧间隔（毫秒）。10 帧 × 200ms = 2 秒素材 */
        const val VIDEO_FRAME_INTERVAL_MS = 200L

        /** 单帧延时兜底值 */
        private const val DEFAULT_DELAY_MS = 100
    }

    /**
     * 转换媒体文件。
     *
     * @param uri       用户选择的文件
     * @param maxFrames 最多取多少帧
     * @return 转换结果；解析失败返回 null
     */
    suspend fun convert(
        uri: Uri,
        maxFrames: Int = DEFAULT_MAX_FRAMES,
    ): FrameSet? = withContext(Dispatchers.IO) {
        try {
            val mime = context.contentResolver.getType(uri) ?: ""
            val name = queryFileName(uri) ?: "unknown"

            Log.i(TAG, "convert: mime=$mime name=$name maxFrames=$maxFrames")

            when {
                mime.contains("gif", ignoreCase = true) -> decodeGif(uri, name, maxFrames)
                mime.startsWith("video/", ignoreCase = true) -> decodeVideo(uri, name, maxFrames)
                mime.startsWith("image/", ignoreCase = true) -> decodeStaticImage(uri, name)
                else -> {
                    // MIME 不可靠时按扩展名兜底
                    val lower = name.lowercase()
                    when {
                        lower.endsWith(".gif") -> decodeGif(uri, name, maxFrames)
                        lower.endsWith(".mp4") || lower.endsWith(".mkv") ||
                            lower.endsWith(".webm") || lower.endsWith(".mov") ||
                            lower.endsWith(".avi") ->
                            decodeVideo(uri, name, maxFrames)
                        else -> decodeStaticImage(uri, name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "convert failed", e)
            null
        }
    }

    // ==================== GIF ====================

    /**
     * 解码 GIF 的每一帧。
     *
     * 【为什么用 Movie 而不是 ImageDecoder】
     * `ImageDecoder.decodeDrawable()` 拿到的 `AnimatedImageDrawable` 没有公开的
     * 逐帧导出 API，只能让它在屏幕上自己播放。要提取每一帧的像素，
     * 最可靠的方式是 `android.graphics.Movie`（虽然标记为 deprecated，
     * 但功能完整、全 API 级别可用）：
     *
     * ```
     * movie.setTime(t)        // 定位到时间 t
     * movie.draw(canvas, 0, 0) // 把该时刻的帧画到 Canvas
     * ```
     *
     * 按固定间隔遍历整段动画时长，就能逐帧取出。
     */
    @Suppress("DEPRECATION")
    private fun decodeGif(uri: Uri, name: String, maxFrames: Int): FrameSet {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开 GIF")

        val bytes = input.use { it.readBytes() }
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("GIF 解码失败（Movie.decodeByteArray 返回 null）")

        val durationMs = movie.duration().takeIf { it > 0 } ?: (maxFrames * 100)
        Log.i(TAG, "GIF duration=${durationMs}ms width=${movie.width()} height=${movie.height()}")

        // 按 durationMs / maxFrames 的间隔取样，保证取到均匀分布的帧
        val stepMs = (durationMs.toFloat() / maxFrames).toInt().coerceAtLeast(20)

        val frames = mutableListOf<PixelFrame>()
        var timeMs = 0

        // 准备画布：先画到原始尺寸，再缩放到目标尺寸
        val srcW = movie.width().coerceAtLeast(1)
        val srcH = movie.height().coerceAtLeast(1)
        val srcBmp = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(srcBmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        while (timeMs < durationMs && frames.size < maxFrames) {
            // 清空画布，避免上一帧残留（GIF 帧可能是叠加模式）
            srcBmp.eraseColor(android.graphics.Color.TRANSPARENT)
            movie.setTime(timeMs)
            movie.draw(canvas, 0f, 0f)

            // 缩放到目标尺寸
            val scaled = Bitmap.createScaledBitmap(srcBmp, TARGET_SIZE, TARGET_SIZE, true)
            frames.add(bitmapToFrame(scaled, stepMs))
            scaled.recycle()

            timeMs += stepMs
        }

        srcBmp.recycle()

        if (frames.isEmpty()) {
            throw IOException("GIF 未提取到任何帧")
        }

        // 判断是否被截断：如果 GIF 总帧数可能超过 maxFrames
        val truncated = (durationMs / stepMs) > maxFrames
        Log.i(TAG, "GIF frames extracted: ${frames.size}, truncated=$truncated")

        return FrameSet(frames, name, truncated)
    }

    // ==================== 视频 ====================

    private fun decodeVideo(uri: Uri, name: String, maxFrames: Int): FrameSet {
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<PixelFrame>()
        var truncated = false

        try {
            retriever.setDataSource(context, uri)

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            Log.i(TAG, "video duration=${durationMs}ms")

            if (durationMs <= 0L) {
                throw IOException("无法读取视频时长")
            }

            // 均匀抽帧：把时长均分成 maxFrames 份
            val stepMs = (durationMs / maxFrames).coerceAtLeast(50L)

            var timeMs = 0L
            while (frames.size < maxFrames && timeMs < durationMs) {
                val bmp = retriever.getFrameAtTime(
                    timeMs * 1000, // 微秒
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                if (bmp != null) {
                    val scaled = Bitmap.createScaledBitmap(
                        bmp, TARGET_SIZE, TARGET_SIZE, true
                    )
                    if (scaled !== bmp) bmp.recycle()

                    frames.add(bitmapToFrame(scaled, stepMs.toInt()))
                    scaled.recycle()
                }
                timeMs += stepMs
            }

            // 如果视频实际可抽的帧比上限多，视为截断
            truncated = (durationMs / stepMs) > frames.size
        } finally {
            retriever.release()
        }

        if (frames.isEmpty()) {
            throw IOException("视频抽帧失败")
        }

        Log.i(TAG, "video frames extracted: ${frames.size}, truncated=$truncated")
        return FrameSet(frames, name, truncated)
    }

    // ==================== 静态图片 ====================

    private fun decodeStaticImage(uri: Uri, name: String): FrameSet {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开文件")

        val bmp = input.use { BitmapFactory.decodeStream(it) }
            ?: throw IOException("图片解码失败")

        val scaled = Bitmap.createScaledBitmap(bmp, TARGET_SIZE, TARGET_SIZE, true)
        if (scaled !== bmp) bmp.recycle()

        val frame = bitmapToFrame(scaled, DEFAULT_DELAY_MS)
        scaled.recycle()

        return FrameSet(listOf(frame), name, truncated = false)
    }

    // ==================== 位图 → RGB565 ====================

    /**
     * 把 Bitmap 转成 RGB565 字节数组。
     *
     * **字节序为「大端」**（高字节在前），与 STM32 端解析一致：
     * ```
     * rgb565[2i]   = (color >> 8) & 0xFF   // 高字节先发
     * rgb565[2i+1] = color & 0xFF
     * ```
     *
     * RGB565 打包：R 5 位(15-11) + G 6 位(10-5) + B 5 位(4-0)
     */
    private fun bitmapToFrame(bmp: Bitmap, delayMs: Int): PixelFrame {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val out = ByteArray(w * h * 2)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF

            // 8 位 → 5/6/5 位
            val r5 = r shr 3
            val g6 = g shr 2
            val b5 = b shr 3

            val rgb565 = (r5 shl 11) or (g6 shl 5) or b5

            // 大端：高字节在前
            out[i * 2] = ((rgb565 shr 8) and 0xFF).toByte()
            out[i * 2 + 1] = (rgb565 and 0xFF).toByte()
        }

        return PixelFrame(w, h, out, delayMs)
    }

    // ==================== 工具 ====================

    private fun queryFileName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME
                    )
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "query file name failed", e)
            null
        }
    }
}
