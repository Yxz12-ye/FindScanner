# FindScanner API v1

本文件与 `openapi.yaml` 是本次 Android 逻辑接入的服务端对接契约。客户端已实现调用；服务端、训练及定位算法由后端实现。更新日期：2026-10-30。

## 1. 通用约定

- 基地址只包含 HTTP/HTTPS 协议、主机和可选端口，例如 `http://192.168.1.42:8000/`。客户端为不带协议的地址补 `http://`，不接受路径、查询、片段或 URL 用户密码。
- JSON UTF-8，`Content-Type: application/json`，前缀 `/api/v1`，无账号、密码、Token。HTTPS 使用系统证书校验，不跳过证书错误，不跟随重定向。
- 时间统一为 UTC Unix 毫秒 `int64`；不传手机时区。MAC 是地址字符串，RSSI 单位 dBm。抽象位置是字符串，支持中文、空格和 `(1,2)`，客户端仅裁剪首尾空白，不解析为坐标。
- `region` 是后端数据与模型隔离键，`deviceId` 由用户填写。客户端会裁剪两者首尾空白并要求非空；后端仍须验证。接口无鉴权，不应直接向不可信网络暴露。
- 所有表中字段均必填，除非标明“可省略”。允许 `null` 的字段也会由客户端显式发送 `null`。服务端可以增加响应字段，客户端忽略未知响应字段。

## 2. 本地 debug 模式

连接页的服务器地址裁剪后等于 `debug`（大小写不敏感）时，客户端直接进入主页，不解析 DNS、不创建连接，也不调用三个 API。

- 区域、设备 ID、采集位置仍需填写，配置仍保存在 DataStore。
- Wi-Fi、BLE、GPS 仍按实际硬件和权限采集；不会生成演示信号、演示 GPS 或演示概率。
- 每轮仍写入 Room，状态为 `LOCAL_ONLY`，界面显示“仅本地 · debug”；不进入上传队列，不允许手动上传。
- debug 激活期间，历史真实服务器记录也暂停补传。连接真实服务器后只恢复真实记录的队列；debug 记录永不转换为待上传。
- 位置推理按钮禁用，ViewModel、Repository 和网络层同样阻止 debug 推理。历史推理仅允许查看，明确标记已过期。
- debug 是客户端保留关键字，不是服务端路径或服务端必须实现的模式。`http://debug/` 是普通主机地址，不是 debug 关键字。

## 3. 连接

`POST /api/v1/connect`，成功 HTTP 200。

```json
{"region":"实验室 A","deviceId":"scanner-01","clientVersion":"1.0","protocolVersion":1}
```

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| region | 非空 string | 区域隔离键 |
| deviceId | 非空 string | 用户提供的设备标识 |
| clientVersion | 非空 string | APK versionName |
| protocolVersion | integer，固定 1 | 客户端支持的协议版本 |

```json
{"protocolVersion":1,"inferenceReady":false}
```

`inferenceReady` 为 boolean，仅表示连接时该区域模型是否就绪。无模型也能连接和采集；后续推理仍以每次响应为准。客户端不接受非 1 的协议版本，不保存未成功连接的配置。进程重启后恢复配置和历史，但须点击连接，不自动恢复扫描。

## 4. 共用信号结构 SignalFrame

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| id | UUID string | 本轮请求/记录 ID，每轮新建；采集重试不变 |
| sessionId | UUID string | 会话 ID；暂停/继续同一采集会话不变 |
| region | 非空 string | 本轮创建时的区域 |
| deviceId | 非空 string | 本轮创建时的设备标识 |
| windowStartedAt | int64 | 窗口开始 UTC 毫秒 |
| windowEndedAt | int64 | 窗口结束 UTC 毫秒，必须晚于开始 |
| wifi | RadioSignal[] | 可以为 `[]`，同一 BSSID 保留一条 |
| ble | RadioSignal[] | 可以为 `[]`，同一地址只保留本窗口最后一次广播 |
| gps | GpsFix 或 null | 无有效定位时为 null，不使用零坐标占位 |
| sensors | SensorStatuses | 三类传感器的独立状态与原因 |

### RadioSignal

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| mac | string | Wi-Fi BSSID / BLE 广播地址，不是 SSID，不要求地址长期稳定 |
| rssi | integer | 观测 RSSI，单位 dBm，保留平台值 |
| observedAt | int64 | 此信号被观测的 UTC 毫秒；不是上传时间 |
| ageMs | int64 ≥ 0 | 窗口结束时数据年龄，由单调时钟计算 |
| fresh | boolean | 观测是否落在本窗口内；Wi-Fi 缓存可以为 false |
| name | string 或 null | Wi-Fi SSID / BLE 广播名称，仅辅助展示 |
| frequencyMhz | integer 或 null | Wi-Fi 频率 MHz；BLE 为 null |

10 秒保证的是记录周期，不保证每轮 Wi-Fi 都有新扫描结果。主动 Wi-Fi 请求间隔至少 30 秒，监听系统扫描完成广播，每轮读取最近结果。扫描被节流、失败或超时不会阻塞 BLE/GPS 和后续记录。后端应结合 `fresh`、`ageMs`、`observedAt` 处理旧缓存，不应把上传时间当采样时间。BLE 只扫描广播，不配对、不连接。

### GpsFix

`latitude` / `longitude`：number，分别为 [-90,90] / [-180,180]；`accuracyMeters`：number ≥ 0；`observedAt`：int64 定位时间；`altitudeMeters`：number 或 null。最近定位距窗口结束超过 60 秒时，GPS 返回 null，原因 `stale_fix`。仅近似位置授权时可保存系统提供的近似 GPS，原因 `approximate_location`；它不等于获得 Wi-Fi 所需精确定位权限。

### SensorStatuses

包含 `wifi`、`ble`、`gps` 三个对象，每个对象有 `status` 和 `reason`（string 或 null）。

| status | 含义 |
| --- | --- |
| ok | 有可用数据，Wi-Fi 至少有一个本轮新观测 |
| cached | Wi-Fi 有数据，但全部来自旧缓存 |
| empty | 扫描已运行但没有可用信号 |
| unavailable | 硬件缺失、未定位、定位过期或需要重新启动扫描 |
| permission_denied | 所需权限未授予/撤销 |
| disabled | Wi-Fi、蓝牙或定位服务关闭 |
| error | 平台扫描/定位错误 |

常见 reason：`precise_location_required`、`scan_permission_required`、`location_permission_required`、`hardware_missing`、`wifi_off`、`bluetooth_off`、`location_services_off`、`no_fix`、`stale_fix`、`accuracy_missing`、`approximate_location`、`scan_throttled_or_failed`、`scan_not_updated`、`scan_timeout`、`restart_scan_required`、`scan_failed:<平台错误码>` 或异常类名。reason 是诊断文本，可扩展，服务端不应依赖穷举。部分数据甚至全部不可用的采集轮次仍合法，必须保留状态；不得补造信号或 GPS。

## 5. 采集上传与幂等

`POST /api/v1/samples`，请求是 SignalFrame 加必填非空 `locationLabel`。

```json
{
  "id":"3a49a554-4475-4ae7-856d-5042de204bcf",
  "sessionId":"a00e2cab-13b7-405e-a352-7d253f1d110a",
  "region":"实验室 A","deviceId":"scanner-01",
  "windowStartedAt":1793318400000,"windowEndedAt":1793318410000,
  "wifi":[{"mac":"AA:BB:CC:DD:EE:01","rssi":-55,"observedAt":1793318408000,"ageMs":2000,"fresh":true,"name":"Lab-AP","frequencyMhz":2412}],
  "ble":[],"gps":null,
  "sensors":{"wifi":{"status":"ok","reason":null},"ble":{"status":"empty","reason":null},"gps":{"status":"unavailable","reason":"no_fix"}},
  "locationLabel":"餐厅 (1,2)"
}
```

首次持久化成功后返回 HTTP 201（也接受 200）：

```json
{"id":"3a49a554-4475-4ae7-856d-5042de204bcf","status":"accepted"}
```

相同请求重试返回 HTTP 200，`status` 为 `duplicate`，必须返回同一个 `id`。后端以区域和 id 建立唯一约束，原子写入后才确认；同 id 同内容不得再次入库。相同 id 的不同内容返回 HTTP 409 `IDEMPOTENCY_CONFLICT`，禁止覆盖原样本。客户端把错误确认 ID、未知确认状态或异常 JSON 视为未确认。

客户端先落库，再上传。每条记录绑定原始基地址及完整不可变 JSON，切换配置不会改写历史目标、区域、设备或标签。重试发送相同 id 和 JSON 内容，响应丢失后重试也能安全去重；不使用另外的 Idempotency-Key 头。

## 6. 连续推理

`POST /api/v1/inferences`，请求仅为 SignalFrame，**禁止 `locationLabel`**。后端不得把请求作为训练样本入库；它不是 `/samples` 的别名。

每 10 秒收集一个窗口，单次最多一个推理请求，繁忙期间的新窗口不排队，不离线重放。Wi-Fi、BLE 为空且 GPS 为 null 时，客户端直接显示“没有可用信号”，不发请求。

成功匹配，HTTP 200：

```json
{
  "id":"3a49a554-4475-4ae7-856d-5042de204bcf",
  "inferredAt":1793318410200,"status":"ok",
  "candidates":[{"locationLabel":"餐厅","probability":0.8},{"locationLabel":"走廊","probability":0.2}]
}
```

- `id` 必须回显本次请求 ID，`inferredAt` 是服务器完成推理的 UTC 毫秒，必须大于 0。
- `status=ok` 时 candidates 非空、位置名非空且不重复；概率是有限数值，范围 [0,1]，全列表概率和为 1（客户端容忍 ±0.01 的浮点误差）。客户端按概率降序展示，最大值作为最可能位置。
- 后端若只返回 top-k，需对返回的候选重新归一化；概率表示返回候选集内的相对概率，不保证跨模型的校准置信度。UI 百分比仅用于展示。
- 无模型：`{"id":"<请求 UUID>","inferredAt":1793318410200,"status":"no_model","candidates":[]}`。
- 无匹配：同样 HTTP 200，`status=no_match` 且 candidates=[]。不得使用 404 表达业务无模型，不得伪造均匀概率。
- 不接受未知状态、错误 ID、非法概率、重复位置或成功但空候选。显示异常信息并等待下一轮新采样，不重试旧推理。
- 暂停、离开或进入后台会取消请求，使用会话代次校验丢弃迟到结果。结果超过本地收到后 30 秒，或采样暂停/进入历史查看时标记已过期。

## 7. 错误、超时与重试

非 2xx 使用统一错误对象：

```json
{"error":{"code":"VALIDATION_ERROR","message":"deviceId must not be empty"}}
```

| HTTP | code 示例 | 客户端行为 |
| --- | --- | --- |
| 400 / 422 | VALIDATION_ERROR | 采集标记拒绝，不自动重试；支持详情手动重试 |
| 404 | REGION_NOT_FOUND / ENDPOINT_NOT_FOUND | 提示服务端错误；不自动重试采集 |
| 409 | IDEMPOTENCY_CONFLICT / PROTOCOL_UNSUPPORTED | 停止该采集自动重试，检查内容/协议 |
| 408 | REQUEST_TIMEOUT | 自动退避重试采集 |
| 429 | RATE_LIMITED | 自动退避重试采集 |
| 500 / 502 / 503 / 504 | INTERNAL_ERROR / SERVICE_UNAVAILABLE | 自动退避重试采集 |

客户端通过 Toast 展示 HTTP 状态码、`error.code` 和 `error.message`；连接页同时保留错误文字，方便查看完整原因。兼容顶层 `message`、字符串 `detail`/`error` 及纯文本错误；空响应、HTML 或无法识别的 JSON 回退为 HTTP 状态码。诊断只读取最多 4 KiB 的预览，展示内容规范空白并限制为 200 字符，避免把整页 HTML/堆栈塞进通知。

错误文本仅用于展示，不参与重试决策。上传自动重试只依据 IOException（含超时、连接错误）或 HTTP 408/429/5xx；其他错误，包括异常协议、非法 JSON、重定向等，标记 `REJECTED`，需用户检查后重试。HTTPS 校验错误不会绕过证书验证。自动补传失败的原因保留在记录详情，不为每条记录重复弹出 Toast。

连接超时 5 秒、读超时 8 秒、整个调用上限 9 秒；OkHttp 隐式连接重试关闭。采集按失败次数退避 1、2、4、8…256、300 秒，此后上限 300 秒。当前实现不读取 Retry-After。应用前台且网络可用时每秒检查队列，逐条补传；支持未验证互联网的局域网 Wi-Fi。后台不扫描、不补传，无后台定位、无前台服务。切换到连接/权限页暂停队列；连接真实服务器后恢复原目标队列。

手动重试只重置失败/拒绝记录的退避，不改变 ID、内容和目标。已上传记录不提供重复上传入口。debug 记录不允许重试。推理和连接均不自动重试；连续推理失败后仅下一轮新请求继续。

## 8. curl 联调

以下以 Bash 为例（PowerShell 可使用 `curl.exe` 并按该 shell 的引用规则传 JSON）。仓库 `docs/sample.json`、`docs/inference.json` 已提供可用示例，在 `docs` 目录执行。每次新一轮生成新的 UUID，采集重试保持原文件。

```bash
BASE=http://192.168.1.42:8000
curl -i "$BASE/api/v1/connect" -H 'Content-Type: application/json' \
  -d '{"region":"实验室 A","deviceId":"scanner-01","clientVersion":"1.0","protocolVersion":1}'
curl -i "$BASE/api/v1/samples" -H 'Content-Type: application/json' --data-binary @sample.json
# 重复同一命令：必须 duplicate 且不新增样本
curl -i "$BASE/api/v1/samples" -H 'Content-Type: application/json' --data-binary @sample.json
curl -i "$BASE/api/v1/inferences" -H 'Content-Type: application/json' --data-binary @inference.json
```

## 9. 后端联调检查

检查空数组、缺失 GPS、全部不可用状态、旧 Wi-Fi 缓存、中文/坐标文本标签、幂等与冲突、区域隔离、无模型、无匹配、候选概率、异常 JSON、超时和断网。Android 本地验证步骤与本次执行结果见 `TESTING.md`。无账号鉴权和服务器训练/算法不在客户端实现范围。
