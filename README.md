# 候车

原生 Android 公交查询项目。目前完成数据层，界面仍为占位页。

支持厦门公交和掌上公交 H5 查询：附近站点、线路/站点搜索、站点详情、站点车辆、线路详情、车辆位置、到站信息、发车时刻表。包含城市检索、15 秒内存缓存、收藏与搜索记录持久化、LocationManager 定位及 WGS84→GCJ02 转换。

截图：待界面实现后补充。

## 构建

需要 JDK 17+、Android SDK 35，并设置 `ANDROID_HOME`。本机可使用固定工具链入口：

```sh
./build.sh :app:compileDebugKotlin
./build.sh :app:testDebugUnitTest
./build.sh :app:testDebugUnitTest --tests '*LiveApiTest*' -Dlive=true
```

通用环境使用 `./gradlew assembleRelease`。在 `keystore.properties` 配置发布密钥；未配置时使用 debug 签名。测试复用 Gradle 8.11.1 发行包自带的 JUnit/Hamcrest，不新增下载依赖。

## 数据层接入

- `Cities { context.assets.open("cities.json") }`：首次调用在 IO 解析并缓存城市表。
- `Prefs(context).deviceId()`：首次生成并固定的 32 位十六进制设备编号。
- 使用该编号构造 `XmbusClient` 和 `BusRepository`；两个客户端默认共享 `BusNetwork.client`。
- UI 取得权限后调用 `LocationProvider.locate()`；结果已转换 GCJ02。超时优先返回最后已知位置，并标记 `fromLastKnown`。
- `resolveCity` 仅根据厦门附近站点和 30 km 阈值识别厦门；其他地区返回上次选择/厦门默认值。`Cities.defaultCity` 可按上次选择、识别结果、厦门的顺序选择初始城市。
- `Station.linesError` 保存附近站点补线路的局部失败，便于 UI 提示。其余请求失败抛出带中文文案和类别的 `BusException`。协程取消直接传播。
- 签名在本地生成，普通接口使用 MD5 大写十六进制后 Base64，v2 使用两轮 MD5；固定输入通过 Python 权威实现校验。

联网自检真实结果见 [docs/live-check.md](docs/live-check.md)。定位权限/硬件与 DataStore 的设备集成仍需 Android 真机验证。

数据来自公开的公交查询接口，仅供个人使用。服务覆盖、运营与到站资料以公交运营方实际情况为准。
