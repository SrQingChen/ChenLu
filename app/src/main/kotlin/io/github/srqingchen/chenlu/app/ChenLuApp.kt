package io.github.srqingchen.chenlu.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.srqingchen.chenlu.core.data.TaskRepository
import io.github.srqingchen.chenlu.core.shizuku.ShizukuManager
import io.github.srqingchen.chenlu.engine.accessibility.AccessibilityInputEngine
import io.github.srqingchen.chenlu.engine.api.EngineRegistry
import io.github.srqingchen.chenlu.engine.shizuku.ShizukuInputEngine
import io.github.srqingchen.chenlu.service.island.FocusIslandPublisher

class ChenLuApp : Application() {

    private var activityCount = 0

    override fun onCreate() {
        super.onCreate()
        // 组装根：注册双引擎并开始监听 Shizuku。M1 引入 Hilt 后由 DI 容器接管。
        ShizukuManager.start(this)
        TaskRepository.init(this)
        EngineRegistry.register(AccessibilityInputEngine())
        EngineRegistry.register(ShizukuInputEngine(this))

        // 前后台监听：离开应用上待命岛，回应用收起
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                activityCount++
                if (activityCount == 1) {
                    FocusIslandPublisher.appForeground = true
                    FocusIslandPublisher.onAppForeground(this@ChenLuApp)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                activityCount--
                if (activityCount == 0) {
                    FocusIslandPublisher.appForeground = false
                    FocusIslandPublisher.onAppBackground(this@ChenLuApp)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
