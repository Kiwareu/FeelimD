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
    private const val KEY_COUNT_PREFIX = "count:"
    /** 收入阈值：同一词上屏满 3 次即自动收入。 */
    const val THRESHOLD = 3
    const val MIN_WORD_LEN = 2
    const val MAX_WORD_LEN = 8
    /** 自造词 user 段上限（与 learnUserWord 同值）。 */
    private const val MAX_USER_WORDS = 200

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isChineseWord(word: String): Boolean =
        word.length in MIN_WORD_LEN..MAX_WORD_LEN &&
            word.all { it in '\u4E00'..'\u9FFF' }

    /** 已自动收入的词（设置页列表用；与手动收入的词分开记）。 */
    fun autoAddedWords(context: Context): List<String> =
        (prefs(context).getStringSet(KEY_AUTO_ADDED, emptySet()) ?: emptySet())
            .sorted()

    /**
     * 记录一次上屏词。返回 true = 本次刚把该词自动收入自造词，调用方
     * 应发 ACTION_CUSTOM_PHRASES_CHANGED 让引擎重载；其余情况 false。
     */
    fun recordCommit(context: Context, word: String): Boolean {
        if (!com.feelime.ime.readHabitLexicon(context)) return false
        if (!isChineseWord(word)) return false
        val store = prefs(context)
        val added = (store.getStringSet(KEY_AUTO_ADDED, emptySet()) ?: emptySet())
        if (word in added) return false
        val state = CustomPhraseStore.load(context)
        if (state.user.any { it.first == word }) return false
        if (state.user.size >= MAX_USER_WORDS) return false
        val countKey = KEY_COUNT_PREFIX + word
        val count = store.getInt(countKey, 0) + 1
        if (count < THRESHOLD) {
            store.edit().putInt(countKey, count).apply()
            return false
        }
        val auto = CustomPhraseStore.autoPinyinCodes(context, word)
        if (auto.isEmpty()) {
            // 注不上音的词不收入；记入已处理，避免每次提交都重算。
            store.edit().putStringSet(KEY_AUTO_ADDED, added + word).apply()
            return false
        }
        CustomPhraseStore.save(
            context,
            state.enabled,
            state.items,
            state.imported,
            state.user + (word to auto[0]),
            englishEnabled = state.englishEnabled,
            symbolRanks = state.symbolRanks,
            rankAll = state.rankAll,
            symbolFreqSort = state.symbolFreqSort,
        )
        store.edit()
            .putStringSet(KEY_AUTO_ADDED, added + word)
            .remove(countKey)
            .apply()
        return true
    }

    /**
     * 设置页删除一条自动收入的词：同时从自造词 user 段与已收入记录里
     * 清掉（删后若再打满阈值可重新收入）。返回 true = 确实删了一条。
     */
    fun removeAutoWord(context: Context, word: String): Boolean {
        val store = prefs(context)
        val added = (store.getStringSet(KEY_AUTO_ADDED, emptySet()) ?: emptySet())
        if (word !in added) return false
        val state = CustomPhraseStore.load(context)
        if (state.user.none { it.first == word }) {
            store.edit().putStringSet(KEY_AUTO_ADDED, added - word).apply()
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
        store.edit().putStringSet(KEY_AUTO_ADDED, added - word).apply()
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
