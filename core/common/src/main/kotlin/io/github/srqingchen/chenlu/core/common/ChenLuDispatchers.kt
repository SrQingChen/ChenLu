package io.github.srqingchen.chenlu.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 全局协程调度器出口。
 * M1 引入 Hilt 后改为可注入的 DispatcherProvider，调用点无需变更。
 */
object ChenLuDispatchers {
    val default: CoroutineDispatcher = Dispatchers.Default
    val io: CoroutineDispatcher = Dispatchers.IO
}
