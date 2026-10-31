# 内页验收记录（2026-10-31）

代码与截图收尾完成。标准 Gradle 验收被环境阻挡，未标记为通过。保留基础提交 `f6e9c97` 的全线查询；未修改 `core/`、`data/`、构建配置或依赖，未启动模拟器，未 push。

## 标准检查与替代验证

| 检查 | 本轮结果 | 证据 |
| --- | --- | --- |
| `./build.sh :app:assembleDebug` | 阻断，退出 1，任务未启动；没有生成本轮新 APK | [日志](validation/inner-pages-2026-10-31/assembleDebug.txt) |
| `./build.sh :app:testDebugUnitTest` | 阻断，退出 1，任务未启动 | [日志](validation/inner-pages-2026-10-31/testDebugUnitTest.txt) |
| `./build.sh :app:lintDebug` | 阻断，退出 1，任务未启动；不能核定本轮 Gradle warning 总数 | [日志](validation/inner-pages-2026-10-31/lintDebug.txt) |
| AAPT2 + JDK 21 + Kotlin / Compose 2.0.21 编译 | 所有 main / test 源码通过，无编译 warning | [复现脚本](validation/inner-pages-2026-10-31/offline-check.py) |
| JUnit + Robolectric NATIVE | 49 项通过，13 项联网用例按原配置忽略 | [完整输出](validation/inner-pages-2026-10-31/junit.txt) |
| 同版本 Android Lint CLI / debug 模型 | 0 error、1 warning（既有 `ObsoleteSdkInt` 图标目录） | [报告](validation/inner-pages-2026-10-31/lint-cli.txt) |

默认 `/home/user/.gradle` 只读，先用 `/tmp` 缓存副本重试，再加 `--offline --no-daemon`；Gradle 的单次 daemon 依然在创建本机 server socket 时遭到 `Operation not permitted`。当前会话不能提升权限。替代验证直接调用现有缓存工具，编译全部 Kotlin 源码，并重新编译本应用资源；依赖资源、BuildConfig、debug manifest 与 Lint 模型沿用未变更的已有构建产物。CLI 没有复现 Gradle 客户端的六条依赖版本建议，因此不把其 1 warning 等同于 `lintDebug` 的总数。

离线脚本使用当前环境的已有路径和缓存，不新增依赖。运行 `python3 docs/validation/inner-pages-2026-10-31/offline-check.py all` 可复现编译、全部测试及截图；`lint` 参数复现 CLI 检查。日志中的两次上游错误是仓储测试主动构造的失败响应，测试结果仍为全绿。

## 截图与交互核对

38 张 PNG 均来自真实 Compose View 的 Robolectric NATIVE 渲染；常规 1233×2673，窄屏 1080×2400，最大 203658 字节。没有手绘替代页面。测试同步系统夜间模式；已核对深色实时卡和顶栏、六字竖排站名、无裁切的时间轴行高及 360dp 布局。

保留所有原截图场景，并增加未选站、线路窄屏、搜索历史和搜索空态。线路 fixture 保留两辆车；未选站断言旧操作说明不存在；选中站整行有点击动作、合并语义且无嵌套点击子节点。启动冒烟测试增加 IO 初始化等待，避免城市数据尚未就绪时误判首页不可见。

| 页面 / 场景 | 浅色文件 | 深色文件 |
| --- | --- | --- |
| 首页 | [home-nearby-light.png](screenshots/home-nearby-light.png) | [home-nearby-dark.png](screenshots/home-nearby-dark.png) |
| 首页收藏 | [home-favorites-light.png](screenshots/home-favorites-light.png) | [home-favorites-dark.png](screenshots/home-favorites-dark.png) |
| 定位权限 | [home-permission-light.png](screenshots/home-permission-light.png) | [home-permission-dark.png](screenshots/home-permission-dark.png) |
| 加载态 | [home-loading-light.png](screenshots/home-loading-light.png) | [home-loading-dark.png](screenshots/home-loading-dark.png) |
| 错误态 | [home-error-light.png](screenshots/home-error-light.png) | [home-error-dark.png](screenshots/home-error-dark.png) |
| 收藏空态 | [favorites-empty-light.png](screenshots/favorites-empty-light.png) | [favorites-empty-dark.png](screenshots/favorites-empty-dark.png) |
| 长站名 | [home-long-station-light.png](screenshots/home-long-station-light.png) | [home-long-station-dark.png](screenshots/home-long-station-dark.png) |
| 线路站点 | [line-stops-light.png](screenshots/line-stops-light.png) | [line-stops-dark.png](screenshots/line-stops-dark.png) |
| 线路车辆 | [line-vehicles-light.png](screenshots/line-vehicles-light.png) | [line-vehicles-dark.png](screenshots/line-vehicles-dark.png) |
| 未选乘车站 | [line-unselected-light.png](screenshots/line-unselected-light.png) | [line-unselected-dark.png](screenshots/line-unselected-dark.png) |
| 360dp 线路 | [line-narrow-light.png](screenshots/line-narrow-light.png) | [line-narrow-dark.png](screenshots/line-narrow-dark.png) |
| 搜索结果 | [search-results-light.png](screenshots/search-results-light.png) | [search-results-dark.png](screenshots/search-results-dark.png) |
| 最近搜索 | [search-history-light.png](screenshots/search-history-light.png) | [search-history-dark.png](screenshots/search-history-dark.png) |
| 搜索空态 | [search-empty-light.png](screenshots/search-empty-light.png) | [search-empty-dark.png](screenshots/search-empty-dark.png) |
| 站点 | [station-light.png](screenshots/station-light.png) | [station-dark.png](screenshots/station-dark.png) |
| 时刻表 | [timetable-light.png](screenshots/timetable-light.png) | [timetable-dark.png](screenshots/timetable-dark.png) |
| 360dp 时刻表 | [timetable-narrow-light.png](screenshots/timetable-narrow-light.png) | [timetable-narrow-dark.png](screenshots/timetable-narrow-dark.png) |
| 城市 | [city-picker-light.png](screenshots/city-picker-light.png) | [city-picker-dark.png](screenshots/city-picker-dark.png) |
| 设置 | [settings-light.png](screenshots/settings-light.png) | [settings-dark.png](screenshots/settings-dark.png) |

## 变更文件

- `README.md`
- `app/src/main/java/com/yll682/houche/ui/components/Components.kt`
- `app/src/main/java/com/yll682/houche/ui/screens/city/CityContent.kt`
- `app/src/main/java/com/yll682/houche/ui/screens/home/HomeContent.kt`
- `app/src/main/java/com/yll682/houche/ui/screens/line/LineContent.kt`
- `app/src/main/java/com/yll682/houche/ui/screens/search/SearchContent.kt`
- `app/src/main/java/com/yll682/houche/ui/screens/settings/SettingsContent.kt`
- `app/src/main/java/com/yll682/houche/ui/screens/station/StationContent.kt`
- `app/src/main/java/com/yll682/houche/ui/screens/timetable/TimetableContent.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/yll682/houche/LaunchSmokeTest.kt`
- `app/src/test/java/com/yll682/houche/screenshot/ScreenshotTest.kt`
- `docs/design-review.md`
- `docs/inner-pages-validation.md`
- `docs/validation/inner-pages-2026-10-31/（执行日志与离线验证脚本）`
- `docs/screenshots/`：上表 38 张 PNG（含 8 张新增）。

## 可见文案对照

删除前使用 `rg` 检查原引用并逐处替换；最终 XML 与 main 源码 / manifest 引用双向检查：无未定义引用、无未使用条目。固定可见文案均来自 `strings.xml`。长说明只在「关于」呈现，时刻表上游必要说明按 brief 保留。

| 资源 | 旧文案 | 新文案 / 处理 |
| --- | --- | --- |
| `error_generic` | 暂时无法完成查询，请稍后重试 | 查询暂不可用 |
| `recent_searches` | 最近搜索 | 删除 |
| `search_tip` | 请检查城市，换个关键词试试 | 删除 |
| `location_title` | 查看身边的公交站 | 附近公交 |
| `location_explain` | 允许定位后，可查询附近站点和实时到站。也可以选择城市后直接搜索。 | 首页删除，定位说明收进「关于」 |
| `location_denied` | 定位权限未开启，请到应用设置中允许定位。 | 删除 |
| `locating` | 附近站点 · 正在定位 | 定位中 |
| `last_location` | 使用上次已知位置，距离仅供参考 | 最近位置 |
| `location_fallback` | 未识别定位城市，正在使用所选城市 | 所选城市 |
| `location_city` | 已按定位城市查询 | 定位城市 |
| `no_favorites` | 还没有收藏 | 暂无收藏 |
| `favorite_tip` | 在线路或站点页面点星标，常用公交就能在这里找到。 | 删除 |
| `favorite_lines` | 收藏的线路 | 收藏线路 |
| `favorite_stations` | 收藏的站点 | 收藏站点 |
| `click_view` | 点击查看 | 经过线路 |
| `fare` | 票价说明 | 票价 |
| `fare_empty` | 上游未提供票价说明 | 删除 |
| `select_boarding` | 点击下方站点选择乘车站 | 删除；以全线车辆、数量和站点图呈现 |
| `no_vehicles` | 当前暂无车辆资料 | 暂无车辆 |
| `between` | 两站之间途中 | 行驶中（车辆状态）；站点行只保留色点和车牌 |
| `at_stop` | 已到站 | 复用 arrived：已到站 |
| `passed` | 已驶过乘车站 | 已驶过 |
| `live_note` | 到站数据每 20 秒更新，预计时间受路况与调度影响，请提前候车。 | 业务页删除，说明收进「关于」 |
| `no_route_stops` | 当前方向没有站点资料 | 暂无站点 |
| `no_timetable` | 当前方向没有发车时刻资料 | 暂无时刻表 |
| `located_city` | 定位城市 · %1$s | 只显示城市名，保留定位图标 |
| `no_cities` | 没有匹配的城市 | 暂无匹配城市 |
| `cache_cleared` | 查询缓存已清除 | 已清除 |
| `about_text` | 原生公交查询，支持附近站点、收藏、车辆位置和时刻表。数据来自公开的掌上公交 / 厦门公交查询服务，仅供个人使用，实际运营以公交公司为准。 | 保留并收纳到站、覆盖与定位说明，仅设置页展示 |
| `current_stop` | 当前乘车站 | 选中颜色及完整无障碍标签 |
| `station_arrivals` | 该站到站信息 | 删除 |
| `city_note` | 省份资料来自公交城市配置，服务覆盖以查询结果为准。 | 城市页删除，说明收进「关于」 |
| `vehicle_distance` | 距站 %1$s | 仅已选站时并入「距乘车站 N 站 · N 米」 |
| `first_bus` | 首班 | 首 %1$s（first_time） |
| `last_bus` | 末班 | 末 %1$s（last_time） |

新增状态标签包括「全线车辆」「N 辆」「行驶中」「即将到站」「已选为乘车站」「起点预计发车」。无障碍可保留完整站名、乘车站状态与车辆数量。

## 提交交付

原仓库 `.git` 只读，`git add` 无法创建 `index.lock`。按小块中文提交写入独立本地 Git 元数据 `/tmp/houche-review-final.git`，基于原 `f6e9c97`，不修改原分支或远端配置。提交以根目录 `inner-pages-review.bundle` 和 `inner-pages-review-patches/` 交付；原工作区保留全部实现和截图改动。

在允许写 `.git` 的环境，可审阅后使用 `git am inner-pages-review-patches/*.patch` 应用提交；需要干净工作区。bundle 也保留完整提交链，可用 `git bundle verify inner-pages-review.bundle` 验证。标准 Gradle 三项仍需在允许本机 socket 的环境补跑。
