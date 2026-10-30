# 数据层联网自检

最终配置复跑（2026-10-30 15:33 UTC）：**3 项通过、1 项失败**。厦门搜索、线路详情和附近站点均通过；北京搜索重试后仍超时。本文件同时保留首次全部通过与最终复跑失败的真实结果。

首次执行时间：2026-10-30 15:30 UTC。

实际命令：

```sh
./build.sh :app:testDebugUnitTest --tests '*LiveApiTest*' -Dlive=true
```

首次结果：`BUILD SUCCESSFUL in 29s`；JUnit XML 记录 `tests="4" skipped="0" failures="0" errors="0"`。没有临时移除 `@Ignore`；自定义 JUnit runner 根据传入测试 JVM 的 `live` 属性解除方法级忽略。普通单测默认跳过这 4 项。

| 城市 | 查询 | 真实返回 | 结论 |
| --- | --- | --- | --- |
| 厦门市 | 搜索 91路（快照线路/站点并行） | 15 条线路、0 个站点；91路，方向 1，高崎T4公交场站 → 前埔公交场站 | 通过 |
| 厦门市 | 91路线路详情，方向 1 | 32 站；线路编号 a2e913fc4967b27f1d72edd76cbcc831；首末班 06:30 / 22:00 | 通过 |
| 厦门市 | 附近站点，GCJ02 (24.48,118.13) | 4 个站；最近“实验幼儿园”，SM000170，365 m；3 条经过线路 | 通过 |
| 北京市 | 搜索 1路（CMD 114 / 110 并行） | 347 条线路、22 个站点；1路，方向 1，四惠枢纽站 → 老山公交场站 | 重试后通过 |

北京查询第一次请求出现 `HttpRequestTimeoutException`（`https://h5.mygolbs.com/ApiData.do`，15000 ms）。程序按配置自动重试 1 次，随后成功；该用例总耗时 21.689 秒。首次执行没有最终失败的用例；最终配置复跑的失败见下文。北京返回为关键词包含匹配，347 条不表示 347 条名称恰为“1路”的线路。

实际标准输出：

```text
联网自检：厦门市 附近 (24.48,118.13)：站点=4，首站=Station(name=实验幼儿园, number=SM000170, lat=24.48214279, lng=118.12726919, distance=365.0, coordinateSystem=GCJ02, platformNumbers=[SM000169, SM000170], lines=[LineSummary(name=3路, direction=2, from=第一码头, to=莲花五村, stationOrder=20), LineSummary(name=45路, direction=2, from=厦大（南普陀）, to=金尚公交场站, stationOrder=21), LineSummary(name=130路, direction=1, from=火车站南广场, to=长浩东路, stationOrder=8)], sameNum=null, platforms=[], linesError=null)
联网自检：厦门市 搜索 91路：线路=15，站点=0，样例=LineSummary(name=91路, direction=1, from=高崎T4公交场站, to=前埔公交场站, stationOrder=null)
联网自检：北京市 搜索 1路：线路=347，站点=22，样例=LineSummary(name=1路, direction=1, from=四惠枢纽站, to=老山公交场站, stationOrder=null)
联网自检：厦门市 线路详情：name=91路，id=a2e913fc4967b27f1d72edd76cbcc831，方向=1，站点=32，首末=06:30/22:00，起终=高崎T4公交场站/前埔公交场站，首站=RouteStation(name=高崎T4公交场站, order=1, id=00f3d537bb68ab189902a81666e23cc8, lat=24.54193506, lng=118.15230555)
```

签名期望值使用 `python3 -c` 从 `/data/repos/bus-query/bus_api_client.py` 导入两个函数现算。系统 Python 未安装 requests；计算时仅为未使用的 requests 导入放置空模块，签名函数原样执行，没有修改参考实现。

这次联网覆盖上述四个用例，不代表全部城市和全部接口可用。LocationManager 权限、硬件与 DataStore 持久化尚未进行 Android 真机测试。UI 本次未实现。

## 最终配置复跑

同一命令再次真实执行，JUnit XML：`tests="4" skipped="0" failures="1" errors="0"`，`BUILD FAILED in 41s`。该轮只修改了测试库加入 classpath 的方式，保留原依赖声明；业务代码与第一次相同。

| 城市 | 用例 | 实际结果 |
| --- | --- | --- |
| 厦门市 | 搜索 91路 | 通过；15 条线路、0 个站点 |
| 厦门市 | 91路详情 | 通过；32 站 |
| 厦门市 | 附近 (24.48,118.13) | 通过；4 个站 |
| 北京市 | 搜索 1路 | 失败；一次网络重试后仍然 15000 ms 超时，未返回完整的搜索结果 |

北京失败原因的原始异常：

```text
com.yll682.houche.core.BusException: 公交查询失败，请检查网络后重试
Caused by: io.ktor.client.plugins.HttpRequestTimeoutException:
Request timeout has expired [url=https://h5.mygolbs.com/ApiData.do, request_timeout=15000 ms]
```

该北京用例耗时 34.727 秒。线路和站点请求并行，日志没有标注超时的是 CMD 114 还是 CMD 110，不能进一步断言是哪条指令。首次查询成功、复跑超时，表明当前环境下 H5 请求存在间歇性超时；没有将失败改成跳过，也没有放宽规格规定的超时与重试次数。

本轮真实标准输出：

```text
联网自检：厦门市 附近 (24.48,118.13)：站点=4，首站=Station(name=实验幼儿园, number=SM000170, lat=24.48214279, lng=118.12726919, distance=365.0, coordinateSystem=GCJ02, platformNumbers=[SM000169, SM000170], lines=[LineSummary(name=3路, direction=2, from=第一码头, to=莲花五村, stationOrder=20), LineSummary(name=45路, direction=2, from=厦大（南普陀）, to=金尚公交场站, stationOrder=21), LineSummary(name=130路, direction=1, from=火车站南广场, to=长浩东路, stationOrder=8)], sameNum=null, platforms=[], linesError=null)
联网自检：厦门市 搜索 91路：线路=15，站点=0，样例=LineSummary(name=91路, direction=1, from=高崎T4公交场站, to=前埔公交场站, stationOrder=null)
联网自检：厦门市 线路详情：name=91路，id=a2e913fc4967b27f1d72edd76cbcc831，方向=1，站点=32，首末=06:30/22:00，起终=高崎T4公交场站/前埔公交场站，首站=RouteStation(name=高崎T4公交场站, order=1, id=00f3d537bb68ab189902a81666e23cc8, lat=24.54193506, lng=118.15230555)
```

## 编译、离线测试与自查

真实执行过 `./build.sh :app:compileDebugKotlin` 和 `./build.sh :app:testDebugUnitTest`。修正泛型推断与测试设备编号后通过：SigningTest 3 项、RepositoryTest 5 项通过，LiveApiTest 4 项默认跳过。另实际执行 `:app:assembleDebug` 打包成功。

按规格第 4 节核对：城市表 IO 单次解析、0592 唯一判定、两套签名及公共头、参数归一化/集合校验、8 个业务方法与字段映射、排序/降级文案、前三站并发与失败隔离、15 秒 TTL/512 条缓存、固定设备编号、定位双源与 15 秒兜底、境内判断与 GCJ02 转换、厦门 30 km 城市识别回退均已实现。核心层没有 Android import 或 GlobalScope。
