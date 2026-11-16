package com.feelime.ime.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 百度拼音 .bdict 导入解析器（3.74.11：用户要直接导入网上下载的
 * 百度词库，不借助外部工具）。
 *
 * 格式（公开逆向格式；解析逻辑以 studyzy/imewlconverter 的
 * BaiduBdictImporter.cs 为准，GPL-3.0，并用 imewlconverter 仓库
 * 自带的真实样本验证：travel.bdict 302 条、百度官网.bdict 9 条，
 * 与其测试期望精确一致）：
 * - 魔数 "biptbdsw"（文件头 8 字节 ASCII）。
 * - 词条区结束位置：int32 LE @0x60（新版头部）；旧版该字段为 0，
 *   此时用 0x44 处的词条区长度推算（0x350 + len）。
 * - 词条从 0x350 起，逐条（词长 len:int32；len 组声母/韵母索引
 *   各 1 字节；词：2*len 字节 UTF-16LE）。声母表 24 项、韵母表
 *   33 项（空声母 "" 对应零声母音节；韵母 "v" 即 ü）。
 * - 码 = 声韵拼合（小写字母）；整词无有效拼音则跳过。无词频：
 *   保持文件序。
 *
 * 产出与 DictYamlImporter 同款 Result，进 CustomPhraseStore 的
 * imported 段。结构损坏（魔数不对、结束位置离谱、文件截断）抛
 * [BdictFormatException]，调用方报 DICT_BDICT_INVALID。
 */
object BdictImporter {
    private val MAGIC = "biptbdsw".toByteArray(Charsets.US_ASCII)
    private const val WORDS_OFFSET = 0x350
    private const val MAX_WORD_LEN = 1000
    private const val MAX_WORD_CHARS = 32
    private const val MAX_CODE_CHARS = 48
    private val CODE = Regex("^[a-z]{1,$MAX_CODE_CHARS}$")

    private val SHENGMU = arrayOf(
        "c", "d", "b", "f", "g", "h", "ch", "j", "k", "l",
        "m", "n", "", "p", "q", "r", "s", "t", "sh", "zh",
        "w", "x", "y", "z",
    )
    private val YUNMU = arrayOf(
        "uang", "iang", "iong", "ang", "eng", "ian", "iao", "ing", "ong", "uai",
        "uan", "ai", "an", "ao", "ei", "en", "er", "ua", "ie", "in",
        "iu", "ou", "ia", "ue", "ui", "un", "uo", "a", "e", "i",
        "o", "u", "v",
    )

    class BdictFormatException(message: String) : IllegalArgumentException(message)

    fun isBdict(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size) return false
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return false
        return true
    }

    fun parseBytes(
        bytes: ByteArray,
        existing: Set<Pair<String, String>> = emptySet(),
    ): DictYamlImporter.Result {
        if (!isBdict(bytes)) throw BdictFormatException("not a .bdict file (bad magic)")
        if (bytes.size < WORDS_OFFSET + 4) {
            throw BdictFormatException("truncated header")
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var end = buf.getInt(0x60)
        if (end <= 0) end = WORDS_OFFSET + buf.getInt(0x44)
        if (end <= WORDS_OFFSET || end > bytes.size) {
            throw BdictFormatException("implausible word section end: $end")
        }
        val entries = ArrayList<Triple<String, String, Int>>()
        var skipped = 0
        var pos = WORDS_OFFSET
        while (pos < end) {
            if (pos + 4 > end) throw BdictFormatException("truncated word length")
            val len = buf.getInt(pos)
            pos += 4
            if (len == 0) break
            if (len < 0 || len > MAX_WORD_LEN) {
                throw BdictFormatException("implausible word length: $len")
            }
            if (pos + 2 * len > end) throw BdictFormatException("truncated pinyin indices")
            val code = buildString {
                for (i in 0 until len) {
                    val sm = bytes[pos + 2 * i].toInt() and 0xFF
                    val ym = bytes[pos + 2 * i + 1].toInt() and 0xFF
                    append(
                        if (sm < SHENGMU.size && ym < YUNMU.size) SHENGMU[sm] + YUNMU[ym] else "",
                    )
                }
            }.let { normalizeCode(it) }
            pos += 2 * len
            if (pos + 2 * len > end) throw BdictFormatException("truncated word text")
            val word = String(bytes, pos, 2 * len, Charsets.UTF_16LE)
            pos += 2 * len
            if (word.codePointCount(0, word.length) > MAX_WORD_CHARS || !CODE.matches(code)) {
                skipped++
            } else {
                entries.add(Triple(word, code, 0))
            }
        }
        return DictYamlImporter.finishBinaryImport(
            entries, existing, sortByFreq = false, skipped = skipped,
        )
    }

    /** 声韵拼合 → 码：小写、ü→v，只留 [a-z]（与 ScelImporter 同款归一）。 */
    private fun normalizeCode(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch in 'a'..'z' -> out.append(ch)
                ch in 'A'..'Z' -> out.append(ch.lowercaseChar())
                ch == 'ü' || ch == 'Ü' -> out.append('v')
                else -> Unit
            }
        }
        return out.toString()
    }
}
