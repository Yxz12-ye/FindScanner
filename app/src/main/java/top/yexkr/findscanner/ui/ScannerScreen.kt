package top.yexkr.findscanner.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.net.toUri
import top.yexkr.findscanner.*
import top.yexkr.findscanner.R
import top.yexkr.findscanner.data.*
import top.yexkr.findscanner.scanner.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun time(timestamp: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerApp(model: ScannerViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val allRecords by model.samples.collectAsStateWithLifecycle()
    val inferenceHistory by model.inferences.collectAsStateWithLifecycle()
    val page = state.page
    val selected = remember(allRecords, state.selectedId) { allRecords.find { it.id == state.selectedId } }
    val context = LocalContext.current
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    var toast by remember { mutableStateOf<Toast?>(null) }
    fun notifyUser(message: String) {
        toast?.cancel()
        toast = Toast.makeText(context, message, Toast.LENGTH_LONG).also { it.show() }
    }
    DisposableEffect(context) { onDispose { toast?.cancel() } }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    fun refreshPermissions() { model.permissions(scanPermissions().filter { context.hasPermission(it) }.toSet()) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
        if (scanPermissions().all { context.hasPermission(it) }) model.navigate(Page.Connection)
        else notifyUser("部分权限未授予，仍可继续；可在系统设置中重试授权")
    }
    val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (localNetworkPermissions().all { context.hasPermission(it) }) model.connect()
        else notifyUser("未获得局域网权限，请授权后重试连接")
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissions()
            if (event == Lifecycle.Event.ON_STOP) view.keepScreenOn = false
        }
        owner.lifecycle.addObserver(observer)
        refreshPermissions()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(view, state.running, state.inferring) {
        view.keepScreenOn = state.running || state.inferring
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.notifications.collect { notifyUser(it) } }
    }
    BackHandler(page != Page.Permissions) { model.back() }
    val config = state.config ?: state.draft
    val debug = config.isDebug
    val title = when (page) {
        Page.Permissions, Page.Home -> "FindScanner"
        Page.Connection -> "连接服务器"
        Page.Collection -> "采集 · ${state.sessionLabel}"
        Page.Detail -> "采集详情"
        Page.Inference -> "位置推理"
        Page.SampleHistory -> "本地采集历史"
        Page.InferenceHistory -> "本地推理历史"
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清理本地历史？") },
        text = { Text("将删除全部采集和推理历史，其中 ${allRecords.count { it.status != UploadStatus.UPLOADED }} 条记录尚未上传（含仅本地调试记录）。删除后不可恢复。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; model.clearHistory() }) { Text("确认清理") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
    Scaffold(modifier = Modifier.imePadding(), topBar = {
        Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
            TopAppBar(title = {
                Column {
                    Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (page in listOf(Page.Collection, Page.Detail, Page.Inference)) {
                        val original = if (page == Page.Detail) selected?.frame else null
                        Text("${original?.region ?: config.region} · ${original?.deviceId ?: config.deviceId}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }, navigationIcon = {
                if (page == Page.Permissions || page == Page.Home) {
                    Box(Modifier.padding(start = 16.dp, end = 8.dp).size(36.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_radar), null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                } else IconButton(onClick = model::back) { Icon(painterResource(R.drawable.ic_arrow_back), "返回") }
            }, actions = {
                if (page == Page.Permissions) BadgeLabel("1 / 3", Modifier.padding(end = 16.dp))
                else {
                    if (page == Page.Home) BadgeLabel(if (debug) "● 本地调试" else "● 已连接")
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vertical), "更多") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("连接设置") }, onClick = { menu = false; model.navigate(Page.Connection) })
                            DropdownMenuItem(text = { Text("权限与设备状态") }, onClick = { menu = false; model.navigate(Page.Permissions) })
                            DropdownMenuItem(text = { Text("采集历史") }, onClick = { menu = false; model.navigate(Page.SampleHistory) })
                            DropdownMenuItem(text = { Text("推理历史") }, onClick = { menu = false; model.navigate(Page.InferenceHistory) })
                            DropdownMenuItem(text = { Text("清理本地历史") }, onClick = { menu = false; confirmClear = true })
                        }
                    }
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
            if (page == Page.Home) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TabButton("采集数据", !state.inferenceTab, Modifier.weight(1f)) { model.tab(false) }
                TabButton("位置推理", state.inferenceTab, Modifier.weight(1f)) { model.tab(true) }
            }
        }
    }, bottomBar = {
        if (page !in listOf(Page.Detail, Page.SampleHistory, Page.InferenceHistory)) BottomActionBar {
            when (page) {
                Page.Permissions -> MainButton(if (scanPermissions().all { it in state.granted }) "继续" else "申请权限并继续", R.drawable.ic_check) {
                    if (scanPermissions().all { it in state.granted }) model.navigate(Page.Connection) else permissions.launch(scanPermissions())
                }
                Page.Connection -> MainButton(if (state.connecting) "正在连接…" else "连接并继续", R.drawable.ic_arrow_forward, enabled = state.ready && !state.connecting) {
                    if (!state.draft.isDebug && localNetworkPermissions().any { !context.hasPermission(it) }) localNetwork.launch(localNetworkPermissions())
                    else model.connect()
                }
                Page.Home -> if (state.inferenceTab) MainButton(if (debug) "调试模式下推理不可用" else "开始推理", R.drawable.ic_location, enabled = !debug, onClick = model::startInference)
                    else MainButton("采集准备", R.drawable.ic_play, onClick = model::prepareCollection)
                Page.Collection -> MainButton(if (state.running) "暂停采集" else "开始采集", if (state.running) R.drawable.ic_pause else R.drawable.ic_play, onClick = model::toggleCollection)
                Page.Inference -> MainButton(if (state.inferring) "中止推理" else if (debug) "调试模式下推理不可用" else "继续推理",
                    if (state.inferring) R.drawable.ic_pause else R.drawable.ic_play, enabled = !debug, onClick = model::toggleInference)
                else -> Unit
            }
        }
    }) { padding ->
        ScannerPageTransition(state, Modifier.fillMaxSize()) { displayedState ->
            val state = displayedState
            val page = state.page
            val config = state.config ?: state.draft
            val debug = config.isDebug
            val records = remember(allRecords, state.sessionId) { allRecords.filter { it.frame.sessionId == state.sessionId } }
            val selected = remember(allRecords, state.selectedId) { allRecords.find { it.id == state.selectedId } }
            val scrollState = rememberScrollState()
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(horizontal = 16.dp, vertical = 12.dp)) {
                val centerContent = page == Page.Permissions || page == Page.Connection
                val fixedContent = page in listOf(Page.Collection, Page.Detail, Page.SampleHistory, Page.InferenceHistory)
                val bodyModifier = if (fixedContent) Modifier.fillMaxSize() else Modifier.fillMaxWidth().verticalScroll(scrollState).heightIn(min = maxHeight)
                Column(bodyModifier, verticalArrangement = Arrangement.spacedBy(12.dp, if (centerContent) Alignment.CenterVertically else Alignment.Top)) {
                    when (page) {
                        Page.Permissions -> {
                            Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f), CircleShape), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(68.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(painterResource(R.drawable.ic_radar), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Row(Modifier.width(174.dp), horizontalArrangement = Arrangement.SpaceBetween) { SensorIcon("wifi"); SensorIcon("ble") }
                            }
                            Heading("开始之前", "扫描周围的 Wi-Fi 与 BLE 信号，为室内定位采集环境指纹。")
                            Panel {
                                KeyValue("Wi-Fi / 精确定位", if (Manifest.permission.ACCESS_FINE_LOCATION in state.granted) "已允许" else "待授权")
                                HorizontalDivider()
                                KeyValue("附近的蓝牙设备", if (scanPermissions().filter { "BLUETOOTH" in it }.all { it in state.granted }) "已允许" else "待授权")
                                HorizontalDivider()
                                KeyValue("近似定位", if (Manifest.permission.ACCESS_COARSE_LOCATION in state.granted) "已允许" else "待授权")
                            }
                            Footnote(state.deviceStatus)
                            Footnote("仅在采集/推理期间使用；拒绝权限时记录具体原因，GPS 尚未定位不代表权限未授予。")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }) { Text("系统权限设置") }
                                TextButton(onClick = { model.navigate(Page.Connection) }) { Text("继续（允许部分数据）") }
                            }
                        }
                        Page.Connection -> {
                            Heading("连接到服务器", "填写采集使用的服务器、区域与设备标识。")
                            Panel {
                                Input("服务器地址", "IP 地址 : 端口 / debug", state.draft.server, model::editServer, KeyboardType.Uri)
                                Text("支持 HTTP / HTTPS；输入 debug 进入离线页面调试。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Input("区域", "例如：实验室 A", state.draft.region, model::editRegion)
                                Input("设备 ID", "采集者的唯一标识", state.draft.deviceId, model::editDevice)
                                Note("每条采集记录绑定创建时的服务器、区域与设备 ID。")
                                state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            }
                            Footnote(if (state.draft.isDebug) "debug：跳过连接，真实信号仅保存在本地，不上传，不支持位置推理。" else "连接成功后保存配置；无账号密码或 Token。")
                        }
                        Page.Home -> {
                            Text("${config.region} · ${config.deviceId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!state.inferenceTab) {
                                Panel {
                                    SectionTitle("采集位置", "必填")
                                    Text("输入你当前站立的抽象位置，无需使用 GPS 坐标。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    OutlinedTextField(value = state.location, onValueChange = model::location, label = { Text("位置名称") }, placeholder = { Text("餐厅、(1,2)、走廊…") },
                                        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp), leadingIcon = { Icon(painterResource(R.drawable.ic_location), null) })
                                    val labels = allRecords.map { it.locationLabel }.distinct().take(4)
                                    if (labels.isNotEmpty()) {
                                        Text("常用位置", style = MaterialTheme.typography.labelLarge)
                                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            labels.forEach { label -> SuggestionChip(onClick = { model.location(label) }, label = { Text(label, maxLines = 1) }) }
                                        }
                                    }
                                }
                                Panel {
                                    SectionTitle("采集设置")
                                    KeyValue("信号类型", "Wi-Fi + BLE + GPS")
                                    HorizontalDivider(); KeyValue("采集间隔", "10 秒 / 轮")
                                    HorizontalDivider(); KeyValue("上传目标", if (debug) "仅本地（debug）" else config.server, mono = true)
                                }
                                HistoryRow("本地采集历史 · ${allRecords.size} 轮", allRecords.firstOrNull()?.let { "${it.locationLabel} · ${time(it.createdAt)}" } ?: "暂无记录") { model.navigate(Page.SampleHistory) }
                                if (debug) Note("调试记录永不上传，切换真实服务器后也不会补传。")
                            } else {
                                Panel(tinted = true) {
                                    BadgeLabel(if (debug) "调试模式 · 推理不可用" else if (state.inferenceReady) "本区域模型已就绪" else "本区域暂无模型")
                                    Heading("推断你当前的位置", "基于本区域的 Wi-Fi 与 BLE 信号指纹，匹配最可能的位置。")
                                }
                                Panel {
                                    SectionTitle("推理准备")
                                    KeyValue("区域", config.region); HorizontalDivider()
                                    KeyValue("采样周期", "10 秒"); HorizontalDivider()
                                    KeyValue("推理方式", if (debug) "不可用" else "服务器推理")
                                }
                                HistoryRow("本地推理历史 · ${inferenceHistory.size} 轮", inferenceHistory.firstOrNull()?.let { "${it.message} · ${time(it.frame.windowEndedAt)}" } ?: "暂无记录") { model.navigate(Page.InferenceHistory) }
                                Footnote(if (debug) "请连接真实服务器后使用位置推理。" else "推理信号不作为训练样本上传。")
                            }
                        }
                        Page.Collection -> {
                            Panel(tinted = true) {
                                SectionTitle(if (state.running) "正在采集信号" else "准备就绪", if (state.running) "第 ${records.size + 1} 轮" else "已暂停")
                                Text(if (state.running) "正在汇总本轮 Wi-Fi、BLE 与 GPS" else "点击底部「开始采集」，每 10 秒生成一轮记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SamplingProgress(state.progress, state.running)
                            }
                            SectionTitle("采集记录", "${records.size} 轮${if (debug) " · 仅本地" else ""}")
                            SampleList(records, Modifier.weight(1f)) { model.detail(it.id) }
                            val signals = remember(records) { records.flatMap { it.frame.wifi + it.frame.ble } }
                            StatsRow(listOf(records.size.toString() to "采集轮次", signals.size.toString() to "信号条数", (signals.takeIf { it.isNotEmpty() }?.map { it.rssi }?.average()?.toInt()?.toString() ?: "—") to "平均 RSSI"))
                            Footnote("保持静止，信号更稳定 · Wi-Fi 可能使用旧缓存")
                        }
                        Page.Detail -> if (selected != null) RecordDetail(selected, state.config?.isDebug == true) { model.retryUpload(selected.id) }
                            else Footnote("记录不存在或已清理")
                        Page.Inference -> {
                            val history = state.inference
                            val candidates = history?.result?.candidates.orEmpty()
                            val best = candidates.firstOrNull()
                            Panel {
                                SectionTitle("最可能的位置", if (state.expired) "结果已过期" else if (best != null) "置信度 ${(best.probability * 100).toInt()}%" else "等待结果")
                                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(best?.locationLabel ?: "—", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(state.inferenceMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (best != null) Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(progress = { best.probability.toFloat() }, modifier = Modifier.fillMaxSize(), strokeWidth = 7.dp, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                                        Text("${(best.probability * 100).toInt()}%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    }
                                }
                                HorizontalDivider()
                                Text(if (state.inferring) "● 持续推理中 · 每 10 秒采样" else "推理已中止 · 由用户手动继续", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                SamplingProgress(state.progress, state.inferring)
                            }
                            StatsRow(listOf((history?.frame?.signalCount?.toString() ?: "—") to "采样信号", (history?.frame?.windowEndedAt?.let(::time) ?: "—") to "采样时间", "服务器" to "结果来源"))
                            SectionTitle("位置概率", "${candidates.size} 个候选")
                            Panel { if (candidates.isEmpty()) Text("${state.inferenceMessage}，没有可展示的概率。")
                                candidates.forEach { candidate ->
                                    KeyValue(candidate.locationLabel, "${"%.1f".format(candidate.probability * 100)}%")
                                    LinearProgressIndicator(progress = { candidate.probability.toFloat() }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape))
                                }
                            }
                            if (state.expired) Footnote("结果已过期，仅供历史查看。")
                        }
                        Page.SampleHistory -> {
                            SectionTitle("全部采集记录", "${allRecords.size} 轮")
                            SampleList(allRecords, Modifier.weight(1f)) { model.detail(it.id, true) }
                        }
                        Page.InferenceHistory -> {
                            SectionTitle("全部推理记录", "${inferenceHistory.size} 轮")
                            LazyPanel(Modifier.weight(1f)) {
                                if (inferenceHistory.isEmpty()) item { Text("暂无推理历史", Modifier.padding(16.dp)) }
                                items(inferenceHistory, key = { it.frame.id }) { history ->
                                    val interactions = remember { MutableInteractionSource() }
                                    Column(Modifier.fillMaxWidth().pressFeedback(interactions).clip(RoundedCornerShape(12.dp))
                                        .clickable(interactionSource = interactions, indication = ripple()) { model.showInference(history) }
                                        .padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(history.result?.candidates?.firstOrNull()?.locationLabel ?: history.message, fontWeight = FontWeight.Bold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${history.frame.region} · ${time(history.frame.windowEndedAt)}", style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SamplingProgress(progress: Float, active: Boolean) {
    val displayedProgress = if (active) progress.coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(progress = { displayedProgress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
    KeyValue(if (active) "下一轮采样" else "采样间隔", if (active) "${kotlin.math.ceil(10 * (1 - displayedProgress)).toInt()} 秒" else "10 秒 · 已暂停")
}

@Composable
private fun SampleList(records: List<SampleRecord>, modifier: Modifier, onClick: (SampleRecord) -> Unit) {
    LazyPanel(modifier) {
        if (records.isEmpty()) item { Text("暂无采集记录，首个完整 10 秒窗口结束后生成。", Modifier.padding(vertical = 16.dp)) }
        items(records, key = { it.id }) { record ->
            val frame = record.frame
            val interactions = remember { MutableInteractionSource() }
            Row(Modifier.fillMaxWidth().pressFeedback(interactions).clip(RoundedCornerShape(12.dp))
                .clickable(interactionSource = interactions, indication = ripple()) { onClick(record) }
                .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SensorIcon(if (record.status == UploadStatus.UPLOADED) "check" else "clock")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(record.locationLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${frame.wifi.size} AP · ${frame.ble.size} BLE", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(time(record.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), maxLines = 1, softWrap = false)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${frame.averageRssi} dBm", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(record.status.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
                Icon(painterResource(R.drawable.ic_chevron_right), null)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ColumnScope.RecordDetail(record: SampleRecord, debug: Boolean, onUpload: () -> Unit) {
    var filter by rememberSaveable(record.id) { mutableIntStateOf(0) }
    val frame = record.frame
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.weight(1f)) { InlineStat("${frame.averageRssi} dBm", "平均 RSSI") }
            Box(Modifier.weight(1f)) { InlineStat(frame.signalCount.toString(), "信号数量") }
            Box(Modifier.weight(1f)) { InlineStat("${(frame.windowEndedAt - frame.windowStartedAt) / 1000.0} s", "采样窗口") }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            BadgeLabel(record.status.label)
            if (record.status in listOf(UploadStatus.FAILED, UploadStatus.REJECTED)) TextButton(onClick = onUpload, enabled = !debug) { Text("重新上传") }
        }
        record.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("全部 ${frame.signalCount}", "Wi-Fi ${frame.wifi.size}", "BLE ${frame.ble.size}").forEachIndexed { index, name ->
            FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(name) })
        }
    }
    Text("按信号强度排序 · ${time(frame.windowEndedAt)}", style = MaterialTheme.typography.labelMedium)
    val signals = (if (filter == 2) emptyList() else frame.wifi.map { it to false }) + (if (filter == 1) emptyList() else frame.ble.map { it to true })
    LazyPanel(Modifier.weight(1f)) {
        if (signals.isEmpty()) item { Text("本轮没有此类信号", Modifier.padding(vertical = 12.dp)) }
        items(signals.sortedByDescending { it.first.rssi }, key = { "${it.second}:${it.first.mac}" }) { (signal, ble) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SensorIcon(if (ble) "ble" else "wifi", small = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(signal.mac, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), maxLines = 1, softWrap = false)
                    Text("${signal.name ?: if (ble) "BLE" else "Wi-Fi"} · ${if (signal.fresh) "本轮新观测" else "缓存"} ${signal.ageMs / 1000}s · ${time(signal.observedAt)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), maxLines = 1, softWrap = false)
                }
                Text("${signal.rssi}\ndBm", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
        }
    }
    Panel(spacing = 4) {
        SectionTitle("GPS 与传感器")
        KeyValue("GPS", frame.gps?.let { "${it.latitude}, ${it.longitude} ±${it.accuracyMeters}m" } ?: "不可用")
        frame.gps?.let { KeyValue("定位时间", time(it.observedAt)) }
        listOf("Wi-Fi" to frame.sensors.wifi, "BLE" to frame.sensors.ble, "GPS" to frame.sensors.gps).forEach { (name, status) ->
            KeyValue(name, "${status.status}${status.reason?.let { " · $it" } ?: ""}")
        }
        KeyValue("原始上传目标", record.server)
    }
}
