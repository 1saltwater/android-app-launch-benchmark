package com.junj.metrics

import com.junj.device.adb.AdbExecutor
import com.junj.device.adb.ProcessAdbExecutor
import com.junj.domain.metrics.SamplingItem
import com.junj.metrics.parser.CommandType
import com.junj.metrics.parser.ResultField
import com.junj.metrics.parser.parseHyperholdReport
import com.junj.metrics.parser.parseVmstat
import com.junj.metrics.parser.parseZramBdStatResult
import com.junj.metrics.parser.parseZramMmStatResult
import com.junj.metrics.parser.regexFindField
import com.junj.output.logging.CommandDiagnostics
//新增
import com.junj.metrics.parser.parseSwapLatencySummary

class MetricsCollector(
    private val adb: AdbExecutor = ProcessAdbExecutor,
) {
    fun collect(sampleStamp: Int): SamplingItem {
        val memInfo = adb.shell("cat /proc/meminfo")
        val pressureMemory = adb.rootShell("cat /proc/pressure/memory")

        val vmstatResult = adb.rootShell("vmstat")
        val vmstat = parseVmstat(vmstatResult.output)
        if (vmstat == null) {
            // vmstat 跑成功了但解析不出数字：多半是这台机器的 vmstat 列数/表头和解析规则不一致。
            CommandDiagnostics.record("vmstat/unparsed", "vmstat", vmstatResult.output)
        }

        val thermal = adb.rootShell("dumpsys thermalservice")
        if (regexFindField(CommandType.DUMPSYS_THERMALSERVICE, ResultField.BIG_TEMP, thermal.output) == null) {
            // 温度一项都解析不到：多半是这台 ROM 的 dumpsys 输出格式（或温区名字）不一样。
            CommandDiagnostics.record(
                "thermalservice/no-temps",
                "dumpsys thermalservice",
                keepFromHalSection(thermal.output),
            )
        }

        val zramMmStat = adb.rootShell("cat /sys/block/zram0/mm_stat")
        val zramMm = parseZramMmStatResult(zramMmStat.output)
        if (zramMm == null) {
            CommandDiagnostics.record("zram-mm_stat/unparsed", "cat /sys/block/zram0/mm_stat", zramMmStat.output)
        }

        val zramBd = parseZramBdStatResult(adb.rootShell("cat /sys/block/zram0/bd_stat").output)

        val hyperholdReport = adb.rootShell("cat /sys/block/zram0/hyperhold_report")
        val hh = parseHyperholdReport(hyperholdReport.output)
        if (hh == null) {
            CommandDiagnostics.record(
                "hyperhold/unparsed",
                "cat /sys/block/zram0/hyperhold_report",
                hyperholdReport.output,
            )

        }
        //swap延迟
        val swapLatency = parseSwapLatencySummary(
            adb.rootShell("cat /sys/kernel/mm/swap_latency/summary").output
        )

        return SamplingItem(
            sampleStamp = sampleStamp,

            meminfoMemTotal = regexFindField(CommandType.CAT_PROC_MEMINFO, ResultField.MEM_TOTAL, memInfo.output)?.toLongOrNull() ?: 0,
            meminfoMemFree = regexFindField(CommandType.CAT_PROC_MEMINFO, ResultField.MEM_FREE, memInfo.output)?.toLongOrNull() ?: 0,
            meminfoMemAvailable = regexFindField(CommandType.CAT_PROC_MEMINFO, ResultField.MEM_AVAILABLE, memInfo.output)?.toLongOrNull() ?: 0,
            meminfoSwapTotal = regexFindField(CommandType.CAT_PROC_MEMINFO, ResultField.SWAP_TOTAL, memInfo.output)?.toLongOrNull() ?: 0,
            meminfoSwapFree = regexFindField(CommandType.CAT_PROC_MEMINFO, ResultField.SWAP_FREE, memInfo.output)?.toLongOrNull() ?: 0,
            meminfoSwapCached = regexFindField(CommandType.CAT_PROC_MEMINFO, ResultField.SWAP_CACHED, memInfo.output)?.toLongOrNull() ?: 0,

            psiSome = regexFindField(CommandType.CAT_PROC_PRESSURE_MEMORY, ResultField.PSI_SOME_TOTAL, pressureMemory.output)?.toLongOrNull() ?: 0,
            psiFull = regexFindField(CommandType.CAT_PROC_PRESSURE_MEMORY, ResultField.PSI_FULL_TOTAL, pressureMemory.output)?.toLongOrNull() ?: 0,

            vmstatR = vmstat?.vmstatR ?: 0,
            vmstatB = vmstat?.vmstatB ?: 0,
            vmstatSwpd = vmstat?.vmstatSwpd ?: 0L,
            vmstatFree = vmstat?.vmstatFree ?: 0L,
            vmstatBuff = vmstat?.vmstatBuff ?: 0L,
            vmstatCache = vmstat?.vmstatCache ?: 0L,
            vmstatSi = vmstat?.vmstatSi ?: 0L,
            vmstatSo = vmstat?.vmstatSo ?: 0L,
            vmstatBi = vmstat?.vmstatBi ?: 0L,
            vmstatBo = vmstat?.vmstatBo ?: 0L,
            vmstatIn = vmstat?.vmstatIn ?: 0L,
            vmstatCs = vmstat?.vmstatCs ?: 0L,
            vmstatUs = vmstat?.vmstatUs ?: 0,
            vmstatSy = vmstat?.vmstatSy ?: 0,
            vmstatId = vmstat?.vmstatId ?: 0,
            vmstatWa = vmstat?.vmstatWa ?: 0,

            bigTemp = regexFindField(CommandType.DUMPSYS_THERMALSERVICE, ResultField.BIG_TEMP, thermal.output)?.toDoubleOrNull() ?: 0.0,
            midTemp = regexFindField(CommandType.DUMPSYS_THERMALSERVICE, ResultField.MID_TEMP, thermal.output)?.toDoubleOrNull() ?: 0.0,
            littleTemp = regexFindField(CommandType.DUMPSYS_THERMALSERVICE, ResultField.LITTLE_TEMP, thermal.output)?.toDoubleOrNull() ?: 0.0,
            socThermTemp = regexFindField(CommandType.DUMPSYS_THERMALSERVICE, ResultField.SOC_THERM_TEMP, thermal.output)?.toDoubleOrNull() ?: 0.0,
            virtualSkinTemp = regexFindField(CommandType.DUMPSYS_THERMALSERVICE, ResultField.VIRTUAL_SKIN_TEMP, thermal.output)?.toDoubleOrNull() ?: 0.0,

            zramOrigDataSize = zramMm?.origDataSize ?: 0,
            zramComprDataSize = zramMm?.comprDataSize ?: 0,
            zramMemUsedTotal = zramMm?.memUsedTotal ?: 0,
            zramMemUsedMax = zramMm?.memUsedMax ?: 0,
            zramBdCount = zramBd?.bdCount ?: 0,
            zramBdReads = zramBd?.bdReads ?: 0,
            zramBdWrites = zramBd?.bdWrites ?: 0,

            zswapStoredPages = readRootLong("cat /sys/kernel/debug/zswap/stored_pages"),
            zswapPoolTotalSize = readRootLong("cat /sys/kernel/debug/zswap/pool_total_size"),
            zswapPoolLimitHit = readRootLong("cat /sys/kernel/debug/zswap/pool_limit_hit"),
            zswapWrittenBackPages = readRootLong("cat /sys/kernel/debug/zswap/written_back_pages"),
            
            //hyperhold统计
            hhIoReqCnt = hh?.ioReqCnt ?: 0,
            hhIoReqBytes = hh?.ioReqBytes ?: 0,
            hhReadInBytes = hh?.readInBytes ?: 0,
            hhWriteRawBytes = hh?.writeRawBytes ?: 0,
            hhWriteUfsBytes = hh?.writeUfsBytes ?: 0,
            hhFaultoutFaultCnt = hh?.faultoutFaultCnt ?: 0,
            hhPfObjs = hh?.pfObjs ?: 0,
            hhPfHitObjs = hh?.pfHitObjs ?: 0,
            hhPfNeverUsedObjs = hh?.pfNeverUsedObjs ?: 0,
            hhPfNeverUsedBytes = hh?.pfNeverUsedBytes ?: 0,
            hhInflightPages = hh?.inflightPages ?: 0,
            hhInflightPagesPeak = hh?.inflightPagesPeak ?: 0,
            hhMovedInBytes = hh?.movedInBytes ?: 0,
            hhMovedInPages = hh?.movedInPages ?: 0,
            hhCachePages = hh?.cachePages ?: 0,
            hhCachePagesPeak = hh?.cachePagesPeak ?: 0,

            //swap延迟统计
            slMode = swapLatency?.mode ?: 0,
            slSampleRate = swapLatency?.sampleRate ?: 0,
            slInCount = swapLatency?.inCount ?: 0,
            slInSampled = swapLatency?.inSampled ?: 0,
            slInMeanNs = swapLatency?.inMeanNs ?: 0,
            slInP50Ns = swapLatency?.inP50Ns ?: 0,
            slInP90Ns = swapLatency?.inP90Ns ?: 0,
            slInP99Ns = swapLatency?.inP99Ns ?: 0,
            slInMaxNs = swapLatency?.inMaxNs ?: 0,
        )
    }

    private fun readRootLong(command: String): Long =
        adb.rootShell(command).output.trim().toLongOrNull() ?: 0L

    /**
     * 记录 thermalservice 原始输出时，从 "Current temperatures" 这段开始截，
     * 免得前面一大堆无关内容把真正需要看的温度段挤出记录范围。
     */
    private fun keepFromHalSection(output: String): String {
        val index = output.indexOf("Current temperatures")
        return if (index >= 0) output.substring(index) else output
    }
}
