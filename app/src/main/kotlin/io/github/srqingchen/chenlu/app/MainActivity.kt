package io.github.srqingchen.chenlu.app

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.srqingchen.chenlu.core.designsystem.theme.ChenLuTheme
import io.github.srqingchen.chenlu.feature.home.HomeScreen
import io.github.srqingchen.chenlu.service.overlay.OverlayHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 悬浮窗启动后默认开启（已授权时；未授权则由首页引导）
        if (Settings.canDrawOverlays(this)) {
            window.decorView.post { OverlayHost.showBall(this) }
        }
        setContent {
            ChenLuTheme {
                HomeScreen()
            }
        }
    }
}
