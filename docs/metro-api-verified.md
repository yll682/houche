# 厦门地铁网关实测记录（国内出口，已验证）

日期：2026-11-06
方法：用 CNB 云原生构建机（出口 IP 均为**中国广东广州电信**，如 42.193.175.155 / 175.178.88.37 / 111.230.93.5 / 45.40.228.119）实跑 curl，
结果由构建脚本提交回仓库 `cnb.cool/yll682/metroprobe` 的 `result` 分支读取。
沙箱本身是境外出口，对该域名一律 TCP 超时——**用户实测的「境外不可达、境内可用」得到确认**。

## 一、可达性（已推翻旧结论）

| 目标 | 境外（沙箱） | 境内（CNB 构建机） |
| --- | --- | --- |
| `app.i-amtr.com` | TCP 超时 | **HTTP 200，响应 0.1~0.2s** |
| `app.i-amtr.com/` 根路径 | — | 200，正文 `Coming soon ......` |
| `app.i-amtr.com/amtr-info/`、`/amtr-service/`、`/amtr-trip/` | — | 200，均为 uni-app 打包的 H5，资源可下载 |

旧报告里「域名已下线」的说法**不成立**，只是境外被墙。

## 二、网关强制校验的请求头（按服务端报错顺序逐项探明）

对 `POST/GET https://app.i-amtr.com/gateway/api/iets-oms-service/pub/...`：

| 缺什么 | 服务端回 |
| --- | --- |
| `sign` | `{"code":10004,"msg":"sign缺失"}` |
| `app-id` | `{"code":10004,"msg":"app-id缺失"}` |
| `version` | `{"code":10004,"msg":"version缺失"}` |
| app-id 不是已配置的 | `{"code":10012,"msg":"app-key未配置"}` |

关键结论：
- `sign` 在**请求头**里，不是 query 参数（query 里放 sign 无效，仍报 sign缺失）。
- `sign` 只校验「存在」，**内容不校验到这一步**（传 16 个 0 也能过，直接跳到下一项报错）。
- **`app-id: oms` 是唯一被网关认可的取值**。枚举 30 个候选（oms/app/public/h5/web/WX_METRO/AMTR/xmmetro/APP00001/…）只有 `oms` 不报"app-key未配置"。
- `version` 任意值都通过（1.0 / 2.0 / 3.0 / 4.0.9 / 0.0.1 等价）。

## 三、签名算法（已还原并通过网关校验）

来源：`amtr-trip` 的 H5 源码 `https://app.i-amtr.com/amtr-trip/assets/index-b4bb7423.js`（公开前端资源，1.1 MB 未混淆的业务段可读）。

```js
async function KO(e) {                       // WT.trip 分支
  const t = Date.now();
  const n = Object.fromEntries(Object.entries(e).sort(([a],[b]) => a.localeCompare(b)));
  const originStr = `oms3lbg5pns82ym9at76jvzuxd4r1wfoq0h${t}1.0${n && "{}" != JSON.stringify(n) ? JSON.stringify(n) : ""}`;
  const sign = md5(originStr);               // 小写 hex
  const token = storage.getSync("token");
  return { "app-id": "oms", version: "1.0", timestamp: t, sign, token };
}
```

即：**`sign = md5("oms3lbg5pns82ym9at76jvzuxd4r1wfoq0h" + timestamp + "1.0" + 排序后的 JSON body)`**

实测：带上该 sign + `app-id: oms` + `version: 1.0` 后，网关**接受签名**，错误推进到下一层：

```
{"code":999,"msg":"未认证或者token已过期"}
```

## 四、卡点：还必须登录 token

签名过了之后，**全部 30 条 `pub/*` 路径一律返回 `999 未认证或者token已过期`**，包括逻辑上登录前必须能调的：

`pub/operate/appInit3`、`pub/operate/getAppInit`、`pub/init/getSystemContent`、
`pub/operate/privacyInit`、`pub/auth/queryChannelInfo`、`pub/login/oauth`、
`pub/unifiedLogin/loginSpecialUnified`、`pub/login/loginLimitApplyInfo`

试过 token 取空串 / 不传 / `null` / `undefined` / `0` / `guest`，全部 999。

H5 侧印证了这一点：`amtr-trip` 的 token 来自原生桥 `window.xmrbi.getToken()` / `xmrbi.auth()`——
即 **token 由壳（App）注入，浏览器里根本拿不到**。地铁站点数据 `allStation` 走的是
`POST https://app.i-amtr.com/iets-web-app/pub/station/allStation`，那条通道用的是另一套
（`client-key: WX_METRO` + `clientSecret` + `base64(upper(md5(原串)))`），实测恒回
`{"code":10001,"msg":"非法请求，验签失败"}`；把 `client-key` 换成 `oms` 会变成 `illegal request`
（说明 key 被识别、摘要还差一层），但**该层同样在原生桥环境下才成立**。

**结论：这条接口无法匿名使用。** 它不是"缺个签名"，而是"缺一个登录态"。候车做的是无后端客户端，
把账号密码塞进 App 去登录厦门地铁，既越界也不可靠，不做。

## 五、对候选接口路径的实测更正

旧报告里 51 条 `pub/*` 是**从加固 APK 字符串池推断**的，本次在国内实跑，行为一致（都被 999 挡住），
但以下是**确认存在**的（能走到鉴权层而非 404）：

`pub/station/getAllStations`、`pub/station/allStation`、`pub/station/findHotStations`、
`pub/station/searchAll`、`pub/station/getNearestStation`、`pub/transTime/getTransTimes`、
`pub/route/queryRouteInfo`、`pub/operate/appInit3`、`pub/operate/getAppInit`、
`pub/operate/getNewestVersion`、`pub/operate/getAboutUs`、`pub/operate/getConfigContent`、
`pub/operate/privacyInit`、`pub/operate/marks`、`pub/operate/moreIndexRecommend`、
`pub/operate/moreServices`、`pub/operate/queryHomeBizLayout`、`pub/init/getSystemContent`、
`pub/init/describe`、`pub/service`、`pub/auth/queryChannelInfo`、`pub/fee/queryFee`、
`pub/fee/queryPreference`、`pub/lost/queryList`、`pub/img/station`、
`pub/operate/queryMetroNotice`、`pub/operate/queryMetroNews`、`pub/operate/queryMetroPublicity`、
`pub/operate/floatWindows`、`pub/operate/getCancleReason`。

## 六、备用数据源排查（全部不可用）

| 源 | 结果 |
| --- | --- |
| 高德地铁图 `map.amap.com/service/subway?...srhdata=350200_drw_xiamen.json` | 502 Bad Gateway（带 Referer 同样 502） |
| `map.amap.com/subway/data/350200_drw_xiamen.json` | 404 |
| `ditu.amap.com/service/subway?srhdata=...` | `{"status":"8","data":"404 Not found!"}` |
| `www.xmgdjt.com.cn/api/line/list` | 401 |

## 七、对候车的影响

地铁线路/站序/换乘/首末班继续用**内置 `assets/metro.json`**（1/2/3 号线，24+32+21 站，已随 1.4.0 发布）。
网关那条路留着不写进 App：一个必然返回 999 的请求没有价值，只会让界面多一个假错误。

如果将来运营方开放匿名态接口，或者用户愿意提供登录态抓包，
按第三节的签名算法接进去即可（`app-id=oms`、`version=1.0`、`sign=md5(盐+ts+"1.0"+json)`，
盐是 `oms3lbg5pns82ym9at76jvzuxd4r1wfoq0h`）。