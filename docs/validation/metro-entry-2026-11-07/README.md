# 首页地铁入口修复验证

1.6.0 / vc9 的直接原因是 `HomeScreen` 漏传 `metro` 回调给 `HomeContent`。

`MainActivity → HomeRoute → HomeScreen` 的回调传递正常，但 `HomeScreen` 只传了
`settingsContent`，使 `HomeContent.metro` 使用默认值 `null`。即使首页
`HomeData.hasMetro == true`，`HomeHeader` 收到的回调仍为 `null`，因此不绘制地铁按钮。
设置页能显示站序来源与此现象完全一致。

## 原实现对照

新增 `ScreenshotTest.homeViewModelMetro_light`，使用真实 `cities.json`、
`metro-cities.json`、`metro.json`，通过与正式首页相同的 `PageEntry`、
`LocalViewModelStoreOwner` 和 `model(key = ...)` 创建两个 ViewModel，再绘制 `HomeScreen`。

用 Git HEAD 中的原版 `Metro`、`HomeViewModel`、`HomeScreen` 编译出对照类后，
运行同一个新测试：

- 等到首页 `hasMetro == true`，设置页 `metroSource` 非空。
- 两个 ViewModel 都不处于 `UiState.Error`，上述状态断言通过。
- 随后在地铁按钮的 `assertIsDisplayed()` 准确失败：
  `java.lang.AssertionError: Assert failed: The component is not displayed!`。

原实现截图：[刷新左侧没有地铁入口](home-metro-before-light.png)。
修复后截图：[地铁入口可见](../../screenshots/home-metro-viewmodel-light.png)。
修复后的同一测试同时断言图标可点击、位于刷新左侧，点击后以 `null` 线路参数调用导航。

## 修复范围

- 补齐 `HomeScreen → HomeContent` 的 `metro` 回调。
- 首页根据轻量城市索引判断入口；站表读取和在线请求失败不隐藏入口。
  索引首次失败后间隔 250ms 重试一次，回到前台或点击刷新时仍可重新查询。
  地铁索引失败仅记中文日志，tag 为 `Houche`。
- 读取后再合并当前首页状态，保留期间产生的定位权限、收藏及加载/错误状态。
  原先 `data.copy(hasMetro = 挂起调用)` 会在挂起之前取到旧的 `data`。
- `networkFor` 在一次互斥锁内完成索引匹配和站表读取，复用持锁读取方法。
  只有完整读取、解析及换乘补齐成功后才写缓存，失败可再次读取。
- `AppGraph` 允许注入同一份 `Metro`，便于对两个 ViewModel 注入读取故障。

原来的缓存也仅在成功后写入；未发现在线客户端能够改写 `Metro` 缓存的路径。
原首页的收藏订阅在地铁判断之后开始，订阅本身没有将 `hasMetro` 重置为 false。

## 回归结果

直接使用本机缓存的 Kotlin 2.0.21 编译器（含 Compose、序列化插件）、JUnit 和
Robolectric 执行，无新增依赖。资源来自真实 assets 和已有 Android 资源产物，
未运行 Gradle / build.sh，未访问在线地铁接口。

| 测试 | 数量 | 结果 |
| --- | ---: | --- |
| `MetroDataTest` | 16 | 全部通过 |
| `MetroLoaderTest` | 7 | 全部通过 |
| `MetroRemoteTest`（内存传输） | 18 | 全部通过 |
| `HomeMetroTest` | 6 | 全部通过 |
| `homeViewModelMetro_light` | 1 | 通过 |
| 既有 `homeMetro_light` | 1 | 通过 |
| 合计 | 49 | 零失败 |

新增回归覆盖索引/站表首次读取与解析失败后的恢复、连续调用一致性、30 个并发查询
只读取一次成功缓存、首页与设置资料一致、收藏更新保留入口、索引自动及手动重试、
站表失败不隐藏首页入口、在线等待/失败不影响共享实例上的首页，以及挂起期间的权限状态保留。

应用版本预备为 1.6.1 / vc10。本次未打包或发布；更新清单仍指向已经发布的版本。
