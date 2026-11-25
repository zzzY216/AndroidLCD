package com.loostone.olcd.data.ble

/**
 * BLE 连接状态。
 */
sealed class BleState {
    /** 空闲，未开始扫描 */
    data object Idle : BleState()

    /** 正在扫描 */
    data object Scanning : BleState()

    /** 找到目标设备，正在连接 */
    data class Connecting(val deviceName: String) : BleState()

    /** 已连接，等待服务发现完成 */
    data object DiscoveringServices : BleState()

    /** 已连接且服务就绪，可以发数据 */
    data class Connected(
        val deviceName: String,
        val mtu: Int,
    ) : BleState()

    /** 连接断开 */
    data class Disconnected(val reason: String) : BleState()

    /** 出错 */
    data class Error(val message: String) : BleState()
}

/** 判断当前是否可以发送数据 */
val BleState.canSend: Boolean
    get() = this is BleState.Connected
