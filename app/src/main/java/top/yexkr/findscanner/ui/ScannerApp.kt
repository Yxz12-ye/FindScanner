package top.yexkr.findscanner.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yexkr.findscanner.ui.theme.MyApplicationTheme
import top.yexkr.findscanner.R

private enum class Page { Permissions, Connection, Home, Collection, Detail, Inference }
private data class Signal(val mac: String, val name: String, val rssi: Int, val ble: Boolean = false)
private val demoSignals = listOf(
    Signal("A4:CF:12:8E:3B:01", "TP-LINK_5G · 5745 MHz", -48),
    Signal("3C:84:6A:22:19:FE", "CMCC-Office · 2437 MHz", -55),
    Signal("F0:9F:C2:11:AB:7D", "餐厅-AP-01 · 5180 MHz", -61),
    Signal("78:8A:20:5D:66:90", "BLE · Beacon-1F", -67, true),
    Signal("A4:CF:12:8E:3B:02", "TP-LINK_2.4G · 2462 MHz", -72),
    Signal("90:9A:4A:3F:C2:08", "BLE · Beacon-2F", -78, true),
    Signal("24:6F:28:B1:33:AA", "Guest-WiFi · 2412 MHz", -84)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerApp() {
    var page by rememberSaveable { mutableStateOf(Page.Permissions) }
    var inferenceTab by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf("192.168.1.42:8000") }
    var region by rememberSaveable { mutableStateOf("实验室 A · 3F") }
    var device by rememberSaveable { mutableStateOf("pixel8-a1b2") }
    var location by rememberSaveable { mutableStateOf("餐厅") }
    var running by rememberSaveable { mutableStateOf(false) }
    var inferring by rememberSaveable { mutableStateOf(true) }
    var rounds by rememberSaveable { mutableIntStateOf(5) }
    var selectedRound by rememberSaveable { mutableIntStateOf(3) }
    var ticks by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var permissionsGranted by rememberSaveable { mutableStateOf(false) }
    val scrollState = key(page, inferenceTab) { rememberScrollState() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun notify(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    fun back() {
        page = when (page) {
            Page.Permissions -> Page.Permissions
            Page.Connection -> Page.Permissions
            Page.Home -> Page.Connection
            Page.Detail -> Page.Collection
            else -> Page.Home
        }
        if (page != Page.Collection) running = false
    }
    BackHandler(page != Page.Permissions) { back() }
    LaunchedEffect(running, page) {
        if (!running || (page != Page.Collection && page != Page.Detail)) return@LaunchedEffect
        ticks = 0
        while (true) {
            delay(100)
            ticks++
            if (ticks >= 100) { ticks = 0; rounds++ }
        }
    }
    val title = when (page) {
        Page.Permissions, Page.Home -> "FindScanner"
        Page.Connection -> "连接服务器"
        Page.Collection -> "采集 · ${location.ifBlank { "未命名位置" }}"
        Page.Detail -> "第 $selectedRound 轮采集"
        Page.Inference -> "位置推理"
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (page in listOf(Page.Collection, Page.Detail, Page.Inference)) {
                                Text("$region · $device", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    },
                    navigationIcon = {
                        if (page == Page.Permissions || page == Page.Home) {
                            Box(Modifier.padding(start = 16.dp, end = 8.dp).size(36.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_radar), null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        } else IconButton(onClick = { back() }) { Icon(painterResource(R.drawable.ic_arrow_back), "返回") }
                    },
                    actions = {
                        if (page == Page.Permissions) {
                            BadgeLabel("1 / 3", Modifier.padding(end = 16.dp))
                        } else if (page == Page.Inference) {
                            IconButton(onClick = { inferring = true; notify("已刷新模拟推理结果") }) { Icon(painterResource(R.drawable.ic_refresh), "刷新推理") }
                        } else {
                            if (page == Page.Home) BadgeLabel("● 已连接")
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vertical), "更多") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("连接设置") }, onClick = { menu = false; running = false; page = Page.Connection })
                                    DropdownMenuItem(text = { Text("权限预览") }, onClick = { menu = false; running = false; page = Page.Permissions })
                                    DropdownMenuItem(text = { Text("关于此预览") }, onClick = { menu = false; notify("界面预览 · 所有信号和结果均为模拟数据") })
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                )
                if (page == Page.Home) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TabButton("采集数据", !inferenceTab, Modifier.weight(1f)) { inferenceTab = false }
                        TabButton("位置推理", inferenceTab, Modifier.weight(1f)) { inferenceTab = true }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (page != Page.Detail) BottomActionBar {
                when (page) {
                    Page.Permissions -> MainButton(if (permissionsGranted) "继续" else "全部允许并继续", R.drawable.ic_check) {
                        permissionsGranted = true
                        page = Page.Connection
                    }
                    Page.Connection -> MainButton("连接并继续", R.drawable.ic_arrow_forward) { page = Page.Home }
                    Page.Home -> if (inferenceTab) {
                        MainButton("开始推理", R.drawable.ic_location) { inferring = true; page = Page.Inference }
                    } else {
                        MainButton("采集准备", R.drawable.ic_play) { running = false; page = Page.Collection }
                    }
                    Page.Collection -> MainButton(if (running) "暂停采集" else "开始采集", if (running) R.drawable.ic_pause else R.drawable.ic_play) { running = !running }
                    Page.Inference -> MainButton(if (inferring) "暂停推理" else "继续推理", if (inferring) R.drawable.ic_pause else R.drawable.ic_play) { inferring = !inferring }
                    Page.Detail -> Unit
                }
            }
        }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(horizontal = 16.dp, vertical = 12.dp)) {
            val centerContent = page == Page.Permissions || page == Page.Connection
            val fixedContent = page == Page.Collection || page == Page.Detail
            val bodyModifier = if (fixedContent) Modifier.fillMaxSize()
                else Modifier.fillMaxWidth().verticalScroll(scrollState).heightIn(min = maxHeight)
            Column(bodyModifier,
                verticalArrangement = Arrangement.spacedBy(12.dp, if (centerContent) Alignment.CenterVertically else Alignment.Top)) {
                when (page) {
                    Page.Permissions -> PermissionPage(permissionsGranted, { permissionsGranted = true }) { page = Page.Connection }
                    Page.Connection -> {
                        Heading("连接到服务器", "填写采集使用的服务器、区域与设备标识。")
                        Panel {
                            Input("服务器地址", "IP 地址 : 端口", server, { server = it }, KeyboardType.Uri)
                            Text("支持 IP:端口，也可填写完整 HTTP / HTTPS 地址", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Input("区域", "例如：实验室 A", region, { region = it })
                            Input("设备 ID", "采集者的唯一标识", device, { device = it })
                            Note("每条采集记录都会附带区域与设备 ID。")
                        }
                        Footnote("界面预览 · 无需真实连接即可继续")
                    }
                    Page.Home -> {
                        Text("$region   ·   $device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!inferenceTab) {
                            Panel {
                                SectionTitle("采集位置", "必填")
                                Text("输入你当前站立的抽象位置，无需使用 GPS 坐标。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("位置名称") },
                                    placeholder = { Text("餐厅、(1,2)、走廊…") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    shape = RoundedCornerShape(16.dp), leadingIcon = { Icon(painterResource(R.drawable.ic_location), null) })
                                Text("常用位置", style = MaterialTheme.typography.labelLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("餐厅", "会议室", "(1,2)", "(5,6)").forEach { item ->
                                        SuggestionChip(onClick = { location = item }, label = { Text(item) })
                                    }
                                }
                            }
                            Panel {
                                SectionTitle("采集设置")
                                KeyValue("信号类型", "Wi-Fi + BLE + GPS")
                                HorizontalDivider()
                                KeyValue("采集间隔", "10 秒 / 轮")
                                HorizontalDivider()
                                KeyValue("上传目标", server.ifBlank { "未设置" }, mono = true)
                            }
                            HistoryRow("上次采集：餐厅 · 5 轮", "今天 09:41") { running = false; page = Page.Collection }
                        } else {
                            Panel(tinted = true) {
                                BadgeLabel("指纹库已就绪 · 模拟")
                                Heading("推断你当前的位置", "基于本区域的 Wi-Fi 与 BLE 信号指纹，匹配最可能的位置。")
                                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                    InlineStat("128", "采集点")
                                    InlineStat("1,842", "信号指纹")
                                    InlineStat("4", "位置")
                                }
                            }
                            Panel {
                                SectionTitle("推理准备", "已同步")
                                KeyValue("区域", region)
                                HorizontalDivider()
                                KeyValue("采样周期", "10 秒")
                                HorizontalDivider()
                                KeyValue("推理方式", "服务器推理")
                            }
                            HistoryRow("上次推理：餐厅 · 86%", "3 分钟前") { inferring = false; page = Page.Inference }
                            Footnote("模拟结果 · 可直接进入查看")
                        }
                    }
                    Page.Collection -> {
                        Panel(tinted = true) {
                            SectionTitle(if (running) "正在采集信号" else "准备就绪", if (running) "第 ${rounds + 1} 轮" else "已暂停")
                            Text(if (running) "正在汇总本轮 Wi-Fi、BLE 与 GPS" else "点击底部「开始采集」，预览连续采集。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearProgressIndicator(progress = { if (running) ticks / 100f else 0f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                            KeyValue(if (running) "下一轮采集" else "采集间隔", if (running) "${10 - ticks / 10} 秒" else "10 秒")
                        }
                        SectionTitle("采集记录", "$rounds 轮 · 模拟数据")
                        LazyPanel(Modifier.weight(1f)) {
                            items(count = rounds, key = { rounds - it }) { index ->
                                val round = rounds - index
                                Row(Modifier.fillMaxWidth().clickable { selectedRound = round; page = Page.Detail }.padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    SensorIcon(if (round == rounds) "clock" else "check")
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text("第 $round 轮", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text("5 AP · 2 BLE · 09:41:${(round * 10 % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text("-66 dBm", fontWeight = FontWeight.SemiBold)
                                        Text(if (round == rounds) "待上传" else "已上传", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (round != 1) HorizontalDivider()
                            }
                        }
                        StatsRow(listOf(rounds.toString() to "采集轮次", (rounds * 7).toString() to "信号条数", "-66" to "平均 RSSI"))
                        Footnote("保持静止，信号会更稳定 · 当前为界面演示")
                    }
                    Page.Detail -> DetailPage { notify("模拟上传成功") }
                    Page.Inference -> {
                        Panel {
                            SectionTitle("最可能的位置", "置信度 86%")
                            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("餐厅 · 2F", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                                    Text("匹配 6 / 7 个信号指纹", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(progress = { .86f }, modifier = Modifier.fillMaxSize(), strokeWidth = 7.dp,
                                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                                    Text("86%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                            HorizontalDivider()
                            Text(if (inferring) "● 持续推理中 · 每 10 秒刷新" else "推理已暂停 · 当前显示上次结果", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        StatsRow(listOf("7" to "采样信号", "320 ms" to "推理耗时", "模拟" to "结果来源"))
                        SectionTitle("位置概率", "4 个候选")
                        Panel {
                            listOf("餐厅 · 2F" to .86f, "走廊 · 2F" to .09f, "会议室 · 3F" to .03f, "前台 · 1F" to .02f).forEach { (name, chance) ->
                                KeyValue(name, "${(chance * 100).toInt()}%")
                                LinearProgressIndicator(progress = { chance }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = if (chance > .5f) 1f else .55f), trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                            }
                        }
                        Footnote("结果为模拟数据，尚未连接推理服务")
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionPage(granted: Boolean, onGrant: () -> Unit, onContinue: () -> Unit) {
    var individually by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f), CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(68.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_radar), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Row(Modifier.width(174.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                SensorIcon("wifi")
                SensorIcon("ble")
            }
        }
        Heading("开始之前", "扫描周围的 Wi-Fi 与 BLE 信号，为室内定位采集环境指纹。")
        Panel {
            PermissionRow("wifi", "Wi-Fi 信号", "读取 AP 的 MAC 地址与 RSSI", granted, individually, onGrant)
            HorizontalDivider()
            PermissionRow("ble", "附近的蓝牙设备", "读取 BLE 广播与信号强度", granted, individually, onGrant)
            HorizontalDivider()
            PermissionRow("gps", "精确位置信息", "用于扫描权限与 GPS 结果采集", granted, individually, onGrant)
        }
        Footnote("仅在采集期间使用，不在后台持续定位")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { individually = !individually }) { Text("逐个查看权限") }
            TextButton(onClick = onContinue) { Text("跳过预览") }
        }
    }
}

@Composable
private fun PermissionRow(kind: String, title: String, subtitle: String, granted: Boolean, individual: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = individual, onClick = onClick).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SensorIcon(kind)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (granted) "已允许" else "待授权", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun DetailPage(onUpload: () -> Unit) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InlineStat("-66 dBm", "平均 RSSI")
                InlineStat("7", "信号数量")
                InlineStat("10 s", "采样窗口")
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                BadgeLabel("已上传 · 模拟")
                TextButton(onClick = onUpload) { Text("重新上传") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部 7", "Wi-Fi 5", "BLE 2").forEachIndexed { index, name ->
                FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(name) })
            }
        }
        Text("按信号强度排序", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        key(filter) {
            LazyPanel(Modifier.weight(1f)) {
                val filtered = demoSignals.filter { filter == 0 || (filter == 2) == it.ble }
                items(filtered, key = { it.mac }) { signal ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SensorIcon(if (signal.ble) "ble" else "wifi", small = true)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(signal.mac, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text(signal.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(signal.rssi.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("dBm", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (signal != filtered.last()) HorizontalDivider()
                }
            }
        }
        Panel {
            SectionTitle("GPS 结果", "模拟")
            KeyValue("经纬度", "31.23040, 121.47370", mono = true)
            KeyValue("定位精度", "± 12 m")
            KeyValue("Wi-Fi 数据", "最新扫描 · 2 秒前")
        }
        Footnote("RSSI 为负值，越接近 0，信号越强")
    }
}

@Composable
private fun LazyPanel(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), content = content)
    }
}

@Composable
private fun Panel(tinted: Boolean = false, spacing: Int = 10, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = if (tinted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (tinted) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(spacing.dp), content = content)
    }
}

@Composable
private fun Heading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 21.sp)
    }
}

@Composable
private fun SectionTitle(title: String, badge: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (badge != null) BadgeLabel(badge)
    }
}

@Composable
private fun BadgeLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 10.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun Input(label: String, hint: String, value: String, onChange: (String) -> Unit, type: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, placeholder = { Text(hint) },
        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(keyboardType = type))
}

@Composable
private fun BottomActionBar(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
            content()
        }
    }
}

@Composable
private fun MainButton(text: String, @DrawableRes icon: Int, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Icon(painterResource(icon), null, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TabButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier.height(48.dp)) { Text(text, fontWeight = FontWeight.Bold) }
    else FilledTonalButton(onClick = onClick, modifier = modifier.height(48.dp)) { Text(text) }
}

@Composable
private fun KeyValue(key: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, modifier = Modifier.widthIn(max = 210.dp))
    }
}

@Composable
private fun InlineStat(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatsRow(stats: List<Pair<String, String>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        stats.forEach { (value, label) ->
            Surface(Modifier.weight(1f), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Box(Modifier.padding(10.dp)) { InlineStat(value, label) }
            }
        }
    }
}

@Composable
private fun HistoryRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_history), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), null)
        }
    }
}

@Composable
private fun Note(text: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_info), null, Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Footnote(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SensorIcon(kind: String, small: Boolean = false) {
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.size(if (small) 28.dp else 36.dp).background(if (small) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.primaryContainer,
        RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        val icon = when (kind) {
            "gps" -> R.drawable.ic_location
            "check" -> R.drawable.ic_check
            "clock" -> R.drawable.ic_history
            "ble" -> R.drawable.ic_bluetooth
            else -> R.drawable.ic_wifi
        }
        Icon(painterResource(icon), null, tint = color, modifier = Modifier.size(24.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun ScannerPreview() { MyApplicationTheme { ScannerApp() } }



