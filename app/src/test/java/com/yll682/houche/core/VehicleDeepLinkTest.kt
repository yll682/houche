package com.yll682.houche.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class 车辆深链接测试 {
    private val bases = listOf("https://houche.rainlodyn.cn/share/", "houche://vehicle")
    private val query = "city=0592&line=007&name=7%E8%B7%AF&dir=1&plate=%E9%97%BDD12345"
    private val target = VehicleShareTarget("0592", "007", "7路", "1", "闽D12345")

    @Test fun `分享域名路径和协议固定`() {
        assertEquals("houche.rainlodyn.cn", VehicleShareLink.HOST)
        assertEquals("/share/", VehicleShareLink.PATH)
        assertEquals("houche", VehicleShareLink.SCHEME)
    }

    @Test fun `网页链接按固定顺序输出精确中文百分号编码`() {
        assertEquals(
            "https://houche.rainlodyn.cn/share/?city=0592&line=007&name=7%E8%B7%AF&dir=1&plate=%E9%97%BDD12345",
            VehicleShareLink.buildWebLink("0592", "007", "7路", "1", "闽D12345")
        )
    }

    @Test fun `应用链接按相同顺序输出精确中文百分号编码`() {
        assertEquals(
            "houche://vehicle?city=0592&line=007&name=7%E8%B7%AF&dir=1&plate=%E9%97%BDD12345",
            VehicleShareLink.buildSchemeLink("0592", "007", "7路", "1", "闽D12345")
        )
    }

    @Test fun `线路标识为空仍输出线路键并保留其它空参数`() {
        val expectedQuery = "city=0592&line=&name=&dir=&plate=%E9%97%BDD12345"
        assertEquals(
            "${bases[0]}?$expectedQuery",
            VehicleShareLink.buildWebLink("0592", null, "", "", "闽D12345")
        )
        assertEquals(
            "${bases[1]}?$expectedQuery",
            VehicleShareLink.buildSchemeLink("0592", null, "", "", "闽D12345")
        )
        验证双协议(expectedQuery, target.copy(lineId = null, lineName = "", direction = ""))
    }

    @Test fun `空格用加号而字面加号和其它分隔符精确转义`() {
        val expectedQuery = "city=0592&line=A%2BB&name=7%E8%B7%AF+%2B%26%3D%25%3F%23" +
            "&dir=2&plate=%E9%97%BDD+%2B%26%3D%25%3F%23"
        assertEquals(
            "${bases[0]}?$expectedQuery",
            VehicleShareLink.buildWebLink("0592", "A+B", "7路 +&=%?#", "2", "闽D +&=%?#")
        )
        assertEquals(
            "${bases[1]}?$expectedQuery",
            VehicleShareLink.buildSchemeLink("0592", "A+B", "7路 +&=%?#", "2", "闽D +&=%?#")
        )
    }

    @Test fun `双协议解析中文编码并保留城市和线路前导零`() {
        验证双协议(query, target)
    }

    @Test fun `网页分享路径允许省略末尾斜杠`() {
        assertEquals(target, VehicleShareLink.parse("https://houche.rainlodyn.cn/share?$query"))
    }

    @Test fun `应用链接允许空路径和单个斜杠`() {
        assertEquals(target, VehicleShareLink.parse("houche://vehicle?$query"))
        assertEquals(target, VehicleShareLink.parse("houche://vehicle/?$query"))
    }

    @Test fun `仅一和二两个方向原样有效`() {
        for (direction in listOf("1", "2")) {
            验证双协议(
                "city=0592&line=007&name=7路&dir=$direction&plate=闽D12345",
                target.copy(direction = direction)
            )
        }
    }

    @Test fun `没有查询和空查询均不生成目标`() {
        for (base in bases + listOf("https://houche.rainlodyn.cn/share", "houche://vehicle/")) {
            assertNull(base, VehicleShareLink.parse(base))
            assertNull("$base?", VehicleShareLink.parse("$base?"))
            assertNull("$base?&&", VehicleShareLink.parse("$base?&&"))
        }
    }

    @Test fun `缺少城市或车牌均拒绝`() {
        for (missing in listOf(
            "plate=闽D12345",
            "city=0592",
            "line=007&name=7路&dir=1",
            "City=0592&plate=闽D12345",
            "city=0592&Plate=闽D12345"
        )) {
            验证双协议(missing, null)
        }
    }

    @Test fun `城市或车牌为空及各种空白均拒绝`() {
        for (blank in listOf("", "+", "%20", "%09", "%0A", "%0D%0A", "%E3%80%80")) {
            验证双协议("city=$blank&plate=闽D12345", null)
            验证双协议("city=0592&plate=$blank", null)
        }
        验证双协议("city&plate=闽D12345", null)
        验证双协议("city=0592&plate", null)
    }

    @Test fun `未缺失但包含首尾空格的标识原样保留`() {
        验证双协议(
            "city=+0592+&line=+007+&name=+7路+&dir=1&plate=+闽D12345+",
            VehicleShareTarget(" 0592 ", " 007 ", " 7路 ", "1", " 闽D12345 ")
        )
    }

    @Test fun `缺少线路标识解析为空值`() {
        验证双协议(
            "city=0592&name=7路&dir=1&plate=闽D12345",
            target.copy(lineId = null)
        )
    }

    @Test fun `空线路标识和空白线路标识解析为空值`() {
        for (blank in listOf("", "+", "%09", "%E3%80%80")) {
            验证双协议(
                "city=0592&line=$blank&name=7路&dir=1&plate=闽D12345",
                target.copy(lineId = null)
            )
        }
    }

    @Test fun `缺少线路名或线路名为空时使用空串`() {
        for (name in listOf("", "&name=", "&name")) {
            验证双协议(
                "city=0592&line=007&dir=1&plate=闽D12345$name",
                target.copy(lineName = "")
            )
        }
    }

    @Test fun `方向缺失或非法时保留空串而不猜方向`() {
        val expected = target.copy(direction = "")
        验证双协议("city=0592&line=007&name=7路&plate=闽D12345", expected)
        for (direction in listOf("", "0", "3", "-1", "01", "2.0", "null", "上行", "下行", "+1", "2+", "%EF%BC%91")) {
            验证双协议(
                "city=0592&line=007&name=7路&dir=$direction&plate=闽D12345",
                expected
            )
        }
        验证双协议("city=0592&line=007&name=7路&dir&plate=闽D12345", expected)
    }

    @Test fun `线路名标识和其它参数均不能代替缺失方向`() {
        验证双协议(
            "city=0592&line=1&name=上行1路&direction=1&upDown=2&DIR=1&plate=闽D12345",
            VehicleShareTarget("0592", "1", "上行1路", "", "闽D12345")
        )
    }

    @Test fun `只有必填参数时其它字段按契约留空`() {
        验证双协议("city=0592&plate=闽D12345", VehicleShareTarget("0592", null, "", "", "闽D12345"))
    }

    @Test fun `可以直接解析未转义的中文参数`() {
        验证双协议(
            "city=厦门&line=线路甲&name=公交7路&dir=2&plate=闽D12345",
            VehicleShareTarget("厦门", "线路甲", "公交7路", "2", "闽D12345")
        )
    }

    @Test fun `所有文本字段的中文特殊符号和补充平面字符均可往返`() {
        val expected = VehicleShareTarget(
            cityCode = " 厦门+&=%?# 城 ",
            lineId = "线路 +&=%?#🚌",
            lineName = "公交 +&=%?# 空格 🚌",
            direction = "2",
            plate = " 闽D +&=%?#🚌 "
        )
        val links = listOf(
            VehicleShareLink.buildWebLink(
                expected.cityCode, expected.lineId, expected.lineName, expected.direction, expected.plate
            ),
            VehicleShareLink.buildSchemeLink(
                expected.cityCode, expected.lineId, expected.lineName, expected.direction, expected.plate
            )
        )
        for (link in links) assertEquals(link, expected, VehicleShareLink.parse(link))
    }

    @Test fun `加号百分号加号和百分号空格各按表单规则解析`() {
        for ((encoded, decoded) in listOf("+" to " ", "%20" to " ", "%2B" to "+", "%2b" to "+")) {
            验证双协议(
                "city=0592&line=007&name=$encoded&dir=1&plate=闽D12345",
                target.copy(lineName = decoded)
            )
        }
    }

    @Test fun `百分号只解码一次且编码分隔符不产生新参数`() {
        验证双协议(
            "city=0592&line=007&name=%252B%2526%253D%2525%253F%2523&dir=1&plate=闽D12345",
            target.copy(lineName = "%2B%26%3D%25%3F%23")
        )
        验证双协议(
            "city=0592&name=%26plate%3D闽D12345&dir=1",
            null
        )
    }

    @Test fun `参数顺序可变且参数名同样只解码一次`() {
        验证双协议(
            "pl%61te=闽D12345&d%69r=1&n%61me=7路&l%69ne=007&c%69ty=0592",
            target
        )
        验证双协议("c%2569ty=0592&plate=闽D12345", null)
    }

    @Test fun `原始等号只在第一个位置分隔键值`() {
        验证双协议(
            "city=0592&line=007&name=A=B=C&dir=1&plate=闽D12345",
            target.copy(lineName = "A=B=C")
        )
    }

    @Test fun `未知参数和重复未知参数可忽略`() {
        验证双协议("$query&extra=甲&extra=乙&unrelated&", target)
    }

    @Test fun `每个已知参数重复时无论值是否相同都拒绝`() {
        for ((key, sameValue) in listOf(
            "city" to "0592",
            "line" to "007",
            "name" to "7路",
            "dir" to "1",
            "plate" to "闽D12345"
        )) {
            验证双协议("$query&$key=$sameValue", null)
            验证双协议("$query&$key=其它值", null)
            验证双协议("$query&$key=", null)
        }
    }

    @Test fun `转义参数名不能绕过重复检查`() {
        for (encodedKey in listOf("c%69ty", "l%69ne", "n%61me", "d%69r", "pl%61te")) {
            验证双协议("$query&$encodedKey=另一个值", null)
        }
    }

    @Test fun `协议和主机大小写遵循网址语义`() {
        assertEquals(target, VehicleShareLink.parse("HTTPS://HOUCHE.RAINLODYN.CN/share/?$query"))
        assertEquals(target, VehicleShareLink.parse("HOUCHE://VEHICLE?$query"))
    }

    @Test fun `网页显式默认端口可以解析`() {
        assertEquals(target, VehicleShareLink.parse("https://houche.rainlodyn.cn:443/share/?$query"))
        assertEquals(target, VehicleShareLink.parse("https://houche.rainlodyn.cn:443/share?$query"))
    }

    @Test fun `未知主机协议和路径均拒绝`() {
        for (base in listOf(
            "http://houche.rainlodyn.cn/share/",
            "ftp://houche.rainlodyn.cn/share/",
            "https://example.com/share/",
            "https://houche.rainlodyn.cn.example.com/share/",
            "https://vehicle/share/",
            "https://houche.rainlodyn.cn/",
            "https://houche.rainlodyn.cn/Share/",
            "https://houche.rainlodyn.cn/share//",
            "https://houche.rainlodyn.cn/share/extra",
            "https://houche.rainlodyn.cn/share/../",
            "https://houche.rainlodyn.cn/%73hare/",
            "https://houche.rainlodyn.cn/share%2F",
            "houche://other",
            "houche://other/vehicle",
            "houche://vehicle/extra",
            "houche://vehicle//",
            "houche://vehicle/%2F",
            "houche:///vehicle",
            "houche:vehicle",
            "houche:/vehicle",
            "//houche.rainlodyn.cn/share/",
            "/share/"
        )) {
            val link = "$base?$query"
            assertNull(link, VehicleShareLink.parse(link))
        }
    }

    @Test fun `包含用户信息的链接即使主机正确也拒绝`() {
        for (base in listOf(
            "https://user@houche.rainlodyn.cn/share/",
            "https://user:password@houche.rainlodyn.cn/share/",
            "https://@houche.rainlodyn.cn/share/",
            "https://houche.rainlodyn.cn@example.com/share/",
            "houche://user@vehicle",
            "houche://user:password@vehicle/",
            "houche://@vehicle"
        )) {
            val link = "$base?$query"
            assertNull(link, VehicleShareLink.parse(link))
        }
    }

    @Test fun `非默认端口和应用协议任何显式端口均拒绝`() {
        for (base in listOf(
            "https://houche.rainlodyn.cn:80/share/",
            "https://houche.rainlodyn.cn:444/share/",
            "https://houche.rainlodyn.cn:0/share/",
            "https://houche.rainlodyn.cn:65536/share/",
            "https://houche.rainlodyn.cn:abc/share/",
            "houche://vehicle:443",
            "houche://vehicle:80/",
            "houche://vehicle:0"
        )) {
            val link = "$base?$query"
            assertNull(link, VehicleShareLink.parse(link))
        }
    }

    @Test fun `畸形百分号出现在键值或未知参数中均返回空而不抛异常`() {
        for (invalid in listOf("%", "%0", "%GG", "%2G", "%G2", "%u95FD", "%E9%97%")) {
            验证双协议("city=0592&plate=$invalid", null)
            验证双协议("$query&$invalid=value", null)
            验证双协议("$query&extra=$invalid", null)
        }
    }

    @Test fun `非法或截断的编码字节不能被替换字符悄悄接受`() {
        for (invalid in listOf(
            "%FF", "%FE", "%80", "%C3", "%C3%28", "%E9%97",
            "%C0%AF", "%ED%A0%80", "%F4%90%80%80", "%F0%9F%9A"
        )) {
            验证双协议("city=0592&plate=$invalid", null)
            验证双协议("$query&extra=$invalid", null)
            验证双协议("$query&$invalid=value", null)
        }
    }

    @Test fun `合法编码的替换字符本身仍可作为文本`() {
        验证双协议(
            "city=0592&line=007&name=%EF%BF%BD&dir=1&plate=闽D12345",
            target.copy(lineName = "\uFFFD")
        )
    }

    @Test fun `片段不补齐查询参数且不改变已完整目标`() {
        for (base in bases) {
            assertNull(VehicleShareLink.parse("$base?city=0592#plate=闽D12345"))
            assertEquals(target, VehicleShareLink.parse("$base?$query#ignored"))
            assertNull(VehicleShareLink.parse("$base?$query#%GG"))
        }
    }

    @Test fun `空输入和畸形网址均返回空而不抛异常`() {
        for (invalid in listOf(
            "", " ", "不是网址", "https://[invalid", "https://",
            "https://houche.rainlodyn.cn/share/?city=0592&plate=闽 D",
            "houche://vehicle?city=0592&plate=闽D\n12345"
        )) {
            assertNull(invalid, VehicleShareLink.parse(invalid))
        }
    }

    private fun 验证双协议(rawQuery: String, expected: VehicleShareTarget?) {
        for (base in bases) {
            val link = "$base?$rawQuery"
            assertEquals(link, expected, VehicleShareLink.parse(link))
        }
    }
}
