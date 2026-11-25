package com.loostone.olcd.ui

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loostone.olcd.data.ble.BleState
import com.loostone.olcd.data.repository.OlcdRepository
import com.loostone.olcd.data.repository.TransferState
import com.loostone.olcd.domain.model.FrameSet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 主界面 ViewModel。
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: OlcdRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    /** BLE 连接状态 */
    val bleState: StateFlow<BleState> = repository.bleState

    /** ESP32 的通知 */
    val notifications: StateFlow<String> = repository.notifications

    /** 传输状态 */
    private val _transferState = MutableStateFlow<TransferState>(TransferState.Idle)
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()

    /** 已转换好的帧集合（用于预览） */
    private val _frameSet = MutableStateFlow<FrameSet?>(null)
    val frameSet: StateFlow<FrameSet?> = _frameSet.asStateFlow()

    /** 错误提示（一次性） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 是否正在转换媒体 */
    private val _isConverting = MutableStateFlow(false)
    val isConverting: StateFlow<Boolean> = _isConverting.asStateFlow()

    // ==================== 连接 ====================

    fun connect() {
        viewModelScope.launch {
            _message.value = null
            val ok = repository.connect()
            if (!ok) {
                val s = bleState.value
                _message.value = when (s) {
                    is BleState.Error -> s.message
                    is BleState.Disconnected -> s.reason
                    else -> "连接失败"
                }
            }
        }
    }

    fun disconnect() {
        repository.disconnect()
        _frameSet.value = null
        _transferState.value = TransferState.Idle
    }

    /** 发心跳测试链路 */
    fun ping() {
        viewModelScope.launch {
            val ok = repository.ping()
            _message.value = if (ok) "已发送 PING" else "发送失败"
        }
    }

    // ==================== 媒体转换 ====================

    /**
     * 用户选择文件后调用：转换成 RGB565 帧序列。
     */
    fun loadMedia(uri: Uri) {
        viewModelScope.launch {
            _isConverting.value = true
            _message.value = null
            _frameSet.value = null

            try {
                val result = repository.convertMedia(uri)
                if (result == null) {
                    _message.value = "无法解析该文件，请换一个 GIF/视频/图片"
                } else {
                    _frameSet.value = result
                    Log.i(TAG, "loaded ${result.frameCount} frames from ${result.sourceName}")
                    if (result.truncated) {
                        _message.value =
                            "已提取 ${result.frameCount} 帧（超出上限被截断）"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadMedia failed", e)
                _message.value = "转换失败：${e.message}"
            } finally {
                _isConverting.value = false
            }
        }
    }

    // ==================== 发送 ====================

    /**
     * 把已加载的帧发送到屏幕。
     */
    fun sendToScreen() {
        val frames = _frameSet.value ?: run {
            _message.value = "请先选择要显示的媒体文件"
            return
        }

        viewModelScope.launch {
            val ok = repository.sendFrames(frames) { state ->
                _transferState.value = state
            }

            if (!ok) {
                val s = _transferState.value
                if (s is TransferState.Failed) {
                    _message.value = s.message
                }
            } else {
                _message.value = "已发送 ${frames.frameCount} 帧到屏幕"
            }
        }
    }

    // ==================== 播放控制 ====================

    fun play() {
        viewModelScope.launch {
            val ok = repository.play()
            _message.value = if (ok) "播放" else "播放命令发送失败"
        }
    }

    fun pause() {
        viewModelScope.launch {
            val ok = repository.pause()
            _message.value = if (ok) "暂停" else "暂停命令发送失败"
        }
    }

    fun stop() {
        viewModelScope.launch {
            val ok = repository.stop()
            _message.value = if (ok) "停止" else "停止命令发送失败"
        }
    }

    fun setBrightness(value: Int) {
        viewModelScope.launch {
            repository.setBrightness(value)
        }
    }

    fun clearScreen() {
        viewModelScope.launch {
            val ok = repository.clearScreen()
            _message.value = if (ok) "已清屏" else "清屏失败"
        }
    }

    /** 消费掉一次性提示 */
    fun consumeMessage() {
        _message.value = null
    }
}
