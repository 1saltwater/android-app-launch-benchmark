
---

## 8. 如何阅读 `sample.csv` 里新增的 21 列

### 8.1 文件结构

- 第一行是表头，之后**每 10 秒一行**（`sampleStamp` 从 0 递增，即采样序号）
- 一轮实验（一次 `gradlew run`）的所有采样写在同一个 `logs/<时间戳>_<方案>_<应用组>/sample.csv` 里
- 新增的 21 列全部以 `hh` 开头，排在原有列之后

### 8.2 三类列，读法完全不同

**A 类：累计计数器**（自 hyperhold 初始化/开机以来单调递增，**必须首末相减**）

| 列 | 含义 |
|---|---|
| `hhIoReqCnt` | 真正触发 UFS 回抬的按需缺页请求次数 |
| `hhIoReqBytes` | 这些请求的压缩对象字节和 |
| `hhReadInBytes` | 回抬实际读入的字节（**含按需 + 预取**，32KB 对齐） |
| `hhWriteRawBytes` | 下刷收进 extent 的压缩字节和 |
| `hhWriteUfsBytes` | 下刷实际写入 UFS 的字节（32KB 对齐） |
| `hhFaultoutFaultCnt` | 进入 hyperhold 读路径的次数（含 cache 命中） |
| `hhPfObjs` | 预取读入内存的对象数 |
| `hhPfHitObjs` | 预取对象中之后被读过的数量 |
| `hhPfNeverUsedObjs` | 预取对象中一直未被读的数量 |
| `hhPfNeverUsedBytes` | 同上，压缩字节 |
| `hhInflightPages` | 当前在飞的 extent 缓冲页数（瞬时值） |
| `hhInflightPagesPeak` | 在飞页数历史峰值（只增不减） |
| `hhMovedInBytes` | 回抬搬进 zram 的压缩字节 |
| `hhMovedInPages` | 回抬搬进 zram 的对象数 |
| `hhCachePages` | **瞬时值**，当前 cache 常驻页数 |
| `hhCachePagesPeak` | cache 常驻页数历史峰值 |

**B 类：比例列**（每个采样点自行算出，直接读即可，不用相减）

| 列 | 公式 | 备注 |
|---|---|---|
| `hhReadAmpX100` | `hhReadInBytes * 100 / hhIoReqBytes` | ⚠️ 分子含预取流量，属**混合口径**；纯按需放大需另拆计数器 |
| `hhWriteAmpX100` | `hhWriteUfsBytes * 100 / hhWriteRawBytes` | 正常 100~130 |
| `hhPfHitRateX100` | `hhPfHitObjs * 100 / (hhPfHitObjs + hhPfNeverUsedObjs)` | 分母**不含**未结算对象 |

**C 类：区间增量（带宽）**（本采样点相对上一个采样点）

| 列 | 公式 |
|---|---|
| `hhReadBw(KB/s)` | `(本点 hhReadInBytes - 上点) / 10000` |
| `hhWriteBw(KB/s)` | `(本点 hhWriteUfsBytes - 上点) / 10000` |

### 8.3 单行怎么读

看某一行（一个采样点）时：**先看 B 类和 C 类**（描述"此刻状态"和"刚才 10 秒发生了什么"，不用做减法），**A 类必须和上一行比**才有意义。

判断"这 10 秒发生了什么"：

| 观察 | 结论 |
|---|---|
| `hhReadBw > 0` | 这 10 秒有回抬读 UFS |
| `hhWriteBw > 0` | 这 10 秒有下刷写 UFS |
| `hhWriteBw` 由大变小到 0 | 内存压力缓解，下刷停止 |
| `hhPfObjs` 增长快 | 预取在批量搬数据 |
| `hhCachePages` 恒为 0 | cache 层未启用（`cachelevel=0`） |
| `hhInflightPages` 接近 `hhInflightPagesPeak` | 处于回抬高峰 |

### 8.4 整轮怎么算

因为 A 类是累计值，**本轮总量 = 最后一行 − 第一行**：

```
本轮按需回抬次数   = ΔhhIoReqCnt
本轮平均请求大小   = ΔhhIoReqBytes / ΔhhIoReqCnt
本轮下刷写放大     = ΔhhWriteUfsBytes / ΔhhWriteRawBytes
本轮预取命中率     = ΔhhPfHitObjs / (ΔhhPfHitObjs + ΔhhPfNeverUsedObjs)
本轮预取浪费比例   = ΔhhPfNeverUsedObjs / ΔhhPfObjs
本轮搬回内存       = ΔhhMovedInBytes
本轮在飞峰值       = max(hhInflightPagesPeak)   ← 取轮内最大值
```

**注意**：累计计数器在实验前未必归零，若上一轮刚跑完会带着余量。此时应取"本轮第 2 个采样点 → 最后一个点"的差值，或每轮之间重启设备。

### 8.5 四种 swap 方案下应该看到什么

| 方案 | zram0 是否作 swap | hh 列的预期 |
|---|---|---|
| `no_swap` | 否（swapoff） | 全部为 0 |
| `only_zram` | 是 | **有数据**（hyperhold 只在这条路径工作） |
| `flash_swap` | 否（改用文件 swap） | 全部为 0 |
| `zswap` | 否 | 全部为 0 |

**这条对照本身就是正确性验证**：非 `only_zram` 的组若出现非 0，说明埋点或设备状态有问题。

### 8.6 现成的分析脚本

```python
# 用法: python analyze_hh.py logs/<目录>/sample.csv
import csv, sys

rows = list(csv.DictReader(open(sys.argv[1], encoding="utf-8")))
f, l = rows[0], rows[-1]

def n(r, k):
    try:
        return int(r[k])
    except (KeyError, ValueError):
        return 0

def d(k):
    return n(l, k) - n(f, k)

io_cnt, io_bytes = d("hhIoReqCnt"), d("hhIoReqBytes")
read_in = d("hhReadInBytes")
w_raw, w_ufs = d("hhWriteRawBytes"), d("hhWriteUfsBytes")
pf, hit, miss = d("hhPfObjs"), d("hhPfHitObjs"), d("hhPfNeverUsedObjs")
peak = max(n(r, "hhInflightPagesPeak") for r in rows)
cache_peak = max(n(r, "hhCachePagesPeak") for r in rows)

print(f"采样点数            : {len(rows)}")
print(f"按需回抬            : {io_cnt} 次, {io_bytes} 字节, 平均 {io_bytes / max(io_cnt,1):.0f} 字节/次")
print(f"回抬读入            : {read_in} 字节 = {read_in / 32768:.0f} 个 32KB extent")
print(f"下刷写入 UFS        : {w_ufs} 字节 = {w_ufs / 32768:.0f} 个 32KB extent")
print(f"下刷收集压缩字节    : {w_raw} 字节")
print(f"写放大              : {w_ufs / max(w_raw,1):.3f}")
print(f"预取对象            : {pf}（命中 {hit}, 未用 {miss}, 未结算 {pf-hit-miss}）")
print(f"预取命中率(已结算)  : {100 * hit / max(hit + miss,1):.1f}%")
print(f"预取命中率(占全部)  : {100 * hit / max(pf,1):.1f}%")
print(f"搬回 zram           : {d('hhMovedInBytes')} 字节 / {d('hhMovedInPages')} 个对象")
print(f"在飞缓冲峰值        : {peak} 页 = {peak * 4 / 1024:.1f} MB")
print(f"cache 常驻峰值      : {cache_peak} 页")
```
