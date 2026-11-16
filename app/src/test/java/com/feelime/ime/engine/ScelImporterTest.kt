package com.feelime.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 搜狗 .scel 导入解析（3.74.11）：魔数/拼音表/词组/词频排序/截断/坏文件。 */
class ScelImporterTest {

    /** 按已验证的格式（rime scel2dict.py）手造一个最小 .scel：
     *  拼音表 [ni, hao, a]，词组：你好/ni hao（词频 900）、啊/a（100）、
     *  你好吗/ni hao a（50）。 */
    private fun buildScel(
        pyTable: List<String> = listOf("ni", "hao", "a"),
        groups: List<Triple<List<Int>, List<Pair<String, Int>>, Int>> = listOf(
            Triple(listOf(0, 1), listOf("你好" to 900), 0),
            Triple(listOf(2), listOf("啊" to 100), 0),
            Triple(listOf(0, 1, 2), listOf("你好吗" to 50), 0),
        ),
    ): ByteArray {
        val buf = ByteBuffer.allocate(0x400000).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(byteArrayOf(0x40, 0x15, 0x00, 0x00))
        buf.position(0x120)
        buf.putInt(groups.size)
        buf.putInt(groups.sumOf { it.second.size })
        buf.position(0x130)
        buf.put("测试词库".toByteArray(Charsets.UTF_16LE))
        buf.position(0x1540)
        buf.putInt(pyTable.size)
        pyTable.forEachIndexed { idx, syllable ->
            val bytes = syllable.toByteArray(Charsets.UTF_16LE)
            buf.putShort(idx.toShort())
            buf.putShort(bytes.size.toShort())
            buf.put(bytes)
        }
        for ((pyIdx, words, _) in groups) {
            val pyBytes = ByteBuffer.allocate(pyIdx.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pyIdx.forEach { pyBytes.putShort(it.toShort()) }
            buf.putShort(words.size.toShort())
            buf.putShort(pyBytes.array().size.toShort())
            buf.put(pyBytes.array())
            for ((word, freq) in words) {
                val wordBytes = word.toByteArray(Charsets.UTF_16LE)
                buf.putShort(wordBytes.size.toShort())
                buf.put(wordBytes)
                buf.putShort(10) // ext_len
                buf.putShort(freq.toShort()) // 词频（ext 前 2 字节）
                buf.put(ByteArray(8)) // ext 其余 8 字节
            }
        }
        val out = ByteArray(buf.position())
        buf.rewind()
        buf.get(out)
        return out
    }

    @Test
    fun parsesWordsWithPinyinAndFrequencyOrder() {
        val result = ScelImporter.parse(ByteArrayInputStream(buildScel()))
        // 词频降序：你好（900）> 啊（100）> 你好吗（50）。
        assertEquals(
            listOf("你好" to "nihao", "啊" to "a", "你好吗" to "nihaoa"),
            result.items,
        )
        assertEquals(0, result.skipped)
        assertEquals(0, result.truncated)
    }

    @Test
    fun isScelDetectsMagic() {
        assertTrue(ScelImporter.isScel(buildScel()))
        assertTrue(!ScelImporter.isScel("not a scel file".toByteArray()))
        assertTrue(!ScelImporter.isScel(ByteArray(0)))
    }

    @Test
    fun rejectsBadMagic() {
        val bad = buildScel().also { it[0] = 0x00 }
        assertThrows(
            ScelImporter.ScelFormatException::class.java,
        ) { ScelImporter.parseBytes(bad) }
    }

    @Test
    fun rejectsTruncatedFile() {
        val truncated = buildScel().copyOf(0x1544)
        assertThrows(
            ScelImporter.ScelFormatException::class.java,
        ) { ScelImporter.parseBytes(truncated) }
    }

    @Test
    fun dedupesWithinFileAndAgainstExisting() {
        val groups = listOf(
            Triple(listOf(0, 1), listOf("你好" to 900, "你好" to 800), 0),
            Triple(listOf(0, 1), listOf("新词" to 700), 0),
        )
        val result = ScelImporter.parse(
            ByteArrayInputStream(buildScel(groups = groups)),
            // 去重按（词，码）对：已有的 新词/nihao 不再进，码不同的
            // 同名异音词仍可进（多音字是合法的新条目）。
            existing = setOf("新词" to "nihao"),
        )
        assertEquals(listOf("你好" to "nihao"), result.items)
    }

    @Test
    fun skipsEntriesWithBadCode() {
        // 拼音表里塞一个大写单字母（.scel 里拉丁字母的存法）：归一后
        // 成小写，仍合法；空拼音（码为空）与超长词应被跳过。
        val result = ScelImporter.parse(
            ByteArrayInputStream(
                buildScel(
                    pyTable = listOf("ni", "hao", "W"),
                    groups = listOf(
                        Triple(listOf(0, 1), listOf("你好" to 900), 0),
                        Triple(listOf(2), listOf("澳门w盛典" to 800), 0),
                        Triple(emptyList(), listOf("无音" to 700), 0),
                        Triple(listOf(0), listOf("超长词${"很".repeat(30)}" to 600), 0),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf("你好" to "nihao", "澳门w盛典" to "w"),
            result.items,
        )
        assertEquals(2, result.skipped)
    }

    @Test
    fun truncatesToMaxEntriesKeepingMostFrequent() {
        val groups = (1..6000).map { i ->
            Triple(listOf(0), listOf("词$i" to i), 0)
        }
        val result = ScelImporter.parse(
            ByteArrayInputStream(buildScel(pyTable = listOf("a"), groups = groups)),
        )
        assertEquals(DictYamlImporter.MAX_ENTRIES, result.items.size)
        assertTrue(result.truncated > 0)
        // 词频最高的 5000 条留了下来（词 6000 在，词 1 被截）。
        assertTrue(result.items.any { it.first == "词6000" })
        assertTrue(result.items.none { it.first == "词1" })
    }
}
