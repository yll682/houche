package com.yll682.houche.ui.screens.metro

import com.yll682.houche.AppGraph
import com.yll682.houche.core.City
import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroLoader
import com.yll682.houche.core.MetroProbe
import com.yll682.houche.core.MetroRealtime
import com.yll682.houche.core.realtimeCode
import com.yll682.houche.ui.StateViewModel
import com.yll682.houche.ui.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import java.util.Calendar

/** online 表示这一屏用的是官方接口拿到的站序；note 只在「设置 → 关于」里露脸。 */
data class MetroData(
    val lines: List<MetroLine> = emptyList(),
    val selected: String = "",
    val updated: String = "",
    /** 站序出处，界面不铺开，只在「设置 → 关于」里说明。 */
    val source: String = "",
    val online: Boolean = false,
    val note: String = "",
    /** 实时到站：官方接口当班返回的两个方向。 */
    val realtime: MetroRealtime? = null,
    /** 实时到站的参考站；空串表示还没定。 */
    val realtimeStation: String = "",
    val realtimeStationName: String = "",
    /** 最近一次成功取到实时数据的时间。 */
    val realtimeUpdated: String = "",
    val realtimeNote: String = "",
    val realtimeLoading: Boolean = false,
    /** 当前展开的方向，两边站序相反，切换方向才能看清车往哪开。 */
    val directionIndex: Int = 0
) {
    val line: MetroLine? get() = lines.firstOrNull { it.id == selected } ?: lines.firstOrNull()

    /** 当前展开的方向；没有实时数据时为 null，界面退回线路自身的起终点。 */
    val direction: MetroDirection? get() = realtime?.directions?.getOrNull(directionIndex)

    /**
     * 展示站序跟随所选方向的行驶顺序（始发在前），三处「方向」讲同一件事；
     * 没有方向或站码对不上时退回线路自身顺序。
     * 站点的 order 字段保留原值，liveStations 的按 order 排序只在数据层有意义；
     * 展示顺序以这里重排后的列表为准，不能再用 liveStations。
     */
    val orderedLine: MetroLine? get() {
        val current = line ?: return null
        val order = direction?.stationCodes.orEmpty()
        if (order.isEmpty()) return current
        val byCode = current.stations.associateBy { it.code }
        val travel = order.mapNotNull { byCode[it] }
        if (travel.isEmpty()) return current
        val missing = current.stations.filter { it.code !in order }
        val reordered = travel + missing
        // order 重排后与位置脱钩，重编号保证「第 N 站」等文案跟视觉一致。
        return current.copy(stations = reordered.mapIndexed { index, stop -> stop.copy(order = index + 1) })
    }
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
     * 实时到站是另一条通道的成果：同一波站序刷新不能把它冲掉，否则刚进页面的
     * 第一次实时数据会被迟到的站表响应抹掉，看起来就像「首次必失败」。
     */
    fun load(initial: String? = null): Job {
        loading?.cancel()
        if (initial != null) preferred = initial
        return action {
            loader.load(city).collect { snapshot ->
                val network = snapshot.network
                val lines = network?.openedLines.orEmpty()
                // 换的还是同一条线时保留已有实时；整份资料换线则作废，避免站码错位。
                val previous = data
                val keep = lines.any { it.id == previous.selected } &&
                    previous.line?.id == lines.firstOrNull { it.id == previous.selected }?.id
                update(
                    MetroData(
                        lines = lines,
                        selected = pick(preferred ?: previous.selected, lines),
                        updated = network?.updated.orEmpty(),
                        source = network?.source.orEmpty(),
                        online = snapshot.online,
                        note = snapshot.note,
                        realtime = previous.realtime.takeIf { keep },
                        realtimeStation = previous.realtimeStation.takeIf { keep }.orEmpty(),
                        realtimeStationName = previous.realtimeStationName.takeIf { keep }.orEmpty(),
                        realtimeUpdated = previous.realtimeUpdated.takeIf { keep }.orEmpty(),
                        realtimeNote = previous.realtimeNote.takeIf { keep }.orEmpty(),
                        realtimeLoading = previous.realtimeLoading && keep,
                        directionIndex = previous.directionIndex.takeIf { keep } ?: 0
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
        // 换线后原来那条线的参考站码不再有效，清掉让实时到站回到新线的首站。
        val value = data.copy(selected = id, directionIndex = 0, realtimeStation = "", realtimeStationName = "", realtime = null, realtimeUpdated = "")
        mutable.value = when (val old = mutable.value) {
            is UiState.Error -> old.copy(data = value)
            is UiState.Loading -> old.copy(data = value)
            is UiState.Empty -> old.copy(data = value)
            is UiState.Success -> old.copy(data = value)
        }
        refreshRealtime()
    }

    fun retry() = load(data.selected.ifBlank { null })

    /** 读取最近一次请求的诊断；失败原因不进入地铁页状态。 */
    fun lastProbe(): MetroProbe = graph.metroRemote.last ?: MetroProbe(online = false, note = "")

    /** 换参考站；换站后旧数据立刻作废，避免旧站的到站信息留在屏上。 */
    fun selectStation(code: String) {
        val station = data.line?.liveStations?.firstOrNull { it.code == code } ?: return
        update(data.copy(realtimeStation = station.code, realtimeStationName = station.name, realtime = null, realtimeUpdated = ""))
        refreshRealtime()
    }

    fun selectDirection(index: Int) {
        update(data.copy(directionIndex = index))
    }

    /**
     * 拉一次实时到站。官方 App 每 30 秒问一次，两边节奏接近；
     * 失败只留诊断，不打断站序展示。
     */
    fun refreshRealtime() = action {
        val line = data.line ?: return@action
        val code = line.realtimeCode()
        if (code.isBlank()) return@action
        val station = data.realtimeStation.takeIf { it.isNotBlank() }
            ?: line.liveStations.firstOrNull()?.code.orEmpty()
        update(data.copy(realtimeLoading = true))
        val result = graph.metroRealtime.latest(code, station)
        val name = result.stationName.ifBlank { line.liveStations.firstOrNull { it.code == station }?.name.orEmpty() }
        update(
            data.copy(
                realtime = result,
                realtimeStation = station,
                realtimeStationName = name,
                realtimeUpdated = clock(),
                realtimeNote = result.note,
                realtimeLoading = false
            )
        )
    }

    /** 只显示时分秒，秒级精度对到站信息没有意义。 */
    private fun clock(): String {
        val now = Calendar.getInstance()
        return String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
    }
}