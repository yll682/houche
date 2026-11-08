package com.yll682.houche.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 真实站表负责地名、线路与原始坐标；预期转换值来自运行已还原官方 Java 类的独立结果。 */
class MetroGeographyTest {
    private val lines: List<MetroLine> by lazy {
        val asset = sequenceOf(File("app/src/main/assets/metro.json"), File("src/main/assets/metro.json"))
            .first { it.isFile }
        BusNetwork.json.decodeFromString<MetroNetwork>(asset.readText()).lines
    }

    @Test fun `厦门火车站附近真实资料命中三号线而不补造一号线或BRT`() {
        // 附近探针以官方站点转换后的坐标取四位小数；不是声称抓取到的用户 GPS。
        val nearest = lines.nearestStation(24.4691, 118.1148, 25.0, "GCJ02")!!
        assertEquals("3", nearest.lineId)
        assertEquals("03010000", nearest.stationCode)
        assertTrue(nearest.distanceMeters in 0.0..20.0)
        assertEquals("厦门火车站", lines.first { it.id == "3" }.stations.first { it.code == nearest.stationCode }.name)
    }

    @Test fun `集美学村附近命中一号线真实站点`() {
        val nearest = lines.nearestStation(24.5646, 118.0920, 40.0, "GCJ02")!!
        assertEquals("1", nearest.lineId)
        assertEquals("01140000", nearest.stationCode)
        assertTrue(nearest.distanceMeters in 0.0..20.0)
    }

    @Test fun `集美厦门北站两线不同原始坐标分别选择四号线和一号线`() {
        val line4 = lines.nearestStation(24.6371, 118.0736, 25.0, "GCJ02")!!
        assertEquals("4", line4.lineId)
        assertEquals("04030000", line4.stationCode)
        assertTrue(line4.distanceMeters in 0.0..20.0)
        val line1 = lines.nearestStation(24.6322, 118.0721, 25.0, "GCJ02")!!
        assertEquals("1", line1.lineId)
        assertEquals("01230000", line1.stationCode)
        assertTrue(line1.distanceMeters in 0.0..20.0)
    }

    @Test fun `原始BD09站坐标仅转换一次并与官方Java独立运行结果一致`() {
        // docs/metro-coordinate-evidence.md 记录原始字段、源码 SHA256 与官方类运行输出。
        // 这些常数不是测试中复制一遍生产转换公式算出的期望值。
        val cases = listOf(
            Probe("3", "03010000", "24.475054", "118.121275", 24.469143872551, 118.114767457255),
            Probe("1", "01140000", "24.570854", "118.098386", 24.564570073133, 118.091964751664),
            Probe("4", "04030000", "24.643390", "118.080001", 24.637052307606, 118.073552542621),
            Probe("1", "01230000", "24.638548", "118.078596", 24.632219638959, 118.072144254134)
        )
        for (probe in cases) {
            val line = lines.first { it.id == probe.lineId }
            val station = line.stations.first { it.code == probe.code }
            assertEquals(probe.rawLat, station.lat)
            assertEquals(probe.rawLng, station.lng)
            assertEquals("BD09", station.coordinateSystem)
            val nearest = listOf(line.copy(stations = listOf(station)))
                .nearestStation(probe.gcjLat, probe.gcjLng, 10.0, "GCJ02")!!
            assertEquals(probe.code, nearest.stationCode)
            assertTrue("${probe.code}: ${nearest.distanceMeters}", nearest.distanceMeters < 0.01)
        }
    }

    @Test fun `未知来源不默认套用地铁坐标系且WGS84暂不隐式换算`() {
        val original = line("1", station("01010000"))
        for (system in listOf("unconfirmed", "", "WGS84", "not-a-system")) {
            assertNull(listOf(original.copy(stations = original.stations.map { it.copy(coordinateSystem = system) }))
                .nearestStation(24.5, 118.1, 20.0, "GCJ02"))
            assertNull(listOf(original).nearestStation(24.5, 118.1, 20.0, system))
        }
        assertNull(listOf(original).nearestStation(24.5, 118.1, 20.0, "BD09"))
        assertEquals(MetroCoordinateSystem.UNKNOWN, MetroCoordinateSystem.from(null))
        assertEquals(MetroCoordinateSystem.UNKNOWN, MetroCoordinateSystem.from("unconfirmed"))
        assertEquals(MetroCoordinateSystem.GCJ02, MetroCoordinateSystem.from("gcj02"))
    }

    @Test fun `无权限定位失败未知精度及超过一公里精度不选站`() {
        val available = listOf(line("1", station("01010000")))
        assertNull(available.nearestStation(null, null, null, "GCJ02"))
        assertNull(available.nearestStation(null, 118.1, 20.0, "GCJ02"))
        assertNull(available.nearestStation(24.5, null, 20.0, "GCJ02"))
        for (accuracy in listOf(null, -1.0, 1_000.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(isMetroLocationUsable(24.5, 118.1, accuracy, "GCJ02"))
            assertNull(available.nearestStation(24.5, 118.1, accuracy, "GCJ02"))
        }
        assertTrue(isMetroLocationUsable(24.5, 118.1, 1_000.0, "GCJ02"))
        assertNotNull(available.nearestStation(24.5, 118.1, 1_000.0, "GCJ02"))
    }

    @Test fun `无效定位经纬度安全返回空`() {
        val available = listOf(line("1", station("01010000")))
        for ((lat, lng) in listOf(
            Double.NaN to 118.1, 24.5 to Double.NaN,
            Double.NEGATIVE_INFINITY to 118.1, 24.5 to Double.POSITIVE_INFINITY,
            90.01 to 118.1, -90.01 to 118.1, 24.5 to 180.01, 24.5 to -180.01
        )) {
            assertFalse(isMetroLocationUsable(lat, lng, 20.0, "GCJ02"))
            assertNull(available.nearestStation(lat, lng, 20.0, "GCJ02"))
        }
    }

    @Test fun `仅在五公里内选站并以米返回约一百一十一米的纬度间隔`() {
        // 赤道上一度纬度约111km；只用宽容差检查单位与量级，不复制haversine公式。
        val available = listOf(line("1", station("01010000").copy(lat = "0", lng = "0")))
        val nearby = available.nearestStation(0.001, 0.0, 20.0, "GCJ02")!!
        assertTrue(nearby.distanceMeters in 110.0..112.0)
        assertNotNull(available.nearestStation(0.0449, 0.0, 20.0, "GCJ02"))
        assertNull(available.nearestStation(0.0450, 0.0, 20.0, "GCJ02"))
        assertNull(lines.nearestStation(0.0, 0.0, 20.0, "GCJ02"))
        assertNull(emptyList<MetroLine>().nearestStation(24.5, 118.1, 20.0, "GCJ02"))
    }

    @Test fun `不开通线路站点空站码及坏坐标均不作为候选`() {
        val unusable = listOf(
            line("0", station("00010000")).copy(opened = false),
            line("1", station("01010000").copy(opened = false)),
            line("2", station(" ")),
            line("3", station("03010000").copy(lat = "NaN")),
            line("4", station("04010000").copy(lng = "Infinity")),
            line("5", station("05010000").copy(lat = "90.1")),
            line("6", station("06010000").copy(lng = "-180.1")),
            line("7", station("07010000").copy(lng = "")),
            line(" ", station("08010000"))
        )
        assertNull(unusable.nearestStation(24.5, 118.1, 20.0, "GCJ02"))
        val usable = line("9", station("09010000").copy(lat = "24.501"))
        assertEquals("09010000", (unusable + usable).nearestStation(24.5, 118.1, 20.0, "GCJ02")!!.stationCode)
    }

    @Test fun `跨线同站同距时按线路和站码稳定选择不依赖输入排序`() {
        val candidates = listOf(
            line("10", station("10010000")),
            line("2", station("02010000")),
            line("1", station("01020000"), station("01010000"))
        )
        val nearest = candidates.nearestStation(24.5, 118.1, 20.0, "GCJ02")!!
        assertEquals("1", nearest.lineId)
        assertEquals("01010000", nearest.stationCode)
        assertEquals(nearest, candidates.reversed().map { it.copy(stations = it.stations.reversed()) }
            .nearestStation(24.5, 118.1, 20.0, "GCJ02"))
        assertEquals("2", candidates.take(2).nearestStation(24.5, 118.1, 20.0, "GCJ02")!!.lineId)
    }

    @Test fun `重复线路id的近远记录不因输入顺序而误选`() {
        val duplicates = listOf(
            line("1", station("01010000")),
            line("1", station("01020000").copy(lat = "30.0"))
        )
        for (ordered in listOf(duplicates, duplicates.reversed())) {
            assertNull(ordered.nearestStation(24.5, 118.1, 20.0, "GCJ02"))
            val unambiguous = line("2", station("02010000").copy(lat = "24.501"))
            assertEquals("2", (ordered + unambiguous).nearestStation(24.5, 118.1, 20.0, "GCJ02")!!.lineId)
        }
    }

    @Test fun `同线路近远两条同站码均跳过且不受站序影响`() {
        val duplicates = listOf(station("01010000"), station("01010000").copy(lat = "30.0"))
        for (ordered in listOf(duplicates, duplicates.reversed())) {
            val ambiguous = line("1", *ordered.toTypedArray())
            assertNull(listOf(ambiguous).nearestStation(24.5, 118.1, 20.0, "GCJ02"))
            val unambiguous = station("01020000").copy(lat = "24.501")
            val available = listOf(ambiguous.copy(stations = ordered + unambiguous))
            assertEquals("01020000", available.nearestStation(24.5, 118.1, 20.0, "GCJ02")!!.stationCode)
        }
        // 站码的作用域是线路；不同线路各自唯一的同码不构成回查歧义。
        assertNotNull(listOf(line("1", station("01010000")), line("2", station("01010000")))
            .nearestStation(24.5, 118.1, 20.0, "GCJ02"))
    }

    private fun line(id: String, vararg stations: MetroStation) =
        MetroLine(id = id, name = "测试线路$id", stations = stations.toList())

    private fun station(code: String) = MetroStation(
        order = 1, name = "纯边界测试站", code = code, lat = "24.5", lng = "118.1", coordinateSystem = "GCJ02"
    )

    private data class Probe(
        val lineId: String, val code: String, val rawLat: String, val rawLng: String,
        val gcjLat: Double, val gcjLng: Double
    )
}
