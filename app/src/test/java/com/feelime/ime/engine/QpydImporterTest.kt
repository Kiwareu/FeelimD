package com.feelime.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/** QQ .qpyd 导入解析（3.74.11）：zlib 解压/10 字节索引/撇号拼音/坏文件。 */
class QpydImporterTest {

    /** 按已验证的格式（imewlconverter QQPinyinQpydImporter.cs）手造
     *  一个最小 .qpyd：解压后索引区 2 条（你好/ni'hao、啊/a），
     *  其后是拼音（UTF-8）+ 词（UTF-16LE）数据区。 */
    private fun buildQpyd(
        entries: List<Pair<String, String>> = listOf(
            "ni'hao" to "你好",
            "a" to "啊",
        ),
    ): ByteArray {
        val data = ByteArrayOutputStream()
        val index = ByteArrayOutputStream()
        var dataPos = entries.size * 10
        for ((pinyin, word) in entries) {
            val pyBytes = pinyin.toByteArray(Charsets.UTF_8)
            val wordBytes = word.toByteArray(Charsets.UTF_16LE)
            val entry = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            entry.put(pyBytes.size.toByte())
            entry.put(wordBytes.size.toByte())
            entry.put(ByteArray(4)) // [2..5] 未用
            entry.putInt(dataPos)
            index.write(entry.array())
            data.write(pyBytes)
            data.write(wordBytes)
            dataPos += pyBytes.size + wordBytes.size
        }
        val raw = index.toByteArray() + data.toByteArray()
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val compressed = ByteArray(raw.size + 64)
        val compressedLen = deflater.deflate(compressed)
        deflater.end()
        val start = 0x100
        val out = ByteArray(start + compressedLen)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(0x38, start)
        compressed.copyInto(out, start, 0, compressedLen)
        return out
    }

    @Test
    fun parsesWordsWithApostrophePinyin() {
        val result = QpydImporter.parseBytes(buildQpyd())
        assertEquals(
            listOf("你好" to "nihao", "啊" to "a"),
            result.items,
        )
        assertEquals(0, result.skipped)
        assertEquals(0, result.truncated)
    }

    @Test
    fun rejectsBadStartOffset() {
        val bad = buildQpyd()
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(0x38, bad.size + 10)
        assertThrows(
            QpydImporter.QpydFormatException::class.java,
        ) { QpydImporter.parseBytes(bad) }
    }

    @Test
    fun rejectsNonZlibData() {
        val bad = buildQpyd()
        // 起始处改成随机字节：不是 zlib 流。
        for (i in 0x100 until 0x110) bad[i] = (i * 37).toByte()
        assertThrows(
            QpydImporter.QpydFormatException::class.java,
        ) { QpydImporter.parseBytes(bad) }
    }

    @Test
    fun rejectsTruncatedHeader() {
        val truncated = buildQpyd().copyOf(0x20)
        assertThrows(
            QpydImporter.QpydFormatException::class.java,
        ) { QpydImporter.parseBytes(truncated) }
    }

    @Test
    fun dedupesWithinFileAndAgainstExisting() {
        val entries = listOf("ni'hao" to "你好", "ni'hao" to "你好", "a" to "啊")
        val result = QpydImporter.parseBytes(
            buildQpyd(entries),
            existing = setOf("啊" to "a"),
        )
        assertEquals(listOf("你好" to "nihao"), result.items)
        assertEquals(0, result.skipped)
    }
}
