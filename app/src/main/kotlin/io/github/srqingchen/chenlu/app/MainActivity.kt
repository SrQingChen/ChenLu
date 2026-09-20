package io.github.srqingchen.chenlu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.srqingchen.chenlu.core.designsystem.theme.ChenLuTheme
import io.github.srqingchen.chenlu.feature.home.HomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChenLuTheme {
                HomeScreen()
            }
        }
    }
}
