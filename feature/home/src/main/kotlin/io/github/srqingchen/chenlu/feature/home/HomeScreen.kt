package io.github.srqingchen.chenlu.feature.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import io.github.srqingchen.chenlu.core.data.TaskRepository
import io.github.srqingchen.chenlu.core.designsystem.theme.ChenLuBackground
import io.github.srqingchen.chenlu.core.designsystem.theme.GlassCard
import io.github.srqingchen.chenlu.core.model.AutomationRunState
import io.github.srqingchen.chenlu.core.model.TargetOrder
import io.github.srqingchen.chenlu.core.model.TapConfig
import io.github.srqingchen.chenlu.core.shizuku.ShizukuManager
import io.github.srqingchen.chenlu.core.shizuku.ShizukuState
import io.github.srqingchen.chenlu.engine.api.EngineRegistry
import io.github.srqingchen.chenlu.engine.api.EngineState
import io.github.srqingchen.chenlu.engine.api.InputEngine
import io.github.srqingchen.chenlu.service.AutomationController
import io.github.srqingchen.chenlu.service.AutomationService
import io.github.srqingchen.chenlu.service.island.FocusIslandPublisher
import io.github.srqingchen.chenlu.service.overlay.OverlayHost
import kotlinx.coroutines.launch

/** 底部分区定义：title 大标题，subtitle 功能分类，tags 页头功能标签。 */
private enum class AppSection(
    val title: String,
    val subtitle: String,
    val tags: List<String>,
    val icon: ImageVector,
) {
    HOME("主页", "运行状态与快速控制", listOf("启停", "状态", "权限速览"), Icons.Outlined.Home),
    TASK("任务", "目标点与节奏编排", listOf("屏幕选点", "顺序 / 随机", "任务库"), Icons.Outlined.List),
    ENGINE("引擎", "输入通道管理", listOf("无障碍", "Shizuku", "三级注入链"), Icons.Outlined.Build),
    DIAG("诊断", "权限 · 日志 · 关于", listOf("悬浮窗", "日志导出", "开源信息"), Icons.Outlined.Settings),
}

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
    var sectionIndex by rememberSaveable { mutableStateOf(0) }
    val section = AppSection.entries[sectionIndex]

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
        ballShown = OverlayHost.isBallShown
        onPauseOrDispose { }
    }

    ChenLuBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 0.dp,
                ) {
                    AppSection.entries.forEach { s ->
                        NavigationBarItem(
                            selected = s == section,
                            onClick = { sectionIndex = s.ordinal },
                            icon = { Icon(s.icon, contentDescription = s.title) },
                            label = { Text(s.title) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
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
                SectionBanner(section)
                Spacer(Modifier.height(2.dp))
                when (section) {
                    AppSection.HOME -> HomePage(
                        runState = runState,
                        accessibilityReady = engines.any {
                            it.id == "accessibility" && it.state.value is EngineState.Ready
                        },
                        overlayGranted = overlayGranted,
                        notifGranted = notifGranted,
                        ballShown = ballShown,
                        onToggle = { AutomationService.toggle(context) },
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

                    AppSection.TASK -> TaskPage(
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
                        onPick = {
                            OverlayHost.startTargetPicker(context)
                            ballShown = OverlayHost.isBallShown
                        },
                        onSaveTask = { name ->
                            ChenLuLog.i("tasks", "保存任务: $name")
                            TaskRepository.save(name, runState.config)
                        },
                        onLoadTask = { task ->
                            val loaded = task.config ?: return@TaskPage
                            AutomationController.updateConfig { existing ->
                                loaded.copy(targets = loaded.targets.ifEmpty { existing.targets })
                            }
                            ChenLuLog.i("tasks", "加载任务: ${task.name}")
                        },
                        onDeleteTask = { task ->
                            TaskRepository.delete(task.id)
                            ChenLuLog.i("tasks", "删除任务: ${task.name}")
                        },
                    )

                    AppSection.ENGINE -> EnginePage(
                        engines = engines,
                        preference = preference,
                        shizukuState = shizukuState,
                        onSelectPreference = EngineRegistry::setPreference,
                        onRequestShizukuPermission = { ShizukuManager.requestPermission() },
                        onOpenShizuku = { ShizukuManager.openShizukuApp(context) },
                        onRetryShizuku = { ShizukuManager.rebind() },
                    )

                    AppSection.DIAG -> DiagPage(
                        accessibilityReady = engines.any {
                            it.id == "accessibility" && it.state.value is EngineState.Ready
                        },
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
                        onAppDetails = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
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
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ---------- 分区页头 ----------

@Composable
private fun SectionBanner(section: AppSection) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text("尘露", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(
                    section.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                section.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            section.tags.forEach { tag -> GlassTag(tag) }
        }
    }
}

@Composable
private fun GlassTag(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        ),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// ---------- 主页 ----------

@Composable
private fun HomePage(
    runState: AutomationRunState,
    accessibilityReady: Boolean,
    overlayGranted: Boolean,
    notifGranted: Boolean,
    ballShown: Boolean,
    onToggle: () -> Unit,
    onToggleBall: () -> Unit,
) {
    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (runState.running) "运行中" else "已停止",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = if (runState.running) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "已执行 ${runState.executedCount} 次",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "引擎 · ${engineLabel(runState.activeEngineId)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        runState.lastError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(if (runState.running) "停止" else "开始连点")
        }
    }

    GlassCard {
        Text("权限速览", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        QuickPermRow("无障碍引擎", accessibilityReady)
        QuickPermRow("悬浮窗", overlayGranted)
        QuickPermRow("通知", notifGranted)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (ballShown) "控制球与准星：已显示" else "控制球与准星：已隐藏",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onToggleBall) { Text(if (ballShown) "隐藏" else "显示") }
        }
    }

    GlassCard {
        Text(
            "拖动控制球即可定位：准星实时跟手，松手后球吸附屏幕边缘（与准星重合时吸附另一侧），" +
                "准星停留处即点击位置；单击球启停。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuickPermRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (granted) "✓ 已就绪" else "! 待处理（诊断页）",
            style = MaterialTheme.typography.bodyMedium,
            color = if (granted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

// ---------- 任务页 ----------

@Composable
private fun TaskPage(
    runState: AutomationRunState,
    overlayGranted: Boolean,
    onIntervalChange: (Long) -> Unit,
    onPressChange: (Long) -> Unit,
    onOrderChange: (TargetOrder) -> Unit,
    onPick: () -> Unit,
    onSaveTask: (String) -> Unit,
    onLoadTask: (TaskRepository.SavedTask) -> Unit,
    onDeleteTask: (TaskRepository.SavedTask) -> Unit,
) {
    val config = runState.config

    GlassCard {
        Text("目标点", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            if (config.targets.isEmpty()) {
                "未设置"
            } else if (config.targets.size == 1) {
                "1 个（${config.targets[0].x.toInt()}, ${config.targets[0].y.toInt()}）"
            } else {
                "${config.targets.size} 个（${config.targets.joinToString(limit = 3) { "(${it.x.toInt()},${it.y.toInt()})" }}…）"
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
    }

    GlassCard {
        Text("节奏", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("点击间隔：${config.intervalMs} ms", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = config.intervalMs.toFloat(),
            onValueChange = { onIntervalChange(it.toLong()) },
            valueRange = 16f..1000f,
        )
        Text("按压时长：${config.pressDurationMs} ms", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = config.pressDurationMs.toFloat(),
            onValueChange = { onPressChange(it.toLong().coerceAtLeast(20L)) },
            valueRange = 20f..500f,
        )
    }

    FinishConditionCard(config = config)

    GlassCard {
        Text("防检测", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "坐标抖动与时序抖动让点击更接近真人，配合「随机」顺序构成防检测三件套。0 = 关闭。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "坐标抖动：" + if (config.jitterPx > 0) "±${config.jitterPx}px" else "关",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = config.jitterPx.toFloat(),
            onValueChange = {
                AutomationController.updateConfig { c -> c.copy(jitterPx = it.toInt()) }
            },
            valueRange = 0f..30f,
        )
        Text(
            "时序抖动：" + if (config.jitterMs > 0) "±${config.jitterMs}ms" else "关",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = config.jitterMs.toFloat(),
            onValueChange = {
                AutomationController.updateConfig { c -> c.copy(jitterMs = it.toLong()) }
            },
            valueRange = 0f..100f,
        )
    }

    TaskLibraryCard(onSave = onSaveTask, onLoad = onLoadTask, onDelete = onDeleteTask)
}

/** 完成条件：总次数 / 总时长（0 = 不限），驱动岛上的真实进度与自动停止。 */
@Composable
private fun FinishConditionCard(config: TapConfig) {
    var clicks by remember(config.totalClicks) {
        mutableStateOf(if (config.totalClicks > 0) config.totalClicks.toString() else "")
    }
    var minutes by remember(config.totalDurationMs) {
        mutableStateOf(
            if (config.totalDurationMs > 0) (config.totalDurationMs / 60000).toString() else "",
        )
    }
    GlassCard {
        Text("完成条件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "到量自动停止；岛上进度环将显示 次数/总次数 或 时长/总时长。两者留空 = 不限。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = clicks,
            onValueChange = { clicks = it.filter { c -> c.isDigit() }.take(7) },
            label = { Text("总次数（可选）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = minutes,
            onValueChange = { minutes = it.filter { c -> c.isDigit() }.take(4) },
            label = { Text("总时长（分钟，可选）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                AutomationController.updateConfig {
                    it.copy(
                        totalClicks = clicks.toLongOrNull() ?: 0L,
                        totalDurationMs = (minutes.toLongOrNull() ?: 0L) * 60_000L,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存完成条件") }
    }
}

@Composable
private fun TaskLibraryCard(
    onSave: (String) -> Unit,
    onLoad: (TaskRepository.SavedTask) -> Unit,
    onDelete: (TaskRepository.SavedTask) -> Unit,
) {
    val context = LocalContext.current
    val tasks by TaskRepository.tasks.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }

    GlassCard {
        Text("任务库", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("任务名称") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                onSave(name)
                name = ""
            }) { Text("保存") }
        }
        if (tasks.isEmpty()) {
            Text(
                "暂无任务。保存当前配置（目标点/节奏/顺序）即可复用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            tasks.forEach { task ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(task.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            task.config?.let { c ->
                                "${c.targets.size} 点 · ${c.intervalMs}ms · " +
                                    if (c.order == TargetOrder.RANDOM) "随机" else "顺序"
                            } ?: "配置损坏",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onLoad(task) }) { Text("加载") }
                    TextButton(onClick = {
                        runCatching {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "// ChenLu task: ${task.name}\n${task.configJson}",
                                )
                            }
                            context.startActivity(Intent.createChooser(intent, "分享任务"))
                        }
                    }) { Text("分享") }
                    TextButton(onClick = { onDelete(task) }) { Text("删除") }
                }
            }
        }
    }
}

// ---------- 引擎页 ----------

@Composable
private fun EnginePage(
    engines: List<InputEngine>,
    preference: String?,
    shizukuState: ShizukuState,
    onSelectPreference: (String?) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRetryShizuku: () -> Unit,
) {
    GlassCard {
        Text("引擎选择", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                Text(stateText, style = MaterialTheme.typography.bodyMedium, color = color)
            }
            Text(
                engine.capabilities.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ShizukuActionRow(
            state = shizukuState,
            onRequestPermission = onRequestShizukuPermission,
            onOpenShizuku = onOpenShizuku,
            onRetry = onRetryShizuku,
        )
    }

    // 无障碍找不到入口时的 Shizuku 一键开启
    if (shizukuState is ShizukuState.Ready &&
        engines.any { it.id == "accessibility" && it.state.value is EngineState.Unavailable }
    ) {
        val context = LocalContext.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        GlassCard {
            Text(
                "无障碍入口找不到？",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        val component =
                            "${context.packageName}/io.github.srqingchen.chenlu.engine.accessibility.ChenLuAccessibilityService"
                        ShizukuManager.enableAccessibilityService(context.packageName, component)
                        kotlinx.coroutines.delay(3000L)
                        val ready = EngineRegistry.engines.value.any {
                            it.id == "accessibility" && it.state.value is EngineState.Ready
                        }
                        ChenLuLog.i(
                            "shizuku",
                            if (ready) "一键开启后 3 秒验证：无障碍已连接" else "一键开启后 3 秒验证：仍未连接（写入可能被系统回滚，见 readback 日志）",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("经 Shizuku 一键开启无障碍")
            }
            Text(
                "先解除侧载应用的受限设置（appops），再写入系统设置并回读校验；结果见诊断页日志。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    GlassCard {
        Text("原生超级岛（澎湃 OS 3）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("任务状态上岛", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = FocusIslandPublisher.enabled,
                onCheckedChange = { FocusIslandPublisher.enabled = it },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("兼容模式（断 xmsf 鉴权）", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = FocusIslandPublisher.compatMode,
                onCheckedChange = { FocusIslandPublisher.compatMode = it },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("常驻待命岛（离开应用显示）", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = FocusIslandPublisher.idleEnabled,
                onCheckedChange = { FocusIslandPublisher.idleEnabled = it },
            )
        }
        Text(
            "离开应用界面即上岛（待命态，轻点岛上的「开始」随时连点），回到应用自动收起；" +
                "任务运行时岛显示真实进度（次数/总次数 或 时长/总时长）；" +
                "在通知栏下拉展开可看到简易控制面板（启停 / 间隔± / 打开应用）。" +
                "兼容模式说明：岛展示期间临时切断小米推送服务联网令鉴权放行（MAA 同款），" +
                "期间全机小米推送可能延迟，岛收起即恢复。未生效时退化为常驻通知。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (engines.any { it.id == "accessibility" && it.state.value is EngineState.Unavailable }) {
        GlassCard {
            Text(
                "无障碍列表中找不到尘露？",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "1. 应用详情 → 右上角 ⋮ → 「允许受限设置」（Android 13+ 侧载限制）；\n" +
                    "2. 设置 → 更多设置 → 无障碍 → 已下载的应用，开启「尘露 · 手势引擎」。" +
                    "（详见诊断页的引导按钮）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    GlassCard {
        Text("Shizuku 激活指引", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "1. 安装 Shizuku（moe.shizuku.privileged.api）；\n" +
                "2. 开发者选项 → 无线调试 → 使用配对码配对（仅首次）；\n" +
                "3. 在 Shizuku 内点击「启动」；\n" +
                "4. 回到尘露引擎页点击「授权」。\n\n" +
                "小米/澎湃：需开启「USB 调试（安全设置）」（通常要求插 SIM 并登录小米账号），" +
                "否则注入被拒。重启后需重新启动 Shizuku。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

// ---------- 诊断页 ----------

@Composable
private fun DiagPage(
    accessibilityReady: Boolean,
    overlayGranted: Boolean,
    notifGranted: Boolean,
    ballShown: Boolean,
    onAccessibilitySettings: () -> Unit,
    onOverlaySettings: () -> Unit,
    onNotifSettings: () -> Unit,
    onAppDetails: () -> Unit,
    onToggleBall: () -> Unit,
) {
    GlassCard {
        Text("权限", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        PermRow("无障碍（手势引擎）", accessibilityReady, "去设置", onAccessibilitySettings)
        PermRow("悬浮窗", overlayGranted, "去授权", onOverlaySettings)
        PermRow("通知", notifGranted, "去开启", onNotifSettings)
        PermRow("受限设置（找不到无障碍入口时）", null, "应用详情", onAppDetails)
        PermRow(
            label = if (ballShown) "控制球与准星：已显示" else "控制球与准星：已隐藏",
            granted = null,
            actionText = if (ballShown) "隐藏" else "显示",
            onAction = onToggleBall,
        )
    }

    DiagnosticCard()

    GlassCard {
        Text("关于", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "尘露 v0.5.0 · GPL-3.0 开源 · github.com/SrQingChen/ChenLu\n" +
                "免费无广告。仅供学习研究与个人效率用途，请遵守目标应用条款（详见仓库 DISCLAIMER）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermRow(
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
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
        TextButton(onClick = onAction, enabled = granted != true) { Text(actionText) }
    }
}

@Composable
private fun DiagnosticCard() {
    val context = LocalContext.current
    val entries by ChenLuLog.entries.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    GlassCard {
        Text("运行日志（最新在上）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (entries.isEmpty()) {
            Text(
                "暂无日志。开启引擎并执行一次点击后，这里会记录注入结果与失败原因。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 显示最近的日志，限制高度避免过长
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                entries.takeLast(60).reversed().forEach { e ->
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

internal fun engineLabel(id: String?): String = when (id) {
    "accessibility" -> "无障碍"
    "shizuku" -> "Shizuku"
    null -> "—"
    else -> id
}
