package com.feelime.ime

import org.json.JSONObject

/**
 * MarianMT（Helsinki-NLP Opus-MT，经 Xenova 转 ONNX）分词器：纯 Kotlin，
 * 不依赖 SentencePiece 原生库。
 *
 * 词表来源是模型仓库里的 `tokenizer.json`（HuggingFace `tokenizers` 格式）：
 * model.type = "Unigram"，vocab 是 65,001 条 [piece, score]；pre-tokenizer
 * 是 WhitespaceSplit + Metaspace（"▁" U+2581，add_prefix_space）；post-processor
 * 在句尾补一个 </s>；decoder 是 Metaspace。编码按 SentencePiece Unigram
 * 的标准做法：Metaspace 归一化后在词表上跑 Viterbi 求最高分切分，未覆盖的
 * 字符落到 <unk>。
 *
 * 不读 .spm 文件：tokenizer.json 与 .spm 是同一 SentencePiece 模型的两种
 * 序列化，词表完全一致；.spm 是 protobuf，没有现成的 Kotlin 解析器，
 * tokenizer.json 用 org.json 就能读，所以 manifest 里只发 tokenizer.json。
 *
 * 线程安全：构造后只读；[encode]/[decode] 可在任意线程调用。
 */
class MarianTokenizer private constructor(
    /** 词表，与 id 一一对应（id = 下标）。 */
    val pieces: List<String>,
    private val scores: FloatArray,
    /** 未登录字符的 id（tokenizer.json model.unk_id，Opus-MT 上是 2）。 */
    val unkId: Int,
) {
    val vocabSize: Int get() = pieces.size

    /** 词表里最长的 piece（按 UTF-16 码元计），Viterbi 的前瞻上限。 */
    private val maxPieceLength: Int = pieces.maxOfOrNull { it.length } ?: 0

    private val pieceToId: Map<String, Int> =
        pieces.withIndex().associate { (index, piece) -> piece to index }

    /**
     * 编码：Metaspace 归一化（空白切分后每词前加 "▁"，句首也加）→ Viterbi
     * 最高分切分 → 末尾补 [eosId]（post-processor 的 </s>）。空输入只剩
     * 一个 </s>（编码器仍需要它，空串翻译直接返回空串，不会走到这里）。
     */
    fun encode(text: String, eosId: Int = EOS_ID): IntArray {
        val words = text.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (words.isEmpty()) return intArrayOf(eosId)
        val spaced = buildString(words.sumOf { it.length + 1 }) {
            for (word in words) {
                append(METASPACE)
                append(word)
            }
        }
        val ids = viterbi(spaced)
        return (ids + eosId).toIntArray()
    }

    /** Viterbi：best[i] = spaced[0..i) 的最高分；回溯取切分。未覆盖的
     *  单个码点（多半是生僻字/emoji）以 <unk> 计，分数 0，不中断整句。 */
    private fun viterbi(spaced: String): List<Int> {
        val n = spaced.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val prev = IntArray(n + 1) { -1 }
        val pieceIdAt = IntArray(n + 1) { -1 }
        best[0] = 0f
        for (end in 1..n) {
            val startMin = maxOf(0, end - maxPieceLength)
            for (start in end - 1 downTo startMin) {
                if (best[start] == Float.NEGATIVE_INFINITY) continue
                val id = pieceToId[spaced.substring(start, end)] ?: continue
                val score = best[start] + scores[id]
                if (score > best[end]) {
                    best[end] = score
                    prev[end] = start
                    pieceIdAt[end] = id
                }
            }
            // 整个字（一个码点）都盖不住：以 <unk> 吃掉它，继续往后翻。
            if (best[end] == Float.NEGATIVE_INFINITY) {
                val start = end - 1
                // 代理对（emoji 等增补字符）占两个码元，<unk> 一次吃一对。
                val charStart =
                    if (start > 0 && Character.isLowSurrogate(spaced[start]) &&
                        Character.isHighSurrogate(spaced[start - 1])
                    ) {
                        start - 1
                    } else {
                        start
                    }
                if (best[charStart] != Float.NEGATIVE_INFINITY) {
                    best[end] = best[charStart]
                    prev[end] = charStart
                    pieceIdAt[end] = unkId
                }
            }
        }
        if (best[n] == Float.NEGATIVE_INFINITY) return listOf(unkId)
        val ids = ArrayList<Int>()
        var pos = n
        while (pos > 0) {
            ids.add(pieceIdAt[pos])
            pos = prev[pos]
        }
        return ids.reversed()
    }

    /**
     * 解码：id → piece 拼起来，跳过 </s>/<pad> 等特殊 token，Metaspace
     * 还原（"▁"→空格，句首空格去掉）。非法 id 按 <unk> 的 piece 处理
     * （正常推理不会出现，防御而已）。
     */
    fun decode(ids: IntArray): String {
        val joined = buildString {
            for (id in ids) {
                if (id == EOS_ID || id == PAD_ID) continue
                val piece = pieces.getOrNull(id) ?: pieces.getOrNull(unkId) ?: continue
                append(piece)
            }
        }
        if (joined.isEmpty()) return ""
        return joined.replace(METASPACE, ' ').trimStart()
    }

    companion object {
        const val EOS_ID = 0
        const val UNK_ID = 1
        const val PAD_ID = 65000

        /** decoder_start_token_id（Marian 配置值，两方向相同）。 */
        const val DECODER_START_ID = 65000

        private const val METASPACE = '▁'
        private val WHITESPACE_REGEX = "\\s+".toRegex()

        /**
         * 从 tokenizer.json 全文解析。要求 model.type == "Unigram" 且
         * vocab 非空；格式不对直接抛 IllegalArgumentException（调用方在
         * worker 线程接住，转成"翻译模型文件损坏"之类的用户文案）。
         */
        fun fromJson(json: String): MarianTokenizer {
            val root = JSONObject(json)
            val model = root.optJSONObject("model")
                ?: throw IllegalArgumentException("tokenizer.json 缺少 model")
            if (model.optString("type") != "Unigram") {
                throw IllegalArgumentException(
                    "不支持的分词器类型：${model.optString("type")}",
                )
            }
            val vocab = model.optJSONArray("vocab")
                ?: throw IllegalArgumentException("tokenizer.json 缺少 model.vocab")
            if (vocab.length() == 0) {
                throw IllegalArgumentException("tokenizer.json 词表为空")
            }
            val pieces = ArrayList<String>(vocab.length())
            val scores = FloatArray(vocab.length())
            for (i in 0 until vocab.length()) {
                val entry = vocab.optJSONArray(i)
                    ?: throw IllegalArgumentException("tokenizer.json 词表第 $i 条格式不对")
                pieces.add(entry.optString(0))
                scores[i] = entry.optDouble(1, 0.0).toFloat()
            }
            val unkId = model.optInt("unk_id", UNK_ID)
            return MarianTokenizer(pieces, scores, unkId)
        }
    }
}
