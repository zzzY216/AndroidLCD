# OLCD Android App

手机通过 BLE 连接 ESP32-S3，下发 GIF / 视频 / 图片，最终显示在 STM32 驱动的 240×240 屏幕上。

```
手机 App ──BLE──► ESP32-S3 ──UART2(921600)──► STM32F103 ──SPI──► ST7789 240×240
```

技术栈：**Jetpack Compose + Hilt + Kotlin Coroutines**

---

## 一、快速开始

### 构建

```powershell
cd D:\android\android_pro\OLCD
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`（约 18 MB）

### 安装

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### 使用流程

1. **授予蓝牙权限**（首次启动会弹窗）
2. **点「连接 ESP32」** —— 自动扫描名为 `OLCD-ESP32` 的设备
3. **点「选择文件」** —— 选 GIF / 视频 / 图片
4. **点「发送到屏幕」** —— 传输并显示
5. 用「播放 / 暂停 / 停止 / 清屏 / 亮度」控制

---

## 二、⭐ 你应该上传什么格式？

### 推荐：GIF

| 优势 | 说明 |
|---|---|
| **体积小** | 同样动画，GIF 比视频小很多 |
| **自带帧延时** | 每帧有独立的显示时长，动画节奏准确 |
| **解码简单** | Android 原生支持，无需额外库 |
| **可控** | 帧数、尺寸都容易掌握 |

**推荐规格**：
- 尺寸：任意（会自动缩放到 120×120）
- 帧数：**10 帧以内**（超出会截断）
- 时长：1~2 秒最佳

### 可以：视频（MP4 / MKV / WebM / MOV）

也可以，但要注意：

- 会自动按间隔均匀抽 **10 帧**
- 体积大的视频解码慢
- 无声（只取画面）

**推荐规格**：
- 时长：2 秒左右（10 帧 × 200ms）
- 分辨率：不用太高，反正会缩到 120×120

### 可以：静态图片（JPG / PNG / WebP）

单帧显示，适合放个 logo 或图标。

### ⚠️ 不推荐：长动画

**传输时间与帧数成正比**：

| 帧数 | 预计传输时间 | 体验 |
|---|---|---|
| 5 帧 | 约 5 秒 | 很流畅 |
| **10 帧** | **约 10 秒** | **推荐** |
| 20 帧 | 约 20 秒 | 有点久 |
| 50 帧 | 约 50 秒 | 太久 |

> 超过 10 帧会被自动截断（界面上会提示）。这是固件里 `DEFAULT_MAX_FRAMES` 决定的。

---

## 三、图片是怎么处理的

### 完整转换链

```
用户选的 GIF/视频/图片
        ↓ MediaConverter
裁切缩放为 120×120 的 Bitmap
        ↓ Bitmap.getPixels()
ARGB_8888 像素数组
        ↓ 位宽压缩
RGB565（R 5位 + G 6位 + B 5位）
        ↓ 大端打包
ByteArray（每像素 2 字节，高字节在前）
        ↓ Protocol.buildFrame()
带 CRC16 的协议帧
        ↓ BleManager 分片
BLE 写入 ESP32
```

### 为什么是 120×120 而不是 240×240

屏幕实际是 240×240，但我们传 **120×120**，让 STM32 端**放大 2 倍**显示。

| 方案 | 每帧数据量 | 传输时间 | 画质 |
|---|---|---|---|
| 240×240 | 115,200 字节 | 约 3 秒/帧 | 原生 |
| **120×120** | **28,800 字节** | **约 1 秒/帧** | 放大 2 倍 |

**传输量降到 1/4，速度提升 4 倍**。而屏幕本身只有 240×240，放大后观感差异不大。

> 这个 2 倍放大是在 STM32 端通过 `SET_SCALE` 命令实现的，
> App 在发送前会自动先发这条命令。

### 字节序为什么是「大端」

RGB565 值 `0xF800`（红色）在内存里可以有两种排法：

```
大端（我们用这个）：F8 00   ← 高字节在前
小端（错误的）：    00 F8
```

STM32 端的 `img_rx_push_pixel(hi, lo)` 期待**先收到高字节**，
所以必须用大端。**如果搞错，屏幕上所有颜色都会错乱**（典型是红蓝互换）。

这个细节有专门的单元测试守着：`Rgb565Test.byteOrderIsBigEndianNotLittleEndian`。

---

## 四、协议实现（必须与两端一致）

帧格式：
```
A5 5A | CMD | SEQ | LEN(2) | PAYLOAD | CRC16(2)
```

CRC16 = CRC-16/CCITT-FALSE，覆盖 `CMD + SEQ + LEN + PAYLOAD`

**三端必须完全同步**：

| 端 | 文件位置 |
|---|---|
| Android | `data/protocol/Protocol.kt` |
| ESP32 | `D:\esp32\MyProject\OLCD\main\proto.c` |
| STM32 | `D:\stm32\MyProject\Hardware\proto.c` |

### 验证基准（已通过测试）

```kotlin
crc16("123456789") == 0x29B1
buildFrame(PING, 0) == A5 5A 01 00 00 00 74 F2
```

这两条已写入 `ProtocolTest`，改动协议时会立刻报错。

### 发送一帧图像的完整序列

```
SET_SCALE(2)              ← 告诉 STM32 放大 2 倍
IMAGE_BEGIN(120,120,0,0)  ← 告知尺寸，STM32 设置窗口
IMAGE_DATA × N            ← 像素数据，每片 512 字节
IMAGE_END                 ← 结束
[帧间延时]
SET_SCALE 不用重发
IMAGE_BEGIN(120,120,0,0)  ← 下一帧重新定位
...
```

**为什么每帧都要发 IMAGE_BEGIN**：
STM32 只有 20KB RAM，**没有帧缓冲**。它是在 `IMAGE_BEGIN` 时设置写入窗口、
`IMAGE_DATA` 时直接写屏。所以每帧都要重新定位。

---

## 五、代码结构

```
app/src/main/java/com/loostone/olcd/
├── MainActivity.kt              入口，@AndroidEntryPoint
├── OlcdApplication.kt           @HiltAndroidApp
├── di/
│   └── AppModule.kt             Hilt 依赖提供
├── data/
│   ├── ble/
│   │   ├── BleUuids.kt          UUID 常量（与 ESP32 一致）
│   │   ├── BleState.kt          连接状态机
│   │   └── BleManager.kt        扫描/连接/MTU/分片写入
│   ├── media/
│   │   └── MediaConverter.kt    GIF/视频/图片 → RGB565
│   ├── protocol/
│   │   └── Protocol.kt          CRC16 + 帧封装
│   └── repository/
│       └── OlcdRepository.kt    业务流程编排
├── domain/model/
│   └── PixelFrame.kt            帧数据模型
└── ui/
    ├── MainViewModel.kt         UI 状态与事件
    └── MainScreen.kt            Compose 界面
```

### 分层依赖

```
ui ──► repository ──► (ble, media, protocol)
 │                        │
 └──► domain/model ◄──────┘
```

---

## 六、关键技术决策

### 1. BLE 写入必须串行化

Android 的 `writeCharacteristic` **一次只能有一个进行中的操作**。
并发调用会返回 `false` 或静默丢失数据。

本项目的做法：每次写入用 `suspendCancellableCoroutine` 挂起，
等 `onCharacteristicWrite` 回调后再发下一片。

### 2. 片间延时不能设为 0

```kotlin
private const val CHUNK_GAP_MS = 8L  // 片间 8ms
```

**这不是随便设的**：ESP32 会把数据转发给 STM32，而 STM32 是
**软件模拟 SPI** 写屏（每个像素都要翻转 GPIO），处理速度有限。

间隔太短 → STM32 的 UART 接收缓冲（512 字节）溢出 → 图像传一半卡住。

如果实测仍然丢数据，把 `CHUNK_GAP_MS` 调到 12~15。

### 3. MTU 协商

- 默认 MTU 23 → 每片只能带 20 字节
- 协商后 MTU 512 → 每片可带 509 字节

连接后立即 `requestMtu(512)`，ESP32 侧配置的也是 512。
片大小按 `mtu - 3` 动态计算（3 是 ATT 头开销）。

### 4. GIF 用 Movie 而不是 ImageDecoder

`ImageDecoder.decodeDrawable()` 拿到的 `AnimatedImageDrawable`
**没有公开的逐帧导出 API**，只能让它在屏幕上自己播。

要取每一帧的像素，`android.graphics.Movie` 是唯一可靠方式：
```kotlin
movie.setTime(t)          // 定位到时刻 t
movie.draw(canvas, 0, 0)  // 画出那一帧
```

虽然 `Movie` 标记为 deprecated，但功能完整且全 API 级别可用。

---

## 七、遇到的构建问题（已解决）

这些是 AGP 9 + Kotlin 2.2 新版本组合下的坑，记录下来。

### 1. `Cannot add extension with name 'kotlin'`

**原因**：AGP 9 **已内置 Kotlin 支持**，不能再显式应用 `kotlin-android` 插件。

**解决**：去掉 `alias(libs.plugins.kotlin.android)`。

### 2. `Using kotlin.sourceSets DSL is not allowed with built-in Kotlin`

**原因**：AGP 9 默认禁止插件通过 `kotlin.sourceSets` 添加源码目录，
但 **KSP（Hilt 需要）仍在使用这个 DSL**。

**解决**：`gradle.properties` 里加官方提供的开关：
```properties
android.disallowKotlinSourceSets=false
```

### 3. `Android BaseExtension not found`（Hilt 插件）

**原因**：Hilt 2.57.1 的 Gradle 插件不兼容 AGP 9。

**解决**：升级到 **Hilt 2.60.1**。

### 4. 配置缓存导致构建失败

**现象**：
```
Configuration cache state could not be cached:
field `__runtimeDependenciesNavigationFiles__` of task
`:app:processDebugNavigationResources`
```

**原因**：AGP 9 的 `ProcessNavigationXmlTask` 字段无法序列化。

**解决**：
```properties
org.gradle.configuration-cache=false
```

### 5. compileSdk 必须是 37

**现象**：
```
Dependency 'androidx.core:core-ktx:1.19.1' requires libraries and
applications that depend on it to compile against version 37 or later
```

**解决**：`compileSdk { version = release(37) { minorApiLevel = 1 } }`

### 6. Java 版本必须是 17

AGP 9 + Kotlin 2.2 要求 Java 17（原来项目是 11）。已在 `compileOptions` 里改。

---

## 八、常见问题

### 搜不到 ESP32

- 确认 ESP32 已烧录且日志出现 `advertising as "OLCD-ESP32"`
- 手机蓝牙已开启
- 已授予蓝牙权限（Android 12+ 需要 `BLUETOOTH_SCAN` 和 `BLUETOOTH_CONNECT`）

### 连接成功但发数据失败

- 看 ESP32 日志有没有收到数据
- 检查是否连的是 `6E400002`（RX 特征）而非 `6E400003`（TX）

### 图像显示一半就停

- **加大 `CHUNK_GAP_MS`**（`OlcdRepository.kt` 里，改成 12~15）
- 减少帧数（10 → 5）
- 检查 ESP32 日志有无 `rx queue full` 警告

### 颜色不对（红蓝互换）

字节序问题。确认 `MediaConverter.bitmapToFrame` 里是：
```kotlin
out[i * 2]     = ((rgb565 shr 8) and 0xFF).toByte()  // 高字节在前
out[i * 2 + 1] = (rgb565 and 0xFF).toByte()
```

### 动画播放不流畅

- 帧数太多（传输时间正比于帧数）
- 降低帧数到 5~10

### 构建报错 `Could not resolve`

网络问题。用代理或确认能访问 `maven.google.com` 和 `repo1.maven.org`。

---

## 九、测试

```powershell
cd D:\android\android_pro\OLCD
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest
```

当前结果：

| 测试类 | 用例数 | 状态 |
|---|---|---|
| `ProtocolTest` | 12 | ✅ 全通过 |
| `Rgb565Test` | 9 | ✅ 全通过 |

**关键测试**：
- `buildFrame_ping_matchesDocumentedBytes` —— PING 帧字节与两端完全一致
- `byteOrderIsBigEndianNotLittleEndian` —— 防止字节序写错导致颜色错乱
- `crc16_knownValue` —— CRC 标准基准值

---

## 十、依赖版本

| 项目 | 版本 |
|---|---|
| AGP | 9.3.3 |
| Kotlin | 2.2.10 |
| Gradle | 9.5.0 |
| compileSdk | **37** |
| minSdk | 24 |
| Compose BOM | 2026.02.01 |
| Hilt | 2.60.1 |
| KSP | 2.2.10-2.0.2 |
| JDK | 17（编译）/ 21（Gradle 运行） |

---

## 十一、下一步

如果要做得更完善，可以考虑：

1. **预览真实图像**：目前 `FramePreviewStrip` 只显示帧号，
   可以改成把 `PixelFrame` 转回 Bitmap 在界面上真正预览。
2. **上传进度条细化**：当前按帧显示，可以做到按字节平滑显示。
3. **记住上次连接**：用 DataStore 存设备地址，跳过扫描。
4. **批量文件队列**：一次选多个文件排队发送。
5. **图片裁切界面**：让用户自己框选区域，而不是强制缩放到 120×120。
#   A n d r o i d L C D  
 