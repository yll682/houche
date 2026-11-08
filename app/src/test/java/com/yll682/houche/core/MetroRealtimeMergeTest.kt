package com.yll682.houche.core

import org.junit.Assert.*
import org.junit.Test

/** 真实配对是无 ID 的相容观测，不把离线近似关联写成已确认的物理车辆身份。 */
class MetroRealtimeMergeTest {
    private val remote = MetroRealtimeRemote()
    private val codes = listOf("A", "B", "C", "D", "E", "F", "G")

    @Test fun `24批真实双参考响应保留160个位置组和本站原始到站行`() {
        val directory = MetroFixtures.file("metro-round2/rt-02000000-095729.json").parentFile
        val adminFiles = directory.listFiles().orEmpty()
            .filter { it.name.matches(Regex("rt-02000000-\\d{6}\\.json")) }.sortedBy { it.name }
        assertEquals(24, adminFiles.size)
        var rawCount = 0
        var positionCount = 0
        var approximateGroups = 0
        var singleObservations = 0
        adminFiles.forEach { file ->
            val admin = remote.parse(file.readText(), "02000000")
            val mq = remote.parse(directory.resolve(file.name.removeSuffix(".json") + "-mq.json").readText(), "02000000")
            listOf(admin, mq).forEach { sample ->
                assertNotNull(file.name, sample.source?.serverEpochMillis)
                assertEquals(sample.stationCode, sample.source?.referenceStationCode)
                sample.directions.forEach { assertEquals(sample.stationCode, it.source?.referenceStationCode) }
            }
            val merged = mergeMetroRealtime(listOf(admin, mq), admin.stationCode, "02000000")
            assertTrue(file.name, merged.online)
            assertEquals("02100000", merged.stationCode)
            assertEquals(setOf("02100000", "02090000"), merged.sources.map { it.referenceStationCode }.toSet())
            assertEquals(2, merged.directions.size)
            merged.directions.forEach { direction ->
                val original = admin.directions.single { it.directionKey == direction.directionKey }
                val other = mq.directions.single { it.directionKey == direction.directionKey }
                assertTrue(direction.arrivalsAvailable)
                assertEquals(original.trains.map(::arrivalValues), direction.trains.map(::arrivalValues))
                assertEquals(3, direction.trains.size)
                assertTrue(direction.trains.all { it.source?.referenceStationCode == admin.stationCode })
                // 真实样本每份响应同 next 唯一；已审计的重叠组各保留一个位置。
                assertEquals((original.trains + other.trains).map { it.nextStationCode }.toSet(), direction.positions.map { it.nextStationCode }.toSet())
                assertEquals(direction.positions.size, direction.positions.map { it.nextStationCode }.distinct().size)
                direction.positions.forEach { train ->
                    assertTrue(direction.positionOf(train) >= 0)
                    assertNotNull(train.source)
                    if (train.matchedObservations.isEmpty()) singleObservations++ else {
                        assertEquals(2, train.matchedObservations.size)
                        assertEquals(2, train.matchedObservations.map { it.source.referenceStationCode }.distinct().size)
                        assertTrue(train.matchedObservations.any {
                            it.source == train.source && it.arriveSeconds == train.arriveSeconds &&
                                it.scale == train.scale && it.arrivingFlag == train.arrivingFlag
                        })
                        approximateGroups++
                    }
                }
                rawCount += original.trains.size + other.trains.size
                positionCount += direction.positions.size
            }
        }
        assertEquals(288, rawCount)
        assertEquals(160, positionCount)
        assertEquals(128, approximateGroups)
        assertEquals(32, singleObservations)
    }

    @Test fun `095729行政中心马青路区间车只补位置且不冒充行政中心ETA`() {
        val admin = parse("095729")
        val mq = parse("095729", true)
        val merged = mergeMetroRealtime(listOf(admin, mq), "02100000")
        val direction = merged.directions.single { it.stationCodes.first() == "02320000" }
        assertEquals(4, direction.positions.size)
        assertEquals(3, direction.trains.size)
        assertFalse(direction.trains.any { it.nextStationCode == "02090000" })
        val target = direction.positions.single { it.nextStationCode == "02090000" }
        assertEquals("02090000", target.source?.referenceStationCode)
        assertEquals(82, target.arriveSeconds)
        assertEquals(0.30, target.scale, 0.0)
        assertEquals(1, target.arrivingFlag)
        assertEquals(22.5, direction.positionOf(target), 0.0)
        assertTrue(target.matchedObservations.isEmpty())
        val overlap = direction.positions.single { it.nextStationCode == "02120000" }
        assertEquals("02100000", overlap.source?.referenceStationCode)
        assertEquals(332, overlap.arriveSeconds)
        assertEquals(setOf(332, 442), overlap.matchedObservations.map { it.arriveSeconds }.toSet())
    }

    @Test fun `参考站相关旗标使用最近参考观测且不随输入顺序或用户选站变化`() {
        val admin = parse("095729")
        val mq = parse("095729", true)
        val forward = mergeMetroRealtime(listOf(admin, mq), "02100000")
        val sameSelection = mergeMetroRealtime(listOf(mq.copy(directions = mq.directions.reversed()), admin), "02100000")
        assertEquals(forward.directions.map { it.directionKey }, sameSelection.directions.map { it.directionKey })
        val reordered = mergeMetroRealtime(listOf(mq.copy(directions = mq.directions.reversed()), admin), "02090000")
        assertEquals(positionValues(forward), positionValues(reordered))
        val direction = forward.directions.single { it.stationCodes.first() == "02010000" }
        val train = direction.positions.single { it.nextStationCode == "02090000" }
        assertEquals("02090000", train.source?.referenceStationCode)
        assertEquals(2, train.arrivingFlag)
        assertEquals(20, train.arriveSeconds)
        assertEquals(8.0, direction.positionOf(train), 0.0)
        val selectedArrival = direction.trains.single { it.nextStationCode == "02090000" }
        assertEquals("02100000", selectedArrival.source?.referenceStationCode)
        assertEquals(1, selectedArrival.arrivingFlag)
        assertEquals(137, selectedArrival.arriveSeconds)
        assertEquals(setOf(1, 2), train.matchedObservations.map { it.arrivingFlag }.toSet())
        val otherBatch = mergeMetroRealtime(listOf(parse("095629"), parse("095629", true)), "02090000")
        val opposite = otherBatch.directions.single { it.stationCodes.first() == "02320000" }
            .positions.single { it.nextStationCode == "02100000" }
        assertEquals("02100000", opposite.source?.referenceStationCode)
        assertEquals(2, opposite.arrivingFlag)
    }

    @Test fun `补充区间车没有本站班序而选中马青路时是原始第一班`() {
        val snapshots = listOf(parse("095729"), parse("095729", true))
        val admin = mergeMetroRealtime(snapshots, "02100000").directions.single { it.stationCodes.first() == "02320000" }
        val mq = mergeMetroRealtime(snapshots, "02090000").directions.single { it.stationCodes.first() == "02320000" }
        assertNull(admin.arrivalOrderOf(admin.positions.single { it.nextStationCode == "02090000" }))
        assertEquals(1, mq.arrivalOrderOf(mq.positions.single { it.nextStationCode == "02090000" }))
        assertEquals(1, admin.arrivalOrderOf(admin.positions.single { it.nextStationCode == "02120000" }))
        assertEquals(2, mq.arrivalOrderOf(mq.positions.single { it.nextStationCode == "02120000" }))
    }

    @Test fun `位置代表来自另一参考站仍用原始观测关联本站行而非位置下标`() {
        val direction = mergeMetroRealtime(listOf(parse("095729"), parse("095729", true)), "02100000")
            .directions.single { it.stationCodes.first() == "02010000" }
        val firstPosition = direction.positions.single { it.nextStationCode == "02090000" }
        assertEquals("02090000", firstPosition.source?.referenceStationCode)
        assertEquals(20, firstPosition.arriveSeconds)
        assertEquals(137, direction.trains.first().arriveSeconds)
        assertEquals(1, direction.arrivalOrderOf(firstPosition))
        assertEquals(2, direction.arrivalOrderOf(direction.positions.single { it.nextStationCode == "02080000" }))
    }

    @Test fun `重复到站行或不匹配的来源不随意返回班序`() {
        val direction = mergeMetroRealtime(listOf(snapshot("E", listOf(train("B", 500)))), "E").directions.single()
        val position = direction.positions.single()
        assertEquals(1, direction.arrivalOrderOf(position))
        assertNull(direction.copy(trains = direction.trains + direction.trains).arrivalOrderOf(position))
        assertNull(direction.arrivalOrderOf(position.copy(source = MetroObservationSource("F", serverEpochMillis = 1000L))))
    }

    @Test fun `最近位置按当前方向距本站距离选择并在同距时选上游`() {
        val direction = snapshot("D", emptyList()).directions.single().copy(positionTrains = listOf(
            train("C", 50).copy(arrivingFlag = 2), train("G", 80).copy(arrivingFlag = 2), train("UNKNOWN", 10)
        ))
        assertEquals(2.0, direction.nearestPositionTo("D")!!, 0.0)
        val reverse = direction.copy(stationCodes = codes.reversed(), positionTrains = listOf(
            train("E", 50).copy(arrivingFlag = 2), train("A", 80).copy(arrivingFlag = 2)
        ))
        assertEquals(2.0, reverse.nearestPositionTo("D")!!, 0.0)
        val tie = direction.copy(positionTrains = listOf(train("E", 80).copy(arrivingFlag = 2), train("C", 50).copy(arrivingFlag = 2)))
        assertEquals(2.0, tie.nearestPositionTo("D")!!, 0.0)
        assertNull(direction.nearestPositionTo("MISSING"))
        assertNull(direction.copy(positionTrains = emptyList()).nearestPositionTo("D"))
    }

    @Test fun `同名端点但内部code链不兼容不跨方向合并`() {
        val first = snapshot("E", listOf(train("B", 500), train("C", 800)))
        val second = snapshot("F", listOf(train("B", 600), train("C", 900)))
        val changed = second.directions.single().copy(stationCodes = listOf("A", "C", "B", "D", "E", "F", "G"))
        val merged = mergeMetroRealtime(listOf(first, second.copy(directions = listOf(changed))), "E")
        assertEquals(2, merged.directions.size)
        assertEquals(4, merged.directions.sumOf { it.positions.size })
        assertTrue(merged.directions.all { direction -> direction.positions.all { it.matchedObservations.isEmpty() } })
        assertEquals(1, merged.directions.count { it.arrivalsAvailable })
        val noSelection = mergeMetroRealtime(listOf(first, second.copy(directions = listOf(changed))), "NOT_SELECTED")
        val reversed = mergeMetroRealtime(listOf(second.copy(directions = listOf(changed)), first), "NOT_SELECTED")
        assertEquals(noSelection.directions.map { it.directionKey }, reversed.directions.map { it.directionKey })
    }

    @Test fun `站序缺码不借数组位置并方向或影响其他完整链车辆`() {
        val first = snapshot("E", listOf(train("B", 500), train("C", 800)))
        val second = snapshot("F", listOf(train("B", 600), train("C", 900)))
        val incomplete = second.directions.single().copy(stationCodes = listOf("A", "", "C", "D", "E", "F", "G"))
        assertNull(incomplete.directionKey)
        val merged = mergeMetroRealtime(listOf(first, second.copy(directions = listOf(incomplete))), "E")
        assertEquals(2, merged.directions.size)
        assertEquals(4, merged.directions.sumOf { it.positions.size })
        assertEquals(setOf("B", "C"), merged.directions.single { it.arrivalsAvailable }.positions.map { it.nextStationCode }.toSet())
    }

    @Test fun `同next两辆车不因另有两个一致偏移见证而一锅合并`() {
        val left = snapshot("E", listOf(train("B", 500), train("B", 650), train("C", 900), train("D", 1200)))
        val right = snapshot("F", listOf(train("B", 600), train("B", 750), train("C", 1000), train("D", 1300)))
        val result = mergeMetroRealtime(listOf(left, right), "E").directions.single()
        assertEquals(6, result.positions.size)
        assertEquals(4, result.positions.count { it.nextStationCode == "B" })
        assertTrue(result.positions.filter { it.nextStationCode == "B" }.all { it.matchedObservations.isEmpty() })
        assertEquals(2, result.positions.count { it.matchedObservations.size == 2 })
        assertEquals(3, result.trains.size)
    }

    @Test fun `只有单候选或站间偏移不一致时保留两源记录`() {
        val single = mergeMetroRealtime(listOf(snapshot("E", listOf(train("B", 500))), snapshot("F", listOf(train("B", 600)))), "E")
        assertEquals(2, single.directions.single().positions.size)
        val inconsistent = mergeMetroRealtime(listOf(
            snapshot("E", listOf(train("B", 500), train("C", 800))),
            snapshot("F", listOf(train("B", 600), train("C", 1000)))
        ), "E")
        assertEquals(4, inconsistent.directions.single().positions.size)
        val wrongSign = mergeMetroRealtime(listOf(
            snapshot("E", listOf(train("B", 500), train("C", 800))),
            snapshot("F", listOf(train("B", 400), train("C", 700)))
        ), "E")
        assertEquals(4, wrongSign.directions.single().positions.size)
    }

    @Test fun `缺码和未知位置原车保留而不妨碍另外两车近似去重`() {
        val result = mergeMetroRealtime(listOf(
            snapshot("E", listOf(train("", 100), train("B", 500), train("C", 800))),
            snapshot("F", listOf(train("UNKNOWN", 200), train("B", 600), train("C", 900)))
        ), "E").directions.single()
        assertEquals(4, result.positions.size)
        assertEquals(2, result.positions.count { it.matchedObservations.size == 2 })
        assertEquals(2, result.positions.count { result.positionOf(it) == -1.0 })
    }

    @Test fun `缺失无效或差距过大的scale不参与近似识别`() {
        val left = snapshot("E", listOf(train("B", 500), train("C", 800)))
        listOf(
            train("B", 600).copy(scaleKnown = false),
            train("B", 600).copy(scale = Double.NaN),
            train("B", 600).copy(scale = -0.1),
            train("B", 600).copy(scale = 0.45)
        ).forEach { invalid ->
            val right = snapshot("F", listOf(invalid, train("C", 900)))
            assertEquals(4, mergeMetroRealtime(listOf(left, right), "E").directions.single().positions.size)
        }
    }

    @Test fun `时间缺失或相距过大不跨批去重但单调接收时间可作同域兜底`() {
        val left = snapshot("E", listOf(train("B", 500), train("C", 800)))
        val right = snapshot("F", listOf(train("B", 600), train("C", 900)), 7_000L)
        assertEquals(4, mergeMetroRealtime(listOf(left, right), "E").directions.single().positions.size)
        val withoutTime = withSource(right, MetroObservationSource("F"))
        assertEquals(4, mergeMetroRealtime(listOf(left, withoutTime), "E").directions.single().positions.size)
        val monotonicLeft = withSource(left, MetroObservationSource("E", receivedAtElapsedMillis = 1_000L))
        val monotonicRight = withSource(right, MetroObservationSource("F", receivedAtElapsedMillis = 1_200L))
        assertEquals(2, mergeMetroRealtime(listOf(monotonicLeft, monotonicRight), "E").directions.single().positions.size)
    }

    @Test fun `三源只有传递关系而无每对直接证据时不删任一原车`() {
        val samples = listOf(
            snapshot("E", listOf(train("B", 500), train("C", 800)), 1_000L),
            snapshot("F", listOf(train("B", 600), train("C", 900)), 4_000L),
            snapshot("G", listOf(train("B", 700), train("C", 1000)), 7_000L)
        )
        val result = mergeMetroRealtime(samples, "E")
        assertEquals(6, result.directions.single().positions.size)
        assertTrue(result.directions.single().positions.all { it.matchedObservations.isEmpty() })
        assertEquals(positionValues(result), positionValues(mergeMetroRealtime(samples.reversed(), "E")))
    }

    @Test fun `三源每对均通过时每组只留一车并保留三个真实来源`() {
        val result = mergeMetroRealtime(listOf(
            snapshot("E", listOf(train("B", 500), train("C", 800)), 1_000L),
            snapshot("F", listOf(train("B", 600), train("C", 900)), 1_200L),
            snapshot("G", listOf(train("B", 700), train("C", 1000)), 1_400L)
        ), "F").directions.single()
        assertEquals(2, result.positions.size)
        assertTrue(result.positions.all { it.matchedObservations.size == 3 })
        assertTrue(result.positions.all { it.source?.referenceStationCode == "E" })
        assertTrue(result.trains.all { it.source?.referenceStationCode == "F" })
    }

    @Test fun `本站失败其他参考站成功仅有位置而不生成本站到站行`() {
        val unavailable = MetroRealtime(false, "HTTP 500", lineCode = "L", stationCode = "E")
        val result = mergeMetroRealtime(listOf(unavailable, snapshot("F", listOf(train("B", 600), train("C", 900)))), "E")
        assertTrue(result.online)
        assertEquals("E", result.stationCode)
        assertTrue(result.note.contains("本站到站暂不可用"))
        val direction = result.directions.single()
        assertFalse(direction.arrivalsAvailable)
        assertTrue(direction.trains.isEmpty())
        assertEquals(2, direction.positions.size)
        assertTrue(direction.positions.all { it.source?.referenceStationCode == "F" })
        val failed = mergeMetroRealtime(listOf(unavailable), "E")
        assertFalse(failed.online)
        assertTrue(failed.directions.isEmpty())
    }

    @Test fun `本站有效空响应仍保留其他位置且下一轮不合并旧车`() {
        val full = snapshot("E", listOf(train("B", 500), train("C", 800)))
        assertEquals(2, mergeMetroRealtime(listOf(full), "E").directions.single().positions.size)
        val empty = snapshot("E", emptyList(), 20_000L)
        val next = mergeMetroRealtime(listOf(empty), "E").directions.single()
        assertTrue(next.arrivalsAvailable)
        assertTrue(next.trains.isEmpty())
        assertTrue(next.positions.isEmpty())
        val partial = mergeMetroRealtime(listOf(empty, snapshot("F", listOf(train("D", 700)), 20_200L)), "E").directions.single()
        assertTrue(partial.arrivalsAvailable)
        assertTrue(partial.trains.isEmpty())
        assertEquals(listOf("D"), partial.positions.map { it.nextStationCode })
    }

    @Test fun `重复参考站只取新响应而单站copy默认位置跟随新trains`() {
        val old = snapshot("E", listOf(train("B", 500)), 1_000L)
        val fresh = snapshot("E", listOf(train("D", 800)), 2_000L)
        val merged = mergeMetroRealtime(listOf(old, fresh), "E").directions.single()
        assertEquals(listOf("D"), merged.positions.map { it.nextStationCode })
        assertEquals(listOf("D"), merged.trains.map { it.nextStationCode })
        assertTrue(old.directions.single().copy(trains = emptyList()).positions.isEmpty())
    }

    @Test fun `未知参考来源和没有共同时间的同参考观测不冒认同一响应`() {
        val unknown = withSource(snapshot("", listOf(train("B", 500))), MetroObservationSource(""))
        assertEquals(2, mergeMetroRealtime(listOf(unknown, unknown.copy()), "E").directions.single().positions.size)
        val withoutTime = withSource(snapshot("E", listOf(train("B", 500))), MetroObservationSource("E"))
        assertEquals(2, mergeMetroRealtime(listOf(withoutTime, withoutTime.copy()), "E").directions.single().positions.size)
        val equalTime = listOf(snapshot("E", listOf(train("B", 500))), snapshot("E", listOf(train("D", 800))))
        assertEquals(setOf("B", "D"), mergeMetroRealtime(equalTime, "E").directions.single().positions.map { it.nextStationCode }.toSet())
        val conflict = snapshot("E", listOf(train("B", 500).copy(source = MetroObservationSource("G", serverEpochMillis = 1000L)), train("C", 800)))
        val other = snapshot("F", listOf(train("B", 600), train("C", 900)))
        assertEquals(4, mergeMetroRealtime(listOf(conflict, other), "E").directions.single().positions.size)
    }

    private fun parse(time: String, mq: Boolean = false): MetroRealtime = remote.parse(
        MetroFixtures.file("metro-round2/rt-02000000-$time${if (mq) "-mq" else ""}.json").readText(), "02000000"
    )

    private fun train(next: String, seconds: Int) = MetroTrain(seconds, next, next, 0.3, 1, 0, "")

    private fun snapshot(reference: String, trains: List<MetroTrain>, at: Long = 1_000L): MetroRealtime {
        val source = MetroObservationSource(reference, serverEpochMillis = at)
        return MetroRealtime(true, "test", "L", reference, reference, listOf(
            MetroDirection("同名始发", "同名终点", "06:00", "23:00", 0, trains, codes, source = source)
        ), source = source)
    }

    private fun withSource(snapshot: MetroRealtime, source: MetroObservationSource): MetroRealtime = snapshot.copy(
        source = source, directions = snapshot.directions.map { it.copy(source = source) }
    )

    private fun arrivalValues(train: MetroTrain) = listOf(train.nextStationCode, train.arriveSeconds, train.arrivingFlag, train.scale)

    private fun positionValues(result: MetroRealtime): Map<MetroDirectionKey?, List<List<Any?>>> = result.directions.associate { direction ->
        direction.directionKey to direction.positions.map { train ->
            listOf(train.nextStationCode, train.source, train.arriveSeconds, train.scale, train.arrivingFlag, train.matchedObservations)
        }
    }
}
