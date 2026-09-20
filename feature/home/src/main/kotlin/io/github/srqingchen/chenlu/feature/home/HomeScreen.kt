package io.github.srqingchen.chenlu.feature.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.srqingchen.chenlu.core.common.ChenLuLog
import io.github.srqingchen.chenlu.core.model.AutomationRunState
import io.github.srqingchen.chenlu.core.model.TargetOrder
import io.github.srqingchen.chenlu.core.shizuku.ShizukuManager
import io.github.srqingchen.chenlu.core.shizuku.ShizukuState
import io.github.srqingchen.chenlu.engine.api.EngineRegistry
import io.github.srqingchen.chenlu.engine.api.EngineState
import io.github.srqingchen.chenlu.engine.api.InputEngine
import io.github.srqingchen.chenlu.service.AutomationController
import io.github.srqingchen.chenlu.service.AutomationService
import io.github.srqingchen.chenlu.service.overlay.OverlayHost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val runState by AutomationController.state.collectAsStateWithLifecycle()
    val engines by EngineRegistry.engines.collectAsStateWithLifecycle()
    val preference by EngineRegistry.preference.collectAsStateWithLifecycle()
    val shizukuState by ShizukuManager.state.collectAsStateWithLifecycle()

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notifGranted by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var ballShown by remember { mutableStateOf(OverlayHost.isBallShown) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notifGranted = NotificationManagerCompat.from(context).areNotificationsEnabled() }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LifecycleResumeEffect(Unit) {
        overlayGranted = Settings.canDrawOverlays(context)
        notifGranted = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("尘露") })
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "如尘随行 · 如露精准",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HeroCard(
                runState = runState,
                onToggle = { AutomationService.toggle(context) },
            )

            SectionHeader("引擎")
            EngineSection(
                engines = engines,
                preference = preference,
                shizukuState = shizukuState,
                onSelectPreference = EngineRegistry::setPreference,
                onRequestShizukuPermission = { ShizukuManager.requestPermission() },
                onOpenShizuku = { ShizukuManager.openShizukuApp(context) },
                onRetryShizuku = { ShizukuManager.rebind() },
            )

            SectionHeader("任务参数")
            ParamsCard(
                runState = runState,
                overlayGranted = overlayGranted,
                onIntervalChange = { ms ->
                    AutomationController.updateConfig { it.copy(intervalMs = ms) }
                },
                onPressChange = { ms ->
                    AutomationController.updateConfig { it.copy(pressDurationMs = ms) }
                },
                onOrderChange = { order ->
                    AutomationController.updateConfig { it.copy(order = order) }
                },
                onPick = { OverlayHost.startTargetPicker(context) },
            )

            SectionHeader("权限与悬浮窗")
            PermissionCard(
                accessibilityReady = engines.any { it.id == "accessibility" && it.state.value is EngineState.Ready },
                overlayGranted = overlayGranted,
                notifGranted = notifGranted,
                ballShown = ballShown,
                onAccessibilitySettings = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onOverlaySettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
                onNotifSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
                onToggleBall = {
                    if (ballShown) {
                        OverlayHost.hideBall(context)
                        ballShown = false
                    } else {
                        ballShown = OverlayHost.showBall(context)
                        overlayGranted = Settings.canDrawOverlays(context)
                    }
                },
            )

            if (!engines.any { it.id == "accessibility" && it.state.value is EngineState.Ready }) {
                AccessibilityHelpCard(
                    onOpenAppDetails = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                )
            }

            SectionHeader("诊断")
            DiagnosticCard(context)

            SectionHeader("Shizuku 激活指引")
            ShizukuGuideCard()

            HorizontalDivider(Modifier.padding(top = 4.dp))
            Text(
                "尘露是免费开源软件（GPL-3.0）· github.com/SrQingChen/ChenLu",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun HeroCard(runState: AutomationRunState, onToggle: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (runState.running) "运行中" else "已停止",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (runState.running) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    "引擎：${engineLabel(runState.activeEngineId)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("已执行 ${runState.executedCount} 次 · 间隔 ${runState.config.intervalMs} ms")
            runState.lastError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                Text(if (runState.running) "停止" else "开始连点")
            }
        }
    }
}

@Composable
private fun EngineSection(
    engines: List<InputEngine>,
    preference: String?,
    shizukuState: ShizukuState,
    onSelectPreference: (String?) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRetryShizuku: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = preference == null,
                    onClick = { onSelectPreference(null) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                ) { Text("自动") }
                SegmentedButton(
                    selected = preference == "accessibility",
                    onClick = { onSelectPreference("accessibility") },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                ) { Text("无障碍") }
                SegmentedButton(
                    selected = preference == "shizuku",
                    onClick = { onSelectPreference("shizuku") },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                ) { Text("Shizuku") }
            }

            engines.forEach { engine ->
                EngineStatusLine(engine)
            }

            ShizukuActionRow(
                state = shizukuState,
                onRequestPermission = onRequestShizukuPermission,
                onOpenShizuku = onOpenShizuku,
                onRetry = onRetryShizuku,
            )
        }
    }
}

@Composable
private fun EngineStatusLine(engine: InputEngine) {
    val state by engine.state.collectAsStateWithLifecycle()
    val (stateText, color) = when (val s = state) {
        EngineState.Initializing -> "连接中" to MaterialTheme.colorScheme.onSurfaceVariant
        EngineState.Ready -> "就绪" to MaterialTheme.colorScheme.primary
        is EngineState.Unavailable -> s.reason to MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(engineLabel(engine.id), style = MaterialTheme.typography.bodyMedium)
        Text(
            stateText,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

@Composable
private fun ShizukuActionRow(
    state: ShizukuState,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        ShizukuState.NotInstalled -> Text(
            "未检测到 Shizuku。安装并激活后可获得更快、更不易被检测的点击引擎；无障碍引擎无需它即可使用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ShizukuState.NotRunning -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Shizuku 未运行", style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onOpenShizuku) { Text("打开 Shizuku") }
        }

        ShizukuState.AwaitingPermission -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("等待授权", style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onRequestPermission) { Text("授权") }
        }

        ShizukuState.Connecting -> Text(
            "正在连接注入服务…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is ShizukuState.Ready -> Text(
            "Shizuku 已连接（shell 权限，uid=${state.uid}）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        is ShizukuState.Failed -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Shizuku：${state.reason}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun ParamsCard(
    runState: AutomationRunState,
    overlayGranted: Boolean,
    onIntervalChange: (Long) -> Unit,
    onPressChange: (Long) -> Unit,
    onOrderChange: (TargetOrder) -> Unit,
    onPick: () -> Unit,
) {
    val config = runState.config
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (config.targets.isEmpty()) {
                    "目标点：未设置"
                } else if (config.targets.size == 1) {
                    "目标点：1 个（${config.targets[0].x.toInt()}, ${config.targets[0].y.toInt()}）"
                } else {
                    "目标点：${config.targets.size} 个（${config.targets.joinToString(limit = 3) { "(${it.x.toInt()},${it.y.toInt()})" }}…）"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (config.targets.size > 1) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = config.order == TargetOrder.SEQUENTIAL,
                        onClick = { onOrderChange(TargetOrder.SEQUENTIAL) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("顺序") }
                    SegmentedButton(
                        selected = config.order == TargetOrder.RANDOM,
                        onClick = { onOrderChange(TargetOrder.RANDOM) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("随机") }
                }
            }
            OutlinedButton(onClick = onPick, enabled = overlayGranted, modifier = Modifier.fillMaxWidth()) {
                Text("屏幕选点（支持多点）")
            }
            if (!overlayGranted) {
                Text(
                    "选点需要悬浮窗权限，请先在“权限与悬浮窗”区授权",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "点击间隔：${config.intervalMs} ms",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = config.intervalMs.toFloat(),
                onValueChange = { onIntervalChange(it.toLong()) },
                valueRange = 16f..1000f,
            )
            Text(
                "按压时长：${config.pressDurationMs} ms",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = config.pressDurationMs.toFloat(),
                onValueChange = { onPressChange(it.toLong().coerceAtLeast(20L)) },
                valueRange = 20f..500f,
            )
        }
    }
}

@Composable
private fun PermissionCard(
    accessibilityReady: Boolean,
    overlayGranted: Boolean,
    notifGranted: Boolean,
    ballShown: Boolean,
    onAccessibilitySettings: () -> Unit,
    onOverlaySettings: () -> Unit,
    onNotifSettings: () -> Unit,
    onToggleBall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PermissionRow(
                label = "无障碍（手势引擎）",
                granted = accessibilityReady,
                actionText = "去设置",
                onAction = onAccessibilitySettings,
            )
            PermissionRow(
                label = "悬浮窗",
                granted = overlayGranted,
                actionText = "去授权",
                onAction = onOverlaySettings,
            )
            PermissionRow(
                label = "通知",
                granted = notifGranted,
                actionText = "去开启",
                onAction = onNotifSettings,
            )
            PermissionRow(
                label = if (ballShown) "控制球与准星：已显示" else "控制球与准星：已隐藏",
                granted = null,
                actionText = if (ballShown) "隐藏" else "显示",
                onAction = onToggleBall,
            )
            Text(
                "拖动控制球即可定位：准星实时跟手，松手后球吸附屏幕边缘，准星停留处即点击位置；单击球启停。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean?,
    actionText: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (granted) {
                    true -> "✓"
                    false -> "!"
                    null -> "·"
                },
                color = when (granted) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> Color.Unspecified
                },
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        TextButtonLike(actionText, enabled = granted != true, onClick = onAction)
    }
}

@Composable
private fun TextButtonLike(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled) { Text(text) }
}

@Composable
private fun AccessibilityHelpCard(onOpenAppDetails: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "无障碍列表中找不到尘露？",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "1. 打开尘露的应用详情 → 右上角 ⋮ → 「允许受限设置」" +
                    "（Android 13+ 对侧载安装应用的无障碍限制）；\n" +
                    "2. 再到 设置 → 更多设置 → 无障碍 → 已下载的应用，开启「尘露 · 手势引擎」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenAppDetails) { Text("打开尘露应用详情") }
        }
    }
}

@Composable
private fun DiagnosticCard(context: Context) {
    val entries by ChenLuLog.entries.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "最近日志（最新在上）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (entries.isEmpty()) {
                Text(
                    "暂无日志。开启引擎并执行一次点击后，这里会记录注入结果与失败原因。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                entries.takeLast(12).reversed().forEach { e ->
                    Text(
                        ChenLuLog.format(e),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (e.level) {
                            'E' -> MaterialTheme.colorScheme.error
                            'W' -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(ChenLuLog.dump())) }) {
                    Text("复制")
                }
                OutlinedButton(onClick = { shareLogFile(context) }) { Text("导出") }
                TextButton(onClick = { ChenLuLog.clear() }) { Text("清空") }
            }
        }
    }
}

private fun shareLogFile(context: Context) {
    runCatching {
        val dir = java.io.File(context.cacheDir, "logs").apply { mkdirs() }
        val file = java.io.File(dir, "chenlu_log_${System.currentTimeMillis()}.txt")
        file.writeText(ChenLuLog.dump())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享尘露日志"))
    }.onFailure {
        ChenLuLog.e("diag", "导出日志失败: ${it.message}")
    }
}

@Composable
private fun ShizukuGuideCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            "1. 安装 Shizuku（moe.shizuku.privileged.api）；\n" +
                "2. 开发者选项 → 无线调试 → 使用配对码配对（仅首次）；\n" +
                "3. 在 Shizuku 内点击「启动」；\n" +
                "4. 回到尘露点击「授权」。\n\n" +
                "小米/澎湃重要：请确认开发者选项中「USB 调试（安全设置）」已开启，" +
                "这是系统对模拟输入的防护开关，未开启时 Shizuku 注入会被拒绝" +
                "（该开关通常要求插入 SIM 卡并登录小米账号）。设备重启后需重新启动 Shizuku。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

internal fun engineLabel(id: String?): String = when (id) {
    "accessibility" -> "无障碍"
    "shizuku" -> "Shizuku"
    null -> "—"
    else -> id
}
