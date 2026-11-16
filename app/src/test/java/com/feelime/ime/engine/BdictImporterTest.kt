package com.feelime.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 百度 .bdict 导入解析（3.74.11）：魔数/新旧头部/词条/去重/坏文件。 */
class BdictImporterTest {

    /** 声母/韵母索引（与 BdictImporter 的表同序）：n=11,i=29,h=5,ao=13,
     *  d=1,uo=26,t=17,ai=11,l=9,v=32,y=22,ou=21。 */
    private fun buildBdict(
        entries: List<Pair<String, List<Pair<Int, Int>>>> = listOf(
            "你好" to listOf(11 to 29, 5 to 13),
            "多态" to listOf(1 to 26, 17 to 11),
            "旅游" to listOf(9 to 32, 22 to 21),
        ),
        oldHeader: Boolean = false,
    ): ByteArray {
        val buf = ByteBuffer.allocate(0x4000).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("biptbdsw".toByteArray(Charsets.US_ASCII))
        buf.position(0x350)
        for ((word, syllables) in entries) {
            buf.putInt(syllables.size)
            for ((sm, ym) in syllables) {
                buf.put(sm.toByte())
                buf.put(ym.toByte())
            }
            buf.put(word.toByteArray(Charsets.UTF_16LE))
        }
        val end = buf.position()
        if (oldHeader) {
            buf.putInt(0x60, 0)
            buf.putInt(0x44, end - 0x350)
        } else {
            buf.putInt(0x60, end)
        }
        val out = ByteArray(end)
        buf.rewind()
        buf.get(out)
        return out
    }

    @Test
    fun parsesWordsWithPinyinInFileOrder() {
        val result = BdictImporter.parseBytes(buildBdict())
        assertEquals(
            listOf("你好" to "nihao", "多态" to "duotai", "旅游" to "lvyou"),
            result.items,
        )
        assertEquals(0, result.skipped)
        assertEquals(0, result.truncated)
    }

    @Test
    fun oldHeaderFallsBackToLengthAt0x44() {
        val result = BdictImporter.parseBytes(buildBdict(oldHeader = true))
        assertEquals(
            listOf("你好" to "nihao", "多态" to "duotai", "旅游" to "lvyou"),
            result.items,
        )
    }

    @Test
    fun isBdictDetectsMagic() {
        assertTrue(BdictImporter.isBdict(buildBdict()))
        assertTrue(!BdictImporter.isBdict("not a bdict file".toByteArray()))
        assertTrue(!BdictImporter.isBdict(ByteArray(0)))
    }

    @Test
    fun rejectsBadMagic() {
        val bad = buildBdict().also { it[0] = 0x00 }
        assertThrows(
            BdictImporter.BdictFormatException::class.java,
        ) { BdictImporter.parseBytes(bad) }
    }

    @Test
    fun rejectsImplausibleEndPosition() {
        val bad = buildBdict()
        // 把 0x60 的结束位置改成文件外。
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(0x60, bad.size + 100)
        assertThrows(
            BdictImporter.BdictFormatException::class.java,
        ) { BdictImporter.parseBytes(bad) }
    }

    @Test
    fun dedupesWithinFileAndAgainstExisting() {
        val entries = listOf(
            "你好" to listOf(11 to 29, 5 to 13),
            "你好" to listOf(11 to 29, 5 to 13),
            "多态" to listOf(1 to 26, 17 to 11),
        )
        val result = BdictImporter.parseBytes(
            buildBdict(entries),
            existing = setOf("多态" to "duotai"),
        )
        assertEquals(listOf("你好" to "nihao"), result.items)
    }
}
