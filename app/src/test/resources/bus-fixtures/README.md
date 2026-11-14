# 公交真实响应夹具与来源清单

本清单整理于 `2026-11-14T05:08:06.736439Z`。本目录共 **100 份真实来源响应 JSON**：
原有生产客户端厦门 91 路 8 份、北京 1 路 8 份，新增 Python 来源厦门 91 路 42 份、803 路 42 份。
原有 `xiamen-*`、`beijing-*` JSON、`capture-attempts.jsonl`、`verification-results.txt` 均保留原字节。
响应样本不是车辆去重结果：84 个车辆查询响应包含 204 条车辆记录（跨时间及参照站重复计数），其中 78 个响应非空；另有 16 个站表响应。

## 来源和字节保真范围

- 新增 84 份 JSON 从 `/data/work/houche/calib/` 按原文件名、文件名内 UTC 时间戳原字节复制，保留来源文件 mtime；没有格式化、改字段或补造响应。
- 已读取 `/data/work/houche/xmbus_fetch.py`：该脚本用 `urllib` 读取 HTTP 响应后先 `json.loads`，再以 `json.dump(..., ensure_ascii=False)` 写文件。因此新增夹具与**来源落盘文件字节一致**，并非未经处理的 HTTP body 字节。清单记录脚本 SHA-256 及这一差异。
- 原有 16 份生产客户端响应是抓取客户端保存的应用层 body；其字节数、SHA-256 均与 `capture-attempts.jsonl` 中对应成功请求核对一致。这里不把解压后的应用层 body 称为 TLS/压缩线缆数据。
- `fixture-manifest.json` 的 `responses` 逐文件列出来源绝对路径、SHA-256、字节数、接口、线路 ID、方向、参照站序/ID/Name、站表文件、时间来源和车辆数，供测试枚举。清单本身不是响应。
- 测试应枚举清单的 `responses`，不能把本目录所有 `*.json` 都当作真实响应；`excludedArtifacts` 列出统计/期望 JSON，这些是派生产物。

## 实际时间与样本数量

以下日期均为 **2026-11-14**；北京时间为 UTC+08:00，无夏令时。
生产客户端时间来自成功请求日志的开始/结束时刻；Python 逐请求开始/结束时刻没有落盘，表中明确使用来源文件 mtime（落盘时间线索）。

| 组 | JSON / 站表 / 车辆响应 | 非空车辆响应 | 车辆记录数（含重复） | UTC 时间及证据 | 北京时间（UTC+08:00） |
|---|---:|---:|---:|---|---|
| 生产客户端 · 厦门 91 路 | 8 / 2 / 6 | 6 | 15 | 2026-11-14T04:32:40.835147696Z ～ 2026-11-14T04:32:47.322712638Z（请求日志） | 2026-11-14T12:32:40.835147696+08:00 ～ 2026-11-14T12:32:47.322712638+08:00 |
| 生产客户端 · 北京 1 路 | 8 / 2 / 6 | 0 | 0 | 2026-11-14T04:32:47.334197962Z ～ 2026-11-14T04:32:53.640756901Z（请求日志） | 2026-11-14T12:32:47.334197962+08:00 ～ 2026-11-14T12:32:53.640756901+08:00 |
| Python · 厦门 91 路 | 42 / 6 / 36 | 36 | 81 | 2026-11-14T04:50:48.918711620Z ～ 2026-11-14T04:59:10.267039964Z（来源文件 mtime） | 2026-11-14T12:50:48.918711620+08:00 ～ 2026-11-14T12:59:10.267039964+08:00 |
| Python · 厦门 803 路 | 42 / 6 / 36 | 36 | 108 | 2026-11-14T04:51:17.426712605Z ～ 2026-11-14T04:59:34.576950463Z（来源文件 mtime） | 2026-11-14T12:51:17.426712605+08:00 ～ 2026-11-14T12:59:34.576950463+08:00 |

Python 每条线路各 6 批；每批先取 1 个双向 `lineDetail`，再依次查询方向 1/2 的首站、中间站、末站，共 7 个文件。91 路参照站序为 `1/17/32`，803 路为 `1/19/36`。
Python 文件名的 `YYYYMMDDTHHMMSS` 来自 `time.gmtime()`，只表示该线路批次开始时刻：同一批次的 7 次请求共用它，不能冒充每次请求的精确时间。

| Python 线路 | 文件名批次开始 UTC | 文件名批次开始 北京时间 | 响应原始 `ts` 按 Unix 毫秒转换为 UTC | 转换为北京时间 |
|---|---|---|---|---|
| Python · 厦门 91 路 | 2026-11-14T04:50:43Z ～ 2026-11-14T04:58:44Z | 2026-11-14T12:50:43+08:00 ～ 2026-11-14T12:58:44+08:00 | 2026-11-14T04:50:49.294Z ～ 2026-11-14T04:59:11.849Z | 2026-11-14T12:50:49.294+08:00 ～ 2026-11-14T12:59:11.849+08:00 |
| Python · 厦门 803 路 | 2026-11-14T04:51:13Z ～ 2026-11-14T04:59:10Z | 2026-11-14T12:51:13+08:00 ～ 2026-11-14T12:59:10+08:00 | 2026-11-14T04:51:18.251Z ～ 2026-11-14T04:59:36.069Z | 2026-11-14T12:51:18.251+08:00 ～ 2026-11-14T12:59:36.069+08:00 |

响应 `ts` 是服务器字段，与本地 mtime 属于不同时间证据；没有据此修正来源文件时间。
逐车 `dataTime` 原字符串范围保存在清单中。原字段没有时区标记，不能把它当成独立的抓取时间证据。

## 请求参数与参照站

厦门：`POST https://app.xmbus.com/xmbus_web_app/bus/pub/routeQuery/lineDetail`，参数为 `busLineName=91路` 或 `803路`；接口没有方向参数，响应同时含双向站表。
`busList` 使用同一基础路径：`POST .../busList`，参数为 `busLineId,direction,stationSeq,stationId,stationName`。
91 路 `busLineId=a2e913fc4967b27f1d72edd76cbcc831`；803 路 `busLineId=0e2b9e664b62b4626e0e297660589714`。
原有厦门参数有成功请求日志可核；Python 参数由现存脚本和**同批次 JSON 站表**重建，清单设 `paramsCapturedInRequestLog=false`。`fetch.log` 仅能核对批次、线路、方向、参照站序/站名和记录数，没有完整参数、请求头、签名或 HTTP 状态抓包，缺失值保持 `null`。

| 厦门线路 | 方向 | stationSeq | stationName | stationId |
|---|---|---:|---|---|
| 91路 | 1 | 1 | 高崎T4公交场站 | `00f3d537bb68ab189902a81666e23cc8` |
| 91路 | 1 | 17 | 第二福利院 | `d792b7c083582b05f0dc8394f6665d6c` |
| 91路 | 1 | 32 | 前埔公交场站 | `183638b44769e556cad6df49ddfe11d7` |
| 91路 | 2 | 1 | 前埔公交场站 | `eba2a9b1f0014b8a3570fe698bafed99` |
| 91路 | 2 | 17 | 国际山庄 | `45eee9ed2e8fdf5b72a67d6fb9567d1e` |
| 91路 | 2 | 32 | 高崎T4公交场站 | `36ae9128b7cc92d617c6080e40136183` |
| 803路 | 1 | 1 | 一农公交场站 | `2dd3d6d20f081f185b7d664002c644b9` |
| 803路 | 1 | 19 | 马銮湾云汐广场 | `77aea1be6a87632b7d831755ad5eced6` |
| 803路 | 1 | 36 | 一农公交场站 | `1d6193d4d884ba9ad0b04ec06a94cfe6` |
| 803路 | 2 | 1 | 一农公交场站 | `bd038b70b03c507fc81c65046d7ebc2e` |
| 803路 | 2 | 19 | 新阳大道 | `53e5d6c0894ae34dd6b6d239150d031f` |
| 803路 | 2 | 36 | 一农公交场站 | `c5b41a6d7745e97e27d9d64656b602d7` |

803 路同方向首末站都叫「一农公交场站」，但站序和 ID 不同；按清单的方向、站序和 ID 区分。

北京：`POST https://h5.mygolbs.com/ApiData.do`。
站表参数为 `CMD=103,CITYNAME=北京市,LINENAME=1路,DIRECTION=1/2`；车辆查询为 `CMD=104` 并加 `STATIONORDER`。
方向 1 的参照站为 `1 四惠枢纽站 / 15 工会大楼 / 29 老山公交场站`；方向 2 为 `1 老山公交场站 / 15 工会大楼 / 28 四惠枢纽站`，均按本轮真实 CMD 103 站表核对。
北京站表的 `routeId=11`，方向 1 有 29 站、方向 2 有 28 站。清单中的 `stationId=0` 和站名按原站表保留，仅为参照站元数据，CMD 104 没有发送这两个参数。城市 code `010/0592` 也只是清单城市标识。
6 个 CMD 104 响应均为 `routeOnStationRTimeInfoList=[]`、`hasReal=0`、`runState=0`，是已保存的空车辆响应，不据此判断停运或参数无效。

## 已核对的站表关系与缺失例外

所有本轮站表都按方向从 1 连续编号：厦门 91 路每方向 32 站、803 路每方向 36 站；北京为 29/28 站。
在本快照的厦门车辆样本中，按同方向站表把 `stationOrder` 对应为当前站的 1 基站序，下一站用 `stationOrder + 1` 核对；204 条车辆记录的站序均存在于对应站表。
**201 条非空 `nextStationName` 全部与该下一站站名逐字一致；另有 3 条原值为 `null`，不能声称全部样本都有下一站名。** 此处只记录字段与站表的关系，不据此解释车辆物理位置、`inOrOut` 或 `positionState`。

缺失例外均为 **803 路方向 2、批次 `20261114T045605`**，车辆 `闽D17832D`，原始 `dataTime=2026-11-14 12:56:22`，`stationOrder=35`（站表「一农湖头」），站表下一站 36 为「一农公交场站」，而响应 `nextStationName=null`：

- `803路-d2-seq1-busList-20261114T045605.json`，`data[2]`。
- `803路-d2-seq19-busList-20261114T045605.json`，`data[2]`。
- `803路-d2-seq36-busList-20261114T045605.json`，`data[2]`。

清单 `literalStationTableChecks.anomalies` 保留逐条定位。`stationOrder` 表示站段起点，不能证明正停在该站。具体解析结论见 `notes/codex-round12-bus-calibration-result.md`：仅正速度且站表校验通过判 `between`，前方站为绘图锚点；零速或缺字段/冲突判 `unknown`、清空绘图锚点。本批次没有可确认的 `at-stop`，不能用静止时间设置停站阈值。

## 日志与历史结果

- `fetch.log`：从 `/data/work/houche/calib/fetch.log` 原字节复制的 **Python 来源摘要日志，不是响应**。共 72 个车辆查询摘要，与各响应记录数/参照站名核对一致；不能代替完整请求日志。
- `capture-attempts.jsonl`：保留原字节的 **请求尝试日志，不是响应**。共 40 条，其中前一轮 24 条失败尝试（UTC 2026-11-13 18:02:47–18:04:32，北京时间 2026-11-14 02:02:47–02:04:32），本轮 16 条成功且有响应文件。旧失败记录不会因新增成功样本而删除。
- `verification-results.txt`：**第 1 轮历史验证结果**，时间为 UTC 2026-11-13 / 北京时间 2026-11-14 凌晨；不是本轮测试结果，也不是响应或解析期望值。
- `verification-round12.txt`：本轮实际验证结果、执行方法名及当前源码摘要，**不是上游响应**。指定 Gradle 任务启动被沙箱阻止，离线 Kotlin/JUnit 的实际执行数量与结果单列。
- `calibration-statistics.json`：204 条车辆记录的字段统计，含重复抓取；13 个不同车牌，101 个线路/方向/车牌/数据时刻组合，**不是上游响应**。`inOrOut=0/1` 各 52/152 条，其中正速度 22/104 条、零速 30/48 条；正速度与非零 `motionLessTime` 共存 28 条。
- `vehicle-expectations.json`：按人工核验口径冻结的 78 份厦门响应、204 条逐车解析期望，另列 6 份北京空响应，**不是上游响应**。126 条 `between`、78 条 `unknown`；3 条下一站缺失由对应站表补全。单测运行期间只读取期望，不调用被测函数生成期望。

本轮不联网。离线测试复跑命令（需允许 Gradle 本地 socket 的执行环境）：

```bash
export LC_ALL=C.UTF-8 LANG=C.UTF-8
./gradlew --stop
./build.sh :app:testDebugUnitTest --no-build-cache --rerun \
  --tests 'com.yll682.houche.core.Bus*' --tests 'com.yll682.houche.RepositoryTest'
```

## 来源目录是否仍在增加

观察窗口为 `2026-11-14T05:00:00.389233Z` ～ `2026-11-14T05:08:06.734362Z`
（北京时间 `2026-11-14T13:00:00.389233+08:00` ～ `2026-11-14T13:08:06.734362+08:00`）。
窗口内 JSON 均为 84 份，`fetch.log` 均为 28011 字节且 SHA-256 未变化；未观察到新增或改写。
末次检查未发现 `xmbus_fetch.py` 抓取进程。此结论仅限观察窗口；本目录是当前快照，之后若抓取恢复，需再次同步并重建清单。

## 逐文件索引与机器校验

下表逐一列出响应；完整来源、SHA-256、参照站 ID/Name、UTC/北京时间、请求证据在 `fixture-manifest.json.responses`。
方向 `both` 表示双向站表；车辆数 `—` 表示站表，`0` 才表示车辆数组为空。

| 文件名 | 接口 | 线路 | 方向 | 参照站序 | 内容 / 车辆数 |
|---|---|---|---|---:|---|
| `xiamen-91路-dboth-refnone-lineDetail-20261114T043240835Z-1.json` | lineDetail | 91路 | both | — | 站表 d1=32/d2=32；车辆 — |
| `xiamen-91路-d1-ref1-busList-20261114T043243067Z-2.json` | busList | 91路 | 1 | 1 | 车辆 2 |
| `xiamen-91路-d1-ref17-busList-20261114T043245116Z-3.json` | busList | 91路 | 1 | 17 | 车辆 2 |
| `xiamen-91路-d1-ref32-busList-20261114T043245483Z-4.json` | busList | 91路 | 1 | 32 | 车辆 2 |
| `xiamen-91路-dboth-refnone-lineDetail-20261114T043245833Z-5.json` | lineDetail | 91路 | both | — | 站表 d1=32/d2=32；车辆 — |
| `xiamen-91路-d2-ref1-busList-20261114T043246218Z-6.json` | busList | 91路 | 2 | 1 | 车辆 3 |
| `xiamen-91路-d2-ref17-busList-20261114T043246593Z-7.json` | busList | 91路 | 2 | 17 | 车辆 3 |
| `xiamen-91路-d2-ref32-busList-20261114T043246963Z-8.json` | busList | 91路 | 2 | 32 | 车辆 3 |
| `beijing-1路-d1-refnone-cmd103-20261114T043247334Z-1.json` | cmd103 | 1路 | 1 | — | 站表 d1=29；车辆 — |
| `beijing-1路-d1-ref1-cmd104-20261114T043249463Z-2.json` | cmd104 | 1路 | 1 | 1 | 车辆 0 |
| `beijing-1路-d1-ref15-cmd104-20261114T043250198Z-3.json` | cmd104 | 1路 | 1 | 15 | 车辆 0 |
| `beijing-1路-d1-ref29-cmd104-20261114T043250908Z-4.json` | cmd104 | 1路 | 1 | 29 | 车辆 0 |
| `beijing-1路-d2-refnone-cmd103-20261114T043251552Z-5.json` | cmd103 | 1路 | 2 | — | 站表 d2=28；车辆 — |
| `beijing-1路-d2-ref1-cmd104-20261114T043253001Z-6.json` | cmd104 | 1路 | 2 | 1 | 车辆 0 |
| `beijing-1路-d2-ref15-cmd104-20261114T043253213Z-7.json` | cmd104 | 1路 | 2 | 15 | 车辆 0 |
| `beijing-1路-d2-ref28-cmd104-20261114T043253441Z-8.json` | cmd104 | 1路 | 2 | 28 | 车辆 0 |
| `91路-d1-seq1-busList-20261114T045043.json` | busList | 91路 | 1 | 1 | 车辆 3 |
| `91路-d1-seq17-busList-20261114T045043.json` | busList | 91路 | 1 | 17 | 车辆 3 |
| `91路-d1-seq32-busList-20261114T045043.json` | busList | 91路 | 1 | 32 | 车辆 3 |
| `91路-d2-seq1-busList-20261114T045043.json` | busList | 91路 | 2 | 1 | 车辆 2 |
| `91路-d2-seq17-busList-20261114T045043.json` | busList | 91路 | 2 | 17 | 车辆 2 |
| `91路-d2-seq32-busList-20261114T045043.json` | busList | 91路 | 2 | 32 | 车辆 2 |
| `91路-lineDetail-20261114T045043.json` | lineDetail | 91路 | both | — | 站表 d1=32/d2=32；车辆 — |
| `91路-d1-seq1-busList-20261114T045223.json` | busList | 91路 | 1 | 1 | 车辆 2 |
| `91路-d1-seq17-busList-20261114T045223.json` | busList | 91路 | 1 | 17 | 车辆 2 |
| `91路-d1-seq32-busList-20261114T045223.json` | busList | 91路 | 1 | 32 | 车辆 2 |
| `91路-d2-seq1-busList-20261114T045223.json` | busList | 91路 | 2 | 1 | 车辆 2 |
| `91路-d2-seq17-busList-20261114T045223.json` | busList | 91路 | 2 | 17 | 车辆 2 |
| `91路-d2-seq32-busList-20261114T045223.json` | busList | 91路 | 2 | 32 | 车辆 2 |
| `91路-lineDetail-20261114T045223.json` | lineDetail | 91路 | both | — | 站表 d1=32/d2=32；车辆 — |
| `91路-d1-seq1-busList-20261114T045356.json` | busList | 91路 | 1 | 1 | 车辆 2 |
| `91路-d1-seq17-busList-20261114T045356.json` | busList | 91路 | 1 | 17 | 车辆 2 |
| `91路-d1-seq32-busList-20261114T045356.json` | busList | 91路 | 1 | 32 | 车辆 2 |
| `91路-d2-seq1-busList-20261114T045356.json` | busList | 91路 | 2 | 1 | 车辆 2 |
| `91路-d2-seq17-busList-20261114T045356.json` | busList | 91路 | 2 | 17 | 车辆 2 |
| `91路-d2-seq32-busList-20261114T045356.json` | busList | 91路 | 2 | 32 | 车辆 2 |
| `91路-lineDetail-20261114T045356.json` | lineDetail | 91路 | both | — | 站表 d1=32/d2=32；车辆 — |
| `91路-d1-seq1-busList-20261114T045533.json` | busList | 91路 | 1 | 1 | 车辆 2 |
| `91路-d1-seq17-busList-20261114T045533.json` | busList | 91路 | 1 | 17 | 车辆 2 |
| `91路-d1-seq32-busList-20261114T045533.json` | busList | 91路 | 1 | 32 | 车辆 2 |
| `91路-d2-seq1-busList-20261114T045533.json` | busList | 91路 | 2 | 1 | 车辆 2 |
| `91路-d2-seq17-busList-20261114T045533.json` | busList | 91路 | 2 | 17 | 车辆 2 |
| `91路-d2-seq32-busList-20261114T045533.json` | busList | 91路 | 2 | 32 | 车辆 2 |
| `91路-lineDetail-20261114T045533.json` | lineDetail | 91路 | both | — | 站表 d1=32/d2=32；车辆 — |
| `91路-d1-seq1-busList-20261114T045713.json` | busList | 91路 | 1 | 1 | 车辆 2 |
| `91路-d1-seq17-busList-20261114T045713.json` | busList | 91路 | 1 | 17 | 车辆 2 |
| `91路-d1-seq32-busList-20261114T045713.json` | busList | 91路 | 1 | 32 | 车辆 2 |
| `91路-d2-seq1-busList-20261114T045713.json` | busList | 91路 | 2 | 1 | 车辆 3 |
| `91路-d2-seq17-busList-20261114T045713.json` | busList | 91路 | 2 | 17 | 车辆 3 |
| `91路-d2-seq32-busList-20261114T045713.json` | busList | 91路 | 2 | 32 | 车辆 3 |
| `91路-lineDetail-20261114T045713.json` | lineDetail | 91路 | both | — | 站表 d1=32/d2=32；车辆 — |
| `91路-d1-seq1-busList-20261114T045844.json` | busList | 91路 | 1 | 1 | 车辆 2 |
| `91路-d1-seq17-busList-20261114T045844.json` | busList | 91路 | 1 | 17 | 车辆 2 |
| `91路-d1-seq32-busList-20261114T045844.json` | busList | 91路 | 1 | 32 | 车辆 2 |
| `91路-d2-seq1-busList-20261114T045844.json` | busList | 91路 | 2 | 1 | 车辆 3 |
| `91路-d2-seq17-busList-20261114T045844.json` | busList | 91路 | 2 | 17 | 车辆 3 |
| `91路-d2-seq32-busList-20261114T045844.json` | busList | 91路 | 2 | 32 | 车辆 3 |
| `91路-lineDetail-20261114T045844.json` | lineDetail | 91路 | both | — | 站表 d1=32/d2=32；车辆 — |
| `803路-d1-seq1-busList-20261114T045113.json` | busList | 803路 | 1 | 1 | 车辆 3 |
| `803路-d1-seq19-busList-20261114T045113.json` | busList | 803路 | 1 | 19 | 车辆 3 |
| `803路-d1-seq36-busList-20261114T045113.json` | busList | 803路 | 1 | 36 | 车辆 3 |
| `803路-d2-seq1-busList-20261114T045113.json` | busList | 803路 | 2 | 1 | 车辆 4 |
| `803路-d2-seq19-busList-20261114T045113.json` | busList | 803路 | 2 | 19 | 车辆 4 |
| `803路-d2-seq36-busList-20261114T045113.json` | busList | 803路 | 2 | 36 | 车辆 4 |
| `803路-lineDetail-20261114T045113.json` | lineDetail | 803路 | both | — | 站表 d1=36/d2=36；车辆 — |
| `803路-d1-seq1-busList-20261114T045249.json` | busList | 803路 | 1 | 1 | 车辆 3 |
| `803路-d1-seq19-busList-20261114T045249.json` | busList | 803路 | 1 | 19 | 车辆 3 |
| `803路-d1-seq36-busList-20261114T045249.json` | busList | 803路 | 1 | 36 | 车辆 3 |
| `803路-d2-seq1-busList-20261114T045249.json` | busList | 803路 | 2 | 1 | 车辆 3 |
| `803路-d2-seq19-busList-20261114T045249.json` | busList | 803路 | 2 | 19 | 车辆 3 |
| `803路-d2-seq36-busList-20261114T045249.json` | busList | 803路 | 2 | 36 | 车辆 3 |
| `803路-lineDetail-20261114T045249.json` | lineDetail | 803路 | both | — | 站表 d1=36/d2=36；车辆 — |
| `803路-d1-seq1-busList-20261114T045427.json` | busList | 803路 | 1 | 1 | 车辆 3 |
| `803路-d1-seq19-busList-20261114T045427.json` | busList | 803路 | 1 | 19 | 车辆 3 |
| `803路-d1-seq36-busList-20261114T045427.json` | busList | 803路 | 1 | 36 | 车辆 3 |
| `803路-d2-seq1-busList-20261114T045427.json` | busList | 803路 | 2 | 1 | 车辆 3 |
| `803路-d2-seq19-busList-20261114T045427.json` | busList | 803路 | 2 | 19 | 车辆 3 |
| `803路-d2-seq36-busList-20261114T045427.json` | busList | 803路 | 2 | 36 | 车辆 3 |
| `803路-lineDetail-20261114T045427.json` | lineDetail | 803路 | both | — | 站表 d1=36/d2=36；车辆 — |
| `803路-d1-seq1-busList-20261114T045605.json` | busList | 803路 | 1 | 1 | 车辆 3 |
| `803路-d1-seq19-busList-20261114T045605.json` | busList | 803路 | 1 | 19 | 车辆 3 |
| `803路-d1-seq36-busList-20261114T045605.json` | busList | 803路 | 1 | 36 | 车辆 3 |
| `803路-d2-seq1-busList-20261114T045605.json` | busList | 803路 | 2 | 1 | 车辆 3 |
| `803路-d2-seq19-busList-20261114T045605.json` | busList | 803路 | 2 | 19 | 车辆 3 |
| `803路-d2-seq36-busList-20261114T045605.json` | busList | 803路 | 2 | 36 | 车辆 3 |
| `803路-lineDetail-20261114T045605.json` | lineDetail | 803路 | both | — | 站表 d1=36/d2=36；车辆 — |
| `803路-d1-seq1-busList-20261114T045737.json` | busList | 803路 | 1 | 1 | 车辆 3 |
| `803路-d1-seq19-busList-20261114T045737.json` | busList | 803路 | 1 | 19 | 车辆 3 |
| `803路-d1-seq36-busList-20261114T045737.json` | busList | 803路 | 1 | 36 | 车辆 3 |
| `803路-d2-seq1-busList-20261114T045737.json` | busList | 803路 | 2 | 1 | 车辆 2 |
| `803路-d2-seq19-busList-20261114T045737.json` | busList | 803路 | 2 | 19 | 车辆 2 |
| `803路-d2-seq36-busList-20261114T045737.json` | busList | 803路 | 2 | 36 | 车辆 2 |
| `803路-lineDetail-20261114T045737.json` | lineDetail | 803路 | both | — | 站表 d1=36/d2=36；车辆 — |
| `803路-d1-seq1-busList-20261114T045910.json` | busList | 803路 | 1 | 1 | 车辆 3 |
| `803路-d1-seq19-busList-20261114T045910.json` | busList | 803路 | 1 | 19 | 车辆 3 |
| `803路-d1-seq36-busList-20261114T045910.json` | busList | 803路 | 1 | 36 | 车辆 3 |
| `803路-d2-seq1-busList-20261114T045910.json` | busList | 803路 | 2 | 1 | 车辆 3 |
| `803路-d2-seq19-busList-20261114T045910.json` | busList | 803路 | 2 | 19 | 车辆 3 |
| `803路-d2-seq36-busList-20261114T045910.json` | busList | 803路 | 2 | 36 | 车辆 3 |
| `803路-lineDetail-20261114T045910.json` | lineDetail | 803路 | both | — | 站表 d1=36/d2=36；车辆 — |

本轮对新增 84 份 JSON 和 `fetch.log` 分别做来源/目标完整字节比较、SHA-256 比较；原有 16 份响应同时核对成功请求日志的 SHA-256/字节数。全部响应均可 JSON 解码；清单准确枚举 100 个 `.json` 响应，另将统计/期望产物明确排除。
以下离线命令可复核文件枚举、完整字节和已有成功请求日志（在仓库根目录执行；不启动 Gradle）：

```bash
python3 - <<'PY'
from pathlib import Path
import hashlib, json
root = Path('app/src/test/resources/bus-fixtures')
manifest = json.loads((root / 'fixture-manifest.json').read_bytes())
responses = manifest['responses']
excluded = {'fixture-manifest.json'} | {a['fileName'] for a in manifest['excludedArtifacts']}
assert {r['fileName'] for r in responses} == {
    p.name for p in root.glob('*.json') if p.name not in excluded
}
for item in responses + manifest['supportingArtifacts']:
    raw = (root / item['fileName']).read_bytes()
    assert len(raw) == item['bytes']
    assert hashlib.sha256(raw).hexdigest() == item['sha256']
    assert raw == Path(item['sourcePath']).read_bytes()
    if item.get('kind') == 'response':
        json.loads(raw)
attempts = [json.loads(s) for s in (root / 'capture-attempts.jsonl').read_text().splitlines()]
for attempt in attempts:
    if attempt.get('responseFile'):
        raw = (root / attempt['responseFile']).read_bytes()
        assert len(raw) == attempt['bytes']
        assert hashlib.sha256(raw).hexdigest() == attempt['sha256']
print(f"verified {len(responses)} responses and {len(manifest['supportingArtifacts'])} supporting artifacts")
PY
```

来源绝对路径用于本工作区追溯；移走 `/data/work/houche/calib/` 后，仍可用清单 SHA-256 验证仓库夹具，但来源字节比较需要原目录可读。
