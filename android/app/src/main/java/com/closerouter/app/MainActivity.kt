package com.closerouter.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.closerouter.app.data.ProviderProfile
import com.closerouter.app.data.ProviderStore
import com.closerouter.app.proxy.ProxyServer
import com.closerouter.app.proxy.SpeedTester
import kotlinx.coroutines.launch
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        setContent {
            MaterialTheme(colorScheme = bwScheme) { MainScreen() }
        }
    }

    companion object {
        val bwScheme = lightColorScheme(
            primary = Pal.orangeDeep,
            onPrimary = Color.White,
            background = Pal.bg,
            onBackground = Pal.ink,
            surface = Color.White,
            onSurface = Pal.ink,
            surfaceVariant = Color(0xFFF2F2F4),
            onSurfaceVariant = Pal.ink,
            outline = Pal.line
        )
    }
}

@Composable
fun MainScreen() {
    val app = App.instance
    val scope = rememberCoroutineScope()
    val running by ProxyServer.running.collectAsState()
    val today by app.statsStore.today.collectAsState()
    val lastTtft by app.statsStore.lastTtftMs.collectAsState()
    val lastTokS by app.statsStore.lastTokPerSec.collectAsState()
    val totalTokens by app.statsStore.totalTokens.collectAsState()

    // store 每次修改都会 bump changes，collect 后任何路径的改动都会触发重组
    val providersVersion by app.providerStore.changes.collectAsState()
    val providers = remember(providersVersion) { app.providerStore.providers() }
    val active = remember(providersVersion) { app.providerStore.active() }

    var portText by remember { mutableStateOf(App.port.toString()) }
    var message by remember { mutableStateOf("") }
    var speedResult by remember { mutableStateOf("") }
    var speedBrief by remember { mutableStateOf("--") }
    var speedOk by remember { mutableStateOf(false) }
    var speedRunning by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf(false) }
    var batteryTick by remember { mutableStateOf(0) }
    var editingProvider by remember { mutableStateOf<ProviderProfile?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { BAY_COUNT })
    val selected by remember { derivedStateOf { pagerState.currentPage } }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryTick++ }
    val batteryOk = remember(batteryTick) {
        app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true
    }

    val lit = listOf(
        running,
        active?.usable == true,
        running,
        today.requests > 0,
        speedOk,
        exported,
        batteryOk
    )
    val readouts = listOf(
        if (running) "ONLINE" else "STANDBY",
        active?.name?.take(8) ?: "NONE",
        ":${App.port}",
        "${today.requests} REQ",
        if (speedRunning) "RUN.." else speedBrief,
        if (exported) "DONE" else "EXE",
        if (batteryOk) "ON" else "OFF"
    )
    val titles = listOf("电源", "提供方", "端点", "统计", "测速", "导出", "保活")
    val subtitles = listOf(
        "手机大模型 API → 电脑 Agent 转发器",
        "切换、编辑或新建上游 API",
        "电脑通过 USB 或局域网连接到这里",
        "今日用量与最近一次响应速度",
        "向当前提供方发一次测试请求",
        "导出内置 Windows USB / LAN 连接工具",
        "防止系统在后台杀死转发服务"
    )

    val density = LocalDensity.current
    var upperH by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Pal.bg)
            .systemBarsPadding()
    ) {
        val hPx = with(density) { maxHeight.toPx() }
        // 功能区保持固定高度和位置，左右分页与每页纵向滚动各自独立。
        val loPx = with(density) { 48.dp.toPx() }
        val hiPx = hPx - with(density) { 92.dp.toPx() }
        val gapPx = with(density) { 6.dp.toPx() }
        fun sheetTop() = (upperH + gapPx).coerceIn(loPx, hiPx)

        // The cabinet stays fixed while the lower panel pages horizontally.
        Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { upperH = it.height.toFloat() }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("CLOSEROUTER", fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text("R-04", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Pal.mute)
            }

            RackScene(
                selected = selected,
                running = running,
                lit = lit,
                readouts = readouts,
                busy = speedRunning,
                tokPerSec = lastTokS,
                ttftMs = lastTtft,
                totalTokens = totalTokens,
                onPower = { if (running) ProxyService.stop(app) else ProxyService.start(app) },
                onSelect = { page ->
                    scope.launch { pagerState.animateScrollToPage(page) }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(1f / 1.04f)
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .offset { IntOffset(0, sheetTop().roundToInt()) }
                .fillMaxWidth()
                .height(with(density) { (hPx - sheetTop()).toDp() })
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Color.White),
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.Top
        ) { page ->
            Column(Modifier.fillMaxSize()) {
                ControlHeader(page, titles[page], subtitles[page]) { target ->
                    scope.launch { pagerState.animateScrollToPage(target) }
                }
                HorizontalDivider(color = Color(0xFFF0F0EE), thickness = 1.dp)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp)
                ) {
                    when (page) {
                        0 -> {
                            ControlStatus(
                                if (running) "转发服务已启动" else "机柜待机中",
                                if (running) "USB 与局域网端点已就绪，机柜正在运行。" else "启动后点亮机柜，并开启本机 API 转发。",
                                running, if (running) "ONLINE" else "STANDBY"
                            )
                            Spacer(Modifier.height(12.dp))
                            ControlAction(if (running) "停止转发" else "启动转发", "PWR", {
                                if (running) ProxyService.stop(app) else ProxyService.start(app)
                            })
                            Spacer(Modifier.height(14.dp))
                            ControlCard {
                                ControlValue("当前提供方", active?.name ?: "尚未配置")
                                ControlValue("服务端口", ":${App.port}", accent = running)
                            }
                        }
                        1 -> {
                            ControlCard {
                                ControlValue("当前提供方 / ACTIVE PROVIDER", active?.name ?: "尚未配置", accent = true)
                                HorizontalDivider(color = Color(0xFFE7E7E4))
                                ControlValue("API 格式", when (active?.apiType) { "responses" -> "OpenAI Responses"; "anthropic" -> "Anthropic Messages"; else -> "OpenAI Chat" })
                                ControlValue("模型", active?.model?.ifBlank { "未填写" } ?: "未填写")
                                ControlValue("上游地址", active?.baseUrl ?: "未填写")
                            }
                            Spacer(Modifier.height(12.dp))
                            Box {
                                ControlAction("切换提供方", "SEL", { providerMenu = true }, secondary = true)
                                DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false },
                                    containerColor = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Pal.lineSoft)) {
                                    providers.forEach { p ->
                                        DropdownMenuItem(text = { Text((if (p.id == active?.id) "✓  " else "    ") + p.name) }, onClick = {
                                            app.providerStore.setActive(p.id)
                                            providerMenu = false
                                        })
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ControlAction("编辑配置", "EDIT", { editingProvider = active; showEditor = true }, Modifier.weight(1f), secondary = true)
                                ControlAction("新建", "ADD", { editingProvider = ProviderProfile(name = "新提供方"); showEditor = true }, Modifier.weight(1f))
                            }
                        }
                        2 -> {
                            ControlCard {
                                ControlValue("USB / 本机地址", "http://127.0.0.1:${App.port}/v1", accent = true)
                                HorizontalDivider(color = Color(0xFFE7E7E4))
                                ControlValue("LAN / 局域网地址", "http://${lanIp()}:${App.port}/v1")
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(value = portText, onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                                    label = { Text("服务端口", fontSize = 11.sp) }, singleLine = true,
                                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
                                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFFDEDFDF), focusedBorderColor = Pal.orangeDeep))
                                ControlAction("应用", "SET", {
                                    val port = portText.toIntOrNull()
                                    if (port != null && port in 1024..65535) {
                                        app.setPort(port)
                                        message = "端口已改为 $port" + if (running) "（服务已重启）" else ""
                                    } else message = "请输入 1024–65535 之间的端口"
                                }, Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(10.dp))
                            ControlHint("地址可长按复制。修改端口后，电脑端需要使用相同端口连接。")
                        }
                        3 -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ControlMetric("今日请求", "${today.requests}", "REQ", Modifier.weight(1f))
                                ControlMetric("最近首字响应", if (lastTtft >= 0) "$lastTtft" else "--", "MS", Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ControlMetric("输入 Token", "${today.inputTokens}", "TOK", Modifier.weight(1f))
                                ControlMetric("输出 Token", "${today.outputTokens}", "TOK", Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(10.dp))
                            ControlCard { ControlValue("最近生成速度", if (lastTokS >= 0) "%.1f tok/s".format(lastTokS) else "等待请求数据", accent = lastTokS >= 0) }
                        }
                        4 -> {
                            ControlCard {
                                ControlValue("测速目标", active?.name ?: "请先配置提供方")
                                ControlValue("端点格式", active?.apiType ?: "--")
                            }
                            Spacer(Modifier.height(12.dp))
                            ControlAction(if (speedRunning) "正在测速…" else "一键测速", "TEST", {
                                val profile = app.providerStore.active()
                                if (profile == null || !profile.usable) {
                                    speedResult = "请先配置提供方"
                                } else {
                                    speedRunning = true
                                    speedOk = false
                                    speedResult = "正在等待上游响应…"
                                    scope.launch {
                                        try {
                                            val result = SpeedTester.run(profile)
                                            if (result.ok) {
                                                speedResult = "TTFT ${result.ttftMs} ms · %.1f tok/s · 总耗时 ${result.totalMs} ms".format(result.tokPerSec)
                                                speedBrief = "${result.ttftMs}MS"
                                                speedOk = true
                                            } else {
                                                speedResult = "测速失败：${result.error}"
                                                speedBrief = "FAIL"
                                            }
                                        } finally { speedRunning = false }
                                    }
                                }
                            }, enabled = !speedRunning)
                            Spacer(Modifier.height(10.dp))
                            ControlHint("使用当前所选端点发送一次短流式请求，会消耗少量 Token。")
                            if (speedResult.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                ControlStatus(if (speedOk) "测速完成" else if (speedRunning) "正在测试" else "测试结果", speedResult, speedOk, if (speedOk) "PASS" else if (speedRunning) "BUSY" else "INFO")
                            }
                        }
                        5 -> {
                            ControlCard {
                                ControlValue("WINDOWS / 电脑端", "CloseRouter_WebUI.exe")
                                ControlValue("导出目录", "Download/CloseRouter/")
                            }
                            Spacer(Modifier.height(12.dp))
                            ControlAction("导出电脑助手", "EXE", {
                                message = runCatching { ExeExporter.export(app) }.getOrElse { "导出失败：${it.message}" }
                                exported = !message.startsWith("导出失败")
                            })
                            Spacer(Modifier.height(12.dp))
                            ControlHint("拷到电脑后双击打开，选择 USB 或局域网模式连接。无需安装 Python。")
                        }
                        else -> {
                            ControlStatus(if (batteryOk) "已允许后台运行" else "后台保护待设置",
                                if (batteryOk) "已忽略电池优化，可降低后台服务被中断的概率。" else "关闭电池优化，降低息屏后转发服务被终止的概率。",
                                batteryOk, if (batteryOk) "READY" else "SETUP")
                            Spacer(Modifier.height(12.dp))
                            ControlAction(if (batteryOk) "后台保护已启用" else "设置后台保护", "KEEP", {
                                runCatching {
                                    val manager = app.getSystemService(PowerManager::class.java)
                                    if (manager?.isIgnoringBatteryOptimizations(app.packageName) != true) {
                                        app.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                            .setData(Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    } else message = "已忽略电池优化"
                                }.onFailure { message = "无法打开系统设置：${it.message}" }
                            }, secondary = batteryOk)
                            Spacer(Modifier.height(12.dp))
                            ControlHint("vivo / 小米等机型，还需在系统设置中允许本应用“后台运行 / 自启动”。")
                        }
                    }

                    if (message.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        ControlCard { Text(message, fontSize = 11.sp, color = Pal.orangeDeep, lineHeight = 17.sp) }
                    }
                }
            }
        }
    }

    if (showEditor) {
        ProviderEditorDialog(
            initial = editingProvider ?: ProviderProfile(),
            isNew = editingProvider == null || providers.none { it.id == editingProvider!!.id },
            onDismiss = { showEditor = false },
            onSave = { p ->
                val ok = runCatching { app.providerStore.upsert(p) }.getOrElse {
                    message = "保存失败：${it.message ?: it.javaClass.simpleName}"
                    false
                }
                if (ok) {
                    message = "已保存提供方：${p.name}"
                    showEditor = false
                } else if (message.isEmpty() || !message.startsWith("保存失败")) {
                    message = "保存失败：请检查填写内容"
                }
            },
            onDelete = { p ->
                app.providerStore.remove(p.id)
                message = "已删除：${p.name}"
                showEditor = false
            }
        )
    }
}


@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.width(110.dp), fontSize = 13.sp, color = Pal.mute)
        Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun lanIp(): String {
    return runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress ?: "无网络"
    }.getOrDefault("未知")
}

@Composable
fun ProviderEditorDialog(
    initial: ProviderProfile,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProviderProfile) -> Unit,
    onDelete: (ProviderProfile) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var apiType by remember { mutableStateOf(initial.apiType) }
    var error by remember { mutableStateOf<String?>(null) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Pal.orangeDeep,
        unfocusedBorderColor = Pal.line,
        focusedLabelColor = Pal.orangeDeep,
        unfocusedLabelColor = Pal.mute,
        cursorColor = Pal.orangeDeep,
        focusedContainerColor = Color.White,
        unfocusedContainerColor = Color.White
    )
    val fieldShape = RoundedCornerShape(12.dp)
    val gap = Modifier.fillMaxWidth().padding(bottom = 8.dp)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Pal.front,
            border = BorderStroke(1.dp, Pal.line),
            shadowElevation = 8.dp
        ) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "CONFIG / PROVIDER",
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = Pal.orangeDeep, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
                Text(if (isNew) "新建提供方" else "编辑提供方", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.ink)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(2.dp).background(Pal.orange.copy(alpha = 0.7f)))
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = gap, shape = fieldShape, colors = fieldColors)
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("上游地址 / Base URL") }, singleLine = true, modifier = gap, shape = fieldShape, colors = fieldColors)
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API 密钥") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), singleLine = true, modifier = gap, shape = fieldShape, colors = fieldColors)
                OutlinedTextField(model, { model = it }, label = { Text("模型") }, singleLine = true, modifier = gap, shape = fieldShape, colors = fieldColors)
                Text("API 格式 / ENDPOINT", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Pal.mute, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                val types = ProviderProfile.API_TYPES
                val meta = mapOf(
                    "openai" to Triple("Chat", "/chat/completions", "OpenAI 兼容,Bearer 鉴权"),
                    "responses" to Triple("Responses", "/responses", "OpenAI Responses 接口"),
                    "anthropic" to Triple("Messages", "/messages", "Anthropic,x-api-key 鉴权")
                )
                val idx = types.indexOf(apiType).coerceAtLeast(0)
                BoxWithConstraints(
                    Modifier.fillMaxWidth().height(58.dp)
                        .background(Pal.side, RoundedCornerShape(14.dp))
                        .border(1.dp, Pal.lineSoft, RoundedCornerShape(14.dp))
                        .padding(4.dp)
                ) {
                    val cw = maxWidth / types.size
                    val off by animateDpAsState(cw * idx, spring(0.7f, 500f), label = "seg")
                    Box(
                        Modifier.offset(x = off).width(cw).fillMaxHeight()
                            .shadow(3.dp, RoundedCornerShape(11.dp))
                            .background(Brush.verticalGradient(listOf(Color(0xFFFFA24D), Pal.orangeDeep)), RoundedCornerShape(11.dp))
                    )
                    Row(Modifier.fillMaxSize()) {
                        types.forEach { t ->
                            val sel = apiType == t
                            val m = meta[t] ?: Triple(t, "", "")
                            Column(
                                Modifier.weight(1f).fillMaxHeight()
                                    .clip(RoundedCornerShape(11.dp))
                                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { apiType = t },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(m.first, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                    color = if (sel) Color.White else Pal.ink)
                                Text(m.second, fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                                    color = if (sel) Color.White.copy(alpha = 0.85f) else Pal.mute)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(meta[apiType]?.third ?: "", fontSize = 11.sp, color = Pal.mute)
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, color = Color(0xFFC62828), fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!isNew) TextButton(onClick = { onDelete(initial) }) { Text("删除", color = Pal.mute) }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = onDismiss, shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Pal.line),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Pal.ink)
                    ) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val p = initial.copy(name = name, baseUrl = baseUrl, apiKey = apiKey, model = model, apiType = apiType)
                            val err = ProviderStore.validate(p)
                            if (err != null) {
                                error = err // 校验失败：留在对话框内并显示原因
                            } else {
                                error = null
                                runCatching { onSave(p) }.onFailure {
                                    error = "保存失败：${it.message ?: it.javaClass.simpleName}"
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Pal.orangeDeep, contentColor = Color.White)
                    ) { Text("保存") }
                }
            }
        }
    }
}
