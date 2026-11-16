package com.feelime.ime.engine

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 搜狗细胞词库 .scel 导入解析器（3.74.11，issue：用户想从网上下载
 * 别人分享的 .scel 词库直接导进来）。
 *
 * 格式（公开逆向格式，多方实现收敛；解析逻辑以 rime 的
 * tools/scel2dict.py 为准，GPL-3.0，并用一份真实 .scel（搜狗官方
 * 「网络流行新词」，8971 词）实测验证：解析词数与头部 CountWord
 * 精确一致，词条与拼音均真实有效）：
 * - 头部：dict_len（词组数，uint32 LE）@0x120；count_word（总词数，
 *   uint32 LE）@0x124；Name @0x130（64B UTF-16LE）。
 * - 拼音表 @0x1540：uint32 条数，逐条（idx:u16, size:u16,
 *   拼音:size 字节 UTF-16LE）。索引超出表长时按 rime 的回退：
 *   单个小写字母 chr(key - 表长 + 'a')。
 * - 词组（dict_len 组）：（same:u16 同音词数，count:u16 拼音索引
 *   表字节长，拼音索引表:count 字节，逐词（word_len:u16,
 *   词:word_len 字节 UTF-16LE, ext_len:u16, ext:ext_len 字节，
 *   前 2 字节为词频 u16））。
 * - 拼音归一：音节拼合后小写、去声调数字、ü→v，只留 [a-z]；
 *   须合 DictYamlImporter 的码规则（^[a-z]{1,48}$）。
 *
 * 产出与 DictYamlImporter 同款 Result（词，码）对，进 CustomPhraseStore
 * 的 imported 段——与 .dict.yaml 同一重载链路。词频只用于排序：
 * 词按词频降序排，超上限（5000）时留最常用的。结构损坏（魔数不对、
 * 计数离谱、文件截断）抛 [ScelFormatException]，调用方报
 * DICT_SCEL_INVALID，不当成普通读失败。
 */
object ScelImporter {
    /** .scel 魔数（文件头 4 字节）。 */
    private val MAGIC = byteArrayOf(0x40, 0x15, 0x00, 0x00)
    private const val HEADER_DICT_LEN_OFFSET = 0x120
    private const val PY_TABLE_OFFSET = 0x1540
    private const val MAX_PY_ENTRIES = 10000
    private const val MAX_GROUPS = 1_000_000
    private const val MAX_WORD_CHARS = 32
    private const val MAX_CODE_CHARS = 48
    private const val MAX_EXT_LEN = 64
    private val CODE = Regex("^[a-z]{1,$MAX_CODE_CHARS}$")

    class ScelFormatException(message: String) : IllegalArgumentException(message)

    fun isScel(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size) return false
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return false
        return true
    }

    fun parse(
        input: InputStream,
        existing: Set<Pair<String, String>> = emptySet(),
    ): DictYamlImporter.Result {
        val bytes = input.readBytes()
        return parseBytes(bytes, existing)
    }

    fun parseBytes(
        bytes: ByteArray,
        existing: Set<Pair<String, String>> = emptySet(),
    ): DictYamlImporter.Result {
        if (!isScel(bytes)) throw ScelFormatException("not a .scel file (bad magic)")
        if (bytes.size < PY_TABLE_OFFSET + 4) {
            throw ScelFormatException("truncated header")
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dictLen = buf.getInt(HEADER_DICT_LEN_OFFSET)
        if (dictLen < 0 || dictLen > MAX_GROUPS) {
            throw ScelFormatException("implausible group count: $dictLen")
        }
        var pos = PY_TABLE_OFFSET
        val pyCount = buf.getInt(pos)
        pos += 4
        if (pyCount <= 0 || pyCount > MAX_PY_ENTRIES) {
            throw ScelFormatException("implausible pinyin table size: $pyCount")
        }
        val pyTable = HashMap<Int, String>(pyCount)
        repeat(pyCount) {
            val idx = readU16(buf, pos)
            pos += 2
            val size = readU16(buf, pos)
            pos += 2
            if (size < 0 || size > 64 || pos + size > bytes.size) {
                throw ScelFormatException("truncated pinyin table")
            }
            val syllable = String(bytes, pos, size, Charsets.UTF_16LE)
            pos += size
            pyTable[idx] = syllable
        }
        val entries = ArrayList<Triple<String, String, Int>>()
        var skipped = 0
        repeat(dictLen) {
            if (pos + 4 > bytes.size) throw ScelFormatException("truncated word group")
            val same = readU16(buf, pos)
            val pyLen = readU16(buf, pos + 2)
            pos += 4
            if (same < 0 || same > 1000 || pyLen < 0 || pyLen > 512 || pos + pyLen > bytes.size) {
                throw ScelFormatException("truncated pinyin index list")
            }
            val code = buildString {
                var i = 0
                while (i + 1 < pyLen) {
                    val key = readU16(buf, pos + i)
                    i += 2
                    val syllable = if (key < pyTable.size) {
                        pyTable[key]
                    } else {
                        // rime 回退：表外索引是单个 ASCII 字母。
                        (key - pyTable.size + 'a'.code).toChar().toString()
                    }
                    if (syllable != null) append(syllable)
                }
            }.let { normalizeCode(it) }
            pos += pyLen
            repeat(same) {
                if (pos + 2 > bytes.size) throw ScelFormatException("truncated word")
                val wordLen = readU16(buf, pos)
                pos += 2
                // 字节长只作防损坏的宽松上限（带 surrogate 对的词会超
                // 64 字节）；真正的 32 字限制由下面的 codePointCount 把关。
                if (wordLen <= 0 || wordLen > 256 || pos + wordLen > bytes.size) {
                    throw ScelFormatException("truncated word text")
                }
                val word = String(bytes, pos, wordLen, Charsets.UTF_16LE)
                pos += wordLen
                if (pos + 2 > bytes.size) throw ScelFormatException("truncated ext")
                val extLen = readU16(buf, pos)
                pos += 2
                if (extLen < 0 || extLen > MAX_EXT_LEN || pos + extLen > bytes.size) {
                    throw ScelFormatException("truncated ext block")
                }
                val freq = if (extLen >= 2) readU16(buf, pos) else 0
                pos += extLen
                if (word.codePointCount(0, word.length) > MAX_WORD_CHARS ||
                    !CODE.matches(code)
                ) {
                    skipped++
                } else {
                    entries.add(Triple(word, code, freq))
                }
            }
        }
        // 词频降序：超上限时留最常用的；同频保持文件序（稳定排序）。
        val seen = LinkedHashMap<Pair<String, String>, Unit>()
        var truncated = 0
        for ((word, code, _) in entries.sortedByDescending { it.third }) {
            val entry = word to code
            if (entry in existing || entry in seen) continue
            if (seen.size >= DictYamlImporter.MAX_ENTRIES) {
                truncated++
                continue
            }
            seen[entry] = Unit
        }
        return DictYamlImporter.Result(
            items = seen.keys.toList(),
            ranks = emptyMap(),
            rankDeclared = false,
            skipped = skipped,
            truncated = truncated,
        )
    }

    /** 音节拼合 → 码：小写、去声调数字、ü→v，只留 [a-z]。 */
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

    private fun readU16(buf: ByteBuffer, pos: Int): Int {
        return buf.getShort(pos).toInt() and 0xFFFF
    }
}
