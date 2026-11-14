package com.yll682.houche.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 成功路径只读既有真实夹具；缺字段边界只对解析出的对象删字段，不另造列车、时间或站表。
 * 中文类名前保留 Metro，使标准 --tests 'com.yll682.houche.core.Metro*' 能实际选中这些用例。
 */
class Metro到站投影测试 {
    private val remote = MetroRealtimeRemote(elapsedRealtimeMillis = { 0L }, wallClockMillis = { 0L })
    private val sample by lazy { remote.parse(MetroFixtures.realtime.s("rawResponse"), "01000000") }

    @Test
    fun `权威密文样本在自己的参考站自洽且标明推算与单次观测`() {
        val plaintext = BusNetwork.json.parseToJsonElement(MetroFixtures.realtime.s("plaintext"))
        assertEquals(plaintext, BusNetwork.json.parseToJsonElement(
            requireNotNull(MetroGateway.decrypt(MetroFixtures.realtime.s("cipherHexUpper")))
        ))
        assertTrue(sample.online)
        var count = 0
        sample.directions.forEachIndexed { directionIndex, direction ->
            direction.trains.forEachIndexed { trainIndex, train ->
                val result = requireNotNull(projectMetroArrival(direction, train, sample.stationCode, sample.stationName))
                assertEquals(plaintext.list("data")[directionIndex].list("transTimes")[trainIndex].i("arriveTime"),
                    result.arriveSeconds)
                assertEquals(train.arriveSeconds, result.arriveSeconds)
                assertEquals(sample.stationCode, result.stationCode)
                assertEquals(sample.stationName, result.stationName)
                assertSame(train, result.train)
                assertEquals(train.source, result.source)
                assertEquals(MetroTrainIdentityConfidence.SINGLE_OBSERVATION, result.identityConfidence)
                assertTrue(result.isEstimated)
                assertFalse(result.passed)
                count++
            }
        }
        assertEquals(6, count)
    }

    @Test
    fun `一号线两轮48份密文与明文逐车核对并在原参考站投影288次`() {
        var count = 0
        listOf("173230", "173330").forEach { tag ->
            val snapshots = line1Snapshots(tag)
            assertEquals(24, snapshots.size)
            snapshots.forEach { snapshot ->
                val plain = BusNetwork.json.parseToJsonElement(MetroFixtures.file(
                    "line1-multi/rt-01000000-${snapshot.stationCode}-$tag-plain.json"
                ).readText())
                snapshot.directions.forEachIndexed { directionIndex, direction ->
                    direction.trains.forEachIndexed { trainIndex, train ->
                        val result = requireNotNull(projectMetroArrival(direction, train, snapshot.stationCode))
                        assertEquals(plain.list("data")[directionIndex].list("transTimes")[trainIndex].i("arriveTime"),
                            result.arriveSeconds)
                        assertEquals(train.arriveSeconds, result.arriveSeconds)
                        assertNull("方向没有完整站名表，缺名不能猜", result.stationName)
                        count++
                    }
                }
            }
        }
        assertEquals(288, count)
    }

    @Test
    fun `负的最终ETA标已过而仅位于参考站上游仍可尚未到达`() {
        val direction = sample.directions.first()
        val train = direction.trains.first()
        val passed = requireNotNull(projectMetroArrival(direction, train, direction.stationCodes.first()))
        assertTrue(passed.arriveSeconds < 0)
        assertTrue(passed.passed)
        val referenceIndex = direction.stationCodes.indexOf(requireNotNull(train.source).referenceStationCode)
        val upstream = requireNotNull(projectMetroArrival(direction, train, direction.stationCodes[referenceIndex - 1]))
        assertTrue(upstream.arriveSeconds > 0)
        assertFalse("累计秒差为负不等于最终 ETA 为负", upstream.passed)
    }

    @Test
    fun `目标站不在本方向包括空码均不猜且批量结果为空`() {
        val direction = sample.directions.first()
        val train = direction.trains.first()
        listOf("", "不在本方向").forEach { code ->
            assertNull(projectMetroArrival(direction, train, code))
            assertTrue(projectMetroArrivals(direction, code).isEmpty())
        }
    }

    @Test
    fun `车自身来源缺失或参考站不在方向内不能借方向来源`() {
        val direction = sample.directions.first()
        val train = direction.trains.first()
        assertNotNull(direction.source)
        assertNull(projectMetroArrival(direction, train.copy(source = null), sample.stationCode))
        val source = requireNotNull(train.source)
        val withoutReference = direction.copy(
            stationCodes = direction.stationCodes.filterNot { it == source.referenceStationCode }
        )
        assertNull(projectMetroArrival(withoutReference, train, direction.stationCodes.last()))
    }

    @Test
    fun `缺运行表目标累计秒参考累计秒或无效累计序列都不猜`() {
        val direction = sample.directions.first()
        val train = direction.trains.first()
        val profile = requireNotNull(direction.runningTimeProfile)
        val target = direction.stationCodes.last()
        val reference = requireNotNull(train.source).referenceStationCode
        assertNull(projectMetroArrival(direction.copy(runningTimeProfile = null), train, target))
        listOf(target, reference).forEach { missing ->
            val indexes = profile.stationCodes.indices.filter { profile.stationCodes[it] != missing }
            val incomplete = profile.copy(
                stationCodes = indexes.map { profile.stationCodes[it] },
                cumulativeSeconds = indexes.map { profile.cumulativeSeconds[it] }
            )
            assertTrue("删去内部/末端站后表仍有效，但缺该站的累计秒", incomplete.valid)
            assertNull(incomplete.secondsAt(missing))
            assertNull(projectMetroArrival(direction.copy(runningTimeProfile = incomplete), train, target))
        }
        val invalid = profile.copy(cumulativeSeconds = profile.cumulativeSeconds.dropLast(1))
        assertFalse(invalid.valid)
        assertNull(projectMetroArrival(direction.copy(runningTimeProfile = invalid), train, target))
    }

    @Test
    fun `反向跨集美大桥使用真实revTimeCost三分钟而正向四分钟`() {
        val reverseSnapshot = remote.parse(MetroFixtures.file(
            "line1-multi/rt-01000000-01150000-173230.json"
        ).readText(), "01000000")
        val forwardSnapshot = remote.parse(MetroFixtures.file(
            "line1-multi/rt-01000000-01140000-173230.json"
        ).readText(), "01000000")
        val reverse = reverseSnapshot.directions.single { it.destination == "镇海路" }
        val forward = forwardSnapshot.directions.single { it.destination == "岩内" }
        val stations = BusNetwork.json.parseToJsonElement(MetroFixtures.file(
            "line1-multi/rt-01000000-01150000-173230-plain.json"
        ).readText()).list("data").single {
            it.list("wayStations").last().s("stationCode") == reverse.stationCodes.last()
        }.list("wayStations")
        val endpoint = stations.single { it.s("stationCode") == "01140000" }
        val reverseSeconds = requireNotNull(endpoint.i("revTimeCost")) * 60
        val forwardSeconds = requireNotNull(endpoint.i("timeCost")) * 60
        assertEquals(180, reverseSeconds)
        assertEquals(240, forwardSeconds)
        val reverseTrain = reverse.trains.first()
        val forwardTrain = forward.trains.first()
        val up = requireNotNull(projectMetroArrival(reverse, reverseTrain, "01140000"))
        val down = requireNotNull(projectMetroArrival(forward, forwardTrain, "01150000"))
        assertEquals(reverseSeconds, up.arriveSeconds - reverseTrain.arriveSeconds)
        assertEquals(forwardSeconds, down.arriveSeconds - forwardTrain.arriveSeconds)
        assertNotEquals(up.arriveSeconds - reverseTrain.arriveSeconds, down.arriveSeconds - forwardTrain.arriveSeconds)
        assertNull("即便站码集合相同，也拒绝错方向的 profile", projectMetroArrival(
            reverse.copy(runningTimeProfile = forward.runningTimeProfile), reverseTrain, "01140000"
        ))
    }

    @Test
    fun `合并身份明确按代表自身观测投影并保留跨参考站置信度`() {
        var matchedCount = 0
        var differentReferenceCount = 0
        listOf("173230", "173330").forEach { tag ->
            val snapshots = line1Snapshots(tag)
            val frame = mergeMetroRealtime(snapshots, "01080000", "01000000")
            frame.directions.forEach { direction ->
                direction.allIdentifiedTrains.filter { it.matchedObservations.size > 1 }.forEach { train ->
                    val source = requireNotNull(train.source)
                    val originalDirection = snapshots.single { it.stationCode == source.referenceStationCode }
                        .directions.single { it.directionKey == direction.directionKey }
                    val original = originalDirection.trains.single {
                        it.source == source && it.arriveSeconds == train.arriveSeconds &&
                            it.nextStationCode == train.nextStationCode && it.scale == train.scale &&
                            it.arrivingFlag == train.arrivingFlag
                    }
                    val ownReference = requireNotNull(projectMetroArrival(direction, train, source.referenceStationCode))
                    assertEquals(original.arriveSeconds, ownReference.arriveSeconds)
                    assertEquals(MetroTrainIdentityConfidence.APPROXIMATE_CROSS_REFERENCE_MATCH,
                        ownReference.identityConfidence)
                    assertEquals(source, ownReference.source)
                    val projected = requireNotNull(projectMetroArrival(direction, train, frame.stationCode))
                    val independentlyProjected = requireNotNull(projectMetroArrival(
                        originalDirection, original, frame.stationCode
                    ))
                    assertEquals(independentlyProjected.arriveSeconds, projected.arriveSeconds)
                    if (source.referenceStationCode != direction.source?.referenceStationCode) differentReferenceCount++
                    matchedCount++
                }
            }
        }
        assertTrue("必须实际覆盖跨参考站合并身份", matchedCount > 0)
        assertTrue("代表观测参考站确实不同于所选站，避免只在同源车上自证", differentReferenceCount > 0)
    }

    @Test
    fun `批量覆盖全部身份包括隐藏计划且不修改本站三行或身份顺序`() {
        listOf("173230", "173330").forEach { tag ->
            val frame = mergeMetroRealtime(line1Snapshots(tag), "01080000", "01000000")
            frame.directions.forEach { direction ->
                val originalRows = direction.trains
                val originalIdentities = direction.allIdentifiedTrains
                val original = direction.copy()
                val results = projectMetroArrivals(direction, frame.stationCode, frame.stationName)
                assertEquals(originalIdentities.size, results.size)
                assertTrue("被抑制的计划也属于已识别身份", results.size > direction.positions.size)
                assertEquals(originalIdentities, results.map { it.train })
                results.zip(originalIdentities).forEach { (projection, train) -> assertSame(train, projection.train) }
                assertEquals(original, direction)
                assertSame(originalRows, direction.trains)
                assertSame(originalIdentities, direction.allIdentifiedTrains)
                assertEquals(3, direction.trains.size)
                val oneMissing = direction.copy(positionTrains = originalIdentities.mapIndexed { index, train ->
                    if (index == 0) train.copy(source = null) else train
                })
                assertEquals(results.size - 1, projectMetroArrivals(oneMissing, frame.stationCode).size)
            }
        }
    }

    @Test
    fun `缺本站响应仍可单独推算但不能让本站ETA从等待变Ready`() {
        val snapshots = line1Snapshots("173230").filter { it.stationCode != "01080000" }
        assertEquals(23, snapshots.size)
        val frame = mergeMetroRealtime(snapshots, "01080000", "01000000")
        val state = MetroRealtimeState(frame = frame)
        frame.directions.forEach { direction ->
            assertFalse(direction.arrivalsAvailable)
            assertTrue(direction.trains.isEmpty())
            assertTrue(projectMetroArrivals(direction, frame.stationCode).isNotEmpty())
        }
        assertTrue(state.etaState is MetroEtaState.Loading)
        assertTrue(state.direction?.trains.orEmpty().isEmpty())
    }

    @Test
    fun `二号线24对真实密文的288条观测同站自洽且跨站置信度保留`() {
        val directory = requireNotNull(MetroFixtures.file("metro-round2/rt-02000000-095729.json").parentFile)
        val files = directory.listFiles().orEmpty().filter {
            it.name.matches(Regex("rt-02000000-\\d{6}\\.json"))
        }.sortedBy { it.name }
        assertEquals(24, files.size)
        var count = 0
        var approximateCount = 0
        files.forEach { file ->
            val left = remote.parse(file.readText(), "02000000")
            val right = remote.parse(directory.resolve(file.name.removeSuffix(".json") + "-mq.json").readText(), "02000000")
            listOf(left, right).forEach { snapshot ->
                assertTrue(snapshot.online)
                snapshot.directions.forEach { direction ->
                    direction.trains.forEach { train ->
                        assertEquals(train.arriveSeconds, requireNotNull(projectMetroArrival(
                            direction, train, snapshot.stationCode, snapshot.stationName
                        )).arriveSeconds)
                        count++
                    }
                }
            }
            mergeMetroRealtime(listOf(left, right), left.stationCode, "02000000").directions.forEach { direction ->
                projectMetroArrivals(direction, right.stationCode, right.stationName).forEach { projection ->
                    if (projection.train.matchedObservations.size > 1) {
                        assertEquals(MetroTrainIdentityConfidence.APPROXIMATE_CROSS_REFERENCE_MATCH,
                            projection.identityConfidence)
                        approximateCount++
                    }
                }
            }
        }
        assertEquals(288, count)
        assertTrue(approximateCount > 0)
    }

    /**
     * 配对先验只看同轮、同方向、相同 next、已知 scale 差 ≤ .10，且两个参考站互为唯一候选；
     * 不看 ETA 或投影误差筛样本。325 对跨 1～7 站：均值 7.148 秒、p95 20 秒、最坏 26 秒。
     * 这是位置接近且关联无歧义的验收子集，不将其误写为全部近似身份的 60 秒保证。
     */
    @Test
    fun `位置接近且互为唯一候选的真实跨站配对误差均在60秒内且p95不超45秒`() {
        val errors = crossReferenceErrors(maxScaleDifference = .10)
        assertEquals(325, errors.size)
        errors.forEach { error ->
            assertTrue("${error.label}：误差 ${error.seconds} 秒", error.seconds <= 60)
        }
        assertTrue(percentile95(errors) <= 45)
        assertEquals(20, percentile95(errors))
        assertEquals(26, errors.maxOf { it.seconds })
        printDistribution("相同next且scale差不超0.10的唯一配对", errors)
    }

    /**
     * 不限制 scale 差的独立审计保留全部 582 对：均值 15.024 秒、p95 36 秒、最坏 62 秒。
     * 第 8 站距仅一对。不能为满足 60 秒把 62 秒样本删掉、截断或换用另一个方向的表。
     */
    @Test
    fun `同next的全部唯一配对保留真实62秒离群值并报告一至八站分布`() {
        val errors = crossReferenceErrors(maxScaleDifference = null)
        assertEquals(582, errors.size)
        assertEquals((1..8).toSet(), errors.map { it.distance }.toSet())
        assertEquals(36, percentile95(errors))
        assertTrue(percentile95(errors) <= 45)
        assertEquals(62, errors.maxOf { it.seconds })
        assertEquals(1, errors.count { it.seconds > 60 })
        printDistribution("相同next的全部唯一配对", errors)
    }

    /**
     * 生产 matcher 接受跨 next 与退化计划，身份容差宽于 ETA 精度要求；全部原始观测不筛误差。
     * 757 对均值 17.036 秒、p95 42 秒、最坏 145 秒、10 对超过 60 秒，不能宣称全量 ≤60。
     */
    @Test
    fun `生产合并全部757对原始证据如实保留身份关联带来的误差尾部`() {
        val errors = mutableListOf<ProjectionError>()
        listOf("173230", "173330").forEach { tag ->
            val snapshots = line1Snapshots(tag)
            mergeMetroRealtime(snapshots, "01080000", "01000000").directions.forEach { direction ->
                direction.allIdentifiedTrains.forEach { train ->
                    val observations = train.matchedObservations
                    for (left in observations.indices) {
                        for (right in left + 1 until observations.size) {
                            val from = observations[left]
                            val to = observations[right]
                            val original = snapshots.single { it.stationCode == from.source.referenceStationCode }
                                .directions.single { it.directionKey == direction.directionKey }.trains.single {
                                    it.source == from.source && it.arriveSeconds == from.arriveSeconds &&
                                        it.nextStationCode == from.nextStationCode && it.scale == from.scale &&
                                        it.arrivingFlag == from.arrivingFlag
                                }
                            val projection = requireNotNull(projectMetroArrival(
                                direction, original, to.source.referenceStationCode
                            ))
                            errors += ProjectionError(
                                distance = abs(direction.stationCodes.indexOf(from.source.referenceStationCode) -
                                    direction.stationCodes.indexOf(to.source.referenceStationCode)),
                                seconds = abs(projection.arriveSeconds - to.arriveSeconds),
                                label = "$tag/${direction.destination}/${from.source.referenceStationCode}→" +
                                    to.source.referenceStationCode
                            )
                        }
                    }
                }
            }
        }
        assertEquals(757, errors.size)
        assertEquals(42, percentile95(errors))
        assertTrue(percentile95(errors) <= 45)
        assertEquals(145, errors.maxOf { it.seconds })
        assertEquals(10, errors.count { it.seconds > 60 })
        printDistribution("生产matchedObservations全部原始配对", errors)
    }

    private data class ProjectionError(val distance: Int, val seconds: Int, val label: String)

    private fun crossReferenceErrors(maxScaleDifference: Double?): List<ProjectionError> {
        val errors = mutableListOf<ProjectionError>()
        listOf("173230", "173330").forEach { tag ->
            val snapshots = line1Snapshots(tag)
            for (left in snapshots.indices) {
                for (right in left + 1 until snapshots.size) {
                    snapshots[left].directions.forEach { direction ->
                        val target = snapshots[right]
                        val other = target.directions.single { it.directionKey == direction.directionKey }
                        fun candidates(a: MetroTrain, b: MetroTrain): Boolean =
                            a.nextStationCode == b.nextStationCode && a.scaleKnown && b.scaleKnown &&
                                (maxScaleDifference == null || abs(a.scale - b.scale) <= maxScaleDifference + 1e-9)
                        direction.trains.forEach { from ->
                            val to = other.trains.filter { candidates(from, it) }.singleOrNull()
                            if (to != null && direction.trains.count { candidates(it, to) } == 1) {
                                val projection = requireNotNull(projectMetroArrival(
                                    direction, from, target.stationCode, target.stationName
                                ))
                                errors += ProjectionError(
                                    distance = abs(direction.stationCodes.indexOf(
                                        requireNotNull(from.source).referenceStationCode
                                    ) - direction.stationCodes.indexOf(target.stationCode)),
                                    seconds = abs(projection.arriveSeconds - to.arriveSeconds),
                                    label = "$tag/${direction.destination}/${snapshots[left].stationCode}→${target.stationCode}"
                                )
                            }
                        }
                    }
                }
            }
        }
        return errors
    }

    /** nearest-rank p95，不对最坏样本做插值或截断。 */
    private fun percentile95(errors: List<ProjectionError>): Int =
        errors.map { it.seconds }.sorted()[ceil(errors.size * .95).toInt() - 1]

    private fun printDistribution(label: String, errors: List<ProjectionError>) {
        fun summary(values: List<ProjectionError>): String =
            "n=${values.size}, mean=${String.format(Locale.ROOT, "%.3f", values.map { it.seconds }.average())}, " +
                "p95=${percentile95(values)}, max=${values.maxOf { it.seconds }}, >60=${values.count { it.seconds > 60 }}"
        println("$label：${summary(errors)}")
        errors.groupBy { it.distance }.toSortedMap().forEach { (distance, values) ->
            println("$label / $distance 站：${summary(values)}")
        }
    }

    private fun line1Snapshots(tag: String): List<MetroRealtime> =
        requireNotNull(MetroFixtures.file("line1-multi/index.tsv").parentFile).listFiles().orEmpty()
            .filter { it.name.matches(Regex("rt-01000000-\\d{8}-$tag\\.json")) }
            .sortedBy { it.name }
            .map { remote.parse(it.readText(), "01000000") }
}
