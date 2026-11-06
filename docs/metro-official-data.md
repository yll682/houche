# 厦门地铁官方数据提取与接口协议

日期：2026-11-06 / 2026-11-07
对象：厦门地铁官方 App（`com.luqiao.xmmetro` 4.0.9 / vc89，正版，应用宝下载）

## 结论速览

| 项目 | 结果 |
| --- | --- |
| 线网数据来源 | **官方 App 内置运营线路图** `res/raw/xm_20260609_1.svg`，元素 ID 自带线路与站序 |
| 线路 | 1/2/3/4 号线，合计 83 站（24 + 30 + 21 + 8） |
| 线路色 | 1 号线 `#DA7028`、2 号线 `#52A441`、3 号线 `#52B5C8`、4 号线 `#E50012` |
| 加固 | 蛮犀（`com.mx.shell` + `libmxldd.so` / `libvmd.so`） |
| 脱壳 | **成功**：MX 容器两层变换全解，四份 DEX 原始 SHA-1 / Adler-32 全部吻合，恢复 19,054 个方法体 |
| 网关签名 | **已还原**（见第 4 节） |
| 线上验证 | **未完成**：实测期间官方处于后台维护窗口（2026-11-07 00:30–4:30），响应不可信 |

## 1. 数据来源：官方 App 内置线网图

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

### 1.1 口径

- 灰色节点 = 待开通，不计入运营站序（4 号线「翔安机场」因此被排除）
- 换乘只取图中共用坐标的站点，以及图上标注的火车站 / BRT 换乘
- 首末班时间线路图里没有，置空，不用外部资料填
- 6 号线只恢复了 UI 颜色 `#92308D`（`res/values/colors.xml#bg_route_line6`），
  **没有站序**，因此不进 App；凭颜色编路线属于造假

### 1.2 与旧数据的差异

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

地铁站表走 `/iets-web-app/`，由 `HeaderParamInterceptor` 处理
（`analysis/work/mx/restored_jadx/sources/com/luqiao/xmmetro/base/api/HeaderParamInterceptor.java`）：

```text
S = QLJ1aZjhhTEm6RiN
K = AMTR-IETS-0002

origin = S&app-version=V&client-key=K&client-id=1&client-type=android&device-id=D
         &timestamp=T&token=TOKEN&ticket-log=L&S[&k=v...]
sign   = Base64_NO_WRAP(ASCII(uppercaseHex(MD5(UTF8(origin)))))

V = 4.0.9（实际安装版本）  D = DeviceIdUtil 派生  T = 毫秒时间戳
TOKEN = 登录态，未登录为空串   L = 票务日志，通常为空
```

请求头：`client-id: 1`、`client-type: android`、`client-key: AMTR-IETS-0002`、
`device-id: D`、`timestamp: T`、`token: TOKEN`、`app-version: 4.0.9`、
`version: 3.0`、`sign`、`ticket-log`、`device-model`、`area-code`、
`Accept-Encoding: gzip`、`longitude`、`latitude`。**这一支没有 `app-id` 头。**

`device-id` 的派生（`DeviceIdUtil.getDeviceId`）：`AES/ECB/PKCS5Padding(android_id, "QLJ1aZjhhTEm6RiN")`
的前 50 个字符，十六进制转字符串、大写。

响应体是 `{"responseData": "<大写hex>"}`，用同一密钥 AES/ECB/PKCS5Padding 解密，
即得 JSON。

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

## 5. 线上验证状态

**未完成。** 计划中的实测落在官方维护窗口内：

> 2026年11月7日00:30-4:30进行后台系统维护
> 发布者：厦门地铁 发布时间：2026-11-06 23:09:15
> 2026年11月7日00:30-4:30进行后台系统维护，期间将关闭购票业务

维护期内网关返回的一切响应都不能作为签名正确性或匿名可用性的证据。
已知事实：客户端代码允许空 token 并继续签名发请求（53 个 wrapper 无 token 检查，
`Retrofit` 注解也没有 token-required 标签），但**服务端是否接受空 token 尚未证实**。

App 的处理方式保持不变：`MetroRemote` 每次只探测一次，命中就换成在线站序并标「在线」，
未命中完全静默、继续用内置资料，探测结论只写到「设置 → 关于」。

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