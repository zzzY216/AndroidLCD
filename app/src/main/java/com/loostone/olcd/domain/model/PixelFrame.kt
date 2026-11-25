package com.loostone.olcd.domain.model

/**
 * 一帧已转换好的图像数据（RGB565）。
 *
 * @param width   实际像素宽（已按目标尺寸缩放）
 * @param height  实际像素高
 * @param rgb565  像素数据，**大端序**（每像素 2 字节，高字节在前）
 * @param delayMs 这一帧应显示的时长（GIF 帧延时），用于播放节奏
 */
data class PixelFrame(
    val width: Int,
    val height: Int,
    val rgb565: ByteArray,
    val delayMs: Int = 100,
) {
    /** 像素数量 */
    val pixelCount: Int get() = width * height

    /** 字节数（= 像素数 × 2） */
    val byteCount: Int get() = rgb565.size

    // ByteArray 的 equals/hashCode 需要手写，否则用引用比较
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PixelFrame) return false
        return width == other.width &&
            height == other.height &&
            delayMs == other.delayMs &&
            rgb565.contentEquals(other.rgb565)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + rgb565.contentHashCode()
        result = 31 * result + delayMs
        return result
    }
}

/**
 * 转换结果：一组帧。
 *
 * @param frames      帧列表
 * @param sourceName  源文件名，用于界面显示
 * @param truncated   是否因为超出帧数上限而被截断
 */
data class FrameSet(
    val frames: List<PixelFrame>,
    val sourceName: String,
    val truncated: Boolean,
) {
    val frameCount: Int get() = frames.size

    /** 估算 BLE 传输总耗时（毫秒）。按实测约 1 秒/帧（120×120）。 */
    val estimatedTransferMs: Long
        get() = frames.size * 1000L
}
