# 用法: python analyze_hh.py logs/<目录>/sample.csv
#       python analyze_hh.py logs/<目录>            （也接受目录）
#
# 分析 hyperhold 统计 + swap 延迟，结果追加写入 sample.csv 同目录下的 summary.txt
# （不覆盖 gradle 写的内容）
#
# 说明:
#   - hh* 是累计计数器 —— 用「末行 - 首行」得到本轮增量
#   - sl* 是累积统计量（内核 reset 之后累计）——
#     count/sampled 可取差值，mean/p50/p90/p99/max 直接取末行（不可相加）

import csv
import os
import sys

csv_path = sys.argv[1]
if os.path.isdir(csv_path):
    csv_path = os.path.join(csv_path, "sample.csv")

rows = list(csv.DictReader(open(csv_path, encoding="utf-8")))
f, l = rows[0], rows[-1]

# 输出目标：与 sample.csv 同目录的 summary.txt
summary_path = os.path.join(os.path.dirname(os.path.abspath(csv_path)), "summary.txt")
out_file = open(summary_path, "a", encoding="utf-8")


def out(text=""):
    out_file.write(text + "\n")
    out_file.flush()
    print(text)


def n(r, k):
    try:
        return int(float(r[k]))
    except (KeyError, ValueError, TypeError):
        return 0


def d(k):
    return n(l, k) - n(f, k)


# ===================== 1. hyperhold 统计 =====================
io_cnt, io_bytes = d("hhIoReqCnt"), d("hhIoReqBytes")
read_in = d("hhReadInBytes")
w_raw, w_ufs = d("hhWriteRawBytes"), d("hhWriteUfsBytes")
pf, hit, miss = d("hhPfObjs"), d("hhPfHitObjs"), d("hhPfNeverUsedObjs")
peak = max(n(r, "hhInflightPagesPeak") for r in rows)
cache_peak = max(n(r, "hhCachePagesPeak") for r in rows)

out("")
out("===== hyperhold 统计（analyze_hh.py 追加） =====")
out("写入目标文件        : " + summary_path)
out("采样点数            : %d" % len(rows))
out("按需回抬            : %d 次, %d 字节, 平均 %.0f 字节/次" % (io_cnt, io_bytes, io_bytes / max(io_cnt, 1)))
out("回抬读入            : %d 字节 = %.0f 个 32KB extent" % (read_in, read_in / 32768))
out("下刷写入 UFS        : %d 字节 = %.0f 个 32KB extent" % (w_ufs, w_ufs / 32768))
out("下刷收集压缩字节    : %d 字节" % w_raw)
out("写放大              : %.3f" % (w_ufs / max(w_raw, 1)))
out("预取对象            : %d（命中 %d, 未用 %d, 未结算 %d）" % (pf, hit, miss, pf - hit - miss))
out("预取命中率(已结算)  : %.1f%%" % (100.0 * hit / max(hit + miss, 1)))
out("预取命中率(占全部)  : %.1f%%" % (100.0 * hit / max(pf, 1)))
out("搬回 zram           : %d 字节 / %d 个对象" % (d("hhMovedInBytes"), d("hhMovedInPages")))
out("在飞缓冲峰值        : %d 页 = %.1f MB" % (peak, peak * 4 / 1024.0))
out("cache 常驻峰值      : %d 页" % cache_peak)


# ===================== 2. swap-in 延迟（MAJOR fault） =====================
if "slInCount" in l:
    out("")
    out("===== swap-in 延迟（analyze_hh.py 追加） =====")
    out("说明: 只统计 MAJOR fault（真的从设备读页）；内核累积统计量，取本轮末次采样")
    out("统计开关            : %s   采样率: 1/%d"
        % ("on" if n(l, "slMode") else "off", n(l, "slSampleRate") or 1))

    cnt, samp = n(l, "slInCount"), n(l, "slInSampled")
    p50, p99 = n(l, "slInP50Ns"), n(l, "slInP99Ns")

    if cnt == 0 and samp == 0:
        out("--- swap_in_major --- 无数据（统计未开启，或本轮没有 MAJOR fault）")
    else:
        out("--- swap_in_major（真的从设备读页的缺页）---")
        out("  事件总数          : %d  [本轮新增 %d]" % (cnt, cnt - n(f, "slInCount")))
        out("  采样数            : %d  [本轮新增 %d]" % (samp, samp - n(f, "slInSampled")))
        out("  平均延迟          : %d ns = %.2f us" % (n(l, "slInMeanNs"), n(l, "slInMeanNs") / 1000.0))
        out("  P50  延迟         : %d ns = %.2f us" % (p50, p50 / 1000.0))
        out("  P90  延迟         : %d ns = %.2f us" % (n(l, "slInP90Ns"), n(l, "slInP90Ns") / 1000.0))
        out("  P99  延迟         : %d ns = %.2f us" % (p99, p99 / 1000.0))
        out("  最大延迟          : %d ns = %.2f ms" % (n(l, "slInMaxNs"), n(l, "slInMaxNs") / 1000000.0))
        if p50:
            out("  P99/P50 尾延迟比  : %.1fx" % (p99 / float(p50)))
        if samp and samp < 100000:
            out("  提示: 采样数 %d 偏少，P99 及以上抖动较大（可把 rate 调成 1 重跑）" % samp)

out_file.close()
