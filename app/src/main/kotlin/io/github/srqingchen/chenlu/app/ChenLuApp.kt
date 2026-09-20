package io.github.srqingchen.chenlu.app

import android.app.Application
import io.github.srqingchen.chenlu.engine.accessibility.AccessibilityInputEngine
import io.github.srqingchen.chenlu.engine.api.EngineRegistry

class ChenLuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 组装根：注册引擎实现。M1 引入 Hilt 后由 DI 容器接管。
        EngineRegistry.register(AccessibilityInputEngine())
    }
}
