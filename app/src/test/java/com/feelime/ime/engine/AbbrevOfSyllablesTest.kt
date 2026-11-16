package com.feelime.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** 首字母缩写（3.74.11，B 项）：音节列 → 首字母序列。zh/ch/sh 取双字母。 */
class AbbrevOfSyllablesTest {

    @Test
    fun takesFirstLetterPerSyllable() {
        assertEquals("nh", CustomPhraseStore.abbrevOfSyllables(listOf("ni", "hao")))
        assertEquals("wxt", CustomPhraseStore.abbrevOfSyllables(listOf("wo", "xiang", "ta")))
        assertEquals("n", CustomPhraseStore.abbrevOfSyllables(listOf("ni")))
    }

    @Test
    fun takesTwoLettersForZhChSh() {
        assertEquals("zhsh", CustomPhraseStore.abbrevOfSyllables(listOf("zhong", "shan")))
        assertEquals("chq", CustomPhraseStore.abbrevOfSyllables(listOf("chong", "qing")))
        assertEquals("shj", CustomPhraseStore.abbrevOfSyllables(listOf("shang", "jia")))
        // zh/ch/sh 在词中：你好吗 zh → "nhm" 不变，重点是含 zh 的组合。
        assertEquals("nzh", CustomPhraseStore.abbrevOfSyllables(listOf("ni", "zhong")))
    }

    @Test
    fun emptyListGivesEmptyAbbrev() {
        assertEquals("", CustomPhraseStore.abbrevOfSyllables(emptyList()))
    }

    /** 长句首字母连打（3.74.11，B 项澄清）：6–10 字整句的首字母序列。 */
    @Test
    fun longSentenceAbbrevs() {
        // 我今天晚上想吃火锅（9 字；sh/ch 各占两字母，共 11 字符）
        assertEquals(
            "wjtwshxchhg",
            CustomPhraseStore.abbrevOfSyllables(
                listOf("wo", "jin", "tian", "wan", "shang", "xiang", "chi", "huo", "guo")
            )
        )
        // 我们今天晚上去哪里玩（10 字）
        assertEquals(
            "wmjtwshqnlw",
            CustomPhraseStore.abbrevOfSyllables(
                listOf("wo", "men", "jin", "tian", "wan", "shang", "qu", "na", "li", "wan")
            )
        )
        // 你好吗（3 字，含 zh 组合的短句对照）
        assertEquals("nhm", CustomPhraseStore.abbrevOfSyllables(listOf("ni", "hao", "ma")))
        // 明天早上八点开会（8 字，含 sh）
        assertEquals(
            "mtzshbdkh",
            CustomPhraseStore.abbrevOfSyllables(
                listOf("ming", "tian", "zao", "shang", "ba", "dian", "kai", "hui")
            )
        )
    }
}
