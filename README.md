# 候车

原生 Android 公交查询客户端，使用 Kotlin、Jetpack Compose 和 Material 3，直接请求公开公交服务，不需要自建后端。支持系统深浅色模式，固定青绿色品牌主题。

## 功能与界面

| 页面 | 功能 |
| --- | --- |
| 首页 | 定位说明与用户主动授权、附近站点、距离、附近 / 收藏分段、收藏线路 / 站点、下拉重新定位、前三站前台每 20 秒更新到站 |
| 搜索 | 300ms 防抖、线路 / 站点分组、键盘立即查询、最近 10 条搜索及删除 / 清空 |
| 线路 | 方向切换、首末班与票价、收藏、文本分享、选乘车站、下一班、站点时间轴及车辆圆点、车辆列表、前台每 20 秒刷新 |
| 站点 | 同名 / 反向站台切换、经过线路及实时到站、收藏、下拉刷新、前台每 20 秒更新 |
| 时刻表 | 线路方向、首末班、上游下一班、时段折叠、四列时间网格、当前时段高亮及上游说明 |
| 城市选择 | 全屏窗口、已识别定位城市置顶、省 → 市、城市名 / 拼音检索与匹配高亮 |
| 设置 | 默认城市、清查询缓存、关于与版本 |

首次启动不自动申请定位；点击「允许定位」或「开启定位」后才请求，拒绝后提供应用设置入口。未授权也能选择城市、搜索公交。加载采用骨架占位，刷新错误保留已有内容并原位提供重试。

页面使用 `sealed interface Screen` 和 `SnapshotStateList` 手写返回栈，没有引入 navigation-compose 或其他新依赖。首页连续两秒内再次返回可退出。收藏、当前城市和搜索历史通过 DataStore 保存；页面切换使用淡入与上移 8dp、180ms 动画。

## 界面预览

深浅色各一套真实渲染截图（Robolectric 渲染，非设计稿），位于 `docs/screenshots/`。设计规则见 [设计说明](docs/design-review.md)。

版式对标厦门公交官方 App，参考截图（应用商店公开图）存于 `docs/reference/`。

| 首页 | 线路（站点） | 线路（车辆） | 站点 |
| --- | --- | --- | --- |
| [浅色](docs/screenshots/home-nearby-light.png) / [深色](docs/screenshots/home-nearby-dark.png) | [浅色](docs/screenshots/line-stops-light.png) / [深色](docs/screenshots/line-stops-dark.png) | [浅色](docs/screenshots/line-vehicles-light.png) / [深色](docs/screenshots/line-vehicles-dark.png) | [浅色](docs/screenshots/station-light.png) / [深色](docs/screenshots/station-dark.png) |

| 搜索 | 时刻表 | 城市选择 | 设置 |
| --- | --- | --- | --- |
| [浅色](docs/screenshots/search-results-light.png) / [深色](docs/screenshots/search-results-dark.png) | [浅色](docs/screenshots/timetable-light.png) / [深色](docs/screenshots/timetable-dark.png) | [浅色](docs/screenshots/city-picker-light.png) / [深色](docs/screenshots/city-picker-dark.png) | [浅色](docs/screenshots/settings-light.png) / [深色](docs/screenshots/settings-dark.png) |

另外还有加载态、错误态、未授权定位、收藏空态、长站名挤压，以及 360dp 窄屏时刻表等边界截图，见同一目录。

```sh
# 重新生成全部截图
./build.sh :app:testDebugUnitTest --tests '*ScreenshotTest*'
```

## 构建

需要 JDK 17+、Android SDK 35。本仓库 `build.sh` 使用当前环境的 JDK 21（编译目标 JVM 17）和 `/data/tools/android-sdk`；其他机器可调整该脚本的路径，或设置 `JAVA_HOME` / `ANDROID_HOME` 后调用 Gradle Wrapper。

```sh
./build.sh assembleDebug
./build.sh assembleRelease

# 离线测试（联网用例默认 @Ignore）
./build.sh :app:testDebugUnitTest

# 显式真实联网，不会把失败改为跳过
./build.sh :app:testDebugUnitTest --tests '*LiveApiTest*' -Dlive=true
```

产物：

- Debug：`app/build/outputs/apk/debug/app-debug.apk`
- Release：`app/build/outputs/apk/release/app-release.apk`（已启用 R8 与资源压缩）

本轮 Debug / Release 构建、离线测试、Lint 和 APK 核验结果见下方记录；真实联网输出与失败原因见 [docs/live-check.md](docs/live-check.md)。

实际验证（2026-10-30）：

- `./build.sh :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug` 已真实通过；离线单测 8 项通过，13 项联网用例默认跳过。
- 最终源码再次执行 `./build.sh :app:assembleDebug :app:assembleRelease :app:lintDebug --no-daemon`，`BUILD SUCCESSFUL in 2m 46s`；两个最终 APK 已重新核验。
- 本轮显式联网首轮 12 项中 11 通过、北京搜索 1 项超时失败；另两项北京坐标流程 / 附近站点补测通过，真实输出完整保留在联网记录中。
- `lintDebug` 无错误，剩余 8 条告警为固定依赖版本更新建议、已有图标目录及未用颜色资源；没有为消除提示升级依赖。
- 一次增量重打包出现 Gradle daemon 异常退出。降低 Gradle 堆内存、限制为两名 worker，并改用进程内 Kotlin 编译后，Debug / Release 重新打包通过。

实际执行 APK 核验命令（Debug 同样核对）：

```sh
/data/tools/android-sdk/build-tools/35.0.0/aapt2 dump badging app/build/outputs/apk/release/app-release.apk
/data/tools/android-sdk/build-tools/35.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

`aapt2` 输出关键字段：

```text
package: name='com.yll682.houche' versionCode='1' versionName='1.0.0' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
minSdkVersion:'26'
targetSdkVersion:'35'
```

Debug / Release 均为 `applicationId=com.yll682.houche`、`minSdk=26`、`targetSdk=35`、`versionName=1.0.0`、`versionCode=1`。Release 的 `apksigner verify` 返回成功，证书主题为 `CN=Houche, O=yll682, L=Xiamen, ST=Fujian, C=CN`。本机这份 Release 使用已配置的签名；密钥文件与密码未写入版本库。

## 签名

在仓库根创建 `keystore.properties`：

```properties
storeFile=/密钥目录/houche-release.jks
storePassword=自行填写
keyAlias=自行填写
keyPassword=自行填写
```

`keystore.properties`、`*.jks`、`*.keystore` 已忽略，密钥不入库。未配置发布密钥时，当前构建脚本使用 debug 密钥签署 Release，便于本地安装验证；正式发布前应配置自己的发布密钥并保持后续更新签名一致。

## 数据与实现说明

数据层沿用上一轮提交 `98d280e` 的 `core/` 与 `data/`，本轮没有重写或修改它们。Application 初始化手写 `AppGraph`，城市表在 IO 加载，固定设备编号保存在 DataStore，两个客户端共享 HttpClient。查询缓存 TTL 为 15 秒，最多 512 条。

定位采用 Android LocationManager，支持最近已知位置兜底和 WGS84 → GCJ02 转换。定位城市只识别厦门覆盖区（最近站点距离不超过 30 km），其他地区使用上次选择 / 厦门默认值，不具备全国逆地理编码能力。

时刻表优先使用上游分组；厦门未提供分组时按两小时时段展示。上游未提供下一班、车辆、分钟数或发车时间时显示相应空态，不推算或补造数据。搜索接口未返回线路数量的站点显示「点击查看」。北京 1 路本次返回 `hasReal=0` 和空时刻表；北京搜索有间歇性超时，详情见联网记录。

当前环境没有连接 Android 设备，尚未实测定位硬件、权限弹窗、TalkBack、下拉手势与深浅色实际显示；这里的构建 / 联网验证不等于真机验收。进程被系统杀死后重新从首页进入，收藏与设置仍持久保存。

### 模拟器真机渲染核对（2026-10-31）

本轮除 Robolectric 截图外，另在 Android 模拟器（Android 11 / x86_64，无 KVM 时用 `-accel off` 软件模拟）里安装 Debug 包逐页截图比对：授予定位权限并用 `adb emu geo fix 118.13 24.48` 注入厦门坐标后，首页取到真实附近站点（卧龙西路口 187 米、龙山桥 337 米）与到站站数，搜索 91 路得到 35 条线路，线路详情 32 站、首末班 06:30 / 22:00，时刻表出现真实班次分组。深色模式同屏核对通过。

软件模拟下冷启动约十余分钟，且会与 Gradle 抢占 CPU（曾导致测试子进程 120 秒连接超时，属环境资源问题，关闭模拟器后同一命令 26 秒通过）。真机仍待你实测。

## 数据免责声明

接口来自公开的掌上公交 / 厦门公交查询服务，仅供个人使用。服务覆盖、接口可用性和数据准确性由上游决定；到站时间会受路况与调度影响，实际运营以公交运营方为准。
