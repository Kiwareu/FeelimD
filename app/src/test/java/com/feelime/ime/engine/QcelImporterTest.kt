package com.feelime.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** QQ .qcel 导入解析（3.74.11）：魔数/拼音表/词组/表外索引回退/坏文件。 */
class QcelImporterTest {

    /** 按已验证的格式（imewlconverter QQPinyinQcelImporter.cs）手造
     *  一个最小 .qcel：拼音表 [ni, hao, a, ka, ta, zuo]（zuo 为表尾），
     *  词组：你好/ni hao、啊/a、阿卡塔/a ka ta。 */
    private fun buildQcel(
        pyTable: List<String> = listOf("ni", "hao", "a", "ka", "ta", "zuo"),
        groups: List<Pair<List<Int>, List<String>>> = listOf(
            listOf(0, 1) to listOf("你好"),
            listOf(2) to listOf("啊"),
            listOf(2, 3, 4) to listOf("阿卡塔"),
        ),
    ): ByteArray {
        val buf = ByteBuffer.allocate(0x8000).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(byteArrayOf(0x40, 0x15, 0x00, 0x00))
        buf.position(0x124)
        buf.putInt(groups.sumOf { it.second.size })
        buf.position(0x1540)
        buf.putInt(pyTable.size) // 跳过的 4 字节（表长）
        pyTable.forEachIndexed { idx, syllable ->
            val bytes = syllable.toByteArray(Charsets.UTF_16LE)
            buf.putShort(idx.toShort())
            buf.put(bytes.size.toByte())
            buf.put(0.toByte())
            buf.put(bytes)
        }
        for ((pyIdx, words) in groups) {
            val pyBytes = ByteBuffer.allocate(pyIdx.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pyIdx.forEach { pyBytes.putShort(it.toShort()) }
            buf.putShort(words.size.toShort())
            buf.putShort(pyBytes.array().size.toShort())
            buf.put(pyBytes.array())
            for (word in words) {
                val wordBytes = word.toByteArray(Charsets.UTF_16LE)
                buf.putShort(wordBytes.size.toShort())
                buf.put(wordBytes)
                buf.putShort(0) // 未知 short
                buf.putInt(0) // 未知 int
                buf.put(ByteArray(6)) // 尾部 6 字节
            }
        }
        val out = ByteArray(buf.position())
        buf.rewind()
        buf.get(out)
        return out
    }

    @Test
    fun parsesWordsWithPinyin() {
        val result = QcelImporter.parseBytes(buildQcel())
        assertEquals(
            listOf("你好" to "nihao", "啊" to "a", "阿卡塔" to "akata"),
            result.items,
        )
        assertEquals(0, result.skipped)
        assertEquals(0, result.truncated)
    }

    @Test
    fun isQcelDetectsMagic() {
        assertTrue(QcelImporter.isQcel(buildQcel()))
        assertTrue(!QcelImporter.isQcel("not a qcel file".toByteArray()))
        assertTrue(!QcelImporter.isQcel(ByteArray(0)))
    }

    @Test
    fun rejectsBadMagic() {
        val bad = buildQcel().also { it[0] = 0x00 }
        assertThrows(
            QcelImporter.QcelFormatException::class.java,
        ) { QcelImporter.parseBytes(bad) }
    }

    @Test
    fun rejectsTruncatedPinyinTable() {
        val truncated = buildQcel().copyOf(0x1544)
        assertThrows(
            QcelImporter.QcelFormatException::class.java,
        ) { QcelImporter.parseBytes(truncated) }
    }

    @Test
    fun outOfTableIndexFallsBackToLetter() {
        // 表长 6，索引 6 → rime 回退：单个字母 'a'。
        val result = QcelImporter.parseBytes(
            buildQcel(
                groups = listOf(listOf(6) to listOf("甲")),
            ),
        )
        assertEquals(listOf("甲" to "a"), result.items)
    }

    @Test
    fun dedupesWithinFileAndAgainstExisting() {
        val groups = listOf(
            listOf(0, 1) to listOf("你好", "你好"),
            listOf(0, 1) to listOf("新词"),
        )
        val result = QcelImporter.parseBytes(
            buildQcel(groups = groups),
            // 去重按（词，码）对：已有的 新词/nihao 不再进。
            existing = setOf("新词" to "nihao"),
        )
        assertEquals(listOf("你好" to "nihao"), result.items)
    }
}
