package com.feelime.ime.engine

import android.content.Context

/**
 * 拼音字形选择（3.76 原地更新第 4 项：繁体中文（中国台湾）输入）。
 * 原生持有选择（feelime_engine 偏好，与模糊音同一文件）：简体（默认）
 * 或繁体（台湾）。切换后由设置页广播，IME 重建会话即生效；键盘与候选
 * 同步变（候选文本由 librime 的 simplifier 滤镜转换，见下）。
 *
 * 转换由 librime 官方机制承载，不新增 JNI、不改词库：
 * - 词库（luna_pinyin.table.bin）是简体；8 个拼音类 schema
 *   （全拼/模糊全拼/T9/模糊 T9/双拼四方案）的 zh_hant_tw 开关挂
 *   opencc s2tw.json（简 -> 繁（台湾），OpenCC 数据 + FeelimD
 *   台湾用语补表，见 engine-data/rime/opencc/）。
 * - 开关翻转走 key_binder 的 set_option 绑定（pinned librime
 *   key_binder.cc：radio 组按名精确选中，兄弟开关互斥），合成键
 *   Control+Shift+6（繁）/ Control+Shift+7（简）由
 *   [RimeTextEngine.applyScriptVariant] 在每次建会话后发出。
 * - librime 在每次建会话时按 schema 的 reset:1 把开关复位回 zh_hans
 *   （pinned librime engine.cc ConcreteEngine::InitializeOptions），
 *   所以重发是必须的、不是优化；简体模式不用发键（复位值即简体）。
 * - 音形码表（feelime_flypy）与笔画没有拼音 simplifier，不参与；
 *   联想（AssociationStore 的简体 bigram 表）不经 librime，繁体模式
 *   下常查不到、查到也是简体——已知缺口，见 docs/design/keyboard.md。
 */
object ScriptVariant {
    const val PREF_FILE = "feelime_engine"
    const val PREF_KEY = "script_variant"

    const val HANS = "hans"
    const val HANT_TW = "hant_tw"

    fun current(context: Context): String {
        val stored = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getString(PREF_KEY, null)
        return if (stored == HANT_TW) HANT_TW else HANS
    }

    fun isTraditionalTw(context: Context): Boolean = current(context) == HANT_TW

    /** 落盘；未知值返回 false（调用方负责报错，不落盘）。 */
    fun set(context: Context, value: String): Boolean {
        if (value != HANS && value != HANT_TW) return false
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY, value).commit()
        return true
    }
}
