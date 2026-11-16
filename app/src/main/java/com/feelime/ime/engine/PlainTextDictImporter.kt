package com.feelime.ime.engine

import java.io.BufferedReader
import java.io.Reader

/**
 * 纯文本词表导入解析器（3.74.11，.scel 的平替入口：用户从网上下的
 * 分享词库若是 .txt 可直接导；.scel→.txt 的转换办法写在设置页
 * 导入说明里）。
 *
 * 行格式（每行一条，兼容三种常见写法）：
 * - `你好` —— 只有词，拼音由 deriveCode 推导（汉字→拼音首选读音）；
 *   推不出来（生僻字/符号）跳过。
 * - `你好 nihao` / `你好 ni hao` / `你好<TAB>ni hao` —— 词 + 拼音，
 *   空格/撇号/声调数字归一后成码。
 * - `你好 nihao 100` / fcitx5 libime 三列 `词条 全拼 权重` —— 末尾
 *   的纯数字权重忽略。
 * `#` 注释与空行跳过。产出与 DictYamlImporter 同款 Result，进
 * CustomPhraseStore 的 imported 段（与 .dict.yaml 同一重载链路）。
 */
object PlainTextDictImporter {
    private const val MAX_TEXT_CHARS = 32
    private const val MAX_CODE_CHARS = 48
    private val CODE = Regex("^[a-z]{1,$MAX_CODE_CHARS}$")
    private val WEIGHT = Regex("^\\d+$")
    private val PINYIN_TOKEN = Regex("^[a-zA-Z'üÜ]+[1-5]?$")

    fun parse(
        reader: Reader,
        deriveCode: ((String) -> String?)? = null,
        existing: Set<Pair<String, String>> = emptySet(),
    ): DictYamlImporter.Result {
        val items = LinkedHashMap<Pair<String, String>, Unit>()
        var skipped = 0
        var truncated = 0
        BufferedReader(reader).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val tokens = line.split(Regex("[\\t ]+")).filter { it.isNotEmpty() }
                if (tokens.isEmpty()) continue
                val text = tokens[0]
                // 词后第一个纯数字是权重（fcitx5 三列），不是拼音。
                var code: String? = null
                val pinyinTokens = ArrayList<String>()
                for (token in tokens.drop(1)) {
                    if (WEIGHT.matches(token)) break
                    if (PINYIN_TOKEN.matches(token)) {
                        pinyinTokens.add(token)
                    } else {
                        break
                    }
                }
                if (pinyinTokens.isNotEmpty()) {
                    code = normalizeCode(pinyinTokens.joinToString(""))
                }
                if (code.isNullOrEmpty()) {
                    code = deriveCode?.invoke(text)?.let { normalizeCode(it) }
                }
                if (text.codePointCount(0, text.length) > MAX_TEXT_CHARS ||
                    code.isNullOrEmpty() || !CODE.matches(code)
                ) {
                    skipped++
                    continue
                }
                val entry = text to code
                if (entry in existing || entry in items) continue
                if (items.size >= DictYamlImporter.MAX_ENTRIES) {
                    truncated++
                    continue
                }
                items[entry] = Unit
            }
        }
        return DictYamlImporter.Result(
            items = items.keys.toList(),
            ranks = emptyMap(),
            rankDeclared = false,
            skipped = skipped,
            truncated = truncated,
        )
    }

    /** 拼音归一：小写、去声调数字与撇号、ü→v，只留 [a-z]。 */
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
