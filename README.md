<div align="center">
  <h1>FindScanner</h1>
  <p><strong>Wi-Fi / BLE / GPS 指纹采集与位置推理</strong></p>
  <p>
    <img alt="Android" src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
    <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
    <img alt="Compose" src="https://img.shields.io/badge/Compose-Material%203-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white">
    <img alt="API" src="https://img.shields.io/badge/API-v1-6B7280?style=flat-square">
  </p>
</div>

FindScanner 把 Android 手机变成无线信号采集器：以 10 秒为窗口记录 Wi-Fi、BLE 与 GPS，先落库、再上传；服务端返回抽象位置与概率。适用于室内指纹建库和定位联调。

> [!TIP]
> 没有后端也能跑。服务器地址填 `debug`，应用进入纯本地模式：正常采集与查看历史，但永不发起网络请求，也不提供推理。

## 功能

- **10 秒定长窗口**：单调时钟驱动，上传慢不拖采样；暂停丢弃半轮，继续不补造。
- **允许部分数据**：Wi-Fi、BLE、GPS 各自带状态和原因；旧缓存、无定位、权限缺失都能记录。
- **离线优先**：Room 先落库，失败退避重试，前台自动补传；记录绑定创建时的服务器，切换配置不串数据。
- **连续推理**：每 10 秒一次请求，候选按概率排序；校验概率、状态和请求 ID，丢弃迟到响应。

## Shizuku 增强扫描

> [!IMPORTANT]
> 可选功能，默认关闭，仅在需要连续或移动 Wi-Fi 采集时开启。不开启也能正常采集、上传和推理，但 Wi-Fi 样本会更稀、更旧。

**Wi-Fi 节流对采集的实际影响**

Android 9+ 会限制普通应用的 Wi-Fi 扫描频率，前台通常每 2 分钟最多 4 次，后台更严；部分 ROM 即使关闭开发者选项中的扫描节流，限制仍然存在。

应用每 10 秒落一条记录，普通模式每 30 秒请求一次扫描；请求被节流时，系统可能反复返回同一份缓存。后果不是“记录变少”，而是：

- 记录仍每 10 秒生成，但 Wi-Fi 列表可能连续多轮重复；`fresh=false`、`ageMs` 增大，看似有数据，实际没有新观测。
- 移动采集时，旧扫描会被反复算进当前窗口，RSSI 与真实位置错配；轨迹越连续，错位样本越多。
- 指纹库密度下降且分布不均，重复样本增多；后端训练容易学到重复、滞后的信号，在线推理也会更新不及时，导致位置跳变或偏差。
- BLE 与 GPS 不受该节流影响，仍按窗口更新；受影响的主要是 Wi-Fi 指纹。

**Shizuku 的作用**

通过 Shizuku 以 shell 身份执行固定的 `/system/bin/cmd wifi start-scan`，绕开普通应用的扫描频率限制，并在增强模式下约每 5 秒请求一次，让更多轮次拿到新的 Wi-Fi 观测。

该方案目前仅在 HyperOS 上实测，其他 ROM 的系统限制仍可能生效；命令成功也不等于拿到新结果，客户端始终按扫描时间戳判断新旧。

## 快速开始

| 运行 | 构建 |
| --- | --- |
| Android 7.0+（API 24） | Android SDK 37 · Gradle 9.6 · JDK 25 |

```bash
# Debug APK
./gradlew :app:assembleDebug

# 单元测试 + Lint
./gradlew :app:testDebugUnitTest :app:lintDebug

# 设备集成测试（会安装测试 APK）
./gradlew :app:connectedDebugAndroidTest
```

Windows 使用 `gradlew.bat`。APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

使用流程：

1. 授予定位与附近设备权限；Android 17+ 还需局域网权限。
2. 填写 `IP:端口`、HTTPS 地址或 `debug`，以及区域和设备 ID。
3. 输入位置标签开始采集；每轮记录可查看信号、GPS 与上传状态。

## 数据流

```text
Wi-Fi · BLE · GPS
       │
       ▼
  10 秒采样窗口 ──────▶ Room ──────▶ Retrofit /api/v1
       │                  │
       ▼                  ▼
  Compose UI         重试 / 补传队列
```

## API

| 端点 | 用途 |
| --- | --- |
| `POST /api/v1/connect` | 校验协议版本与区域推理状态 |
| `POST /api/v1/samples` | 上传一轮带位置标签的信号样本 |
| `POST /api/v1/inferences` | 提交当前信号，返回候选位置及概率 |

完整契约：[API.md](docs/API.md) · [OpenAPI](docs/openapi.yaml) · [测试与验收](docs/TESTING.md) · 示例 [sample.json](docs/sample.json) / [inference.json](docs/inference.json)

## 说明

- 本仓库只包含 Android 客户端；模型训练、定位算法与后端服务不在此仓库。
- 10 秒是记录周期，不保证每轮 Wi-Fi 都有新扫描结果，系统扫描节流属正常现象。
- 接口无鉴权，仅应在可信网络中使用。
- Shizuku 是可选增强，具体行为与代价见[上文](#shizuku-增强扫描)。
