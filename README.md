# 候车

一个安卓公交查询应用。可以查线路、站点，看车还有多久到。

## 功能

- 附近站点、定位，按距离排序
- 线路：方向、首末班、站点图、全线车辆
- 车辆：实时位置与到站提醒
- 站点：经过线路与到站时间
- 地铁：线路与站点
- 时刻表、搜索、收藏、深浅色
- 应用内检查更新

## 截图

见 `docs/screenshots/`。

## 构建

需要 JDK 17+ 与 Android SDK 35。

```sh
./build.sh assembleDebug
./build.sh assembleRelease
./build.sh :app:testDebugUnitTest
```

产物：`app/build/outputs/apk/`。

## 说明

数据由上游服务提供，可用性与准确性取决于上游；实际运营情况以运营方为准。仅供个人使用。