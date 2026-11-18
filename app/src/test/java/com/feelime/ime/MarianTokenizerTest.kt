package com.feelime.ime

import org.junit.Assert.*
import org.junit.Test

/** [MarianTokenizer] 纯逻辑：用迷你合成词表验 Viterbi 切分与 Metaspace
 *  编解码，不依赖 6 MB 的真实 tokenizer.json（那份只在真机/设备门禁
 *  里随模型下载核对）。合成词表的分数按真实 Unigram 的形态取：
 *  整词分数高、单字分数低、未登录字符落到 <unk>。 */
class MarianTokenizerTest {

    private val tinyJson = """
        {"model": {"type": "Unigram", "unk_id": 1, "vocab": [
          ["</s>", 0.0], ["<unk>", 0.0],
          ["▁hello", -1.0], ["▁world", -1.0], ["▁", -2.0],
          ["he", -3.0], ["llo", -3.0],
          ["h", -5.0], ["e", -5.0], ["l", -5.0], ["o", -5.0],
          ["w", -5.0], ["r", -5.0], ["d", -5.0]
        ]}}
    """.trimIndent()

    private val tokenizer = MarianTokenizer.fromJson(tinyJson)

    @Test fun `vocab parses with unk id`() {
        // 迷你词表共 14 条：</s>、<unk>、▁hello、▁world、▁、he、llo、h、e、l、o、w、r、d。
        assertEquals(14, tokenizer.vocabSize)
        assertEquals(1, tokenizer.unkId)
    }

    @Test fun `encode picks whole words over characters`() {
        // "▁hello"(-1) 胜过 "▁"+"he"+"llo"(-8) 与逐字切分；句尾补 </s>(0)。
        assertArrayEquals(intArrayOf(2, 3, 0), tokenizer.encode("hello world"))
        assertArrayEquals(intArrayOf(2, 0), tokenizer.encode("hello"))
    }

    @Test fun `encode collapses whitespace and trims`() {
        assertArrayEquals(intArrayOf(2, 3, 0), tokenizer.encode("  hello   world  "))
    }

    @Test fun `encode empty is just eos`() {
        assertArrayEquals(intArrayOf(0), tokenizer.encode(""))
        assertArrayEquals(intArrayOf(0), tokenizer.encode("   "))
    }

    @Test fun `unknown character falls back to unk without breaking the sentence`() {
        // "☃" 不在词表：以 <unk>(1) 吃掉，前后照常切分。
        assertArrayEquals(intArrayOf(2, 1, 0), tokenizer.encode("hello☃"))
    }

    @Test fun `decode joins pieces and restores spaces`() {
        assertEquals("hello world", tokenizer.decode(intArrayOf(2, 3, 0)))
        assertEquals("hello", tokenizer.decode(intArrayOf(2, 0)))
        assertEquals("", tokenizer.decode(intArrayOf(0)))
        assertEquals("", tokenizer.decode(intArrayOf()))
    }

    @Test fun `decode skips pad`() {
        assertEquals("hello", tokenizer.decode(intArrayOf(2, MarianTokenizer.PAD_ID, 0)))
    }

    @Test fun `wrong model type is rejected`() {
        try {
            MarianTokenizer.fromJson("""{"model": {"type": "BPE", "vocab": [["a", 0.0]]}}""")
            fail("BPE tokenizer.json 应该被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("BPE"))
        }
    }

    @Test fun `empty vocab is rejected`() {
        try {
            MarianTokenizer.fromJson("""{"model": {"type": "Unigram", "vocab": []}}""")
            fail("空词表应该被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("为空"))
        }
    }

    @Test fun `special ids match the Marian config`() {
        assertEquals(0, MarianTokenizer.EOS_ID)
        assertEquals(1, MarianTokenizer.UNK_ID)
        assertEquals(65000, MarianTokenizer.PAD_ID)
        assertEquals(65000, MarianTokenizer.DECODER_START_ID)
    }
}
