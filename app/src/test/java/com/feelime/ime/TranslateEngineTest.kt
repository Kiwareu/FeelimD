package com.feelime.ime

import org.junit.Assert.*
import org.junit.Test

/** 离线翻译纯逻辑（TranslateDirections + 状态映射 + 档位选择）：
 *  不碰 ONNX 运行时与 Context，跑在 JVM 上。方向表的文件名必须与
 *  models/manifest.json 里的翻译条目 path 一致。 */
class TranslateEngineTest {

    @Test fun `two directions zh-en and en-zh`() {
        assertEquals(2, TranslateDirections.directions.size)
        val zhEn = TranslateDirections.direction("zh", "en")
        val enZh = TranslateDirections.direction("en", "zh")
        assertNotNull(zhEn)
        assertNotNull(enZh)
        assertEquals("translate-zh-en", zhEn!!.modelId)
        assertEquals("translate-en-zh", enZh!!.modelId)
        // role 与 modelId 取同一值，ModelStore 现成 API（spec/sourceFor）
        // 才能按 role 查到翻译模型，见 translate-wiring-spec.md §2。
        assertEquals(zhEn.modelId, zhEn.dir)
        assertEquals(enZh.modelId, enZh.dir)
    }

    @Test fun `each direction has a slim int8 and a full fp16 model`() {
        val zhEn = TranslateDirections.direction("zh", "en")!!
        assertEquals("translate-zh-en-full", zhEn.fullModelId)
        assertEquals("translate-zh-en-full", zhEn.fullDir)
        assertEquals("encoder_model_int8.onnx", zhEn.encoder)
        assertEquals("decoder_model_merged_int8.onnx", zhEn.decoder)
        assertEquals("tokenizer.json", zhEn.tokenizer)
        assertEquals("translate-zh-en/encoder_model_int8.onnx", zhEn.manifestPath(zhEn.encoder))
        assertEquals("translate-zh-en/decoder_model_merged_int8.onnx", zhEn.manifestPath(zhEn.decoder))
        assertEquals("translate-zh-en/tokenizer.json", zhEn.manifestPath(zhEn.tokenizer))
        // 完整档与精简档是同一架构的不同量化，文件名相同、目录不同；
        // 分词器是同一个 tokenizer.json（两档共用一份词表）。
        assertEquals("translate-zh-en-full/encoder_model_int8.onnx", zhEn.fullManifestPath(zhEn.encoder))
        assertEquals("translate-zh-en-full/tokenizer.json", zhEn.fullManifestPath(zhEn.tokenizer))
        val enZh = TranslateDirections.direction("en", "zh")!!
        assertEquals("translate-en-zh-full", enZh.fullModelId)
        assertEquals("translate-en-zh-full", enZh.fullDir)
    }

    @Test fun `direction lookup is case-insensitive and unknown pairs are null`() {
        assertNotNull(TranslateDirections.direction("ZH", "EN"))
        assertNotNull(TranslateDirections.direction("En", "Zh"))
        assertNull(TranslateDirections.direction("zh", "ja"))
        assertNull(TranslateDirections.direction("en", "en"))
        assertNull(TranslateDirections.direction("", ""))
    }

    @Test fun `idle close is 60 seconds`() {
        assertEquals(60_000L, TranslateDirections.IDLE_CLOSE_MS)
    }

    @Test fun `input and output token caps`() {
        assertEquals(511, TranslateDirections.MAX_INPUT_TOKENS)
        assertEquals(128, TranslateDirections.MAX_OUTPUT_TOKENS)
    }

    @Test fun `installed tier prefers full over slim`() {
        assertEquals(TranslateTier.FULL, TranslateDirections.installedTier(true, true))
        assertEquals(TranslateTier.FULL, TranslateDirections.installedTier(true, false))
        assertEquals(TranslateTier.SLIM, TranslateDirections.installedTier(false, true))
        assertNull(TranslateDirections.installedTier(false, false))
    }

    @Test fun `store state maps to the four translate states`() {
        assertEquals(TranslateState.READY, translateStateFrom(ModelStore.State.BUILT_IN))
        assertEquals(TranslateState.READY, translateStateFrom(ModelStore.State.INSTALLED))
        assertEquals(TranslateState.DOWNLOADING, translateStateFrom(ModelStore.State.DOWNLOADING))
        assertEquals(TranslateState.DOWNLOADING, translateStateFrom(ModelStore.State.IMPORTING))
        assertEquals(TranslateState.MISSING, translateStateFrom(ModelStore.State.MISSING))
        assertEquals(TranslateState.MISSING, translateStateFrom(ModelStore.State.BROKEN))
    }
}
