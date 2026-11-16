package com.feelime.ime.engine

import android.content.Context
import android.content.Intent

/**
 * 习惯自动词库（3.74.10）：常打的词满阈值自动收入自造词（custom_phrase
 * user 段），只存本机。计数与已收入记录住在独立 SharedPreferences
 * （habit_lexicon），不碰 custom-phrases.json 的结构。
 *
 * 与 AssocHabit 严格分开：这里决定「候选里有没有这个词」（词库），
 * AssocHabit 只决定联想排序（候选顺序）。两者各存各的。
 *
 * 写入走 CustomPhraseStore.save（与 learnUserWord 同一条写盘/派生
 * 路径），写后由调用方发 ACTION_CUSTOM_PHRASES_CHANGED，接收方
 * reloadGlobal + 重建 session（与手动「收入自造词」同一条路）。
 */
object HabitLexicon {
    private const val PREFS = "habit_lexicon"
    private const val KEY_AUTO_ADDED = "auto_added"
    /** 首字母路已收入的词（3.74.11，B 项澄清：打首字母选定的句第
     *  一次就记，与满 3 次的全拼路分开记）。 */
    private const val KEY_ABBREV_ADDED = "abbrev_added"
    private const val KEY_COUNT_PREFIX = "count:"
    /** 首字母记忆（3.74.11，B 项）：词 → 用户打过它的首字母序列集合。 */
    private const val KEY_ABBREV_PREFIX = "abbrev:"
    /** 收入阈值：同一词上屏满 3 次即自动收入（全拼路）。 */
    const val THRESHOLD = 3
    const val MIN_WORD_LEN = 2
    /** 词/句长上限：3.74.10 是 8 字；3.74.11 放到 16 字——用户要记
     *  的是 6–10 字以上的整句（首字母连打一整句），8 字不够。
     *  注：全拼码超 48 字符的超长句（约 13 字以上）注不上音，
     *  仍按旧规则不收入。 */
    const val MAX_WORD_LEN = 16
    /** 自造词 user 段上限（与 learnUserWord 同值）。 */
    private const val MAX_USER_WORDS = 200
    private const val MAX_CODE_CHARS = 48

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isChineseWord(word: String): Boolean =
        word.length in MIN_WORD_LEN..MAX_WORD_LEN &&
            word.all { it in '\u4E00'..'\u9FFF' }

    /** 已自动收入的词（设置页列表用；与手动收入的词分开记）。
     *  首字母路（第一次就记）与全拼路（满 3 次）都算自动收入。 */
    fun autoAddedWords(context: Context): List<String> {
        val store = prefs(context)
        val added = (store.getStringSet(KEY_AUTO_ADDED, emptySet()) ?: emptySet())
        val abbrevAdded = (store.getStringSet(KEY_ABBREV_ADDED, emptySet()) ?: emptySet())
        return (added + abbrevAdded).sorted()
    }

    /**
     * 输入码是不是该词的一组首字母缩写（3.74.11，B 项）：把词的每种
     * 全拼读音算成首字母序列，与输入码比对。输入码须比全拼短（缩写
     * 本身比全拼短），且全是小写字母。
     */
    private fun validAbbrev(context: Context, word: String, inputKeys: String?): String? {
        if (inputKeys.isNullOrEmpty()) return null
        if (inputKeys.length < 2 || inputKeys.length > MAX_CODE_CHARS) return null
        if (!inputKeys.all { it in 'a'..'z' }) return null
        val auto = CustomPhraseStore.autoPinyinCodes(context, word)
        for (code in auto) {
            if (inputKeys.length >= code.length) continue
            val abbrev = CustomPhraseStore.abbrevOfCode(context, code) ?: continue
            if (abbrev == inputKeys) return inputKeys
        }
        return null
    }

    /**
     * 记录一次上屏词。返回 true = 本次刚把该词（或它的一条首字母码）
     * 自动收入自造词，调用方应发 ACTION_CUSTOM_PHRASES_CHANGED 让
     * 引擎重载；其余情况 false。
     *
     * 首字母记忆（3.74.11，B 项；用户 2026-11-16 澄清版）：目标是
     * 长首字母序列 → 整句（6–10 字以上的一整句，不只是两字词），
     * 映射来源是用户自己的习惯——不是内置词典里现成的词句。用户
     * 打首字母序列上屏（尤其是纠正/补全后选定的那句），第一次就
     * 把（句，首字母序列）记进自造词；下次打同样首字母，该句以精
     * 确码匹配置顶（精确码的 quality 高于简拼展开）。内置词典的
     * 长句只是兜底；用户自己打过的句优先于内置词典（自造词 user
     * 段在候选排序里高于系统词典的同码展开）。
     *
     * 两条路分开记：首字母路立即生效（第 1 次）；全拼路仍满 3 次
     * 才把（词，全拼）收入。首字母路已收入的词不拦全拼路——满 3
     * 次后全拼码也补上，两种打法都能打出这句；手管词（用户手动
     * 维护的自造词）仍不拦不碰，全拼路只跳过它。
     */
    fun recordCommit(context: Context, word: String, inputKeys: String? = null): Boolean {
        if (!com.feelime.ime.readHabitLexicon(context)) return false
        if (!isChineseWord(word)) return false
        val store = prefs(context)
        var state = CustomPhraseStore.load(context)
        var changed = false
        // 1) 首字母路：记下序列；词典里还没有（句，序列）这一条就
        // 立即写进去（第 1 次就生效），并记入 abbrev_added。
        val abbrev = validAbbrev(context, word, inputKeys)
        if (abbrev != null) {
            val abbrevKey = KEY_ABBREV_PREFIX + word
            val known = store.getStringSet(abbrevKey, emptySet()) ?: emptySet()
            if (abbrev !in known) {
                store.edit().putStringSet(abbrevKey, known + abbrev).apply()
            }
            if (state.user.none { it.first == word && it.second == abbrev } &&
                state.user.size < MAX_USER_WORDS
            ) {
                state = state.copy(user = state.user + (word to abbrev))
                CustomPhraseStore.save(
                    context,
                    state.enabled,
                    state.items,
                    state.imported,
                    state.user,
                    englishEnabled = state.englishEnabled,
                    symbolRanks = state.symbolRanks,
                    rankAll = state.rankAll,
                    symbolFreqSort = state.symbolFreqSort,
                )
                val abbrevAdded =
                    (store.getStringSet(KEY_ABBREV_ADDED, emptySet()) ?: emptySet())
                store.edit().putStringSet(KEY_ABBREV_ADDED, abbrevAdded + word).apply()
                changed = true
            }
        }
        // 2) 全拼路：满阈值才把（词，全拼）收入。
        val added = (store.getStringSet(KEY_AUTO_ADDED, emptySet()) ?: emptySet())
        if (word in added) return changed
        val auto = CustomPhraseStore.autoPinyinCodes(context, word)
        if (auto.isEmpty()) {
            // 注不上音的词不收入；记入已处理，避免每次提交都重算。
            store.edit().putStringSet(KEY_AUTO_ADDED, added + word).apply()
            return changed
        }
        // 手管词（在自造词里、但不是首字母路/全拼路收入的）不碰；
        // 首字母路已收入的词不拦——全拼码还没补，继续计数。
        val abbrevAdded = (store.getStringSet(KEY_ABBREV_ADDED, emptySet()) ?: emptySet())
        if (word !in abbrevAdded && state.user.any { it.first == word }) return changed
        if (state.user.any { it.first == word && it.second == auto[0] }) return changed
        if (state.user.size >= MAX_USER_WORDS) return changed
        val countKey = KEY_COUNT_PREFIX + word
        val count = store.getInt(countKey, 0) + 1
        if (count < THRESHOLD) {
            store.edit().putInt(countKey, count).apply()
            return changed
        }
        // 词 + 它记下的首字母序列一起收入（全拼码优先，超上限只加能加的）。
        val abbrevs = (store.getStringSet(KEY_ABBREV_PREFIX + word, emptySet()) ?: emptySet())
            .filter { it != auto[0] && state.user.none { u -> u.first == word && u.second == it } }
        val newEntries = listOf(word to auto[0]) + abbrevs.map { word to it }
        val room = MAX_USER_WORDS - state.user.size
        if (room <= 0) return changed
        CustomPhraseStore.save(
            context,
            state.enabled,
            state.items,
            state.imported,
            state.user + newEntries.take(room),
            englishEnabled = state.englishEnabled,
            symbolRanks = state.symbolRanks,
            rankAll = state.rankAll,
            symbolFreqSort = state.symbolFreqSort,
        )
        store.edit()
            .putStringSet(KEY_AUTO_ADDED, added + word)
            .remove(countKey)
            .remove(KEY_ABBREV_PREFIX + word)
            .apply()
        return true
    }

    /**
     * 设置页删除一条自动收入的词：同时从自造词 user 段与已收入记录里
     * 清掉（删后若再打满阈值可重新收入），它记下的首字母序列一并清掉。
     * 首字母路与全拼路的词都在列表里，两路的记录一起清。
     * 返回 true = 确实删了一条。
     */
    fun removeAutoWord(context: Context, word: String): Boolean {
        val store = prefs(context)
        val added = (store.getStringSet(KEY_AUTO_ADDED, emptySet()) ?: emptySet())
        val abbrevAdded = (store.getStringSet(KEY_ABBREV_ADDED, emptySet()) ?: emptySet())
        if (word !in added && word !in abbrevAdded) return false
        val state = CustomPhraseStore.load(context)
        if (state.user.none { it.first == word }) {
            store.edit().putStringSet(KEY_AUTO_ADDED, added - word)
                .putStringSet(KEY_ABBREV_ADDED, abbrevAdded - word)
                .remove(KEY_ABBREV_PREFIX + word).apply()
            return false
        }
        CustomPhraseStore.save(
            context,
            state.enabled,
            state.items,
            state.imported,
            state.user.filter { it.first != word },
            englishEnabled = state.englishEnabled,
            symbolRanks = state.symbolRanks,
            rankAll = state.rankAll,
            symbolFreqSort = state.symbolFreqSort,
        )
        store.edit().putStringSet(KEY_AUTO_ADDED, added - word)
            .putStringSet(KEY_ABBREV_ADDED, abbrevAdded - word)
            .remove(KEY_ABBREV_PREFIX + word).apply()
        return true
    }

    /** 发「自造词已变」广播（与 learnUserWord 相同的重载触发）。 */
    fun broadcastChanged(context: Context) {
        context.sendBroadcast(
            Intent(com.feelime.ime.ACTION_CUSTOM_PHRASES_CHANGED)
                .setPackage(context.packageName),
        )
    }
}
