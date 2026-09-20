package io.github.srqingchen.chenlu.app

import android.app.Application
import io.github.srqingchen.chenlu.core.data.TaskRepository
import io.github.srqingchen.chenlu.core.shizuku.ShizukuManager
import io.github.srqingchen.chenlu.engine.accessibility.AccessibilityInputEngine
import io.github.srqingchen.chenlu.engine.api.EngineRegistry
import io.github.srqingchen.chenlu.engine.shizuku.ShizukuInputEngine

class ChenLuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 组装根：注册双引擎并开始监听 Shizuku。M1 引入 Hilt 后由 DI 容器接管。
        ShizukuManager.start(this)
        TaskRepository.init(this)
        EngineRegistry.register(AccessibilityInputEngine())
        EngineRegistry.register(ShizukuInputEngine(this))
    }
}
