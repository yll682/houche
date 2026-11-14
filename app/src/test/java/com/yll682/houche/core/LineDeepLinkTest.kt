package com.yll682.houche.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class 线路深链接测试 {
    private val web = "https://houche.rainlodyn.cn/share/"
    private val scheme = "houche://line"
    private val query = "city=0592&line=007&name=7%E8%B7%AF&dir=1"
    private val target = ShareTarget.Line("0592", "007", "7路", "1")

    @Test fun `线路网页链接以类型开头并按固定顺序精确编码且没有车牌参数`() {
        assertEquals(
            "https://houche.rainlodyn.cn/share/?type=line&city=0592&line=007&name=7%E8%B7%AF&dir=1",
            VehicleShareLink.buildLineWebLink("0592", "007", "7路", "1")
        )
    }

    @Test fun `线路应用链接使用线路主机并在查询首位输出类型`() {
        assertEquals(
            "houche://line?type=line&city=0592&line=007&name=7%E8%B7%AF&dir=1",
            VehicleShareLink.buildLineSchemeLink("0592", "007", "7路", "1")
        )
    }

    @Test fun `线路两个构建入口精确转义空格加号分隔符和补充平面字符`() {
        val expectedQuery = "type=line&city=0592+%2B&line=A%2BB+%26%3D%25%3F%23" +
            "&name=7%E8%B7%AF+%2B%26%3D%25%3F%23%F0%9F%9A%8C&dir=2"
        assertEquals(
            "$web?$expectedQuery",
            VehicleShareLink.buildLineWebLink("0592 +", "A+B &=%?#", "7路 +&=%?#🚌", "2")
        )
        assertEquals(
            "$scheme?$expectedQuery",
            VehicleShareLink.buildLineSchemeLink("0592 +", "A+B &=%?#", "7路 +&=%?#🚌", "2")
        )
    }

    @Test fun `线路构建时空编号仍输出线路键且空方向往返为空串`() {
        val expectedQuery = "type=line&city=0592&line=&name=7%E8%B7%AF&dir="
        for (lineId in listOf(null, "")) {
            val webLink = VehicleShareLink.buildLineWebLink("0592", lineId, "7路", "")
            val schemeLink = VehicleShareLink.buildLineSchemeLink("0592", lineId, "7路", "")
            assertEquals("$web?$expectedQuery", webLink)
            assertEquals("$scheme?$expectedQuery", schemeLink)
            assertEquals(target.copy(lineId = null, direction = ""), VehicleShareLink.parse(webLink))
            assertEquals(target.copy(lineId = null, direction = ""), VehicleShareLink.parse(schemeLink))
        }
    }

    @Test fun `车辆线路四入口与旧车辆链接分别生成正确目标类型`() {
        val vehicle = ShareTarget.Vehicle("0592", "007", "7路", "1", "闽D12345")
        for ((link, expected) in listOf(
            "https://houche.rainlodyn.cn/share/?$query&plate=%E9%97%BDD12345" to vehicle,
            "houche://vehicle?$query&plate=%E9%97%BDD12345" to vehicle,
            "https://houche.rainlodyn.cn/share/?type=vehicle&$query&plate=%E9%97%BDD12345" to vehicle,
            "houche://vehicle?type=vehicle&$query&plate=%E9%97%BDD12345" to vehicle,
            "https://houche.rainlodyn.cn/share/?type=line&$query" to target,
            "houche://line?type=line&$query" to target,
            "houche://line?$query" to target
        )) {
            assertEquals(link, expected, VehicleShareLink.parse(link))
        }
    }

    @Test fun `线路双协议保留城市和线路前导零且不需要车牌`() {
        验证线路入口(query, target)
    }

    @Test fun `网页缺省类型继续要求车牌而不根据线路字段推断类型`() {
        assertNull(VehicleShareLink.parse("$web?$query"))
        assertNull(VehicleShareLink.parse("$web?type=vehicle&$query"))
        assertEquals(
            ShareTarget.Vehicle("0592", "007", "7路", "1", "闽D12345"),
            VehicleShareLink.parse("$web?$query&plate=闽D12345")
        )
        assertEquals(target, VehicleShareLink.parse("$web?type=line&$query&plate=闽D12345"))
    }

    @Test fun `应用主机与显式类型冲突时即使车辆字段齐全也拒绝`() {
        for (link in listOf(
            "houche://vehicle?type=line&$query&plate=闽D12345",
            "houche://line?type=vehicle&$query&plate=闽D12345",
            "HOUCHE://VEHICLE?type=line&$query&plate=闽D12345",
            "HOUCHE://LINE?type=vehicle&$query&plate=闽D12345"
        )) {
            assertNull(link, VehicleShareLink.parse(link))
        }
    }

    @Test fun `空白未知大小写变体与被二次编码的类型在所有入口均拒绝`() {
        for (type in listOf(
            "", "+", "%20", "%09", "%E3%80%80", "unknown", "bus", "LINE", "Vehicle",
            "+line", "vehicle+", "%256Cine", "line%00", "line=vehicle"
        )) {
            for (base in listOf(web, "houche://vehicle", scheme)) {
                val link = "$base?type=$type&$query&plate=闽D12345"
                assertNull(link, VehicleShareLink.parse(link))
            }
        }
        for (base in listOf(web, "houche://vehicle", scheme)) {
            val link = "$base?type&$query&plate=闽D12345"
            assertNull(link, VehicleShareLink.parse(link))
        }
    }

    @Test fun `类型键值只解码一次且不受参数顺序限制`() {
        assertEquals(
            target,
            VehicleShareLink.parse("$web?d%69r=1&n%61me=7路&l%69ne=007&c%69ty=0592&t%79pe=%6Cine")
        )
        assertEquals(target, VehicleShareLink.parse("$scheme?$query&t%79pe=%6Cine"))
        assertEquals(
            ShareTarget.Vehicle("0592", "007", "7路", "1", "闽D12345"),
            VehicleShareLink.parse("$web?%2574ype=line&$query&plate=闽D12345")
        )
        assertNull(VehicleShareLink.parse("$web?type=%256Cine&$query"))
    }

    @Test fun `参数名区分大小写且未知类型键不覆盖主机或网页缺省类型`() {
        assertEquals(target, VehicleShareLink.parse("$scheme?Type=vehicle&$query"))
        assertEquals(
            ShareTarget.Vehicle("0592", "007", "7路", "1", "闽D12345"),
            VehicleShareLink.parse("$web?Type=line&$query&plate=闽D12345")
        )
        验证线路入口("City=0592&line=007&name=7路&dir=1", null)
        验证线路入口("city=0592&line=007&Name=7路&dir=1", null)
    }

    @Test fun `缺少城市或线路名时编号方向和车牌均不能补齐必填信息`() {
        for (missing in listOf(
            "line=007&name=7路&dir=1",
            "city=0592&line=007&dir=1"
        )) {
            验证线路入口(missing, null)
            验证线路入口("$missing&plate=闽D12345", null)
        }
    }

    @Test fun `城市或名称为空和各种空白均拒绝`() {
        for (blank in listOf("", "+", "%20", "%09", "%0A", "%0D%0A", "%E3%80%80")) {
            验证线路入口("city=$blank&line=007&name=7路&dir=1", null)
            验证线路入口("city=0592&line=007&name=$blank&dir=1", null)
        }
        验证线路入口("city&line=007&name=7路&dir=1", null)
        验证线路入口("city=0592&line=007&name&dir=1", null)
    }

    @Test fun `线路忽略单个车牌参数且不转成车辆目标`() {
        验证线路入口(query, target)
        for (plate in listOf("", "+", "%09", "%E3%80%80", "闽D12345", "%2B%26%3D%25%3F%23")) {
            验证线路入口("$query&plate=$plate", target)
        }
        验证线路入口("$query&plate", target)
    }

    @Test fun `缺少或空白线路编号解析为空且名称本身足够标识线路`() {
        验证线路入口("city=0592&name=7路&dir=1", target.copy(lineId = null))
        for (blank in listOf("", "+", "%20", "%09", "%E3%80%80")) {
            验证线路入口("city=0592&line=$blank&name=7路&dir=1", target.copy(lineId = null))
        }
        验证线路入口("city=0592&line&name=7路&dir=1", target.copy(lineId = null))
    }

    @Test fun `只有城市名称时可生成线路目标且编号方向按契约留空`() {
        验证线路入口("city=0592&name=7路", target.copy(lineId = null, direction = ""))
    }

    @Test fun `一二方向有效而缺省非法方向均保留空串`() {
        for (direction in listOf("1", "2")) {
            验证线路入口("city=0592&line=007&name=7路&dir=$direction", target.copy(direction = direction))
        }
        val unspecified = target.copy(direction = "")
        验证线路入口("city=0592&line=007&name=7路", unspecified)
        验证线路入口("city=0592&line=007&name=7路&dir", unspecified)
        for (direction in listOf("", "0", "3", "-1", "01", "2.0", "null", "上行", "+1", "2+", "%EF%BC%91")) {
            验证线路入口("city=0592&line=007&name=7路&dir=$direction", unspecified)
        }
    }

    @Test fun `名称编号或其它方向参数不能代替方向键`() {
        验证线路入口(
            "city=0592&line=1&name=上行1路&direction=1&upDown=2&DIR=1",
            ShareTarget.Line("0592", "1", "上行1路", "")
        )
    }

    @Test fun `非空白城市编号名称的首尾空格原样保留`() {
        验证线路入口(
            "city=+0592+&line=+007+&name=+7路+&dir=1",
            ShareTarget.Line(" 0592 ", " 007 ", " 7路 ", "1")
        )
    }

    @Test fun `线路所有文本字段的中文特殊字符与表情均可双协议往返`() {
        val expected = ShareTarget.Line(
            cityCode = " 厦门+&=%?# 城 ",
            lineId = "线路 +&=%?#🚌",
            lineName = "公交 +&=%?# 空格 🚌",
            direction = "2"
        )
        for (link in listOf(
            VehicleShareLink.buildLineWebLink(expected.cityCode, expected.lineId, expected.lineName, expected.direction),
            VehicleShareLink.buildLineSchemeLink(expected.cityCode, expected.lineId, expected.lineName, expected.direction)
        )) {
            assertEquals(link, expected, VehicleShareLink.parse(link))
        }
        验证线路入口("city=厦门&line=线路甲&name=公交7路&dir=2", ShareTarget.Line("厦门", "线路甲", "公交7路", "2"))
    }

    @Test fun `空格和字面加号按表单规则解码且百分号仅解码一次`() {
        for ((encoded, decoded) in listOf("+" to " ", "%20" to " ", "%2B" to "+", "%2b" to "+")) {
            验证线路入口("city=0592&line=007&name=7${encoded}路&dir=1", target.copy(lineName = "7${decoded}路"))
        }
        验证线路入口(
            "city=0592&line=007&name=%252B%2526%253D%2525%253F%2523&dir=1",
            target.copy(lineName = "%2B%26%3D%25%3F%23")
        )
    }

    @Test fun `编码分隔符不会注入类型城市或方向且原始等号保留在值中`() {
        验证线路入口(
            "city=0592&line=007&name=7路%26type%3Dvehicle%26dir%3D2%26city%3D010&dir=1",
            target.copy(lineName = "7路&type=vehicle&dir=2&city=010")
        )
        验证线路入口("city=0592&line=007&name=A=B=C&dir=1", target.copy(lineName = "A=B=C"))
        验证线路入口("name=7路%26city%3D0592&dir=1", null)
    }

    @Test fun `线路链接所有已知参数重复时即使相同或空值也拒绝`() {
        for ((key, value) in listOf(
            "city" to "0592", "line" to "007", "name" to "7路",
            "dir" to "1", "plate" to "闽D12345", "type" to "line"
        )) {
            for (duplicate in listOf(value, "其它值", "")) {
                for (base in listOf(web, scheme)) {
                    val link = "$base?type=line&$query&plate=闽D12345&$key=$duplicate"
                    assertNull(link, VehicleShareLink.parse(link))
                }
            }
        }
        验证线路入口("$query&plate=&plate=", null)
        验证线路入口("$query&plate&plate=闽D12345", null)
    }

    @Test fun `转义已知参数名仍参与重复检查且重复类型不能被最后值覆盖`() {
        for ((encodedKey, value) in listOf(
            "c%69ty" to "0592", "l%69ne" to "007", "n%61me" to "7路",
            "d%69r" to "1", "pl%61te" to "闽D12345", "t%79pe" to "line"
        )) {
            for (base in listOf(web, scheme)) {
                val link = "$base?type=line&$query&plate=闽D12345&$encodedKey=$value"
                assertNull(link, VehicleShareLink.parse(link))
            }
        }
        for (base in listOf(web, scheme)) {
            for (types in listOf("type=line&type=line", "type=line&type=vehicle", "type=vehicle&type=line")) {
                val link = "$base?$types&$query&plate=闽D12345"
                assertNull(link, VehicleShareLink.parse(link))
            }
        }
    }

    @Test fun `未知参数可重复并允许空查询片段`() {
        验证线路入口("&$query&&extra=甲&extra=乙&unrelated&", target)
    }

    @Test fun `线路网页允许两种分享路径和默认端口且协议主机不区分大小写`() {
        for (base in listOf(
            "https://houche.rainlodyn.cn/share/", "https://houche.rainlodyn.cn/share",
            "https://houche.rainlodyn.cn:443/share/", "https://houche.rainlodyn.cn:443/share",
            "HTTPS://HOUCHE.RAINLODYN.CN/share/"
        )) {
            assertEquals(base, target, VehicleShareLink.parse("$base?type=line&$query"))
        }
        for (base in listOf(scheme, "$scheme/", "HOUCHE://LINE", "HOUCHE://LINE/")) {
            assertEquals(base, target, VehicleShareLink.parse("$base?$query"))
            assertEquals(base, target, VehicleShareLink.parse("$base?type=line&$query"))
        }
    }

    @Test fun `线路类型不能绕过协议主机和精确路径限制`() {
        for (base in listOf(
            "http://houche.rainlodyn.cn/share/", "ftp://houche.rainlodyn.cn/share/",
            "https://example.com/share/", "https://houche.rainlodyn.cn.example.com/share/",
            "https://line/share/", "https://houche.rainlodyn.cn/", "https://houche.rainlodyn.cn/Share/",
            "https://houche.rainlodyn.cn/share//", "https://houche.rainlodyn.cn/share/extra",
            "https://houche.rainlodyn.cn/share/../", "https://houche.rainlodyn.cn/%73hare/",
            "https://houche.rainlodyn.cn/share%2F", "houche://other", "houche://other/line",
            "houche://line/extra", "houche://line//", "houche://line/%2F",
            "houche:///line", "houche:/line", "houche:line", "//houche.rainlodyn.cn/share/", "/share/"
        )) {
            val link = "$base?type=line&$query"
            assertNull(link, VehicleShareLink.parse(link))
        }
    }

    @Test fun `线路链接拒绝用户信息和非默认端口以及应用协议任意端口`() {
        for (base in listOf(
            "https://user@houche.rainlodyn.cn/share/", "https://user:password@houche.rainlodyn.cn/share/",
            "https://@houche.rainlodyn.cn/share/", "https://houche.rainlodyn.cn@example.com/share/",
            "houche://user@line", "houche://user:password@line/", "houche://@line",
            "https://houche.rainlodyn.cn:80/share/", "https://houche.rainlodyn.cn:444/share/",
            "https://houche.rainlodyn.cn:0/share/", "https://houche.rainlodyn.cn:65536/share/",
            "https://houche.rainlodyn.cn:abc/share/", "houche://line:443", "houche://line:80/", "houche://line:0"
        )) {
            val link = "$base?type=line&$query"
            assertNull(link, VehicleShareLink.parse(link))
        }
    }

    @Test fun `没有查询和空查询无法生成线路目标`() {
        for (base in listOf(web, "https://houche.rainlodyn.cn/share", scheme, "$scheme/")) {
            for (suffix in listOf("", "?", "?&&", "?type=line", "?type=line&&")) {
                val link = "$base$suffix"
                assertNull(link, VehicleShareLink.parse(link))
            }
        }
    }

    @Test fun `片段不能补齐名称或改变类型且非法百分号片段仍拒绝`() {
        验证线路入口("city=0592&line=007&dir=1#name=7路", null)
        验证线路入口("$query#type=vehicle&city=010&dir=2", target)
        验证线路入口("$query#%GG", null)
        assertNull(VehicleShareLink.parse("$web?$query#type=line"))
    }

    @Test fun `畸形百分号在必填字段忽略字段或参数名中均拒绝且不抛异常`() {
        for (invalid in listOf("%", "%0", "%GG", "%2G", "%G2", "%u95FD", "%E9%97%")) {
            验证线路入口("city=0592&line=007&name=$invalid&dir=1", null)
            验证线路入口("$query&plate=$invalid", null)
            验证线路入口("$query&extra=$invalid", null)
            验证线路入口("$query&$invalid=value", null)
        }
    }

    @Test fun `非法截断过长或代理区编码即使在车牌未知字段或方向中也拒绝`() {
        for (invalid in listOf(
            "%FF", "%FE", "%80", "%C3", "%C3%28", "%E9%97",
            "%C0%AF", "%ED%A0%80", "%F4%90%80%80", "%F0%9F%9A"
        )) {
            验证线路入口("city=$invalid&line=007&name=7路&dir=1", null)
            验证线路入口("city=0592&line=$invalid&name=7路&dir=1", null)
            验证线路入口("city=0592&line=007&name=$invalid&dir=1", null)
            验证线路入口("city=0592&line=007&name=7路&dir=$invalid", null)
            验证线路入口("$query&plate=$invalid", null)
            验证线路入口("$query&extra=$invalid", null)
            验证线路入口("$query&$invalid=value", null)
        }
    }

    @Test fun `合法编码的替换字符可以成为线路名称`() {
        验证线路入口("city=0592&line=007&name=%EF%BF%BD&dir=1", target.copy(lineName = "\uFFFD"))
    }

    @Test fun `原始空格换行和畸形地址不会作为有效线路链接`() {
        for (invalid in listOf(
            "", " ", "不是网址", "https://[invalid", "https://",
            "$web?type=line&city=0592&name=7 路&dir=1",
            "$scheme?city=0592&name=7路\n支线&dir=1"
        )) {
            assertNull(invalid, VehicleShareLink.parse(invalid))
        }
    }

    private fun 验证线路入口(rawQuery: String, expected: ShareTarget.Line?) {
        for (link in listOf("$web?type=line&$rawQuery", "$scheme?$rawQuery", "$scheme?type=line&$rawQuery")) {
            assertEquals(link, expected, VehicleShareLink.parse(link))
        }
    }
}
