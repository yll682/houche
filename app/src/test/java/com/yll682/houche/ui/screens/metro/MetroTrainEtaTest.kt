package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroNetwork
import com.yll682.houche.core.MetroObservationSource
import com.yll682.houche.core.MetroRealtime
import com.yll682.houche.core.MetroRunningTimeProfile
import com.yll682.houche.core.MetroTrain
import com.yll682.houche.core.MetroTrainObservation
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.theme.HoucheShapes
import com.yll682.houche.ui.theme.HoucheTypography
import com.yll682.houche.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「点某列车 + 某站 → 还有多久到」的 UI 口径与交互。
 *
 * 覆盖三件事：
 *
 * 1. 推算口径（纯函数部分）：分钟向上取整、已过站写「已过」、推算失败不给数字、
 *    跨参考站的近似匹配口径更保守；
 * 2. 交互：点车标选中那班车、点站名/站序行选定目标站；点站名**仍然只开面板**，
 *    不直接切参考站（既有的两步流程不许被破坏）；
 * 3. 横向拖动不能误选车（车标可点之后最容易出的问题）。
 *
 * 站码、站名全部来自 `assets/metro.json`，不手抄。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class MetroTrainEtaTest {
    @get:Rule val compose = createComposeRule()

    private val lines: List<MetroLine> by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = context.assets.open("metro.json").bufferedReader().use { it.readText() }
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<MetroNetwork>(text).openedLines
    }
    private val line2: MetroLine by lazy { lines.first { it.id == "2" } }

    /** 往天竺山方向：站序与 metro.json 相反。 */
    private val codes: List<String> by lazy { line2.liveStations.map { it.code }.reversed() }
    private val names: Map<String, String> by lazy { line2.liveStations.associate { it.code to it.name } }

    /** 参考站「新垵」。 */
    private val reference = "02070000"
    private val referenceIndex by lazy { codes.indexOf(reference) }

    /** 区间运行时间：每站 120 秒（真实接口的 wayStations 自带，这里造一个同规格的）。 */
    private val profile by lazy { MetroRunningTimeProfile(codes, codes.indices.map { it * 120 }) }

    /** 下一站在参考站上游 `at` 站的那班车；`scale` 是驶向该站的区间进度。 */
    private fun train(
        seconds: Int, at: Int, scale: Double = 0.0, flag: Int = 1,
        from: String = reference, matched: Int = 0
    ): MetroTrain {
        val next = codes[referenceIndex + at]
        val source = MetroObservationSource(from)
        val observations = (0 until matched).map { index ->
            MetroTrainObservation(
                source = MetroObservationSource(codes[referenceIndex - 1 - index]),
                arriveSeconds = seconds - 120 * (index + 1),
                scale = scale,
                arrivingFlag = flag,
                nextStationCode = codes[referenceIndex - 1 - index]
            )
        }
        return MetroTrain(
            arriveSeconds = seconds, nextStationCode = next, nextStationName = names.getValue(next),
            scale = scale, arrivingFlag = flag, specialPosition = 0, specialPositionDesc = "",
            source = source, matchedObservations = observations
        )
    }

    /**
     * 两班车都在参考站**上游**（还没开过本站）：
     * 第 1 班最靠近本站（坐标最大），第 2 班再往上游一站半。
     */
    private fun direction(
        positions: List<MetroTrain> = listOf(train(300, at = -1, scale = .5), train(600, at = -2)),
        rows: List<MetroTrain> = listOf(train(300, at = -1, scale = .5)),
        withProfile: Boolean = true
    ): MetroDirection = MetroDirection(
        origin = names.getValue(codes.first()), destination = names.getValue(codes.last()),
        startTime = "06:00", endTime = "23:00", delayMinutes = 0,
        trains = rows, stationCodes = codes, source = MetroObservationSource(reference),
        positionTrains = positions, arrivalsAvailable = true,
        runningTimeProfile = if (withProfile) profile else null
    )

    private fun seed(direction: MetroDirection = direction()): MetroData = MetroData(
        lines = lines, selected = "2", updated = "2026-11-08",
        realtime = MetroRealtime(
            online = true, note = "", lineCode = "02000000",
            stationCode = reference, stationName = names.getValue(reference),
            directions = listOf(direction)
        ),
        realtimeStation = reference, realtimeStationName = names.getValue(reference),
        realtimeUpdated = "12:40", directionIndex = 0
    )

    private fun Metro(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = LightColors, typography = HoucheTypography, shapes = HoucheShapes) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    /** 位置条上的站点列：站名做 contentDescription（列表里的行是「站名，首末班…」的长描述）。 */
    private fun stripStation(name: String) = compose.onAllNodesWithContentDescription(name).onFirst()

    // ---- ① 推算口径（纯函数） ----

    @Test
    fun projectedMinutesRoundUpAndAreMarkedEstimated() {
        val data = seed()
        val dir = requireNotNull(data.direction)
        val selected = MetroSelectedTrain(markerKey = 1, nextStationCode = codes[referenceIndex - 1])
        val target = codes[referenceIndex - 1]
        val projection = metroTrainEtaProjection(dir, reference, selected, target) { names[it].orEmpty() }
        assertNotNull("选好车与目标站必须能推算", projection)
        // ETA(S) = 300 + C[S] − C[R] = 300 − 120 = 180 秒 → 3 分钟。
        assertEquals(3, projection!!.minutes)
        assertFalse("还没到目标站，不该写「已过」", projection.passed)
        assertFalse("单次观测不该算近似匹配", projection.approximate)
        assertEquals("班号用位置条同一套「到本站先后」编号", 1, projection.ordinal)
        assertEquals(names.getValue(target), projection.targetStationName)
    }

    @Test
    fun passedTargetStationIsReportedAsPassedWithoutFakeMinutes() {
        val data = seed()
        val dir = requireNotNull(data.direction)
        val selected = MetroSelectedTrain(markerKey = 1, nextStationCode = codes[referenceIndex - 1])
        // 目标站在该车**上游**三站：推算 ETA = 300 − 360 = −60 秒 → 已过。
        val target = codes[referenceIndex - 3]
        val projection = metroTrainEtaProjection(dir, reference, selected, target) { names[it].orEmpty() }
        assertNotNull(projection)
        assertTrue("上游三站应判为已过", projection!!.passed)
        assertNull("已过不再给分钟数（不写 −1 分钟）", projection.minutes)
    }

    @Test
    fun failedProjectionGivesNoNumber() {
        // 缺区间运行资料 → core 直接返回 null，界面只能给中性文案。
        val data = seed(direction(withProfile = false))
        val dir = requireNotNull(data.direction)
        val selected = MetroSelectedTrain(markerKey = 1, nextStationCode = codes[referenceIndex - 1])
        val projection = metroTrainEtaProjection(dir, reference, selected, codes[referenceIndex - 1]) { names[it].orEmpty() }
        assertNotNull("推算失败也要有结果对象，界面据此写「站数未知」", projection)
        assertNull("推算失败不许给数字", projection!!.minutes)
        assertFalse("推算失败不是「已过」", projection.passed)
    }

    @Test
    fun crossReferenceMatchIsMarkedApproximate() {
        val matched = train(300, at = -1, scale = .5, matched = 2)
        val data = seed(direction(positions = listOf(matched), rows = listOf(matched)))
        val dir = requireNotNull(data.direction)
        val selected = MetroSelectedTrain(markerKey = 1, nextStationCode = codes[referenceIndex - 1])
        val projection = metroTrainEtaProjection(dir, reference, selected, codes[referenceIndex - 1]) { names[it].orEmpty() }
        assertNotNull(projection)
        assertTrue("跨参考站的近似匹配口径要更保守", projection!!.approximate)
    }

    @Test
    fun staleSelectionIsDroppedInsteadOfPointingAtAnotherTrain() {
        val data = seed()
        val dir = requireNotNull(data.direction)
        // 下一站码对不上：刷新后 markerKey 已经指向别的车，这次选择必须作废。
        val stale = MetroSelectedTrain(markerKey = 1, nextStationCode = codes[referenceIndex - 2])
        assertNull(metroSelectedMarker(dir, reference, stale))
        assertNull(metroSelectedTrainKey(dir, reference, stale))
        assertNull(metroTrainEtaProjection(dir, reference, stale, codes[referenceIndex - 1]) { names[it].orEmpty() })
    }

    @Test
    fun noTargetStationMeansNothingToProject() {
        val data = seed()
        val dir = requireNotNull(data.direction)
        val selected = MetroSelectedTrain(markerKey = 1, nextStationCode = codes[referenceIndex - 1])
        assertNull(metroTrainEtaProjection(dir, reference, selected, "") { names[it].orEmpty() })
    }

    // ---- ② 交互 ----

    @Test
    fun tappingMarkerSelectsTrainAndStationTapShowsArrival() {
        var data by mutableStateOf(seed())
        val picked = mutableListOf<Int>()
        Metro {
            MetroContent(
                UiState.Success(data), {},
                selectTrain = { key, next ->
                    picked += key
                    data = data.copy(selectedTrain = MetroSelectedTrain(key, next))
                },
                selectTargetStation = { code -> data = data.copy(selectedTargetStation = code) }
            )
        }
        compose.waitForIdle()

        // 没选车时这一行给引导，不留空白。
        compose.onNodeWithText("点列车图标可看它到站时间").assertIsDisplayed()

        compose.onNodeWithContentDescription("第1班").performClick()
        compose.waitForIdle()
        assertEquals("点车标要把 markerKey 交出来", listOf(1), picked)
        compose.onNodeWithContentDescription("第1班").assertIsSelected()
        compose.onNodeWithText("已选 第1班，点站名可看它到该站时间").assertIsDisplayed()

        // 点站名 → 既是推算目标站，也照旧开底部面板（老流程不变）。
        val target = codes[referenceIndex - 1]
        val targetName = names.getValue(target)
        stripStation(targetName).performClick()
        compose.waitForIdle()
        assertEquals("点站名要把目标站交出来", target, data.selectedTargetStation)
        // 弹层在 Dialog 里，Robolectric 下弹层停在屏外，所以只断言它在。
        compose.onNodeWithTag("station-confirm").assertExists()
        // 卡片上那一行（主窗口里）要真的显示出来。
        compose.onNodeWithText("第1班 → $targetName：约 3 分钟（推算）").assertIsDisplayed()
        // 弹层里同一句信息也在。
        compose.onNodeWithText("所选列车到本站：约 3 分钟（推算）").assertExists()
    }

    @Test
    fun tappingMarkerAgainClearsSelection() {
        var data by mutableStateOf(seed())
        Metro {
            MetroContent(
                UiState.Success(data), {},
                selectTrain = { key, next ->
                    val current = data.selectedTrain
                    data = data.copy(
                        selectedTrain = if (current?.markerKey == key) null else MetroSelectedTrain(key, next)
                    )
                }
            )
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("第1班").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("第1班").assertIsSelected()
        compose.onNodeWithContentDescription("第1班").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("第1班").assertIsNotSelected()
        compose.onNodeWithText("点列车图标可看它到站时间").assertIsDisplayed()
    }

    @Test
    fun tappingStationNameDoesNotSwitchReferenceStation() {
        var data by mutableStateOf(seed())
        val switches = mutableListOf<String>()
        Metro {
            MetroContent(
                UiState.Success(data), {},
                selectStation = { code -> switches += code },
                selectTargetStation = { code -> data = data.copy(selectedTargetStation = code) }
            )
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(names.getValue(codes[referenceIndex - 1])).performClick()
        compose.waitForIdle()
        // 点站名只开面板：切参考站仍然要面板里再点一次「设为乘车站」。
        assertTrue("点站名不能直接切参考站，实际 $switches", switches.isEmpty())
        compose.onNodeWithTag("station-confirm").performClick()
        compose.waitForIdle()
        assertEquals(listOf(codes[referenceIndex - 1]), switches)
    }

    @Test
    fun horizontalDragOverMarkerDoesNotSelectTrain() {
        var data by mutableStateOf(seed())
        val picked = mutableListOf<Int>()
        Metro {
            MetroContent(
                UiState.Success(data), {},
                selectTrain = { key, _ -> picked += key }
            )
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("第1班").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertTrue("横向拖动不能触发选车，实际 $picked", picked.isEmpty())
    }

    @Test
    fun stationListRowAlsoSelectsTargetStation() {
        var data by mutableStateOf(seed())
        Metro {
            MetroContent(
                UiState.Success(data), {},
                selectTargetStation = { code -> data = data.copy(selectedTargetStation = code) }
            )
        }
        compose.waitForIdle()
        val target = codes[referenceIndex + 1]
        // 站序列表里的那一行（祖先带 metro-stations 标记），不是位置条上的站名列。
        compose.onNode(
            hasContentDescription(names.getValue(target), substring = true) and
                hasAnyAncestor(hasTestTag("metro-stations")) and hasClickAction()
        ).performClick()
        compose.waitForIdle()
        assertEquals("站序行也要能选目标站", target, data.selectedTargetStation)
    }
}