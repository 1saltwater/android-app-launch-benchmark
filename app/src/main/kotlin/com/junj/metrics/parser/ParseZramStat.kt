package com.junj.metrics.parser

data class ZramMmStatResult(
    val origDataSize: Long, //
    val comprDataSize: Long,
    val memUsedTotal: Long,
    val memUsedMax: Long,
)

data class ZramBdStatResult(
    val bdCount: Long,
    val bdReads: Long,
    val bdWrites: Long,
)

fun parseZramMmStatResult(output: String): ZramMmStatResult? {
    val values = output
        .lineSequence()
        .firstOrNull(){ it.isNotEmpty() }
        ?.trim()
        ?.split(Regex("""\s+"""))
        ?: return null

    if (values.size < 9)
        return null

    // 拿到的不是数字（比如设备端返回了一行报错）时返回 null，
    // 不要让一个异常把整个采样线程干掉、导致一个样本都写不出来。
    return runCatching {
        ZramMmStatResult(
            origDataSize = values[0].toLong(),
            comprDataSize = values[1].toLong(),
            memUsedTotal = values[2].toLong(),
            memUsedMax = values[4].toLong()
        )
    }.getOrNull()
}

fun parseZramBdStatResult(output: String): ZramBdStatResult? {
    val values = output
        .lineSequence()
        .firstOrNull(){ it.isNotEmpty() }
        ?.trim()
        ?.split(Regex("""\s+"""))
        ?: return null
    if (values.size < 3)
        return null

    return runCatching {
        ZramBdStatResult(
            bdCount = values[0].toLong(),
            bdReads = values[1].toLong(),
            bdWrites = values[2].toLong(),
        )
    }.getOrNull()
}

data class HyperholdStatResult(
    val ioReqCnt: Long,
    val ioReqBytes: Long,
    val readInBytes: Long,
    val writeRawBytes: Long,
    val writeUfsBytes: Long,
    val faultoutFaultCnt: Long,
    val pfObjs: Long,
    val pfHitObjs: Long,
    val pfNeverUsedObjs: Long,
    val pfNeverUsedBytes: Long,
    val inflightPages: Long,
    val inflightPagesPeak: Long,
    val movedInBytes: Long,
    val movedInPages: Long,
    val cachePages: Long,
    val cachePagesPeak: Long,
)

/**
 * 解析 /sys/block/zram0/hyperhold_report 中追加的统计段。
 *
 * 段以 "----- hyperhold io stat -----" 开头，之后每行形如 "key: value"。
 * 段之前还有 hyperhold 原有的 fault 记录（也含 "index:" 之类），所以必须先定位到标记行再解析。
 * 设备未刷入统计内核时整段不存在 —— 返回 null，不要抛异常。
 */
fun parseHyperholdReport(output: String): HyperholdStatResult? {
    val fields = mutableMapOf<String, Long>()
    var inSection = false

    output.lineSequence().forEach { line ->
        val t = line.trim()
        if (t.contains("hyperhold io stat")) {
            inSection = true
            return@forEach
        }
        if (!inSection) return@forEach
        val idx = t.indexOf(':')
        if (idx <= 0) return@forEach
        val key = t.substring(0, idx).trim()
        val value = t.substring(idx + 1).trim().toLongOrNull() ?: return@forEach
        fields[key] = value
    }

    if (fields.isEmpty()) return null
    fun v(k: String) = fields[k] ?: 0L

    return HyperholdStatResult(
        ioReqCnt = v("io_req_cnt"),
        ioReqBytes = v("io_req_bytes"),
        readInBytes = v("read_in_bytes"),
        writeRawBytes = v("write_raw_bytes"),
        writeUfsBytes = v("write_ufs_bytes"),
        faultoutFaultCnt = v("faultout_fault_cnt"),
        pfObjs = v("pf_objs"),
        pfHitObjs = v("pf_hit_objs"),
        pfNeverUsedObjs = v("pf_never_used_objs"),
        pfNeverUsedBytes = v("pf_never_used_bytes"),
        inflightPages = v("inflight_pages"),
        inflightPagesPeak = v("inflight_pages_peak"),
        movedInBytes = v("moved_in_bytes"),
        movedInPages = v("moved_in_pages"),
        cachePages = v("cache_pages"),
        cachePagesPeak = v("cache_pages_peak"),
    )
}
