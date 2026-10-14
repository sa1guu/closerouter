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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.*
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

    var providersVersion by remember { mutableStateOf(0) }
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
    var selected by remember { mutableStateOf(0) }

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
        "把电脑助手拷到电脑双击即可连接",
        "防止系统在后台杀死转发服务"
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(Pal.bg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
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
            onSelect = { selected = it },
            modifier = Modifier.fillMaxWidth().aspectRatio(1f / 1.22f)
        )

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text("TAP A BAY · 点击机架单元", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Pal.mute)
            Spacer(Modifier.weight(1f))
            Text(
                "${lit.count { it }}/$BAY_COUNT up · ${today.inputTokens + today.outputTokens} tok · :${App.port}",
                fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Pal.mute
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .padding(horizontal = 20.dp, vertical = 20.dp)
                .heightIn(min = 260.dp)
        ) {
            Text(
                "0${selected + 1} / 0$BAY_COUNT · ${BAY_CODES[selected]}",
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Pal.orangeDeep, fontWeight = FontWeight.Bold
            )
            Text(titles[selected], fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(subtitles[selected], fontSize = 12.sp, color = Pal.mute)
            Spacer(Modifier.height(14.dp))

            when (selected) {
                0 -> {
                    Text(
                        if (running) "● 运行中" else "○ 已停止",
                        fontSize = 16.sp, fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = {
                        if (running) ProxyService.stop(app) else ProxyService.start(app)
                    }) { Text(if (running) "停止转发" else "启动转发") }
                }
                1 -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            OutlinedButton(onClick = { providerMenu = true }) {
                                Text(active?.name ?: "（未配置）", maxLines = 1)
                            }
                            DropdownMenu(
                                expanded = providerMenu,
                                onDismissRequest = { providerMenu = false },
                                containerColor = Pal.front,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Pal.line)
                            ) {
                                providers.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text((if (p.id == active?.id) "✓ " else "　") + p.name) },
                                        onClick = {
                                            app.providerStore.setActive(p.id)
                                            providersVersion++
                                            providerMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            editingProvider = active
                            showEditor = true
                        }) { Text("编辑") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            editingProvider = ProviderProfile(name = "新提供方")
                            showEditor = true
                        }) { Text("新建") }
                    }
                    active?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${it.apiType} · ${it.baseUrl} · ${it.model}",
                            fontSize = 11.sp, color = Pal.mute, fontFamily = FontFamily.Monospace
                        )
                    }
                }
                2 -> {
                    Text("USB: http://127.0.0.1:${App.port}/v1", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("LAN: http://${lanIp()}:${App.port}/v1", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                            label = { Text("端口") },
                            singleLine = true,
                            modifier = Modifier.width(140.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            val p = portText.toIntOrNull()
                            if (p != null && p in 1024..65535) {
                                app.setPort(p)
                                message = "端口已改为 $p" + if (running) "（服务已重启）" else ""
                            } else message = "端口无效"
                        }) { Text("应用") }
                    }
                }
                3 -> {
                    StatRow("今日请求", "${today.requests}")
                    StatRow("今日 Token", "输入 ${today.inputTokens} / 输出 ${today.outputTokens}")
                    StatRow("最近 TTFT", if (lastTtft >= 0) "$lastTtft ms" else "-")
                    StatRow("最近 tok/s", if (lastTokS >= 0) "%.1f".format(lastTokS) else "-")
                }
                4 -> {
                    Button(
                        onClick = {
                            val p = app.providerStore.active()
                            if (p == null || !p.usable) { speedResult = "请先配置提供方"; return@Button }
                            speedRunning = true
                            speedOk = false
                            speedResult = "测速中…"
                            scope.launch {
                                val r = SpeedTester.run(p)
                                if (r.ok) {
                                    speedResult = "TTFT ${r.ttftMs} ms · %.1f tok/s · 总耗时 ${r.totalMs} ms".format(r.tokPerSec)
                                    speedBrief = "${r.ttftMs}MS"
                                    speedOk = true
                                } else {
                                    speedResult = "测速失败：${r.error}"
                                    speedBrief = "FAIL"
                                }
                                speedRunning = false
                            }
                        },
                        enabled = !speedRunning
                    ) { Text("一键测速") }
                    if (speedResult.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(speedResult, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                5 -> {
                    OutlinedButton(onClick = {
                        message = runCatching { ExeExporter.export(app) }.getOrElse { "导出失败：${it.message}" }
                        exported = !message.startsWith("导出失败")
                    }) { Text("导出电脑助手 (closerouter.exe)") }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "导出到 Download/CloseRouter/，拷到电脑双击即可自动连接，无需输命令。",
                        fontSize = 11.sp, color = Pal.mute
                    )
                }
                else -> {
                    OutlinedButton(onClick = {
                        runCatching {
                            val pm = app.getSystemService(PowerManager::class.java)
                            if (pm?.isIgnoringBatteryOptimizations(app.packageName) != true) {
                                app.startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                        .setData(Uri.parse("package:${app.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } else message = "已忽略电池优化"
                        }
                    }) { Text(if (batteryOk) "已忽略电池优化 ✓" else "防后台杀死（忽略电池优化）") }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "vivo / 小米等机型还需在系统设置里手动允许本应用「后台运行 / 自启动」。",
                        fontSize = 11.sp, color = Pal.mute
                    )
                }
            }

            if (message.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(message, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Pal.orangeDeep)
            }
        }
    }

    if (showEditor) {
        ProviderEditorDialog(
            initial = editingProvider ?: ProviderProfile(),
            isNew = editingProvider == null || providers.none { it.id == editingProvider!!.id },
            onDismiss = { showEditor = false },
            onSave = { p ->
                app.providerStore.upsert(p)
                providersVersion++
                showEditor = false
            },
            onDelete = { p ->
                app.providerStore.remove(p.id)
                providersVersion++
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
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("baseUrl") }, singleLine = true, modifier = gap, shape = fieldShape, colors = fieldColors)
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("apiKey") }, singleLine = true, modifier = gap, shape = fieldShape, colors = fieldColors)
                OutlinedTextField(model, { model = it }, label = { Text("模型") }, singleLine = true, modifier = gap, shape = fieldShape, colors = fieldColors)
                Text("API TYPE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Pal.mute)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderProfile.API_TYPES.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = apiType == t, onClick = { apiType = t },
                                colors = RadioButtonDefaults.colors(selectedColor = Pal.orangeDeep, unselectedColor = Pal.line)
                            )
                            Text(t, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Pal.ink)
                        }
                    }
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
                            onSave(initial.copy(name = name, baseUrl = baseUrl, apiKey = apiKey, model = model, apiType = apiType))
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Pal.orangeDeep, contentColor = Color.White)
                    ) { Text("保存") }
                }
            }
        }
    }
}
