package com.yll682.houche.ui.screens.metro

import com.yll682.houche.AppGraph
import com.yll682.houche.core.City
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroProbe
import com.yll682.houche.ui.StateViewModel
import com.yll682.houche.ui.UiState

/** online 表示这一屏用的是官方接口拿到的站序；note 只在「设置 → 关于」里露脸。 */
data class MetroData(
    val lines: List<MetroLine> = emptyList(),
    val selected: String = "",
    val updated: String = "",
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
    init {
        load(initial)
    }

    /**
     * 先用内置 metro.json 把界面铺满，再后台探一次官方接口。
     * 接口通了就整体替换成在线站序；不通就保持内置，只有 note 变化，界面不该出现错误态。
     */
    fun load(initial: String? = null) = action {
        val network = graph.metro.networkFor(city)
        if (network == null) {
            update(MetroData(), empty = true)
            return@action
        }
        val lines = network.openedLines
        update(MetroData(lines = lines, selected = pick(initial, lines), updated = network.updated))

        val probe = graph.metroRemote.latest()
        if (!probe.online || probe.lines.isEmpty()) {
            update(data.copy(note = probe.note))
            return@action
        }
        val online = probe.lines
        update(
            data.copy(
                lines = online,
                selected = pick(data.selected.ifBlank { initial }, online),
                online = true,
                note = probe.note
            )
        )
    }

    private fun pick(candidate: String?, lines: List<MetroLine>): String =
        candidate?.takeIf { id -> lines.any { it.id == id } } ?: lines.firstOrNull()?.id.orEmpty()

    fun select(id: String) {
        if (data.lines.none { it.id == id }) return
        val value = data.copy(selected = id)
        mutable.value = when (val old = mutable.value) {
            is UiState.Error -> old.copy(data = value)
            is UiState.Loading -> old.copy(data = value)
            is UiState.Empty -> old.copy(data = value)
            is UiState.Success -> old.copy(data = value)
        }
    }

    fun retry() = load(data.selected.ifBlank { null })

    /** 供「设置 → 关于」读取的最近一次探测结论。 */
    fun lastProbe(): MetroProbe = MetroProbe(online = data.online, note = data.note)
}