package io.github.srqingchen.chenlu.feature.home

import android.Manifest
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.srqingchen.chenlu.core.model.AutomationRunState
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
    val engines by remember { io.github.srqingchen.chenlu.engine.api.EngineRegistry.engines }
        .collectAsStateWithLifecycle()
    val engine = engines.firstOrNull()

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var ballShown by remember { mutableStateOf(OverlayHost.isBallShown) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LifecycleResumeEffect(Unit) {
        overlayGranted = Settings.canDrawOverlays(context)
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("尘露") }) },
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
                "如尘随行，如露精准",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EngineCard(engine)
            RunCard(
                runState = runState,
                onToggle = { AutomationService.toggle(context) },
                onIntervalChange = { ms ->
                    AutomationController.updateConfig { it.copy(intervalMs = ms) }
                },
            )
            OverlayCard(
                granted = overlayGranted,
                ballShown = ballShown,
                onRequestPermission = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
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
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开无障碍设置（启用手势引擎）")
            }
        }
    }
}

@Composable
private fun EngineCard(engine: InputEngine?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("输入引擎", style = MaterialTheme.typography.titleMedium)
            if (engine == null) {
                Text("未注册引擎", color = MaterialTheme.colorScheme.error)
                return@Column
            }
            val stateText = when (val s = engine.state.collectAsStateWithLifecycle().value) {
                EngineState.Initializing -> "初始化…"
                EngineState.Ready -> "就绪"
                is EngineState.Unavailable -> s.reason
            }
            val ready = engine.state.collectAsStateWithLifecycle().value is EngineState.Ready
            Text(
                text = "${engine.id} · $stateText",
                color = if (ready) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
            Text(
                engine.capabilities.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RunCard(
    runState: AutomationRunState,
    onToggle: () -> Unit,
    onIntervalChange: (Long) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("连点任务", style = MaterialTheme.typography.titleMedium)
            Text(
                "状态：${if (runState.running) "运行中" else "已停止"} · 已执行 ${runState.executedCount} 次",
            )
            runState.lastError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "点击间隔：${runState.config.intervalMs} ms",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = runState.config.intervalMs.toFloat(),
                onValueChange = { onIntervalChange(it.toLong()) },
                valueRange = 16f..1000f,
            )
            Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                Text(if (runState.running) "停止" else "开始连点")
            }
        }
    }
}

@Composable
private fun OverlayCard(
    granted: Boolean,
    ballShown: Boolean,
    onRequestPermission: () -> Unit,
    onToggleBall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("悬浮窗", style = MaterialTheme.typography.titleMedium)
            if (!granted) {
                Text("未授予悬浮窗权限", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("去授予悬浮窗权限")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (ballShown) "控制球已显示" else "控制球已隐藏")
                    OutlinedButton(onClick = onToggleBall) {
                        Text(if (ballShown) "隐藏" else "显示控制球")
                    }
                }
            }
        }
    }
}
