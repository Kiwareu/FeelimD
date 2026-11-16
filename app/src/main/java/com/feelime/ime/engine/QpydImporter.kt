package com.feelime.ime.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream

/**
 * QQ 拼音 .qpyd 导入解析器（3.74.11：用户要直接导入网上下载的
 * QQ 分类词库，不借助外部工具）。
 *
 * 格式（公开逆向格式；解析逻辑以 studyzy/imewlconverter 的
 * QQPinyinQpydImporter.cs 为准，GPL-3.0，并用 imewlconverter
 * 仓库自带的真实样本验证：成语.qpyd 1657 条，词条与拼音均真实
 * 有效）：
 * - 压缩数据起始地址 int32 LE @0x38；该处起是 zlib 流（与
 *   java.util.zip.InflaterInputStream 同款 RFC 1950）。
 * - 解压后的索引区：每条索引 10 字节（[0]=拼音字节长，
 *   [1]=词字节长，[6..9]=拼音起始地址 int32 LE；词起始地址 =
 *   拼音起始 + 拼音字节长）。首条索引的拼音起始地址即数据区起点；
 *   索引区到数据区起点为止。
 * - 拼音：UTF-8，音节间以 ' 分隔；词：UTF-16LE。
 * - 拼音归一：音节拼合后小写、去声调数字、ü→v，只留 [a-z]；
 *   须合 DictYamlImporter 的码规则（^[a-z]{1,48}$）。
 *
 * 产出与 DictYamlImporter 同款 Result，进 CustomPhraseStore 的
 * imported 段。结构损坏（起始地址离谱、zlib 解压失败、索引越界）
 * 抛 [QpydFormatException]，调用方报 DICT_QPYD_INVALID。解压后
 * 设 64MB 上限，防压缩炸弹。
 */
object QpydImporter {
    private const val DATA_START_OFFSET = 0x38
    private const val MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024
    private const val MAX_WORD_CHARS = 32
    private const val MAX_CODE_CHARS = 48
    private val CODE = Regex("^[a-z]{1,$MAX_CODE_CHARS}$")

    class QpydFormatException(message: String) : IllegalArgumentException(message)

    fun parseBytes(
        bytes: ByteArray,
        existing: Set<Pair<String, String>> = emptySet(),
    ): DictYamlImporter.Result {
        if (bytes.size < DATA_START_OFFSET + 4) {
            throw QpydFormatException("truncated header")
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val start = buf.getInt(DATA_START_OFFSET)
        if (start < 0 || start >= bytes.size) {
            throw QpydFormatException("implausible compressed data offset: $start")
        }
        val raw = runCatching { inflate(bytes, start) }.getOrElse {
            throw QpydFormatException("zlib decompress failed: ${it.message}")
        }
        val rawBuf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val entries = ArrayList<Triple<String, String, Int>>()
        var skipped = 0
        var dictStart = -1
        var idx = 0
        while (dictStart == -1 || idx < dictStart) {
            if (idx + 10 > raw.size) throw QpydFormatException("truncated index entry")
            val pyLen = raw[idx].toInt() and 0xFF
            val wordLen = raw[idx + 1].toInt() and 0xFF
            val pyStart = rawBuf.getInt(idx + 6)
            val wordStart = pyStart + pyLen
            if (dictStart == -1) {
                if (pyStart < 0 || pyStart > raw.size) {
                    throw QpydFormatException("implausible pinyin area offset: $pyStart")
                }
                dictStart = pyStart
            }
            if (pyStart < 0 || pyStart + pyLen > raw.size ||
                wordStart < 0 || wordStart + wordLen > raw.size
            ) {
                throw QpydFormatException("entry out of bounds")
            }
            val pinyin = String(raw, pyStart, pyLen, Charsets.UTF_8)
            val word = String(raw, wordStart, wordLen, Charsets.UTF_16LE)
            if (pinyin.isNotEmpty()) {
                val code = normalizeCode(
                    pinyin.split('\'').filter { it.isNotEmpty() }.joinToString(""),
                )
                if (word.codePointCount(0, word.length) > MAX_WORD_CHARS ||
                    !CODE.matches(code)
                ) {
                    skipped++
                } else {
                    entries.add(Triple(word, code, 0))
                }
            } else {
                skipped++
            }
            idx += 10
        }
        return DictYamlImporter.finishBinaryImport(
            entries, existing, sortByFreq = false, skipped = skipped,
        )
    }

    /** zlib 解压（RFC 1950；超 64MB 拒收，防压缩炸弹）。 */
    private fun inflate(bytes: ByteArray, start: Int): ByteArray {
        val out = ByteArrayOutputStream()
        InflaterInputStream(ByteArrayInputStream(bytes, start, bytes.size - start)).use { inf ->
            val chunk = ByteArray(8192)
            while (true) {
                val n = inf.read(chunk)
                if (n < 0) break
                if (out.size() + n > MAX_DECOMPRESSED_BYTES) {
                    throw QpydFormatException("decompressed data too large")
                }
                out.write(chunk, 0, n)
            }
        }
        return out.toByteArray()
    }

    /** 音节拼合 → 码：小写、去声调数字、ü→v，只留 [a-z]（与 ScelImporter 同款归一）。 */
    private fun normalizeCode(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch in 'a'..'z' -> out.append(ch)
                ch in 'A'..'Z' -> out.append(ch.lowercaseChar())
                ch == 'ü' || ch == 'Ü' -> out.append('v')
                // 声调数字 1-5 与分隔符（空格/撇号）直接丢掉。
                else -> Unit
            }
        }
        return out.toString()
    }
}
