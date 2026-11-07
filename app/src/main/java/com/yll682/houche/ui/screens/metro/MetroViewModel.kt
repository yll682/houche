package com.yll682.houche.ui.screens.metro

import com.yll682.houche.AppGraph
import com.yll682.houche.core.City
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroLoader
import com.yll682.houche.core.MetroProbe
import com.yll682.houche.ui.StateViewModel
import com.yll682.houche.ui.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect

/** online 表示这一屏用的是官方接口拿到的站序；note 只在「设置 → 关于」里露脸。 */
data class MetroData(
    val lines: List<MetroLine> = emptyList(),
    val selected: String = "",
    val updated: String = "",
    /** 站序出处，界面不铺开，只在「设置 → 关于」里说明。 */
    val source: String = "",
    val online: Boolean = false,
    val note: String = ""
) {
    val line: MetroLine? get() = lines.firstOrNull { it.id == selected } ?: lines.firstOrNull()
}

class MetroViewModel(
    private val graph: AppGraph,
    val city: City,
    initial: String? = null
) : StateViewModel<MetroData>(MetroData()) {
    private val loader = MetroLoader(graph.metro::networkFor, graph.metroRemote::latest, graph.metro::supports)
    private var loading: Job? = null
    private var preferred = initial

    init {
        mutable.value = UiState.Loading(data)
        load(initial)
    }

    /**
     * 进入页面立即发起在线请求；等待期间用内置资料即时展示，成功后整体采用在线资料。
     * 新一次加载会取消上一次，避免较旧的响应覆盖最新站序。
     */
    fun load(initial: String? = null): Job {
        loading?.cancel()
        if (initial != null) preferred = initial
        return action {
            loader.load(city).collect { snapshot ->
                val network = snapshot.network
                val lines = network?.openedLines.orEmpty()
                update(
                    MetroData(
                        lines = lines,
                        selected = pick(preferred ?: data.selected, lines),
                        updated = network?.updated.orEmpty(),
                        source = network?.source.orEmpty(),
                        online = snapshot.online,
                        note = snapshot.note
                    ),
                    empty = lines.isEmpty()
                )
            }
        }.also { loading = it }
    }

    private fun pick(candidate: String?, lines: List<MetroLine>): String =
        candidate?.takeIf { id -> lines.any { it.id == id } } ?: lines.firstOrNull()?.id.orEmpty()

    fun select(id: String) {
        if (data.lines.none { it.id == id }) return
        preferred = id
        val value = data.copy(selected = id)
        mutable.value = when (val old = mutable.value) {
            is UiState.Error -> old.copy(data = value)
            is UiState.Loading -> old.copy(data = value)
            is UiState.Empty -> old.copy(data = value)
            is UiState.Success -> old.copy(data = value)
        }
    }

    fun retry() = load(data.selected.ifBlank { null })

    /** 读取最近一次请求的诊断；失败原因不进入地铁页状态。 */
    fun lastProbe(): MetroProbe = graph.metroRemote.last ?: MetroProbe(online = false, note = "")
}
