package io.github.srqingchen.chenlu.core.designsystem.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 琉璃质感设计组件：渐变底 + 柔光斑背景，半透明描边圆角卡（玻璃卡）。
 * 立体感来自描边/层次/柔光，不依赖动效。
 */
@Composable
fun ChenLuBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    val top = if (dark) Color(0xFF0E1A17) else Color(0xFFEFF8F5)
    val bottom = if (dark) Color(0xFF060D0B) else Color(0xFFDFF0EA)
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom))),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val primary = if (dark) Color(0x4000897B) else Color(0x2E00897B)
            val secondary = if (dark) Color(0x3326A69A) else Color(0x2426A69A)
            drawCircle(
                brush = Brush.radialGradient(listOf(primary, Color.Transparent)),
                center = Offset(size.width * 0.85f, size.height * 0.10f),
                radius = size.minDimension * 0.9f,
            )
            drawCircle(
                brush = Brush.radialGradient(listOf(secondary, Color.Transparent)),
                center = Offset(size.width * 0.10f, size.height * 0.92f),
                radius = size.minDimension * 0.8f,
            )
        }
        content()
    }
}

/** 半透明描边玻璃卡：内容为已带 18dp 内边距与 8dp 间距的 Column。 */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = if (dark) Color(0xCC13211D) else Color(0xE6FFFFFF),
        border = BorderStroke(1.dp, if (dark) Color(0x14FFFFFF) else Color(0x66FFFFFF)),
        shadowElevation = if (dark) 0.dp else 6.dp,
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}
