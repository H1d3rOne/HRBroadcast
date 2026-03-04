# HRBroadcast - 心率广播

一款运行在 Wear OS 智能手表上的心率广播应用，通过 BLE（低功耗蓝牙）将实时心率数据广播给外部设备。
完整版功耗更大，若被系统杀进程了，可以使用精简版[HRBroadcastLite](https://github.com/H1d3rOne/HRBroadcastLite)

## 目录

- [需求说明](#需求说明)
- [技术框架](#技术框架)
- [界面布局](#界面布局)
- [功能对照表](#功能对照表)
- [项目结构](#项目结构)
- [安装使用](#安装使用)
- [权限说明](#权限说明)

---

## 需求说明

### 背景

在运动健身场景中，用户需要将智能手表的心率数据实时同步到手机App、健身器材或其他蓝牙设备。本应用解决了 Wear OS 手表心率数据广播的需求。

### 核心需求

| 需求 | 描述 |
|------|------|
| 心率监测 | 实时读取手表心率传感器数据 |
| BLE广播 | 通过标准心率服务UUID广播心率数据 |
| 佩戴检测 | 自动检测手表是否佩戴，未佩戴时广播心率为0 |
| 后台运行 | 支持后台持续运行，开机自启动 |
| 电量优化 | 无设备连接时自动停止广播 |
| 状态显示 | 显示心率、广播时长、连接设备数等信息 |

---

## 技术框架

### 开发环境

| 项目 | 版本 |
|------|------|
| Android SDK | 35 |
| Kotlin | 2.0.21 |
| Gradle Plugin | 8.7.2 |
| minSdk | 27 (Android 8.1 Wear OS) |
| targetSdk | 35 |

### 核心依赖

```groovy
// AndroidX Core
implementation("androidx.core:core-ktx:1.15.0")
implementation("androidx.appcompat:appcompat:1.7.0")
implementation("androidx.constraintlayout:constraintlayout:2.2.0")

// Wear OS
implementation("androidx.wear:wear:1.3.0")
implementation("com.google.android.gms:play-services-wearable:19.0.0")

// Lifecycle
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
implementation("androidx.lifecycle:lifecycle-service:2.8.7")

// Material Design
implementation("com.google.android.material:material:1.12.0")
```

### 技术架构

```
┌─────────────────────────────────────────────────────────┐
│                      UI Layer                           │
│  ┌─────────────────────────────────────────────────┐   │
│  │              MainActivity.kt                     │   │
│  │  - 心率显示、计时器、状态更新                      │   │
│  │  - SensorEventListener 传感器监听                │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│                    Service Layer                        │
│  ┌──────────────────┐    ┌──────────────────────────┐  │
│  │ HeartRateService │    │  HeartRateBleService     │  │
│  │ - 后台心率监测    │    │  - BLE GATT Server       │  │
│  │ - 佩戴状态检测    │    │  - 心率特征值通知         │  │
│  │ - WakeLock管理   │    │  - 自动停止计时器         │  │
│  └──────────────────┘    └──────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│                   Broadcast Layer                       │
│  ┌──────────────────┐    ┌──────────────────────────┐  │
│  │   BootReceiver   │    │     ScreenReceiver       │  │
│  │ - 开机自启动      │    │  - 屏幕亮起启动服务       │  │
│  └──────────────────┘    └──────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### BLE 协议

使用标准心率服务 UUID：

| UUID | 名称 | 说明 |
|------|------|------|
| 0x180D | Heart Rate Service | 心率服务 |
| 0x2A37 | Heart Rate Measurement | 心率测量特征值 |

---

## 界面布局

### 主界面结构

```
┌─────────────────────────────────┐
│ 心率广播                    14:30│  ← 标题栏
├─────────────────────────────────┤
│                                 │
│           ♥ 心形图标             │  ← 90dp
│                                 │
│            75                   │  ← 心率数值 64sp
│           BPM                   │  ← 单位 18sp
│                                 │
│         00:00:00                │  ← 广播计时器 16sp
│                                 │
│         正在广播...              │  ← 状态文本 14sp
│                                 │
│       [ 开启广播 ]               │  ← 按钮 52dp高
│                                 │
│         OPPO Watch              │  ← 设备名 13sp
│                                 │
└─────────────────────────────────┘
```

### 颜色主题

| 颜色名称 | 色值 | 用途 |
|----------|------|------|
| background | #1A1A2E | 深色背景 |
| heart_rate_text | #FF6B6B | 心率文字/心形图标 |
| unit_text | #AAAAAA | 单位文字 |
| status_text | #FFFFFF | 状态文字/标题 |
| accent | #4ECDC4 | 强调色/计时器 |

### 界面元素

| 元素ID | 类型 | 大小 | 说明 |
|--------|------|------|------|
| titleText | TextView | 24sp | 应用标题"心率广播" |
| timeText | TextView | 16sp | 当前系统时间 |
| heartIcon | ImageView | 90dp | 心形动画图标 |
| heartRateText | TextView | 64sp | 心率数值显示 |
| unitText | TextView | 18sp | 单位"BPM" |
| timerText | TextView | 16sp | 广播计时器 |
| statusText | TextView | 14sp | 状态信息 |
| connectionCountText | TextView | 14sp | 连接设备数 |
| broadcastButton | AppCompatButton | 52dp | 开启/停止广播按钮 |
| deviceNameText | TextView | 13sp | 手表蓝牙设备名 |

---

## 功能对照表

### 核心功能

| 功能模块 | 实现类 | 关键方法 | 说明 |
|----------|--------|----------|------|
| 心率监测 | MainActivity | onSensorChanged() | 监听TYPE_HEART_RATE传感器 |
| BLE广播 | HeartRateBleService | startAdvertising() | 开始BLE广播 |
| GATT服务 | HeartRateBleService | setupGattServer() | 设置GATT服务器 |
| 心率更新 | HeartRateBleService | notifyHeartRate() | 通知心率特征值变化 |
| 佩戴检测 | MainActivity | startWearingCheck() | 10秒无心率数据判定未佩戴 |
| 计时器 | MainActivity | timerRunnable | 每秒更新广播时长 |
| 时间显示 | MainActivity | timeRunnable | 每分钟更新系统时间 |
| 自动停止 | HeartRateBleService | autoStopRunnable | 1分钟无连接自动停止 |

### 状态流转

```
┌─────────────┐     点击开启广播     ┌─────────────┐
│   待机状态   │ ──────────────────▶ │  广播状态   │
│             │                     │             │
│ - 显示"--"  │                     │ - 显示心率  │
│ - 计时器归零 │                     │ - 计时运行  │
│ - 按钮绿色  │                     │ - 按钮红色  │
└─────────────┘                     └─────────────┘
       ▲                                   │
       │                                   │
       │         点击停止广播               │
       └───────────────────────────────────┘
```

### 佩戴状态

| 状态 | 心率数据 | 广播内容 | UI显示 |
|------|----------|----------|--------|
| 已佩戴 | 有效心率值 | 实时心率 | 心率数值 + "正在广播..." |
| 未佩戴 | 无数据/0 | 心率0（仅一次） | "--" + "手表未佩戴" |

---

## 项目结构

```
HRBroadcast/
├── app/
│   ├── build.gradle.kts              # 应用构建配置
│   └── src/main/
│       ├── AndroidManifest.xml       # 应用清单
│       ├── java/com/hrbroadcast/
│       │   ├── MainActivity.kt       # 主活动
│       │   ├── HeartRateBleService.kt # BLE广播服务
│       │   ├── HeartRateService.kt   # 后台心率服务
│       │   ├── ScreenReceiver.kt     # 屏幕状态接收器
│       │   └── BootReceiver.kt       # 开机启动接收器
│       └── res/
│           ├── layout/
│           │   └── activity_main.xml # 主布局
│           ├── drawable/
│           │   ├── ic_heart.xml      # 心形图标
│           │   ├── btn_broadcast.xml # 开启按钮背景
│           │   ├── btn_broadcast_stop.xml # 停止按钮背景
│           │   └── ic_launcher_foreground.xml # 应用图标
│           ├── anim/
│           │   └── pulse.xml         # 心跳动画
│           ├── mipmap-anydpi-v26/
│           │   ├── ic_launcher.xml   # 自适应图标
│           │   └── ic_launcher_round.xml
│           └── values/
│               ├── colors.xml        # 颜色资源
│               ├── strings.xml       # 字符串资源
│               └── themes.xml        # 主题样式
├── build.gradle.kts                  # 项目构建配置
├── settings.gradle.kts               # 项目设置
└── gradle.properties                 # Gradle属性
```

---

## 安装使用

### 编译安装

```bash
# 编译Debug版本
./gradlew assembleDebug

# 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 启动应用
adb shell am start -n com.hrbroadcast/.MainActivity
```

### 使用步骤

1. 打开应用，授权身体传感器和蓝牙权限
2. 点击"开启广播"按钮开始广播心率
3. 使用手机App或其他BLE设备搜索连接
4. 连接成功后可实时接收心率数据
5. 点击"停止广播"或等待自动停止

---

## 权限说明

| 权限 | 用途 |
|------|------|
| BODY_SENSORS | 读取心率传感器数据 |
| BLUETOOTH | 基础蓝牙功能 |
| BLUETOOTH_ADMIN | 蓝牙管理 |
| BLUETOOTH_ADVERTISE | BLE广播（Android 12+） |
| BLUETOOTH_CONNECT | BLE连接（Android 12+） |
| FOREGROUND_SERVICE | 前台服务 |
| WAKE_LOCK | 保持CPU唤醒 |
| RECEIVE_BOOT_COMPLETED | 开机自启动 |

---

## License

MIT License
