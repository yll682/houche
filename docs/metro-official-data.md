# 厦门地铁官方数据提取与接口协议

日期：2026-11-06 / 2026-11-07
对象：厦门地铁官方 App（`com.luqiao.xmmetro` 4.0.9 / vc89，正版，应用宝下载）

## 结论速览

| 项目 | 结果 |
| --- | --- |
| 线网数据来源 | **官方在线站表接口优先**；`assets/metro.json` 保存同接口抓取的内置兜底快照 |
| 线路 | 1/2/3/4/6 号线，合计 102 站（24 + 32 + 21 + 8 + 17），另有 BRT 45 站不作地铁展示 |
| 线路色 | 1 号线 `#DA7028`、2 号线 `#52A441`、3 号线 `#52B5C8`、4 号线 `#E50012`，取自官方线路图；6 号线 `#92308D` 取自 `bg_route_line6` |
| 加固 | 蛮犀（`com.mx.shell` + `libmxldd.so` / `libvmd.so`） |
| 脱壳 | **成功**：MX 容器两层变换全解，四份 DEX 原始 SHA-1 / Adler-32 全部吻合，恢复 19,054 个方法体 |
| 网关签名 | **已还原**（见第 4 节） |
| 线上验证 | **已在境内网络实测通过**：福建厦门机器匿名请求成功，空 token 被接受 |
| 架构 | 每次进页先启动在线请求，内置快照供等待时展示；在线成功整份替换并标「在线」，失败静默保留内置 |

## 1. 数据来源：官方站表与线路图

当前内置资源与在线数据均取自 `POST https://app.i-amtr.com/iets-web-app/pub/station/allStation`。
境内实测返回 147 个站点：METRO 102 个，BRT 45 个；客户端只把 `vehicle=METRO` 分组成地铁线路。
二号线接口有 32 站，六号线有 17 站，均完整保留，不受旧线路图的节点数量限制。
`showFlag=false` 的四、六号线仍可查看站序，并使用「未运营」短标签和弱化样式。
逐站首末班及经纬度均来自接口；缺失时间留空，不从旧快照补入在线站表。

以下保留线路图提取的历史证据，用于核对线路色与解释旧数据的差异，不再作为站表主来源。

APK 里带 6 份线路图 SVG（资源名被混淆成 `Xlu.svg` 这类随机短码，原始名在 `resources.arsc` 里）：

| 混淆名 | 原始资源名 |
| --- | --- |
| `Xlu.svg` | `xm_20260609_1.svg`（最新） |
| `3nk.svg` | `xm_20260603_1.svg` |
| `1yV.svg` | `xm_20250401.svg` |
| `B6q.svg` | `xm_20250114.svg` |
| `kZs.svg` | `xm_20230717.svg` |
| `NYX.svg` | `xm_20220901.svg` |

SVG 元素 ID 是结构化的，站序不用靠几何推断：

```xml
<text id="text01010000" transform="matrix(1 0 0 1 1239.9674 3573.0112)">镇海路</text>
<circle id="stad01010000" fill="#FFFFFF" stroke="#DA7028" stroke-width="6" cx="1197.6" cy="3562" r="20" />
<path id="line01020000-01010000" fill="#DA7028" d="..."/>
```

- `text{线路2位}{站号2位}0000` → 中文站名；`eng{...}` → 英文名
- `stad{线路}{站号}0000` → 站点圆点
- `line{起站码}-{终站码}` → 相邻区间连线，`fill` 即线路色

站点圆点三种形态，含义不同：

| 形态 | 含义 |
| --- | --- |
| `r=20`，`stroke` = 线路色，`stroke-width=6` | 普通运营站 |
| `r=30.3`，无 `stroke` | 换乘站（多线共用的大圆） |
| `r=13`，`stroke=#9D9C9D`，`stroke-width=4` | 待开通节点（图中灰色无名） |

有几站（如 2 号线 `02100000`）画的是 `<path>` 而非 `<circle>`，没有 `r` 属性，
所以判定必须按**描边颜色**而不是半径。

提取脚本：[`analysis/extract_svg.py`](../../analysis/extract_svg.py)（纯标准库）。
独立复核：Codex 从同一批 SVG 另行提取，两边**逐站一致、颜色一致**。

### 1.1 历史线路图提取口径

- 灰色节点 = 待开通，不计入运营站序（4 号线「翔安机场」因此被排除）
- 换乘只取图中共用坐标的站点，以及图上标注的火车站 / BRT 换乘
- 首末班时间线路图里没有，置空，不用外部资料填
- 图中没有 6 号线站序，只能恢复 UI 颜色 `#92308D`（`res/values/colors.xml#bg_route_line6`）。
  当前六号线的 17 个站点由官方站表接口提供，不由颜色或外部资料推测。

### 1.2 历史线路图与早期数据的差异

此前 App 里的地铁资料来自中文维基百科，与官方线网图有实质出入：

| 项 | 旧（维基） | 新（官方图） |
| --- | --- | --- |
| 线路数 | 3 | **4**（含 4 号线试乘段） |
| 1 号线颜色 | `#E56849` | `#DA7028` |
| 2 号线颜色 | `#4DAA50` | `#52A441` |
| 3 号线颜色 | `#00BFD3` | `#52B5C8` |
| 2 号线站数 | 32 | 30（另 2 个是图中灰色无名节点） |
| 3 号线独有站 | 无「人才中心」「华荣路」「小东山」「湖里创新园」 | 有 |
| 2 号线独有站 | 无「两岸金融中心」「湿地公园」「钟宅」 | 有 |

## 2. 加固分析：蛮犀（com.mx.shell）

APK 内 30 MB 的 `classes.dex` 头部只报 3 个类、`string_ids_size=104`，
但数据段完整保留了原应用的中文字符串池（乘车码、一卡通补缴码、一网通免密支付…）。
真实 DEX 并不在这个文件里，而在 `assets/android.msf`（230,112 字节，熵 7.999 bit/byte）
与 DEX 内部的 MX 容器中。

### 2.1 MX 容器结构

占位 DEX 的 `map_list` 之后、文件偏移 `0x1440` 处是 `mx 01 02` 容器，
余长 30,610,480 字节。容器头部给出四个分块的偏移与长度，
MX 头字段给出了**全局密钥**的位置（`0x20` → 字符串池偏移 `0xb0`；`0x78` → 池内偏移 `0x77`；`0x7a` → 长度 16）：

```text
48 18 e7 51 73 05 b0 ac 30 2f 87 89 44 38 f3 23
```

### 2.2 两层变换

壳有两次缓冲区变换，**只做第一层是不够的**：

```python
S = RC4_KSA_256(key16)               # 仅 KSA，后面没有 PRGA
for j in range(len(buffer) // 256):
    q = 256*j + (j & 255)
    k = S[j & 255]
    buffer[q] = ((buffer[q] + k) & 255) ^ k
buffer[:2048] = SM4_ECB_DECRYPT(buffer[:2048], bytes(S[:16]))  # 无 padding
```

前三块先各用分块 key 做这次变换，再用全局 key 做第二次；
第四块先 LZMA 解压（properties 在负载末尾 `5d00000001`，lc=3/lp=0/pb=2，字典 16 MiB），再用全局 key。

第二层完成后，四份文件**原本携带的 SHA-1 与 Adler-32 同时正确**——
这是「解对了」而非「看起来对」的判据。

产出：`analysis/work/mx/layer2_classes1..4.dex`（无损受保护 DEX）。

### 2.3 按需方法体恢复

MX 头 `0x3c` 指向 `0x1bccd40` 的额外压缩块，解压得 `method-section.bin`（2,550,904 字节），
头部 `magic 0x40404040`，方法数 **19,054**，编号基值 `0x40000128`，记录表每项 24 字节。
按 `id - base` 查记录，用全局 key 的 KSA 表循环 XOR 恢复指令字节，
并对每处断言 `insns_size` 与原 `code_item` 完全相等，不重新编造寄存器数或 try 区。

四份 DEX 分别恢复 5,148 / 8,419 / 5,379 / 108 个方法。
JADX 1.5.1 成功加载全部 29,015 个 `class_defs`（此前会因 MUTF-8 与索引错误加载失败）。

脚本：[`analysis/work/mx/scripts/recover_mx_complete.py`](../../analysis/work/mx/scripts/recover_mx_complete.py)，
只用 Python 标准库与 OpenSSL，不执行 Android / JNI / 模拟器。

### 2.4 仍未解决

- `assets/android.msf` 未解密（14,382 个 16 字节块，174 组重复，支持 ECB 类确定性分块加密的猜测，
  但未证明是 AES / SM4；11,242 个候选密钥测试均未命中）
- `Vmc.i*` 虚拟化方法（如 `Application.onCreate`）仍是桩
- JADX 全量反编译仍有 23 个错误

## 3. 原生代码里找到的线路资料接口

从还原后的 DEX 里提取出 **203 条真实 HTTP 注解路径**（`annotations_directory_item` →
方法注解 → Retrofit 注解 → `method_ids` 关联，不是字符串池猜测），
其中 55 条 `pub/`、137 条 `pri/`、11 条公交 `app/pri/`。

完整清单：`analysis/work/mx/evidence/native-api-inventory.json`（含 method_idx、注解偏移、
原生类与方法、wrapper、实际 URL）与 `native-api-inventory.md`。

与地铁线路最相关的：

```text
pub/station/allStation          pub/station/findHotStations
pub/station/getNearestStation   pub/station/searchAll
pub/station/facilityInside      pub/station/facilityOutside
pub/brt/station/facilityOutside pub/img/routeMapWithBrt
pub/img/station                 pub/transTime/getTransTimes
pub/route/queryRouteInfo        pub/operate/appInit3
pub/operate/getAppInit          pub/init/getSystemContent
pub/fee/queryFee                pub/fee/queryPreference
pub/login/oauth                 pub/auth/queryChannelInfo
```

## 4. 签名算法（原生生产版本）

请求方法固定为 **POST**，完整地址为 `https://app.i-amtr.com/iets-web-app/pub/station/allStation`。
站表查询没有 query 参数，也不发送 `requestData`。

地铁站表走 `/iets-web-app/`，由 `HeaderParamInterceptor` 处理
（`analysis/work/mx/restored_jadx/sources/com/luqiao/xmmetro/base/api/HeaderParamInterceptor.java`）：

```text
S = QLJ1aZjhhTEm6RiN
K = AMTR-IETS-0002

origin = S&app-version=V&client-key=K&client-id=1&client-type=android&device-id=D&timestamp=T&token=TOKEN&ticket-log=L&S[&k=v...]
sign   = Base64_NO_WRAP(ASCII(uppercaseHex(MD5(UTF8(origin)))))

V = 4.0.9（实际安装版本）  D = DeviceIdUtil 派生  T = 毫秒时间戳
TOKEN = 登录态，未登录为空串   L = 票务日志，通常为空
```

若其他接口 query 带 `requestData`，先用同一 AES 密钥解密其中的 JSON 参数，按 TreeMap 的键升序在末尾追加 `&k=v`。

请求头：`client-id: 1`、`client-type: android`、`client-key: AMTR-IETS-0002`、
`device-id: D`、`timestamp: T`、`token: TOKEN`、`app-version: 4.0.9`、
`version: 3.0`、`sign`、`ticket-log`、`device-model`、`area-code`、
`Accept-Encoding: gzip`、`longitude`、`latitude`。**这一支没有 `app-id` 头。**
未登录的 `token`、`ticket-log`、`device-model`、`area-code`、`longitude`、`latitude` 均发送空串。
仅在响应的 `Content-Encoding` 含 `gzip` 时用 JDK `GZIPInputStream` 解压；声明接受 gzip 但响应无压缩头时，直接读取明文 JSON。

`device-id` 的派生（`DeviceIdUtil.getDeviceId`）：`AES/ECB/PKCS5Padding(android_id, "QLJ1aZjhhTEm6RiN")`
的前 50 个字符，十六进制转字符串、大写。
候车使用固定自有编号 `houche-android-metro-client` 代替 Android ID，按同一算法派生稳定设备编号；不生成随机数。

成功外层响应只有 `{"responseData":"<大写hex>"}`，不要求 `code` 或 `msg`；若显式返回非零 `code`，则按网关拒绝处理。
用同一密钥 AES/ECB/PKCS5Padding 解密 `responseData`，得到 UTF-8 JSON：
`{"code":0,"msg":"success","data":[站点对象,...],"ts":...,"traceId":...}`。内层 `code` 必须为零，非零时按站表接口报错处理。
AES 密钥取 `QLJ1aZjhhTEm6RiN` 的 UTF-8 原文十六字节，不足补零，无 IV，密文是大写十六进制。

站点按 `lineCode` 分组、`stationCode` 排序；`name` 为站名，`firstBusTime` / `lastBusTime` 为本方向首末班，
`longitude` / `latitude` 为坐标，`showFlag` / `activeFlag` 为状态。
反向时间另有 `revFirstBusTime` / `revLastBusTime`；当前页面展示本方向时间，不混用反向字段。
`showFlag=false` 的站点照常保留、弱化显示；换乘线路依据同份站表的同名地铁站推导，不补造 BRT / 国铁信息。
字段缺失或类型不符时安全读取；没有有效站名的条目跳过，未知线路名仍可按线路码展示，未知颜色退回主题色。

### 4.1 另两套签名（不要混用）

| 场景 | 算法 |
| --- | --- |
| 旧网关 `OMS` 分支 | `MD5("oms" + "3lbg5pns82ym9at76jvzuxd4r1wfoq0h" + timestamp + "1.0" + bodyJSON)`，小写 hex；头 `app-id: oms`、`version: 1.0` |
| 新公交 / 出行请求 | 双 **SM3**：`sign = SM3(SM3(origin).小写hex + appSecret)`；请求体另用 **SM4/ECB/PKCS5Padding** 加密成大写 hex 放在 `requestParam` |

`SignUtils.appSign` 是双 SM3（不是 MD5）；`APP00001` 只出现在 `SignUtils.main` 的测试字符串里，
**不是生产配置**——旧报告据此推断是错的。

### 4.2 关键常量

| 名称 | 值 | 用途 |
| --- | --- | --- |
| `CLIENT_KEY_APP` | `AMTR-IETS-0002` | `/iets-web-app/` 生产 client-key |
| `CLIENT_SECRET_APP` | `QLJ1aZjhhTEm6RiN` | 旧网关盐、AES requestData、DeviceIdUtil |
| `CLIENT_ID_APP` | `1` | 地铁旧网关 client-id |
| `CLIENT_TYPE_APP` | `android` | 地铁旧网关 client-type |
| `CLIENT_SECRET_APP_OFFWEB` | `3lbg5pns82ym9at76jvzuxd4r1wfoq0h` | OMS `app-id=oms` 的盐 |
| `CLIENT_KEY_APP1` | `AMTR-IETS-0001` | 商城 / OAuth 跳转 appid |
| `CLIENT_SECRET_APP1` | `60ce9b39c84f11e788ec0050569f4c02` | 商城跳转 DES uid |
| `NEW_BUS_APP_ID` | `xmbus-app-01` | 新请求 header `appid` |
| `TRAVEL_CLIENT_SECRET_APP` | `e2tIvG8WzZaR9dmPqL5sNfKcA6xY7bHn` | 新请求 client-key 与 SM3 尾盐 |
| `TRAVEL_SM4SECRET` | `4218fc2ebe0af24d` | 新请求 SM4 key 文本（16 字节 ASCII） |

## 5. 境内实测与架构决定

**已在境内网络实测通过。** 2026-11-07 使用位于福建厦门的机器按上述协议请求，
匿名空 token 被服务端接受，外层仅有 `responseData`，解密后的内层返回 `code=0`。
共取得 147 个站点：地铁一号线 24、二号线 32、三号线 21、四号线 8、六号线 17，加 BRT 45。
四、六号线整条 `showFlag=false`；六号线虽未进入官方 App 内置线路图，接口已有完整站序。
早前在维护窗口内的请求不足以判断匿名可用性，该疑问现已由境内成功实测解决。

采用「在线优先、内置兜底」：

1. 每次进入地铁页，先用轻量 `assets/metro-cities.json` 索引判断当前城市有无内置地铁资料，不使用电话区号。有资料时由 `MetroLoader` 以 `UNDISPATCHED` 先进入在线调用，再读取完整内置站表供即时展示；无资料时不请求在线接口，也不读取完整站表。索引随内置站表一起维护，测试核对两者城市与有效线路一致。
2. 在线成功后整份替换线路、站序、时间与状态，并标「在线」。不与内置旧线路合并，不用内置站数限制新线路；不沿用内置发布日期。
3. 网络、HTTP、签名、JSON、AES 或有效站序校验失败时，完全静默保留内置页面，不弹窗、不显示红色错误，也不更新现有展示状态。
4. 请求总超时八秒，临时网络 / HTTP 故障最多重试一次；只记录最近诊断，不缓存失败结果。离开页面取消请求，再次进入重新查询。

这种方式把数据采用的优先级与首屏展示分开：在线结果始终覆盖快照，内置仅服务等待与离线可用性。
严格等待请求失败才绘制内置会让正常页面在断网时空等八秒，不符合即时可用的体验要求。
逐站首末班在站名下用小字「首 / 末」展示，保留「次日00:00」等原文；四、六号线在分区标题标「未运营」（三字），轨道与站名弱化。

本轮验证不请求官方网关。离线测试使用 `analysis/fixtures/metro-response-sample.json` 中真实密文验证解密与解析，
使用 `metro-from-api.json` 校验完整内置快照，并覆盖在线三号线站序替换内置、增线与失败静默兜底。
本次外层响应回归不运行 Gradle / build.sh，也不请求线上接口。

前次离线核对结果：样本的 OpenSSL 解密与明文逐字一致；内置资源与完整抓取素材逐字段一致。
直接调用现有 Kotlin 2.0.21 编译器、序列化与 Compose 编译插件，全部生产 Kotlin 源码检查通过；
Android 资源经 AAPT2 编译与链接通过。直接用 JUnit 运行三组地铁测试共 29 项，全部通过。
这些检查没有启动 Gradle / build.sh，没有访问官方接口，也没有生成或验收新的 APK；
生产源码仅出现原有设置页的恒真条件告警。

本次外层响应回归：成功外层仅含 `responseData`，`outerKeys` 同步，并增加 `10004 sign缺失` 网关错误样本。
原有裁剪样本只有四条站点、未保留 `ts` / `traceId`，原始密文与明文均保持不变；另用明确构造的变体验证这两个字段的兼容性。
OpenSSL 以 `enc -d -aes-128-ecb -K <密钥十六进制> -nosalt` 解密后与样本明文逐字节一致。
直接用现有 Kotlin 编译器与 JUnit 运行三组地铁测试共 37 项，全部通过，覆盖无外层状态码的在线成功、网关拒绝、内层报错、gzip / 明文响应、城市覆盖与在线调用先于完整站表读取。
全部生产 Kotlin 源码编译检查通过，仅有上述原有设置页告警；本次未运行 Gradle / build.sh，未请求线上接口。

## 6. 相关文件

| 路径 | 内容 |
| --- | --- |
| `analysis/extract_svg.py` | 从官方线网图提取线路数据 |
| `analysis/build_app_metro_json.py` | 整理成 App 内置资源 |
| `analysis/metro_client.py` | 按原生实现复现网关请求（含加解密） |
| `analysis/metro-unpack.md` | 完整脱壳与协议分析报告 |
| `analysis/work/mx/layer2_classes*.dex` | 无损还原的受保护 DEX |
| `analysis/work/mx/restored_classes*.dex` | 恢复 MX 方法后的 DEX |
| `analysis/work/mx/restored_jadx/sources/` | 可读 Java 源码（含签名类） |
| `analysis/work/mx/evidence/native-api-inventory.json` | 203 条原生接口清单 |
| `analysis/work/mx/metro-official.json` | 官方线网图提取结果 |
| `app/src/main/assets/metro.json` | App 内置的地铁资料 |
| `app/src/main/assets/metro-cities.json` | 内置资料的轻量城市索引，供在线请求前判断覆盖范围 |
| `analysis/fixtures/metro-response-sample.json` | 现有裁剪样本的 AES 密文与明文，外层仅含 responseData；另含网关错误样本 |
| `analysis/fixtures/metro-from-api.json` | 官方接口完整抓取，五条线共 102 站的内置兜底基准 |
| `app/src/main/java/com/yll682/houche/core/MetroLoader.kt` | 在线请求与即时兜底展示的加载流程 |

逆向分析工作素材位于 `/data/projects/houche/analysis/`，本轮只读；当前仓库中的离线回归素材位于 `analysis/fixtures/`。
