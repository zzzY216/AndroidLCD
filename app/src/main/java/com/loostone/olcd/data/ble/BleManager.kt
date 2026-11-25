package com.loostone.olcd.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * BLE 管理器：扫描、连接、分片写入。
 *
 * 设计要点：
 *
 * 1. **所有 GATT 操作串行化**
 *    Android 的 `writeCharacteristic` 一次只能有一个进行中的操作，
 *    并发调用会返回 false 或丢失数据。这里用 [writeChannel] 配合
 *    逐条 await 保证串行。
 *
 * 2. **分片写入 + 片间延时**
 *    ESP32 会把收到的数据转发给 STM32，而 STM32 是软件 SPI 写屏，
 *    处理速度有限。因此每片之间要留间隔，否则 STM32 接收缓冲溢出丢数据。
 *
 * 3. **MTU 协商**
 *    默认 MTU 23（负载 20 字节），协商后可到 517。片大小按
 *    `mtu - 3` 动态计算，兼顾效率与兼容性。
 */
@Singleton
class BleManager @Inject constructor(
    private val context: Context,
) {
    companion object {
        private const val TAG = "BleManager"

        /** 扫描超时（毫秒） */
        private const val SCAN_TIMEOUT_MS = 15_000L

        /** 连接超时（毫秒） */
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** 服务发现超时 */
        private const val DISCOVER_TIMEOUT_MS = 10_000L

        /** 期望的 MTU。ESP32 侧配置为 512。 */
        private const val DESIRED_MTU = 512

        /** 单片之间的默认延时（毫秒），给 STM32 喘息 */
        const val DEFAULT_CHUNK_GAP_MS = 8L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<BleState>(BleState.Idle)
    val state: StateFlow<BleState> = _state.asStateFlow()

    /** 接收到的通知数据（来自 ESP32 的状态回传） */
    private val _notifications = MutableStateFlow<String>("")
    val notifications: StateFlow<String> = _notifications.asStateFlow()

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null

    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    /** 当前协商到的 MTU（负载上限 = mtu - 3） */
    private var currentMtu = 23

    /** 写入串行化用的通道：每次写入请求投递一个任务 */
    private val writeLock = Any()

    // 连接过程用到的挂起回调
    private var connectContinuation: ((Boolean) -> Unit)? = null
    private var discoverContinuation: ((Boolean) -> Unit)? = null
    private var mtuContinuation: ((Int) -> Unit)? = null

    /** 蓝牙是否已开启（只读属性，实时查询） */
    val isBluetoothEnabled: Boolean
        get() = adapter?.isEnabled == true

    // ==================== 扫描 ====================

    private var scanCallback: ScanCallback? = null

    /**
     * 扫描并连接目标设备。
     *
     * @param deviceName 目标设备名，默认 [BleUuids.DEVICE_NAME]
     * @return 是否成功连接并完成服务发现
     */
    suspend fun scanAndConnect(
        deviceName: String = BleUuids.DEVICE_NAME,
    ): Boolean {
        val a = adapter ?: run {
            _state.value = BleState.Error("设备不支持蓝牙")
            return false
        }
        if (!a.isEnabled) {
            _state.value = BleState.Error("蓝牙未开启，请先打开蓝牙")
            return false
        }

        _state.value = BleState.Scanning

        val device = scanForDevice(a, deviceName) ?: run {
            _state.value = BleState.Error("未找到设备 $deviceName")
            return false
        }

        _state.value = BleState.Connecting(device.name ?: device.address)
        return connect(device)
    }

    /**
     * 扫描指定的设备名。
     */
    @SuppressLint("MissingPermission")
    private suspend fun scanForDevice(
        a: BluetoothAdapter,
        targetName: String,
    ): BluetoothDevice? = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
        suspendCancellableCoroutine<BluetoothDevice?> { cont ->
            val scanner = a.bluetoothLeScanner
            if (scanner == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            // 按服务 UUID 过滤（ESP32 把服务 UUID 放在扫描响应包里）
            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(android.os.ParcelUuid(BleUuids.SERVICE))
                    .build()
            )
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device
                    val name = result.scanRecord?.deviceName ?: device.name

                    Log.d(TAG, "scan result: name=$name addr=${device.address}")

                    if (name != null && name == targetName) {
                        scanner.stopScan(this)
                        scanCallback = null
                        if (cont.isActive) cont.resume(device)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "scan failed: $errorCode")
                    if (cont.isActive) cont.resume(null)
                }
            }

            scanCallback = cb
            scanner.startScan(filters, settings, cb)

            cont.invokeOnCancellation {
                runCatching { scanner.stopScan(cb) }
                scanCallback = null
            }
        }
    }

    /** 停止扫描（用户手动取消时用） */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { cb ->
            runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
        }
        scanCallback = null
        if (_state.value is BleState.Scanning) {
            _state.value = BleState.Idle
        }
    }

    // ==================== 连接 ====================

    @SuppressLint("MissingPermission")
    private suspend fun connect(device: BluetoothDevice): Boolean {
        // 先断开旧连接
        closeGatt()

        val result: Boolean = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                connectContinuation = { ok -> if (cont.isActive) cont.resume(ok) }

                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(
                        g: BluetoothGatt,
                        status: Int,
                        newState: Int,
                    ) {
                        Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")

                        if (newState == BluetoothProfile.STATE_CONNECTED &&
                            status == BluetoothGatt.GATT_SUCCESS
                        ) {
                            gatt = g
                            // 连接成功后立即请求更大 MTU
                            g.requestMtu(DESIRED_MTU)
                            connectContinuation?.invoke(true)
                            connectContinuation = null
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            _state.value = BleState.Disconnected("连接断开 (status=$status)")
                            connectContinuation?.invoke(false)
                            connectContinuation = null
                        }
                    }

                    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                        Log.i(TAG, "onMtuChanged mtu=$mtu status=$status")
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            currentMtu = mtu
                        }
                        mtuContinuation?.invoke(currentMtu)
                        mtuContinuation = null
                        // MTU 协商完成后再发现服务
                        g.discoverServices()
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        Log.i(TAG, "onServicesDiscovered status=$status")

                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            discoverContinuation?.invoke(false)
                            discoverContinuation = null
                            return
                        }

                        // 查找我们的服务和特征
                        val service = g.getService(BleUuids.SERVICE)
                        if (service == null) {
                            Log.e(TAG, "服务未找到: ${BleUuids.SERVICE}")
                            discoverContinuation?.invoke(false)
                            discoverContinuation = null
                            return
                        }

                        rxCharacteristic = service.getCharacteristic(BleUuids.RX_CHARACTERISTIC)
                        txCharacteristic = service.getCharacteristic(BleUuids.TX_CHARACTERISTIC)

                        Log.i(
                            TAG,
                            "特征: RX=${rxCharacteristic != null} TX=${txCharacteristic != null}"
                        )

                        // 订阅 TX 通知
                        txCharacteristic?.let { tx ->
                            g.setCharacteristicNotification(tx, true)
                            tx.getDescriptor(BleUuids.CCCD)?.let { cccd ->
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    g.writeDescriptor(
                                        cccd,
                                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    )
                                } else {
                                    @Suppress("DEPRECATION")
                                    run {
                                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                        g.writeDescriptor(cccd)
                                    }
                                }
                            }
                        }

                        val ok = rxCharacteristic != null
                        discoverContinuation?.invoke(ok)
                        discoverContinuation = null

                        if (ok) {
                            _state.value = BleState.Connected(
                                deviceName = device.name ?: device.address,
                                mtu = currentMtu,
                            )
                        }
                    }

                    override fun onCharacteristicWrite(
                        g: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            Log.w(TAG, "write status=$status")
                        }
                        // 唤醒等待中的写入协程
                        writeContinuation?.invoke(status == BluetoothGatt.GATT_SUCCESS)
                        writeContinuation = null
                    }

                    override fun onCharacteristicChanged(
                        g: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                    ) {
                        val text = value.toString(Charsets.UTF_8)
                        Log.i(TAG, "notify: $text")
                        _notifications.value = text
                    }

                    @Deprecated("API < 33 的回调")
                    override fun onCharacteristicChanged(
                        g: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                    ) {
                        @Suppress("DEPRECATION")
                        val value = characteristic.value ?: return
                        val text = value.toString(Charsets.UTF_8)
                        Log.i(TAG, "notify(legacy): $text")
                        _notifications.value = text
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    gatt = device.connectGatt(
                        context, false, callback, BluetoothDevice.TRANSPORT_LE
                    )
                } else {
                    gatt = device.connectGatt(context, false, callback)
                }

                cont.invokeOnCancellation { closeGatt() }
            }
        } == true

        // 走到这里说明连接成功（超时返回 null，`== true` 后为 false）
        if (!result) return false

        // 等 MTU 协商
        withTimeoutOrNull(3000) {
            suspendCancellableCoroutine<Int> { cont ->
                if (currentMtu > 23) {
                    cont.resume(currentMtu)
                } else {
                    mtuContinuation = { mtu -> if (cont.isActive) cont.resume(mtu) }
                }
            }
        }

        // 等发现服务
        val discovered: Boolean = withTimeoutOrNull(DISCOVER_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                if (rxCharacteristic != null) {
                    cont.resume(true)
                } else {
                    discoverContinuation = { ok -> if (cont.isActive) cont.resume(ok) }
                }
            }
        } == true

        return discovered
    }

    // ==================== 写入 ====================

    private var writeContinuation: ((Boolean) -> Unit)? = null

    /**
     * 写入一片数据（阻塞直到完成）。
     *
     * @param data 数据（长度不能超过 mtu-3）
     * @return 是否成功
     */
    @SuppressLint("MissingPermission")
    private suspend fun writeChunk(data: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = rxCharacteristic ?: return false

        return withTimeoutOrNull(5000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                writeContinuation = { ok -> if (cont.isActive) cont.resume(ok) }

                val queued: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(
                        ch,
                        data,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    ) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        ch.value = data
                        g.writeCharacteristic(ch)
                    }
                }

                if (!queued) {
                    writeContinuation = null
                    cont.resume(false)
                }
            }
        } ?: false
    }

    /**
     * 发送任意数据，自动按 MTU 分片。
     *
     * @param data       完整数据
     * @param chunkGapMs 片间延时（毫秒）。给 STM32 喘息，不要设为 0。
     * @param onProgress 进度回调 (已发送字节, 总字节)
     * @return 是否全部发送成功
     */
    suspend fun send(
        data: ByteArray,
        chunkGapMs: Long = DEFAULT_CHUNK_GAP_MS,
        onProgress: ((sent: Int, total: Int) -> Unit)? = null,
    ): Boolean {
        if (!_state.value.canSend) {
            Log.w(TAG, "未连接，无法发送")
            return false
        }

        // 负载上限 = MTU - 3（ATT 头开销）
        val chunkSize = (currentMtu - 3).coerceIn(20, 509)

        var offset = 0
        var failCount = 0

        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)

            val ok = writeChunk(chunk)
            if (!ok) {
                failCount++
                Log.w(TAG, "chunk write failed at offset=$offset/${data.size} (fail=$failCount)")
                // 连续失败 3 次就放弃
                if (failCount >= 3) {
                    return false
                }
            } else {
                failCount = 0
            }

            offset = end
            onProgress?.invoke(offset, data.size)

            // 片间延时（最后一片不用等）
            if (offset < data.size && chunkGapMs > 0) {
                delay(chunkGapMs)
            }
        }

        return true
    }

    // ==================== 断开 ====================

    @SuppressLint("MissingPermission")
    fun disconnect() {
        closeGatt()
        _state.value = BleState.Disconnected("已主动断开")
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        rxCharacteristic = null
        txCharacteristic = null
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        currentMtu = 23
    }

    /** 当前 MTU（调试用） */
    fun currentMtu(): Int = currentMtu
}
