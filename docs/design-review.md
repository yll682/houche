# 可视化验收与设计复查

验收日期：2026-10-30。先运行真实 Compose 截图，逐张检查首轮 26 张浅/深色图片，修改后重新生成并检查最终 28 张。最终图片来自应用的 Content，而非设计稿或人工拼装界面。

## 文件与状态边界

`app/src/main/java/com/yll682/houche/ui/screens/` 下各目录结构：

- 各页面的 `XxxScreen.kt` 严格只做 `collectAsStateWithLifecycle` 和参数/回调转发，`XxxContent.kt` 是纯渲染。
- `home/HomeRoute.kt`：定位权限、恢复前台检查、轮询、可保存的首页分段状态。
- `line/LineRoute.kt`：面板/票价展开状态、系统分享、轮询。
- `station/StationRoute.kt`：站点实时轮询。
- `search/SearchRoute.kt`：初始焦点、软键盘显示与收起。
- `timetable/TimetableRoute.kt`：可保存的分组折叠状态。
- `city/CityRoute.kt`：Dialog、返回键、可保存的查询/省份状态。原城市接口接收列表而非 ViewModel，`CitySheet.kt` 仅转发 `CityData` 和回调，城市业务状态仍由上层 RootViewModel 提供。
- `settings/SettingsViewModel.kt`：新增，接管清缓存与异常、短时成功提示状态；`SettingsScreen.kt` 订阅转发，`SettingsContent.kt` 根据状态展示设置、底部 Snackbar 和错误重试。

Route 层隔离平台行为和本地交互适配，既保留原有行为，也让 Screen 本身只负责状态订阅。
七个 Content 不引用 ViewModel，不订阅 Flow，不申请权限、不访问仓库、不启动协程、不发起轮询、不调用系统分享。分段、票价、折叠组、城市查询的交互状态均从参数传入，所有行为通过 lambda 转发。`modifier` 作用于 Content 的根 Scaffold。设置版本信息也由状态传入。

## 复现

在仓库根执行：

```sh
./build.sh :app:testDebugUnitTest --tests '*ScreenshotTest*'
./build.sh :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
```

测试文件：`app/src/test/java/com/yll682/houche/screenshot/ScreenshotTest.kt`。使用 Robolectric 4.14.1、SDK 34、Native Graphics，设备限定为 `zh-rCN-w411dp-h891dp-xxhdpi`。浅/深色直接使用应用同一套颜色、Typography 和 Shapes，不依赖宿主系统主题；测试使用普通 Application，避免启动真实网络与定位。

每例使用 `createComposeRule()` 挂载真实 Content，等待 Compose 空闲，再通过 `captureToImage().asAndroidBitmap()` 编码 PNG。每张断言 **1233 × 2673** 像素、采样亮度方差 **> 100**、体积 **≤ 400KiB**。输出目录由 Gradle 传入绝对路径，且登记为测试输出；不依赖启动命令时的工作目录。单独需要强制重新生成可加 `--rerun-tasks`。

**绘制兼容说明**：首次直接使用 Compose 1.7 的内置 `captureToImage()` 时，26 例均在窗口强制重绘阶段超时。这是 [Robolectric 上游记录的问题 #8071](https://github.com/robolectric/robolectric/issues/8071)。因此测试内提供同名私有扩展，取得真实根 Compose View，在 UI 线程通过 Native Canvas 执行 `view.draw()`，返回 ImageBitmap 后仍按上述流程保存。没有使用空白图、人工绘图或预存图片兜底。这个兼容扩展仅用于根节点截屏；没有实现任意节点裁切或跨窗口拼接。城市页直接测试 CityContent，Dialog 的系统窗口装饰不在图中。

## 数据来源

真实样例来自 [live-check.md](live-check.md)：厦门实验幼儿园、SM000169/SM000170、365 米、3/45/130 路及对应起终点；91 路的高崎 T4 → 前埔、06:30–22:00 及 06:30、06:45、08:13、08:26 等计划时刻；北京 1 路四惠枢纽站 → 老山公交场站、05:00–23:00、票价 2–6 元、29 个完整真实站序；天安门东地铁站、44 米及地铁 1 号线八通线两个方向。

上游记录的车辆列表为空。截图中的车牌、两辆车的位置、分钟数、站点页到站演示、时刻表“当前时段/高亮班次”是固定演示状态，不表示此刻真实运营。线路页与北京站点使用同城数据，首页厦门样例不混入北京站点。测试完全离线。

## 发现与修改

| 看到的问题 | 已完成的修改 | 复查图片 |
| --- | --- | --- |
| 长地铁线路徽标占据半行，方向的“站”字单独掉行 | 超过 6 字的线路名独占一行，方向和实时信息使用下一行；方向最多两行并有省略保护 | 首页长站名，浅/深色 |
| 长站名与右侧距离标签贴近 | 站名列增加 12dp 尾间距；距离保持自然宽度 | 首页长站名，浅/深色 |
| 空收藏提示末尾单字掉行，下面还列出两条“0 项”标题 | 说明限宽 280dp 并居中；空分区不再显示标题 | 收藏空状态，浅/深色 |
| 未授权定位提示卡片比搜索框和分段控件窄 | 权限卡片内容填满宽度，与页面 16dp 外边距对齐 | 首页未授权，浅/深色 |
| 深色车辆状态点仍用固定深绿/深蓝，显得偏暗 | 到站使用主题 primary，在途使用 tertiary；明暗主题均提供适合的颜色，同时保留文字状态 | 站点、线路站点、首页附近，浅/深色 |
| 站点页线路卡片内边距 12dp，首页同类卡片为 16dp | 站点线路卡片统一 16dp 内边距 | 站点，浅/深色 |
| 距离标签使用 10dp/6dp 内边距，站点轨迹使用 10dp 段高 | 改为 12dp/8dp；轨迹段高改为 12dp，状态点改为 8dp；边框和轨迹线宽不属于布局间距 | 首页、站点、线路站点，浅/深色 |
| 搜索框浮动标签贴近顶部边界 | 顶栏增加 8dp 垂直留白 | 搜索结果，浅/深色 |

时刻表的四列班次、当前时段容器与选中班次前景色在两套主题下均可辨认，无需额外硬编码。设置关于文字自然换行；城市省份列表、已定位城市、错误卡片和重试按钮均可读。加载态同时保留刷新提示与骨架；错误态保留已有数据和重试；空收藏及未授权状态都有下一步操作。

## 图片索引与逐张复查

所有 PNG 位于 [screenshots/](screenshots/)。以下每一行均已检查浅色与深色：

| 场景 | 浅色 | 深色 | 最终复查 |
| --- | --- | --- | --- |
| 首页附近 | [查看](screenshots/首页附近-浅色.png) | [查看](screenshots/首页附近-深色.png) | 三条线路与到站信息对齐 |
| 首页收藏 | [查看](screenshots/首页收藏-浅色.png) | [查看](screenshots/首页收藏-深色.png) | 线路与站点分区明确 |
| 首页未授权 | [查看](screenshots/首页未授权-浅色.png) | [查看](screenshots/首页未授权-深色.png) | 卡片铺满，操作清楚 |
| 搜索结果 | [查看](screenshots/搜索结果-浅色.png) | [查看](screenshots/搜索结果-深色.png) | 标签留白、线路与站点结果正常 |
| 线路站点 | [查看](screenshots/线路站点-浅色.png) | [查看](screenshots/线路站点-深色.png) | 乘车站、车辆与站序可辨认 |
| 线路车辆 | [查看](screenshots/线路车辆-浅色.png) | [查看](screenshots/线路车辆-深色.png) | 车辆状态、距离和数据时间可读 |
| 站点 | [查看](screenshots/站点-浅色.png) | [查看](screenshots/站点-深色.png) | 站台选择与三条线路间距统一 |
| 时刻表 | [查看](screenshots/时刻表-浅色.png) | [查看](screenshots/时刻表-深色.png) | 四列时间、高亮班次与说明正常 |
| 城市选择 | [查看](screenshots/城市选择-浅色.png) | [查看](screenshots/城市选择-深色.png) | 搜索、定位城市、各省份可读 |
| 设置 | [查看](screenshots/设置-浅色.png) | [查看](screenshots/设置-深色.png) | 关于文案换行正常 |
| 首页加载 | [查看](screenshots/首页加载-浅色.png) | [查看](screenshots/首页加载-深色.png) | 刷新提示与骨架可辨认 |
| 首页错误 | [查看](screenshots/首页错误-浅色.png) | [查看](screenshots/首页错误-深色.png) | 错误重试与旧数据均保留 |
| 收藏空状态 | [查看](screenshots/收藏空状态-浅色.png) | [查看](screenshots/收藏空状态-深色.png) | 提示居中，无多余零项标题 |
| 首页长站名 | [查看](screenshots/首页长站名-浅色.png) | [查看](screenshots/首页长站名-深色.png) | 长线路名和方向完整展示 |

## 尚未覆盖

- 当前为 411 × 891dp、标准字体比例的首屏检查。超大字体、横屏、平板及厂商字体尚未截图验收。
- 测试截图覆盖 Content；系统权限弹窗、分享面板、软键盘、系统栏与真实 Dialog 装饰还需设备检查。Native Canvas 与设备 GPU 的阴影/抗锯齿可能存在差异。
- 长列表超出首屏的内容通过滚动查看，本轮没有生成全长拼接图；站点预览底部弹层也未加入图库。
- 方差断言能拦截空白或纯色，不能自动判断具体设计是否退化；这里没有将当前 PNG 当作像素级 golden 自动比较。
- 上游接口间歇性超时、无运营车辆及定位硬件行为仍以联网记录和设备验收为准，本次没有修改数据层或声称解决这些问题。

## 最终验证结果

最终代码实际执行 `./build.sh :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest`，结果 **BUILD SUCCESSFUL in 2m 45s**。JUnit XML：ScreenshotTest 28 项、RepositoryTest 5 项、SigningTest 3 项全部通过，失败/错误均为 0；LiveApiTest 13 项按现有默认配置跳过。没有通过移除测试或放宽方差/尺寸断言获得通过。

最终 28 张 PNG 全部为 1233 × 2673，最大 **229,817 字节（约 225KiB）**，总计约 4.1MiB，无需降采样。`git diff --check` 通过。implementation 依赖保持原样，仅增加指定测试依赖、Compose 测试 BOM 与测试资源配置。
