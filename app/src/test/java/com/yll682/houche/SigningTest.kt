package com.yll682.houche

import com.yll682.houche.core.*
import org.junit.Assert.*
import org.junit.Test

class SigningTest {
    // 期望值由 python3 -c 调用权威实现现算，固定毫秒时间与 UTF-8 中文输入。
    @Test fun normalSignature() {
        assertEquals("OTE1MUY0RjFFMDY4ODc2RjYxNjNGNjY1NDlFRjcyNjQ=", normalSign(
            mapOf("busLineName" to "91路", "empty" to null, "html" to "<站>", "literal" to "null"),
            "0123456789abcdef0123456789abcdef", "1700000000123"))
    }
    @Test fun v2Signature() {
        assertEquals("4C337DCE865A3E7B09021BD55480E51F", v2Sign("stationLineBus", mapOf(
            "stationNo" to "HL000111", "stationName" to "高崎T4公交场站", "stationLon" to "118.15230555", "stationLat" to "24.54193506", "lineNum" to "3"), "1700000000123"))
    }
    @Test fun invalidV2Parameters() {
        try { v2Sign("lineBus", mapOf("lineName" to "91路"), "1700000000123"); fail("应拒绝缺少参数") }
        catch (error: BusException) { assertEquals(BusException.Kind.ARGUMENT, error.kind) }
    }
}
