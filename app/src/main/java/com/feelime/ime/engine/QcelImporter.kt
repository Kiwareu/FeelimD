package com.feelime.ime.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * QQ 拼音 .qcel 导入解析器（3.74.11：用户要直接导入网上下载的
 * QQ 细胞词库，不借助外部工具）。
 *
 * 格式（公开逆向格式，与 .scel 同族；解析逻辑以 studyzy/
 * imewlconverter 的 QQPinyinQcelImporter.cs 为准，GPL-3.0，并用
 * imewlconverter 仓库自带的真实样本验证：星际战甲.qcel 4675 条，
 * 与其测试期望精确一致，[2] = 阿卡塔 / a'ka'ta）：
 * - 魔数 40 15 00 00（与 .scel 相同——只凭魔数认不出 .qcel，
 *   分流以文件后缀为准；改了后缀的 .qcel 由调用方在 .scel 解析
 *   失败后改试本解析器兜底）。
 * - 词数 uint32 LE @0x124；拼音表 @0x1540：先跳 4 字节，逐条
 *   （mark:u16, pyLen:u8, 拼音:pyLen 字节 UTF-16LE），到 "zuo"
 *   为表尾。索引超出表长时按 rime 的回退：单个小写字母
 *   chr(key - 表长 + 'a')。
 * - 词组：（same:u16 同音词数，pinyinLen:u16 拼音索引表字节长，
 *   拼音索引表:pinyinLen 字节，逐词（word_len:u16, 词:word_len
 *   字节 UTF-16LE，跳 2 字节 + 4 字节，尾部再跳 6 字节））。
 * - 拼音归一：音节拼合后小写、去声调数字、ü→v，只留 [a-z]；
 *   须合 DictYamlImporter 的码规则（^[a-z]{1,48}$）。
 *
 * 产出与 DictYamlImporter 同款 Result，进 CustomPhraseStore 的
 * imported 段。结构损坏（魔数不对、计数离谱、文件截断）抛
 * [QcelFormatException]，调用方报 DICT_QCEL_INVALID。
 */
object QcelImporter {
    /** .qcel 魔数（文件头 4 字节，与 .scel 相同）。 */
    private val MAGIC = byteArrayOf(0x40, 0x15, 0x00, 0x00)
    private const val WORD_COUNT_OFFSET = 0x124
    private const val PY_TABLE_OFFSET = 0x1540
    private const val MAX_PY_ENTRIES = 10000
    private const val MAX_GROUPS = 1_000_000
    private const val MAX_WORD_CHARS = 32
    private const val MAX_CODE_CHARS = 48
    private val CODE = Regex("^[a-z]{1,$MAX_CODE_CHARS}$")

    class QcelFormatException(message: String) : IllegalArgumentException(message)

    fun isQcel(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size) return false
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return false
        return true
    }

    fun parseBytes(
        bytes: ByteArray,
        existing: Set<Pair<String, String>> = emptySet(),
    ): DictYamlImporter.Result {
        if (!isQcel(bytes)) throw QcelFormatException("not a .qcel file (bad magic)")
        if (bytes.size < PY_TABLE_OFFSET + 4) {
            throw QcelFormatException("truncated header")
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val wordCount = buf.getInt(WORD_COUNT_OFFSET)
        if (wordCount < 0 || wordCount > MAX_GROUPS) {
            throw QcelFormatException("implausible word count: $wordCount")
        }
        var pos = PY_TABLE_OFFSET + 4 // 跳过 4 字节（表长，与 .scel 同位）
        val pyTable = HashMap<Int, String>()
        var guard = 0
        while (true) {
            if (guard++ > MAX_PY_ENTRIES) {
                throw QcelFormatException("pinyin table too large")
            }
            if (pos + 4 > bytes.size) throw QcelFormatException("truncated pinyin table")
            val mark = readU16(buf, pos)
            val pyLen = bytes[pos + 2].toInt() and 0xFF
            pos += 4
            if (pyLen <= 0) break
            if (pos + pyLen > bytes.size) throw QcelFormatException("truncated pinyin entry")
            val syllable = String(bytes, pos, pyLen, Charsets.UTF_16LE)
            pos += pyLen
            pyTable[mark] = syllable
            if (syllable == "zuo") break // 表尾
        }
        val entries = ArrayList<Triple<String, String, Int>>()
        var skipped = 0
        var current = 0
        while (pos < bytes.size) {
            if (pos + 4 > bytes.size) break
            val same = readU16(buf, pos)
            val pyLen = readU16(buf, pos + 2)
            pos += 4
            if (same == 0 && pyLen == 0) break
            if (same < 0 || same > 1000 || pyLen < 0 || pyLen > 512 ||
                pos + pyLen > bytes.size
            ) {
                throw QcelFormatException("truncated pinyin index list")
            }
            val code = buildString {
                var i = 0
                while (i + 1 < pyLen) {
                    val key = readU16(buf, pos + i)
                    i += 2
                    val syllable = pyTable[key] ?: if (key >= pyTable.size) {
                        // rime 回退：表外索引是单个 ASCII 字母。
                        (key - pyTable.size + 'a'.code).toChar().toString()
                    } else {
                        null
                    }
                    if (syllable != null) append(syllable)
                }
            }.let { normalizeCode(it) }
            pos += pyLen
            repeat(same) {
                if (pos + 2 > bytes.size) throw QcelFormatException("truncated word")
                val wordLen = readU16(buf, pos)
                pos += 2
                // 字节长只作防损坏的宽松上限；真正的 32 字限制由下面的
                // codePointCount 把关（与 ScelImporter 同款）。
                if (wordLen <= 0 || wordLen > 256 || pos + wordLen > bytes.size) {
                    throw QcelFormatException("truncated word text")
                }
                val word = String(bytes, pos, wordLen, Charsets.UTF_16LE)
                pos += wordLen
                // 词后 2 字节未知 short + 4 字节未知 int + 尾部 6 字节。
                if (pos + 12 > bytes.size) throw QcelFormatException("truncated word tail")
                pos += 12
                if (word.codePointCount(0, word.length) > MAX_WORD_CHARS ||
                    !CODE.matches(code)
                ) {
                    skipped++
                } else {
                    entries.add(Triple(word, code, 0))
                }
            }
            current += same
            if (current >= wordCount || pos >= bytes.size) break
        }
        return DictYamlImporter.finishBinaryImport(
            entries, existing, sortByFreq = false, skipped = skipped,
        )
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

    private fun readU16(buf: ByteBuffer, pos: Int): Int {
        return buf.getShort(pos).toInt() and 0xFFFF
    }
}
