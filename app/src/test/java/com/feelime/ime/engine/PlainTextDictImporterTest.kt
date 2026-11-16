package com.feelime.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

/** 纯文本词表导入解析（3.74.11）：三种行格式、权重忽略、自动注音、去重。 */
class PlainTextDictImporterTest {

    @Test
    fun parsesWordOnlyPinyinAndWeightColumns() {
        val text = """
            # 注释行
            你好 nihao
            大学	da xue
            流行词 liu xing ci 100
            缩写 nh

            空行上一行
        """.trimIndent()
        val result = PlainTextDictImporter.parse(
            StringReader(text),
            deriveCode = { null },
        )
        assertEquals(
            listOf(
                "你好" to "nihao",
                "大学" to "daxue",
                "流行词" to "liuxingci",
                "缩写" to "nh",
            ),
            result.items,
        )
        // 末尾的裸词「空行上一行」无拼音、推导为空，被跳过。
        assertEquals(1, result.skipped)
    }

    @Test
    fun derivesCodeWhenPinyinMissing() {
        val text = "你好\n新词 xin ci\n"
        val result = PlainTextDictImporter.parse(
            StringReader(text),
            deriveCode = { word -> if (word == "你好") "nihao" else null },
        )
        // 你好由推导得码；新词自带拼音。
        assertEquals(listOf("你好" to "nihao", "新词" to "xinci"), result.items)
        assertEquals(0, result.skipped)
    }

    @Test
    fun skipsLinesWithoutDerivableCode() {
        val text = "你好\n符号@@@\n"
        val result = PlainTextDictImporter.parse(
            StringReader(text),
            deriveCode = { null },
        )
        assertEquals(emptyList<Pair<String, String>>(), result.items)
        assertEquals(2, result.skipped)
    }

    @Test
    fun normalizesToneDigitsAndUmlaut() {
        val text = "你好 ni3 hao3\n绿 lv4\n"
        val result = PlainTextDictImporter.parse(
            StringReader(text),
            deriveCode = { null },
        )
        assertEquals(listOf("你好" to "nihao", "绿" to "lv"), result.items)
    }

    @Test
    fun dedupesWithinFileAndAgainstExisting() {
        val text = "你好 nihao\n你好 nihao\n新词 xin ci\n"
        val result = PlainTextDictImporter.parse(
            StringReader(text),
            deriveCode = { null },
            existing = setOf("新词" to "xinci"),
        )
        assertEquals(listOf("你好" to "nihao"), result.items)
    }
}
