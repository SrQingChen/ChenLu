package io.github.srqingchen.chenlu.engine.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 引擎注册表：app 启动时注册各实现。
 * 解析顺序：用户偏好的引擎（需就绪）→ 任一就绪引擎 → 首个已注册引擎。
 */
object EngineRegistry {

    private val _engines = MutableStateFlow<List<InputEngine>>(emptyList())
    val engines: StateFlow<List<InputEngine>> = _engines.asStateFlow()

    /** null 表示“自动”（首个就绪引擎，Shizuku 优先注册者语义由注册顺序决定）。 */
    private val _preference = MutableStateFlow<String?>(null)
    val preference: StateFlow<String?> = _preference.asStateFlow()

    fun setPreference(engineId: String?) {
        _preference.value = engineId
    }

    fun register(engine: InputEngine) {
        _engines.update { list -> list.filterNot { it.id == engine.id } + engine }
    }

    fun unregister(engineId: String) {
        _engines.update { list -> list.filterNot { it.id == engineId } }
    }

    fun activeEngine(): InputEngine? {
        val list = _engines.value
        val preferred = _preference.value
        return list.firstOrNull { it.id == preferred && it.state.value is EngineState.Ready }
            ?: list.firstOrNull { it.state.value is EngineState.Ready }
            ?: list.firstOrNull()
    }
}
