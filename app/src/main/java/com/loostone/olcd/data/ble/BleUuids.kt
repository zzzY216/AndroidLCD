package com.loostone.olcd.data.ble

import java.util.UUID

/**
 * BLE 服务的 UUID 常量，必须与 ESP32 端一致。
 *
 * ESP32 侧定义在 `D:\esp32\MyProject\OLCD\main\ble_server.c`：
 * ```
 * 服务   6E400001-B5A3-F393-E0A9-E50E24DCCA9E
 * RX     6E400002-B5A3-F393-E0A9-E50E24DCCA9E  (Write，手机→ESP32)
 * TX     6E400003-B5A3-F393-E0A9-E50E24DCCA9E  (Notify，ESP32→手机)
 * ```
 *
 * 这组 UUID 是 Nordic UART Service (NUS) 兼容格式。
 */
object BleUuids {

    /** 设备名（ESP32 广播时用的名字） */
    const val DEVICE_NAME = "OLCD-ESP32"

    /** 主服务 */
    val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    /** 手机写入数据用的特征（Write / Write No Response） */
    val RX_CHARACTERISTIC: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    /** ESP32 通知手机用的特征（Notify） */
    val TX_CHARACTERISTIC: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    /** 客户端配置描述符（订阅通知需要写这个） */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
