package com.yll682.houche.ui.screens.metro

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.yll682.houche.AppGraph
import com.yll682.houche.core.City
import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroDirectionKey
import com.yll682.houche.core.MetroEtaState
import com.yll682.houche.core.MetroInitialLocation
import com.yll682.houche.core.MetroInitialSelection
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroLoader
import com.yll682.houche.core.MetroProbe
import com.yll682.houche.core.MetroRealtime
import com.yll682.houche.core.MetroRealtimeRetryController
import com.yll682.houche.core.MetroRealtimeState
import com.yll682.houche.core.MetroOperatingStatus
import com.yll682.houche.core.MetroOperatingWindow
import com.yll682.houche.core.metroOperatingWindow
import com.yll682.houche.core.METRO_OPERATING_ZONE
import com.yll682.houche.core.METRO_REALTIME_WINDOW_MILLIS
import com.yll682.houche.core.MetroSnapshot
import com.yll682.houche.core.normalizeMetroOpenings
import com.yll682.houche.core.realtimeCode
import com.yll682.houche.data.Position
import com.yll682.houche.ui.StateViewModel
import com.yll682.houche.ui.UiState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
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
    /** 当前实时请求的序号；换线或换参考站后置零，迟到的响应不能回写。 */
    val realtimeRequestId: Long = 0,
    /** 当前展开的方向，两边站序相反，切换方向才能看清车往哪开。 */
    val directionIndex: Int = 0,
    /** 完整站码链稳定标识方向；响应数组调序不能改变用户所选方向。 */
    val selectedDirectionKey: MetroDirectionKey? = null,
    /** 原始成功批次；realtime 是按当前参考站摘除无效 ETA 后的展示投影。 */
    val realtimeLastSuccessfulFrame: MetroRealtime? = null,
    /** 请求失败与成功空车分开；成功位置批次仍可保留并标注陈旧时长。 */
    val realtimeUnavailable: Boolean = false,
    /** 独立本站成功响应，不能用它的时间替位置批次续命。 */
    val realtimeArrivalFrame: MetroRealtime? = null,
    /** 由 VM 的可注入时钟更新；纯数据默认未知，getter 不读取系统墙钟。 */
    val realtimeOutOfService: Boolean = false
) {
    val line: MetroLine? get() = lines.firstOrNull { it.id == selected } ?: lines.firstOrNull()

    val realtimeState: MetroRealtimeState get() = MetroRealtimeState(
        frame = realtimeLastSuccessfulFrame ?: realtime,
        stationCode = realtimeStation.ifBlank { realtime?.stationCode.orEmpty() }.ifBlank { line?.liveStations?.firstOrNull()?.code.orEmpty() },
        stationName = realtimeStationName.ifBlank { realtime?.stationName.orEmpty() },
        loading = realtimeLoading,
        unavailableNote = realtimeNote.takeIf { realtimeUnavailable },
        selectedDirectionKey = selectedDirectionKey,
        directionIndex = directionIndex,
        arrivalFrame = realtimeArrivalFrame,
        outOfService = realtimeOutOfService
    )

    /** 当前展开的方向；切站等待保留方向和位置，不借用旧站 ETA。 */
    val direction: MetroDirection? get() = realtimeState.direction

    /** UI 可分别绑定等待、成功空车、本站到站行和不可用，ETA 区域无需随空列表消失。 */
    val etaState: MetroEtaState get() = realtimeState.etaState

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

/** 请求发起时固定查询上下文，完成后只允许最新的同线路请求回写。 */
internal data class MetroRealtimeRequest(
    val id: Long,
    val lineId: String,
    val lineCode: String,
    val stationCode: String,
    val stationCodes: List<String>
)

sealed interface MetroEvent {
    data class StationTimetableUnavailable(val stationName: String) : MetroEvent
}

private fun MetroData.withRealtimeState(value: MetroRealtimeState): MetroData = copy(
    realtime = value.displayFrame,
    realtimeLastSuccessfulFrame = value.frame,
    realtimeStation = value.stationCode,
    realtimeStationName = value.stationName,
    realtimeLoading = value.loading,
    realtimeUnavailable = value.unavailableNote != null,
    realtimeNote = value.unavailableNote ?: realtimeNote.takeUnless { realtimeUnavailable }.orEmpty(),
    realtimeArrivalFrame = value.arrivalFrame,
    realtimeOutOfService = value.outOfService,
    selectedDirectionKey = value.selectedDirectionKey,
    directionIndex = value.selectedDirectionIndex
)

internal fun MetroData.operatingWindowAt(now: Instant): MetroOperatingWindow {
    val current = line ?: return MetroOperatingWindow(MetroOperatingStatus.Unknown)
    val code = realtimeStation.ifBlank { current.liveStations.firstOrNull()?.code.orEmpty() }
    val station = current.liveStations.firstOrNull { it.code == code }
        ?: return MetroOperatingWindow(MetroOperatingStatus.Unknown)
    return metroOperatingWindow(current, station, now)
}

internal fun MetroData.withOperatingHours(now: Instant): MetroData {
    val closed = operatingWindowAt(now).status == MetroOperatingStatus.OutOfService
    if (closed == realtimeOutOfService) return this
    val next = withRealtimeState(realtimeState.setOutOfService(closed))
    return next.copy(realtimeNote = "", realtimeRequestId = if (closed) 0 else next.realtimeRequestId)
}

/** 切站只改变参考站和 ETA 状态；位置批次、采样时间及方向都保留。 */
internal fun MetroData.selectRealtimeStation(code: String): MetroData {
    val station = line?.liveStations?.firstOrNull { it.code == code } ?: return this
    // 同一目标仍在等待时保持序号，刷新/轮询共享同一条请求和退避链。
    if (realtimeStation == station.code) return this
    return withRealtimeState(realtimeState.selectStation(station.code, station.name)).copy(
        realtimeRequestId = 0
    )
}

/** 选线会清掉另一条线的请求上下文、方向和位置，保留现有 select(id) 行为。 */
internal fun MetroData.selectRealtimeLine(id: String): MetroData {
    if (lines.none { it.id == id }) return this
    return copy(
        selected = id, directionIndex = 0, realtimeStation = "", realtimeStationName = "", realtime = null,
        realtimeUpdated = "", realtimeNote = "", realtimeLoading = false, realtimeRequestId = 0,
        selectedDirectionKey = null, realtimeLastSuccessfulFrame = null, realtimeUnavailable = false,
        realtimeArrivalFrame = null, realtimeOutOfService = false
    )
}

/** 换乘和首次定位直接提交完整目标，中间不曾选中目标线首站。 */
internal fun MetroData.selectRealtimeTarget(lineId: String, stationCode: String): MetroData {
    if (!hasRealtimeTarget(lineId, stationCode)) return this
    val selectedLine = if (selected == lineId) this else selectRealtimeLine(lineId)
    return selectedLine.selectRealtimeStation(stationCode)
}

/** 也用于无状态变化的有效手动目标，保证复用请求之前仍能消费首次定位机会。 */
internal fun MetroData.hasRealtimeTarget(lineId: String, stationCode: String): Boolean {
    // 先按完整身份核验唯一，再检查运营状态；关闭的重复记录仍代表身份有歧义。
    val targetLine = lines.singleOrNull { it.id == lineId }
        ?.takeIf { it.opened && it.id.isNotBlank() } ?: return false
    return targetLine.stations.singleOrNull { it.code == stationCode }
        ?.takeIf { it.opened && it.code.isNotBlank() } != null
}

/** 站表替换只保留有效的既有选择；不会因站序重排而回到首站。 */
internal fun MetroData.withMetroSnapshot(snapshot: MetroSnapshot, preferred: String?): MetroData {
    val network = snapshot.network
    val lines = normalizeMetroOpenings(network?.openedLines.orEmpty())
    val selected = preferred?.takeIf { id -> lines.any { it.id == id } }
        ?: this.selected.takeIf { id -> lines.any { it.id == id } }
        ?: lines.firstOrNull()?.id.orEmpty()
    val currentLine = lines.firstOrNull { it.id == selected }
    val keep = line?.id != null && line?.id == currentLine?.id && line?.realtimeCode() == currentLine?.realtimeCode()
    val keepStation = keep && (realtimeStation.isBlank() || currentLine?.liveStations?.any { it.code == realtimeStation } == true)
    val keepRealtime = keep && keepStation
    return MetroData(
        lines = lines,
        selected = selected,
        updated = network?.updated.orEmpty(),
        source = network?.source.orEmpty(),
        online = snapshot.online,
        note = snapshot.note,
        realtime = realtime.takeIf { keepRealtime },
        realtimeStation = realtimeStation.takeIf { keepStation }.orEmpty(),
        realtimeStationName = currentLine?.liveStations?.firstOrNull { keepStation && it.code == realtimeStation }?.name
            ?: realtimeStationName.takeIf { keepStation }.orEmpty(),
        realtimeUpdated = realtimeUpdated.takeIf { keepRealtime }.orEmpty(),
        realtimeNote = realtimeNote.takeIf { keepRealtime }.orEmpty(),
        realtimeLoading = realtimeLoading && keepRealtime,
        realtimeRequestId = realtimeRequestId.takeIf { keepRealtime } ?: 0,
        directionIndex = directionIndex.takeIf { keepRealtime } ?: 0,
        selectedDirectionKey = selectedDirectionKey.takeIf { keepRealtime },
        realtimeLastSuccessfulFrame = realtimeLastSuccessfulFrame.takeIf { keepRealtime },
        realtimeUnavailable = realtimeUnavailable && keepRealtime,
        realtimeArrivalFrame = realtimeArrivalFrame.takeIf { keepRealtime },
        realtimeOutOfService = realtimeOutOfService && keepRealtime
    )
}

internal fun MetroData.selectRealtimeDirection(index: Int): MetroData =
    withRealtimeState(realtimeState.selectDirection(index))

internal fun MetroData.beginRealtimeRequest(id: Long): Pair<MetroData, MetroRealtimeRequest>? {
    val current = line ?: return null
    val code = current.realtimeCode()
    if (code.isBlank()) return null
    val station = realtimeStation.takeIf { it.isNotBlank() } ?: current.liveStations.firstOrNull()?.code.orEmpty()
    if (station.isBlank()) return null
    val request = MetroRealtimeRequest(id, current.id, code, station, current.liveStations.map { it.code })
    return withRealtimeState(realtimeState.beginRequest()).copy(realtimeRequestId = id) to request
}

/** 失败与成功空车分别处理；时间只记录成功响应，参考站码和名称始终一起归并。 */
internal fun MetroData.completeRealtimeRequest(request: MetroRealtimeRequest, result: MetroRealtime, updatedAt: String): MetroData {
    if (realtimeRequestId != request.id || line?.id != request.lineId) return this
    val currentStation = realtimeStation.ifBlank { line?.liveStations?.firstOrNull()?.code.orEmpty() }
    if (currentStation != request.stationCode || line?.realtimeCode() != request.lineCode) return this
    if (!result.online) {
        val failed = realtimeState.complete(result)
        return withRealtimeState(failed).copy(realtimeNote = failed.unavailableNote.orEmpty())
    }
    val station = result.stationCode.ifBlank { request.stationCode }
    val name = result.stationName.ifBlank { line?.liveStations?.firstOrNull { it.code == station }?.name.orEmpty() }
    return withRealtimeState(realtimeState.complete(result.copy(stationCode = station, stationName = name))).copy(
        realtimeUpdated = updatedAt.ifBlank { realtimeUpdated },
        realtimeNote = result.note
    )
}

/** 前台只接受所请求的本站，不让服务端其他参考站改写用户的选择。 */
internal fun MetroData.completeRealtimeStationRequest(request: MetroRealtimeRequest, result: MetroRealtime, updatedAt: String): MetroData {
    if (!acceptsRealtimeRequest(request)) return this
    if (result.online && result.stationCode.isNotBlank() && result.stationCode != request.stationCode) return this
    val normalized = result.copy(
        stationCode = request.stationCode,
        stationName = result.stationName.ifBlank { line?.liveStations?.firstOrNull { it.code == request.stationCode }?.name.orEmpty() }
    )
    val completed = realtimeState.completeStation(normalized)
    return withRealtimeState(completed).copy(
        realtimeUpdated = if (result.online) updatedAt.ifBlank { realtimeUpdated } else realtimeUpdated,
        realtimeNote = if (result.online) result.note else completed.unavailableNote.orEmpty()
    )
}

private fun MetroData.acceptsRealtimeRequest(request: MetroRealtimeRequest): Boolean =
    !realtimeOutOfService && realtimeRequestId == request.id && line?.id == request.lineId && line?.realtimeCode() == request.lineCode &&
        realtimeStation.ifBlank { line?.liveStations?.firstOrNull()?.code.orEmpty() } == request.stationCode

/** 位置结果只受线路身份约束，切本站后同一条线的在途批次仍可提供位置。 */
internal fun MetroData.completeRealtimePositions(lineId: String, lineCode: String, result: MetroRealtime): MetroData {
    if (realtimeOutOfService || line?.id != lineId || line?.realtimeCode() != lineCode || !result.online) return this
    return withRealtimeState(realtimeState.completePositions(result))
}

class MetroViewModel(
    private val graph: AppGraph,
    val city: City,
    initial: String? = null,
    private val realtimeClock: Clock = Clock.system(METRO_OPERATING_ZONE),
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    private val waitMillis: suspend (Long) -> Unit = { delay(it) }
) : StateViewModel<MetroData>(MetroData()) {
    private val loader = MetroLoader(graph.metro::networkFor, graph.metroRemote::latest, graph.metro::supports)
    private var loading: Job? = null
    private var preferred = initial?.takeIf { it.isNotBlank() }
    private var initialSelection = MetroInitialSelection.start(initial)
    private var initialLocationRequested = false
    private var realtimeVisible = false
    private var realtimeRequestSequence = 0L
    private var positionsJob: Job? = null
    private var positionsLine: String? = null
    private var positionsStartedAt: Long? = null
    private var positionsGeneration = 0L
    private val eventChannel = Channel<MetroEvent>(Channel.BUFFERED)
    val events: Flow<MetroEvent> = eventChannel.receiveAsFlow()
    private val realtimeController = MetroRealtimeRetryController<MetroRealtimeRequest>(
        scope = viewModelScope,
        request = { request ->
            when (val result = graph.metroRealtime.latestStation(request.lineCode, request.stationCode)) {
                is com.yll682.houche.core.MetroRealtimeRequestResult.Deferred -> result
                is com.yll682.houche.core.MetroRealtimeRequestResult.Completed -> {
                    val response = result.realtime
                    if (response.online && response.stationCode.isNotBlank() && response.stationCode != request.stationCode) {
                        com.yll682.houche.core.MetroRealtimeRequestResult.Completed(MetroRealtime(false, "本站响应参考站不匹配"))
                    } else result
                }
            }
        },
        onLoading = {},
        onStarted = { request ->
            // 只固定请求上下文；失败退避和同站刷新保留错误态，不重置为 Loading。
            val current = data
            val station = current.realtimeStation.ifBlank { current.line?.liveStations?.firstOrNull()?.code.orEmpty() }
            if (current.line?.id == request.lineId && current.line?.realtimeCode() == request.lineCode && station == request.stationCode) {
                current.beginRealtimeRequest(request.id)?.first?.let { update(it) }
            }
        },
        onResult = { request, result ->
            syncOperatingHours()
            val current = data
            val sampledAt = result.source?.receivedAtEpochMillis?.let(::clock).orEmpty()
            val completed = current.completeRealtimeStationRequest(request, result, sampledAt)
            if (completed !== current) update(completed)
        },
        sameTarget = { first, second ->
            first.lineId == second.lineId && first.lineCode == second.lineCode && first.stationCode == second.stationCode
        },
        elapsedRealtimeMillis = elapsedRealtimeMillis,
        waitMillis = waitMillis,
        onUnavailable = { request ->
            if (data.acceptsRealtimeRequest(request)) {
                val name = data.line?.liveStations?.firstOrNull { it.code == request.stationCode }?.name.orEmpty()
                eventChannel.trySend(MetroEvent.StationTimetableUnavailable(name))
            }
        }
    )

    init {
        mutable.value = UiState.Loading(data)
        load(initial)
        viewModelScope.launch {
            while (true) {
                val wasClosed = data.realtimeOutOfService
                val open = syncOperatingHours()
                if (wasClosed && open) refreshRealtime()
                // 精确等候已知首末边界；每秒重查墙钟，手动改时/跨时区也能自然更新。
                val next = data.operatingWindowAt(realtimeClock.instant()).nextChangeAt
                val untilBoundary = next?.let { Duration.between(realtimeClock.instant(), it).toMillis().coerceAtLeast(1) }
                waitMillis(minOf(untilBoundary ?: 1_000L, 1_000L))
            }
        }
    }

    /**
     * 进入页面立即发起在线请求；等待期间用内置资料即时展示，成功后整体采用在线资料。
     * 新一次加载会取消上一次，避免较旧的响应覆盖最新站序。
     * 实时到站是另一条通道的成果：同一波站序刷新不能把它冲掉，否则刚进页面的
     * 第一次实时数据会被迟到的站表响应抹掉，看起来就像「首次必失败」。
     */
    fun load(initial: String? = null): Job {
        loading?.cancel()
        if (!initial.isNullOrBlank()) {
            preferred = initial
            initialSelection = initialSelection.manualSelection()
        }
        return action {
            loader.load(city).collect { snapshot ->
                val previous = data
                var next = previous.withMetroSnapshot(snapshot, preferred)
                val initialResult = initialSelection.stationsLoaded(next.lines)
                initialSelection = initialResult.state
                val target = initialResult.station
                val located = target?.let { next.selectRealtimeTarget(it.lineId, it.stationCode) }
                val applied = located != null && located !== next
                if (applied) {
                    next = checkNotNull(located)
                    preferred = target?.lineId
                }
                val changedContext = previous.line?.id != next.line?.id || previous.line?.realtimeCode() != next.line?.realtimeCode() ||
                    previous.realtimeStation != next.realtimeStation
                if (changedContext) {
                    realtimeController.cancel()
                    cancelRealtimePositions()
                }
                update(next.withOperatingHours(realtimeClock.instant()), empty = next.lines.isEmpty())
                syncOperatingHours()
                if (applied || (changedContext && previous.realtimeRequestId != 0L)) refreshRealtime()
            }
        }.also { loading = it }
    }

    /** 深链可能早于站序到达，先记住目标，也防止迟到定位覆盖用户的选择。 */
    fun selectLine(id: String) {
        if (id.isBlank()) return
        if (data.lines.any { it.id == id }) { select(id); return }
        preferred = id
        initialSelection = initialSelection.manualSelection()
    }

    /** 首次定位跟随同城 ViewModel 存活，切 Tab 或返回首页都不再问系统。 */
    fun requestInitialLocation(locate: suspend () -> Position) {
        if (initialLocationRequested) return
        initialLocationRequested = true
        viewModelScope.launch {
            val position = try {
                locate()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                null
            }
            applyInitialLocation(position?.let {
                MetroInitialLocation(it.lat, it.lng, it.accuracy?.toDouble(), it.coordinateSystem)
            })
        }
    }

    /** VM 留在首页 Store 中，隐藏时仍须停掉失败退避和位置批次，首次定位则继续完成。 */
    fun setRealtimeVisible(visible: Boolean) {
        realtimeVisible = visible
        if (!visible) {
            realtimeController.cancel()
            cancelRealtimePositions()
        }
    }

    fun select(id: String) {
        val value = data.selectRealtimeLine(id).withOperatingHours(realtimeClock.instant())
        if (value === data) return
        realtimeController.cancel()
        cancelRealtimePositions()
        initialSelection = initialSelection.manualSelection()
        preferred = id
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

    /** 换参考站时保留成功位置批次，仅将新站 ETA 置为等待。 */
    fun selectStation(code: String) {
        if (data.line?.liveStations?.none { it.code == code } != false) return
        val selected = data.selectRealtimeStation(code).withOperatingHours(realtimeClock.instant())
        initialSelection = initialSelection.manualSelection()
        preferred = selected.line?.id
        if (selected !== data) update(selected)
        refreshRealtime()
    }

    fun selectDirection(index: Int) {
        initialSelection = initialSelection.manualSelection()
        update(data.selectRealtimeDirection(index))
    }

    /** 只验证并提交一次目标，实时请求直接查询目标站，不会先请求目标线首站。 */
    fun selectTransfer(lineId: String, stationCode: String): Boolean {
        if (!data.hasRealtimeTarget(lineId, stationCode)) return false
        val selected = data.selectRealtimeTarget(lineId, stationCode).withOperatingHours(realtimeClock.instant())
        initialSelection = initialSelection.manualSelection()
        preferred = lineId
        if (selected !== data) update(selected)
        refreshRealtime()
        return true
    }

    /**
     * 页面把已经取得的位置传进来；VM 不申请权限、不调用定位。null 同样消费首次机会。
     * 返回 true 仅表示本次立即应用了选站；站表未到时返回 false 并在加载后应用一次。
     * 同一 VM 重组、回前台或位置再次回调均不会再次自动选站。
     */
    fun applyInitialLocation(location: MetroInitialLocation?): Boolean {
        val result = initialSelection.receive(location, data.lines)
        initialSelection = result.state
        val target = result.station ?: return false
        val selected = data.selectRealtimeTarget(target.lineId, target.stationCode).withOperatingHours(realtimeClock.instant())
        if (selected === data) return false
        preferred = target.lineId
        update(selected)
        refreshRealtime()
        return true
    }

    fun applyInitialLocation(lat: Double?, lng: Double?, accuracyMeters: Double?, coordinateSystem: String): Boolean =
        applyInitialLocation(MetroInitialLocation(lat, lng, accuracyMeters, coordinateSystem))

    /**
     * 现有 UI 的轮询和手刷共用入口，均优先真实查询本站；预算排队保持 Loading。
     * 广域位置在另一条通道按二十秒 TTL 更新，本站响应无需等待整批结束。
     */
    fun refreshRealtime(): Job? {
        if (!realtimeVisible) return null
        if (!syncOperatingHours()) return null
        val (_, request) = data.beginRealtimeRequest(++realtimeRequestSequence) ?: run {
            realtimeController.cancel()
            return null
        }
        val job = realtimeController.refresh(request)
        refreshRealtimePositions(request)
        return job
    }

    /** 供以后显式绑定自动位置轮询；当前页面仍兼容 refreshRealtime 的本站优先入口。 */
    fun refreshRealtimeAutomatically() {
        if (!realtimeVisible) return
        if (!syncOperatingHours()) return
        val (_, request) = data.beginRealtimeRequest(realtimeRequestSequence) ?: return
        refreshRealtimePositions(request)
    }

    private fun refreshRealtimePositions(request: MetroRealtimeRequest) {
        if (!syncOperatingHours()) return
        if (positionsLine != request.lineCode) cancelRealtimePositions()
        val now = elapsedRealtimeMillis()
        if (positionsJob?.isActive == true || positionsStartedAt?.let { now - it in 0 until minOf(METRO_REALTIME_WINDOW_MILLIS, com.yll682.houche.data.RefreshPolicy.millis()) } == true) return
        positionsLine = request.lineCode
        positionsStartedAt = now
        val generation = ++positionsGeneration
        positionsJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = graph.metroRealtime.latestLine(request.lineCode, request.stationCodes, request.stationCode)
            syncOperatingHours()
            if (positionsGeneration != generation) return@launch
            val current = data
            val completed = current.completeRealtimePositions(request.lineId, request.lineCode, result)
            if (completed !== current) update(completed)
        }
    }

    private fun cancelRealtimePositions() {
        positionsGeneration++
        positionsJob?.cancel()
        positionsJob = null
        positionsLine = null
        positionsStartedAt = null
    }

    /** 非运营时立即取消本站与位置通道；仅时钟重开时自动恢复，无需页面刷新。 */
    private fun syncOperatingHours(): Boolean {
        val next = data.withOperatingHours(realtimeClock.instant())
        if (next !== data) update(next)
        if (next.realtimeOutOfService) {
            realtimeController.cancel()
            cancelRealtimePositions()
            return false
        }
        return true
    }

    /** 只显示时分秒，秒级精度对到站信息没有意义。 */
    private fun clock(sampledAtEpochMillis: Long): String {
        val now = Calendar.getInstance().apply { timeInMillis = sampledAtEpochMillis }
        return String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
    }
}
