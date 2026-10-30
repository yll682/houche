# 候车 Android 客户端 · 实现规格

本文件是唯一施工图。实现前必须完整阅读，并同时阅读参考实现：

- `/data/repos/bus-query/bus_api_client.py` —— 上游两个客户端的签名与请求细节（唯一权威）
- `/data/repos/bus-query/website/service.py` —— 业务聚合、字段映射、排序规则（唯一权威）
- `/data/repos/bus-query/web/src/App.vue` —— 旧的交互逻辑参考（**视觉不要照抄**，只参考流程）
- `/data/repos/bus-query/手机界面参考与网站布局.md` —— 掌上公交真机的信息组织方式

参考实现是 Python，本项目要在 Kotlin 里**等价复刻**其数据获取与语义。凡是参考实现里出现的字段名、默认值、排序 key、降级文案，都要一一对应。

---

## 1. 产品目标

把 `bus-query` 的「自建 Python 服务端 + PWA」方案，改成**原生 Android App**：

- 无需任何后端服务器：签名算法内置在 App 内，直接请求上游接口。
- 冷启动到可交互 < 1.5 秒（首屏不阻塞在网络请求上）。
- 视觉与交互全部重做，Material 3 风格，支持深色模式。

非目标（本期不做）：地图瓦片、换乘查询、账号体系、到站提醒推送、iOS。

---

## 2. 技术约束

- Kotlin 2.0.21 + Jetpack Compose（BOM 2024.12.01）+ Material 3，`minSdk 26 / targetSdk 35`。
- 依赖已在 `app/build.gradle.kts` 固定：Ktor Client（OkHttp 引擎）、kotlinx.serialization、DataStore Preferences、material-icons-extended。**不要新增依赖**；确实需要时先说明理由。
- 定位只允许用 `android.location.LocationManager`（GPS + NETWORK 双源）。**禁止引入 Google Play Services**（目标机型是国产 Android，可能没有 GMS）。
- 不要引入 Hilt/Koin/Retrofit/Room。手写轻量 DI（`AppGraph` 单例）+ 内存缓存。
- 所有网络请求走同一 `HttpClient`，带 15 秒超时、失败重试 1 次（仅幂等 GET/POST 查询）。
- 代码注释、UI 文案、commit message 一律中文。

---

## 3. 目录与分层

```
app/src/main/java/com/yll682/houche/
├── MainActivity.kt            // 单 Activity + Compose NavHost
├── HoucheApp.kt               // Application，持有 AppGraph
├── core/                      // 纯 Kotlin，禁止 import android.*（除 core/platform）
│   ├── Cities.kt              // 城市表加载与检索
│   ├── Signing.kt             // 两套签名算法
│   ├── XmbusClient.kt         // 厦门客户端
│   ├── ZsgjClient.kt          // 掌上公交 H5 客户端
│   ├── Models.kt              // 领域模型（@Serializable）
│   ├── BusRepository.kt       // 业务聚合 + 15 秒 TTL 缓存
│   └── Errors.kt              // BusException：网络/上游/参数三类
├── data/                      // 持久化
│   ├── Prefs.kt               // DataStore：当前城市、收藏、最近搜索
│   └── LocationProvider.kt    // LocationManager 封装
└── ui/
    ├── theme/                 // Color/Type/Shape/Theme
    ├── components/            // 复用组件
    └── screens/               // home / search / line / station / timetable / city / settings
```

`core/` 必须能在纯 JVM 单元测试里跑（不含 Android 类），这样 `app/src/test` 可以直连真实上游做联网自检。

---

## 4. 数据层规格（最重要）

### 4.1 城市表

`app/src/main/assets/cities.json` 已生成，575 条，字段：`key`（字符串数字，从 "0" 递增）、`name`、`code`（城市编码，如 `0592`；公开城市为空串）、`province`、`pinyin`。

- 启动时在 IO 线程解析一次，缓存到内存（用 `kotlinx.serialization`，不要用 Gson）。
- 判定「走厦门接口」的唯一条件：`code == "0592"`。
- 城市检索：名称包含、拼音前缀/包含（大小写不敏感），无 province 的城市归入「其他」分组。
- 默认城市：优先上次选择；否则按定位逆推（见 4.6）；否则「厦门市」。

### 4.2 参数归一化（两个客户端共用）

```
value == null || value == "null" → ""
把 "<" 替换为 "＜"，">" 替换为 "＞"
其余 → toString()
```

### 4.3 厦门客户端（XmbusClient）

`deviceId`：首次启动生成 32 位十六进制（UUID 去横线），存 DataStore，之后固定不变。`token` 恒为空串。

**normal_sign(params, deviceId, timestamp, token="", version="3.2.2")**

```
salt = "e0hxlyguergtjpr6z7t1fck2mogvgcu9"
source = "{salt}&app-version={version}&client-key=xmbus-app-01&client-id=1&client-type=android"
       + "&device-id={deviceId}&timestamp={timestamp}&token={token}&ticket-log=&{salt}"
source += 按 key 升序，对每个参数拼 "&{key}={value}"
sign = Base64( MD5(source).hexUpperCase() 的 ASCII 字节 )
```

**v2_sign(path, params, timestamp)**

```
固定顺序 order：
  lineBus         → lineName, upDown, carNo
  stationLineBus  → stationNo, stationName, stationLon, stationLat, lineNum
  transferBus / transferDetailBus → stationNames, lineNames, upDowns, stationIndexs
source = "xmrbi202305" + 按 order 依次拼 "&{value}" + "&{timestamp}"
first  = MD5(source).hexUpperCase()
sign   = MD5("{first}&5F4E66A9FA0F11ED80C20CDA411D78A6").hexUpperCase()
```

**请求**

- 普通接口（`NORMAL_PATHS` 集合，见 py 文件）：
  `POST https://app.xmbus.com/xmbus_web_app/bus/pub/routeQuery/{path}?{query}`，用 normal_sign，期望 `code == 0`
- v2 接口（`V2_ORDER` 的四个 path）：
  `POST https://app.xmbus.com/xmbus/api/bus/v2/{path}?{query}`，用 v2_sign，期望 `code == 200`
- 公共请求头（逐字照抄 py）：
  `client-id: 1`、`client-type: android`、`client-key: xmbus-app-01`、`device-id`、`timestamp`（毫秒）、`token:`（空）、`appId: xmrbi202305`、`app-version: 3.2.2`、`appVersion: Android-v3.2.2`、`clientId:`（空）、`device-model: Android`、`area-code:`（空）、`version: v0.1`、`ticket-log:`（空）、`sign`
- 参数放 query string（不是 body）。响应体 `{"success":bool,"code":int,"data":...}`；`success == false` 或 code 不符 → 抛 BusException(上游拒绝)。

### 4.4 掌上公交 H5 客户端（ZsgjClient）

`POST https://h5.mygolbs.com/ApiData.do`，form 编码（`application/x-www-form-urlencoded`），字段 `CMD` + 各参数。
请求头：`User-Agent: Mozilla/5.0`、`X-Requested-With: XMLHttpRequest`、`Accept: application/json, text/javascript, */*; q=0.01`、`Origin: https://h5.mygolbs.com`、`Referer: https://h5.mygolbs.com/`。
响应 `status` 必须为 `1`（数字或字符串），否则抛上游错误。

指令表（`PUBLIC_COMMANDS`，参数集合需校验，缺少或多传都算参数错误）：

| CMD | 必填参数 | 用途 |
|---|---|---|
| 101 | — | 城市列表（本项目不用） |
| 102 | CITYNAME, KEYWORD | 线路+站点混合搜索（本项目不用） |
| 103 | CITYNAME, LINENAME, DIRECTION | 线路详情 |
| 104 | CITYNAME, LINENAME, DIRECTION, STATIONORDER | 实时到站/车辆 |
| 106 | CITYNAME, LAT, LNG | 附近站点 |
| 110 | CITYNAME, KEYWORD | 站点搜索 |
| 114 | CITYNAME, KEYWORD | 线路搜索 |
| 115 | CITYNAME, STATIONNAME, MYLAT, MYLNG, ALL | 站点经过线路 |
| 207 | CITYNAME, ROUTEID, DIRECTION | 发车时刻表 |
| 209 | CITYNAME, STATIONNAME, MYLAT, MYLNG, LAT, LNG | 同名站台列表 |

快照接口（厦门搜索用）：`POST https://snapshot.mygolbs.com/snapshot/searchLinesOrStation`，
JSON body `{cityName, keyword, searchType: "searchLine"|"searchStation", serverTime: 152382832342}`，
请求头 `token: zsgjxljc`，期望 `status == 200`。

### 4.5 业务聚合（与 service.py 一一对应）

实现这些方法，签名用 Kotlin 惯用写法但语义等价：

1. `nearby(city, lat, lng): List<Station>`
   - 厦门：`nearestExactStation {lat,lng,num:"8"}` → `name/stationNo/gcjLat/gcjLng/distance/relativeStationNoList/lineList`，
     line → `name=lineName, direction=upperOrDownInLine, from=(dir==1? suStation: sdStation), to=(dir==1? euStation: edStation), stationOrder=stationOrderInLine`
   - 其他城市：`CMD 106` → `name/lat/lon/dis`（`dis < 0` 视为 null）+ `sameNum`；**对前 3 个站**并发拉 `CMD 115` 填充线路（`name=lineName, direction=upperOrDown, from, to, stationOrder`），单个失败不影响其余
   - 结果按 distance 升序，null 排最后
2. `search(city, keyword): {lines, stations}` —— 两条并行：
   - 厦门：snapshot `searchType=searchLine|searchStation` → `lineName/direction/from/to`；`stationName`
   - 其他：`CMD 114` 取 `buslines`、`CMD 110` 取 `busstations`
3. `stationDetail(city, name, number, lat, lng)` 
   - 厦门：有 number → `exactStationDetail {stationNo:number, reverseStationNo:"", amapStationId:""}`；否则 `stationDetail {busStationName:name}`
   - 其他：`CMD 209` 取 `info[]`，按与 (lat,lng) 的欧氏距离取最近台（无坐标则取第一个）；再 `CMD 115` 填线路，其余台放进 `platforms`
4. `stationBuses(city, station, lat, lng)` 
   - 厦门：必须要有 number（没有则抛 422 语义：「这个站点尚未取得实时车辆接口」）→ `stationLineBus {stationNo, stationName, stationLon, stationLat, lineNum:"20"}`，
     `waiting = carNo 为空 || nextTime 非空`，`remainingStations/distance` 在 waiting 时置 null；`timeText` 从 `CMD 115` 的 `neardis` 按 `(lineName, upDown)` 取
   - 其他：`CMD 115` → `name=lineName, direction=upDown, to, statusText=neartext, timeText=neardis, stationOrder`
   - 排序：`arrival_key = (waiting, distance==null, distance ?: +inf, remainingStations ?: +inf)`
5. `lineDetail(city, name, direction)` 
   - 厦门：`lineDetail {busLineName:name}` → 站点取自 `stationList` 中 `upperOrDown == direction` 的项，`from/to/first/last` 用 `s{u|d}Station` / `s{u|d}Time` 拼接字段，票价取 `comments`
   - 其他：`CMD 103` → `data[{stationName, stationOrder, station_lat, station_lon}]`、`routeName/routeId/upperOrDown/firstLast/commonts/showDepart`
   - `showTimetable`：厦门视为 true（有 timeTable 接口），其他取 `showDepart == 1`
6. `vehicles(city, line, station)` —— 厦门用 `busList {busLineId, direction, stationSeq, stationId, stationName}`（`order = stationOrder + (inOrOut=="1" ? 1 : 0)`）；其他用 `CMD 104`，`statusType 0=at-stop / 2=between`，附 `arrivals/hasReal/runState/nextDeparture`
   - 排序 key：`(order==null||unknown ? 2 : order>stationOrder ? 1 : 0, 距离差, 是否途中, segmentDistance)`
   - `hasReal == 0` → 「这条线路未开通实时数据查询。」；`runState == 1` → 「这条线路已停运。」
7. `arrivals(city, lineName, direction, stationOrder)` —— `CMD 104` 的 `routeOnStationRTimeInfoList`，按 arrival_key 排序
8. `timetable(city, line, direction)` —— 其他城市 `CMD 207`（`list[].times[]`，过滤 `isDelete == 1`）；厦门 `timeTable {busLineId, busLineName}` 取 `upPlan/downPlan`

缓存：key 为方法名 + 全部入参，TTL 15 秒，最多 512 条（近似 LRU 即可）。实时类接口（vehicles/arrivals/stationBuses）可以在 UI 层 20 秒轮询，但缓存 TTL 仍为 15 秒。

### 4.6 定位

`LocationProvider`：优先 `FUSED` 不存在 → 用 `GPS_PROVIDER` + `NETWORK_PROVIDER` 中最后已知位置，再请求一次性更新（`requestSingleUpdate` 在 API 30+ 移除，需用 `requestLocationUpdates` + `LocationListener` + 超时 15 秒兜底）。

坐标是 WGS84，上游厦门接口用 GCJ02。**必须实现 WGS84 → GCJ02 转换**（`coordtransform.wgs84togcj02`，参考 `/data/repos/bus-query/web/src/coordtransform.d.ts` 与 npm 包算法；把算法直接写成 Kotlin，含 out-of-China 判断）。

城市识别：用定位到的 GCJ02 坐标请求「厦门市」的 `nearestExactStation`；若无结果或距离 > 30 km，则回退到上次选择/默认城市，并在 UI 上提示「已按定位城市查询」。

---

## 5. 界面与交互规格（全部重做）

设计基调：**克制、信息密度高、拇指可达**。参考国内公交类 App 的信息组织，但视觉走 Material 3 现代风格，不要拟物、不要渐变泛滥、不要圆角+阴影堆叠。

### 5.1 设计令牌

- 主色 `#0A7A62`（深青绿，取自现有品牌色 `#087d63` 的加深版），onPrimary `#FFFFFF`
- 浅色背景 `#F6F8F7`，卡片 `#FFFFFF`，描边 `#E3E9E7`，主文字 `#12211D`，次文字 `#5A6B66`
- 深色背景 `#0E1512`，卡片 `#16201C`，主色 `#4ED8B4`，onPrimary `#00201A`
- 语义色：到站/即将到 `#12805C`（绿）、站间行驶 `#2563C9`（蓝）、提醒 `#C2410C`（橙红）
- 圆角：卡片 20dp、按钮 999dp（胶囊）、列表项 16dp
- 字体：系统默认（`FontFamily.Default`），数字用 `FontFeature` 等宽（`"tnum"`），到达分钟数 28sp/Medium
- 间距栅格 4dp，页面水平内边距 16dp

### 5.2 首页（`HomeScreen`）

自上而下：

1. **顶部栏**：左侧「候车」小标 + 城市 chip（点击打开城市选择）；右侧设置图标（进入设置：默认城市、清缓存、关于、版本）。滚动时顶栏背景由透明渐变为 surface。
2. **搜索条**：胶囊形，占满宽度，左侧搜索图标，占位「搜线路 / 站点」。点击进入搜索页（不做内联下拉，避免键盘遮挡）。
3. **定位与刷新条**：一行小字「附近站点 · 已定位」+ 右侧刷新按钮 + 「切换城市」文本按钮（定位失败时替换为「开启定位」按钮）。
4. **分段控件**：`附近站点` / `收藏`（Material 3 SegmentedButton）。
5. **列表**：
   - 附近站点行：站名（16sp Medium）+ 距离 chip（如 `120 m` / `1.2 km`）+ 台数标记（有同名站台显示 `2 个站台`）；下方 2–3 条线路行，每条为「`91路` → 开往 XX」+ 右侧到站摘要（如 `3 站 / 4 分钟`，绿点表示已到站、蓝点表示途中、灰点表示未发车/等待发车）。
   - 前 3 个站自动拉实时到站并每 20 秒刷新；其余站显示「点击查看」。
   - 收藏页：收藏的线路 / 收藏的站点两组，空态有插画式空提示 + 「去搜索」按钮。
6. **下拉刷新**（`PullToRefreshBox`）：重新定位 + 刷新附近站点。

交互细节：
- 首次进入若无定位权限，列表位置显示定位说明卡片 + 「允许定位」按钮，决不自动弹系统弹窗（用户点击后再请求）。
- 加载中用 skeleton 占位（灰色圆角块微光动画），不要用居中转圈。
- 任何失败都在原位显示错误卡片 + 「重试」，不要把整页清空。

### 5.3 搜索页（`SearchScreen`）

- 进入即聚焦输入框并弹键盘；顶部返回箭头 + 输入框 + 「取消」。
- 输入防抖 300ms，自动并行查线路与站点；结果分两组 `线路` / `站点`，组标题带数量。
- 空输入时展示「最近搜索」（最多 10 条，可单条删除、可清空）。
- 线路行：左侧线路号徽章（圆角方，主色描边），主文字「XX路 开往 终点」，副文字「起点站 → 终点站」。点击进线路页。
- 站点行：站名 + 「N 条线路」，点击进站点页。
- 键盘回车立即查询；结果为空显示「没有找到相关线路或站点」+ 提示换关键词。

### 5.4 线路页（`LineScreen`）

- 顶部：返回 + 收藏星标 + 分享（分享为纯文本，调 `Intent.ACTION_SEND`）。
- **线路头卡**：大号线路号徽章（如 `91`），右侧「开往 终点站」；第二行 `首班 06:00 · 末班 22:30`；第三行可展开的票价说明（`ⓘ`）；右上角方向切换按钮（图标 + 「反向」），切换时重新请求。
- **下一班卡片**：若已选乘车站，显示大号「4 分钟 / 3 站」+「高峰约 5 分钟一班」之类状态文字；未选站时显示「点击下方站点选择乘车站」。
- **站点时间轴**：竖排列表，左侧序号+连接线；每站显示站名，已过站次要弱化；车辆用彩色圆点定位在对应站（绿=已到站、蓝=途中，附车牌与「两站之间」说明）；点击站点 → 底部 `ModalBottomSheet`：该站到站信息 + 「设为乘车站」。
- **车辆列表**：时间轴上方一个双段切换「站点 / 车辆」，车辆模式列出全部车辆：车牌、状态（已到某站 / 两站之间途中）、距乘车站站数与距离、数据时间。
- **时刻表入口**：若 `showTimetable`，头部右上角「时刻表」按钮 → 时刻表页。
- 自动刷新 20 秒（仅在前台且已选乘车站时）。

### 5.5 站点页（`StationScreen`）

- 顶部返回 + 收藏 + 站名标题 + 距离。
- 若有同名/反向站台：横向 chip 组切换站台，切换后重查。
- 线路列表：每行线路号徽章 + 开往方向 + 右侧到站（`remainNum` 站 / 分钟 / 等待发车 `07:20`）；点击进线路页并带上该站为乘车站。
- 20 秒自动刷新 + 下拉刷新。

### 5.6 时刻表页（`TimetableScreen`）

- 头卡：线路号 + 方向 + 「首班/末班」+ 下一班时间。
- 分组折叠列表（`Anytime` 时段标题如「06:00-08:00」），每组内 4 列时间网格；当前时段高亮。
- 底部灰色说明文字（来自上游 tips）。

### 5.7 城市选择（`CitySheet`，全屏 dialog）

- 顶部搜索框 + 定位城市行（置顶，带定位图标）。
- 按省分组（`LazyColumn` + sticky header），省市两级：先省列表，点省进城市列表。
- 搜索按名称与拼音过滤，结果高亮匹配片段。
- 选择后立即返回首页并刷新附近站点。

### 5.8 通用

- 系统返回键：二级页面返回上一级，首页再按一次提示「再按一次退出」（Toast/Snackbar）。
- 深色模式跟随系统；`dynamicColor` 关闭（保证品牌色一致）。
- 边缘到边缘（`enableEdgeToEdge`），状态栏图标随主题反色。
- 无障碍：所有可点击元素 contentDescription 非空，触控区 ≥ 48dp。
- 动效：页面切换 `AnimatedContent` 淡入+上移 8dp，180ms；列表项 `animateItem()`；**不要**用花哨转场。

---

## 6. 必须提供的自检

1. `app/src/test/java/.../SigningTest.kt`：用参考实现给出的样例校验两种签名（可从 py 文件里取一组固定输入与期望输出，若需现算，运行 `python3 -c` 生成后写入测试）。
2. `app/src/test/java/.../LiveApiTest.kt`：联网自检，默认 `@Ignore`，通过 `-Dlive=true` 启用。至少覆盖：
   - 厦门市：`search("厦门市","91路")` 非空；`lineDetail` 站点数 > 5；`nearby(24.48,118.13)` 非空
   - 北京市：`search("北京市","1路")` 非空
3. `./gradlew :app:assembleDebug` 必须零警告级错误通过；`./gradlew :app:testDebugUnitTest` 通过。
4. 生成 `README.md`：功能说明、截图占位、如何构建（`ANDROID_HOME`、`./gradlew assembleRelease`）、签名说明、免责声明（数据来自公开的公交查询接口，仅供个人使用）。

---

## 7. 代码质量要求

- 每个 `ViewModel` 暴露单一 `UiState`（sealed interface：`Loading / Success / Empty / Error`），UI 只做渲染。
- 网络与 JSON 解析全部在 `Dispatchers.IO`；Compose 侧用 `collectAsStateWithLifecycle`。
- 不用 `GlobalScope`；协程绑定 `viewModelScope`。
- 上游 JSON 解析：用 `JsonElement` 逐字段安全读取（上游字段类型不稳定，如 `stationOrder` 有时是字符串），**不要**写死强类型 data class 去硬映射整个响应体。
- 所有对用户可见的字符串放 `res/values/strings.xml`（中文默认）。
- 不允许吞异常：捕获后包装成 `BusException`，日志用 `android.util.Log` 打 tag `Houche`。