package io.github.srqingchen.chenlu.engine.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 引擎注册表：app 启动时注册各实现。
 * M2 引入 Shizuku 后按“用户偏好 → 能力匹配 → 可用性”解析活跃引擎。
 */
object EngineRegistry {

    private val _engines = MutableStateFlow<List<InputEngine>>(emptyList())
    val engines: StateFlow<List<InputEngine>> = _engines.asStateFlow()

    fun register(engine: InputEngine) {
        _engines.update { list -> list.filterNot { it.id == engine.id } + engine }
    }

    fun unregister(engineId: String) {
        _engines.update { list -> list.filterNot { it.id == engineId } }
    }

    fun active(): InputEngine? = _engines.value.firstOrNull()
}
