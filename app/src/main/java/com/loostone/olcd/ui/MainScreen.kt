package com.loostone.olcd.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.loostone.olcd.data.ble.BleState
import com.loostone.olcd.data.ble.canSend
import com.loostone.olcd.data.repository.TransferState
import com.loostone.olcd.domain.model.PixelFrame
import java.util.concurrent.TimeUnit
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 主界面。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val bleState by viewModel.bleState.collectAsStateWithLifecycle()
    val transferState by viewModel.transferState.collectAsStateWithLifecycle()
    val frameSet by viewModel.frameSet.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isConverting by viewModel.isConverting.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()

    // ==================== 权限 ====================
    val permissions = rememberPermissionsList()
    val permissionState = rememberMultiplePermissionsState(permissions)

    // ==================== 文件选择 ====================
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // 申请持久读取权限，避免重启后失效
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.loadMedia(uri)
        }
    }

    // 消息提示
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OLCD 屏幕控制") },
                actions = {
                    val statusText = when (val s = bleState) {
                        is BleState.Idle -> "未连接"
                        is BleState.Scanning -> "扫描中…"
                        is BleState.Connecting -> "连接中…"
                        is BleState.DiscoveringServices -> "发现服务…"
                        is BleState.Connected -> "已连接"
                        is BleState.Disconnected -> "已断开"
                        is BleState.Error -> "错误"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 权限提示 ----------
            if (!permissionState.allPermissionsGranted) {
                PermissionCard(
                    onRequest = { permissionState.launchMultiplePermissionRequest() }
                )
            }

            // ---------- 连接卡片 ----------
            ConnectionCard(
                state = bleState,
                onConnect = { viewModel.connect() },
                onDisconnect = { viewModel.disconnect() },
                onPing = { viewModel.ping() },
            )

            // ---------- 媒体选择 ----------
            MediaCard(
                isConverting = isConverting,
                frameSet = frameSet,
                connected = bleState.canSend,
                onPickFile = { filePicker.launch(arrayOf("image/*", "video/*")) },
                onSend = { viewModel.sendToScreen() },
                transferState = transferState,
            )

            // ---------- 播放控制 ----------
            ControlCard(
                connected = bleState.canSend,
                onPlay = { viewModel.play() },
                onPause = { viewModel.pause() },
                onStop = { viewModel.stop() },
                onClear = { viewModel.clearScreen() },
                onBrightness = { viewModel.setBrightness(it) },
            )

            // ---------- 通知日志 ----------
            if (notifications.isNotEmpty()) {
                InfoCard(title = "ESP32 回传") {
                    Text(
                        text = notifications,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** 各区所需的权限列表 */
@Composable
private fun rememberPermissionsList(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

// ==================== 各卡片 ====================

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "需要蓝牙权限",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "扫描和连接 ESP32 需要蓝牙访问权限",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRequest) { Text("授予权限") }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: BleState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPing: () -> Unit,
) {
    InfoCard(title = "连接") {
        val (icon, text, color) = when (state) {
            is BleState.Idle -> Triple(Icons.Default.BluetoothDisabled, "未连接", Color.Gray)
            is BleState.Scanning -> Triple(Icons.Default.Bluetooth, "正在扫描 OLCD-ESP32…", Color(0xFF1976D2))
            is BleState.Connecting -> Triple(Icons.Default.Bluetooth, "连接中…", Color(0xFF1976D2))
            is BleState.DiscoveringServices -> Triple(Icons.Default.Bluetooth, "发现服务…", Color(0xFF1976D2))
            is BleState.Connected -> Triple(Icons.Default.Bluetooth, "已连接 (MTU ${state.mtu})", Color(0xFF2E7D32))
            is BleState.Disconnected -> Triple(Icons.Default.BluetoothDisabled, state.reason, Color(0xFFC62828))
            is BleState.Error -> Triple(Icons.Default.BluetoothDisabled, state.message, Color(0xFFC62828))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color)
            Spacer(Modifier.width(8.dp))
            Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state is BleState.Connected) {
                OutlinedButton(onClick = onDisconnect) { Text("断开") }
                FilledTonalButton(onClick = onPing) { Text("PING") }
            } else {
                Button(
                    onClick = onConnect,
                    enabled = state !is BleState.Scanning &&
                        state !is BleState.Connecting &&
                        state !is BleState.DiscoveringServices,
                ) {
                    Text("连接 ESP32")
                }
            }
        }
    }
}

@Composable
private fun MediaCard(
    isConverting: Boolean,
    frameSet: com.loostone.olcd.domain.model.FrameSet?,
    connected: Boolean,
    onPickFile: () -> Unit,
    onSend: () -> Unit,
    transferState: TransferState,
) {
    InfoCard(title = "媒体") {
        Text(
            text = "支持 GIF / 视频 / 图片，自动转为 120×120 RGB565，" +
                "屏幕上放大 2 倍显示（铺满 240×240）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickFile, enabled = !isConverting) {
                Icon(Icons.Default.Upload, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("选择文件")
            }

            Button(
                onClick = onSend,
                enabled = connected && frameSet != null && !isConverting,
            ) {
                Text("发送到屏幕")
            }
        }

        if (isConverting) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在转换…", style = MaterialTheme.typography.bodySmall)
            }
        }

        frameSet?.let { set ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${set.sourceName}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "共 ${set.frameCount} 帧" +
                    if (set.truncated) "（已截断）" else "" +
                    "　预计传输 ${formatDuration(set.estimatedTransferMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            FramePreviewStrip(set.frames)
        }

        // 传输进度
        when (val t = transferState) {
            is TransferState.Sending -> {
                Spacer(Modifier.height(12.dp))
                Text(
                    "发送中：第 ${t.frameIndex}/${t.frameTotal} 帧，" +
                        "${t.bytesSent}/${t.bytesTotal} 字节",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { t.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is TransferState.Done -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    "已完成 ${t.frameCount} 帧传输",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2E7D32),
                )
            }

            is TransferState.Failed -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    t.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            else -> Unit
        }
    }
}

@Composable
private fun ControlCard(
    connected: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onBrightness: (Int) -> Unit,
) {
    var brightness by remember { mutableFloatStateOf(100f) }

    InfoCard(title = "控制") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onPlay, enabled = connected) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("播放")
            }
            FilledTonalButton(onClick = onPause, enabled = connected) {
                Icon(Icons.Default.Pause, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("暂停")
            }
            FilledTonalButton(onClick = onStop, enabled = connected) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("停止")
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(onClick = onClear, enabled = connected) {
            Icon(Icons.Default.Delete, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("清屏")
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "背光亮度：${brightness.toInt()}%",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = brightness,
            onValueChange = { brightness = it },
            onValueChangeFinished = { onBrightness(brightness.toInt()) },
            valueRange = 0f..100f,
            enabled = connected,
        )
    }
}

/** 帧缩略图预览条 */
@Composable
private fun FramePreviewStrip(frames: List<PixelFrame>) {
    // 简化实现：只显示帧数量和尺寸信息，避免在 UI 线程做位图转换
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        frames.take(10).forEachIndexed { index, f ->
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .padding(1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${index + 1}",
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    Text(
        text = "首帧尺寸 ${frames.firstOrNull()?.width ?: 0}×${frames.firstOrNull()?.height ?: 0}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InfoCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** 格式化时长：秒 / 分秒 */
private fun formatDuration(ms: Long): String {
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms)
    return if (seconds < 60) {
        "${seconds} 秒"
    } else {
        "${seconds / 60} 分 ${seconds % 60} 秒"
    }
}
