package com.feelime.ime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 离线翻译（3.76，ONNX 路线）：Helsinki-NLP Opus-MT（MarianMT，6 层
 * Transformer，d_model 512，词表 65,001），经 Xenova 转成 ONNX，
 * 跑在应用已有的 onnxruntime-android 1.27.0 上（手写识别同款运行时，
 * 不引入新原生依赖）。
 *
 * 每个方向一个模型，分两档（manifest 里各一个条目）：
 * - 精简档 int8：编码器 int8 + 合并解码器 int8 + tokenizer.json，
 *   约 114 MiB/方向；
 * - 完整档 fp16：同一架构的 fp16 权重（去量化，质量略优），约 219 MiB/方向。
 * 两档的 tokenizer.json 是同一个文件。引擎优先用已装好的完整档，
 * 没有就用精简档；面板里点下载默认下精简档，完整档在设置页单独下载。
 *
 * 推理：编码器一次前向 → 解码器 greedy 自回归 + KV-cache
 * （merged 解码器的 past_key_values 回灌，use_cache_branch 首步 false、
 * 之后 true；transformers.js 同款用法）。不用 beam search：键盘上翻
 * 一两句话，greedy 又快又够，beam 的 6 路会让每步慢 6 倍。
 *
 * 结构沿用旧版（CyIME slimt 版已删）：单线程 worker（同一时间只开
 * 一个方向的引擎）、最后一次翻译后 60 秒无用就关引擎释放内存、翻译
 * 只翻最新一句（排队时被新句顶掉的旧句不翻）、引擎出错就整个关掉下
 * 次重开。模型目录与下载状态一律走 ModelStore 的现成 API
 * （见 translate-wiring-spec.md §2：不需要补新方法）。
 *
 * .spm 词表文件不进 manifest：分词只用 tokenizer.json（[MarianTokenizer]），
 * .spm 是同一模型的 protobuf 序列化，Kotlin 没有现成解析器，用不上。
 */

/** 一个方向：语言代码、两档的模型 id、三个文件名。 */
data class TranslateDirection(
    val from: String,
    val to: String,
    /** 精简档（int8）的 manifest id，同时也是 role（ModelStore 只按 role
     *  查，role 与 id 取同一值，现有 API 不用改，见 wiring spec §2）。 */
    val modelId: String,
    /** 完整档（fp16）的 manifest id。 */
    val fullModelId: String,
    /** 模型文件在 filesDir/models 下的目录名（= ModelSpec.dir 的第一段）。 */
    val dir: String,
    val fullDir: String,
    val encoder: String,
    val decoder: String,
    val tokenizer: String,
) {
    /** manifest 相对路径（filesDir/models 视角），与 models/manifest.json 一致。 */
    fun manifestPath(name: String): String = "$dir/$name"
    fun fullManifestPath(name: String): String = "$fullDir/$name"
}

/** 档位：已装好的两档里完整档优先（质量略优，速度相近）。 */
enum class TranslateTier { SLIM, FULL }

/** 不依赖 Android Context 的纯逻辑：方向表 + 状态映射 + 档位选择，JVM 单测覆盖。 */
object TranslateDirections {
    val directions = listOf(
        TranslateDirection(
            from = "zh", to = "en",
            modelId = "translate-zh-en", fullModelId = "translate-zh-en-full",
            dir = "translate-zh-en", fullDir = "translate-zh-en-full",
            encoder = "encoder_model_int8.onnx",
            decoder = "decoder_model_merged_int8.onnx",
            tokenizer = "tokenizer.json",
        ),
        TranslateDirection(
            from = "en", to = "zh",
            modelId = "translate-en-zh", fullModelId = "translate-en-zh-full",
            dir = "translate-en-zh", fullDir = "translate-en-zh-full",
            encoder = "encoder_model_int8.onnx",
            decoder = "decoder_model_merged_int8.onnx",
            tokenizer = "tokenizer.json",
        ),
    )

    fun direction(from: String, to: String): TranslateDirection? =
        directions.firstOrNull { it.from == from.lowercase() && it.to == to.lowercase() }

    /** 用完不关会一直占着内存：最后一次翻译后这么久没再用就关掉引擎（两个
     *  ONNX session 与分词器一起释放）。 */
    const val IDLE_CLOSE_MS = 60_000L

    /** 编码器输入上限（Marian 配置 max_length 512；留一位给句尾 </s>）。 */
    const val MAX_INPUT_TOKENS = 511

    /** 译文生成上限（键盘上翻短句够用；过长会拖慢每步的全序列重算——
     *  注意 KV-cache 只省解码器自注意力，logits 仍是全长）。 */
    const val MAX_OUTPUT_TOKENS = 128

    /** 档位选择（纯函数）：完整档装好了就用完整档，否则精简档；都没装好
     *  返回 null。调用方按返回值的有无判断模型是否可翻。 */
    fun installedTier(fullInstalled: Boolean, slimInstalled: Boolean): TranslateTier? =
        when {
            fullInstalled -> TranslateTier.FULL
            slimInstalled -> TranslateTier.SLIM
            else -> null
        }
}

enum class TranslateState { UNSUPPORTED, MISSING, DOWNLOADING, READY }

data class TranslateStatus(
    val state: TranslateState,
    /** 模型整包 MB（四舍五入口径与语音模型一致：+512 KiB 再取整）。 */
    val sizeMb: Int = 0,
    /** 下载中才有值（0..100）；经设置页发起的下载这里拿不到进度，为 null。 */
    val progressPercent: Int? = null,
)

/** ModelStore.State → 四态（纯函数，供 JVM 单测）。ONNX 路线没有原生
 *  .so：arm64/x86_64 都能跑，不再有 UNSUPPORTED 的“库缺失”分支；
 *  方向不支持 / manifest 里没这条目时调用方直接返回 UNSUPPORTED。 */
internal fun translateStateFrom(storeState: ModelStore.State): TranslateState =
    when (storeState) {
        ModelStore.State.BUILT_IN, ModelStore.State.INSTALLED -> TranslateState.READY
        ModelStore.State.DOWNLOADING, ModelStore.State.IMPORTING -> TranslateState.DOWNLOADING
        ModelStore.State.MISSING, ModelStore.State.BROKEN -> TranslateState.MISSING
    }

class TranslateEngine(context: Context) {
    interface Listener {
        fun onDone(text: String)
        fun onError(message: String)
    }

    // 与 AsrEngine 同款归属：引擎自带一个 ModelStore，close() 时 release。
    // 翻译模型不参与 AsrModelSnapshot（它只认 asr-streaming/asr-final/
    // punctuation 三个 role），与语音引擎互不干扰。
    private val appContext = context.applicationContext
    private val modelStore = ModelStore(appContext)

    /** 一个线程按先后翻；同一时间只开一个方向的引擎。 */
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "feelime-translate").apply { isDaemon = true }
    }
    private var engineDirection: TranslateDirection? = null
    private var engineTier: TranslateTier? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokenizer: MarianTokenizer? = null
    private var idleClose: ScheduledFuture<*>? = null

    /** 本引擎发起的下载的最后进度（modelId → 0..100），status() 取用。 */
    private val lastDownloadProgress = ConcurrentHashMap<String, Int>()

    /** 已装好的档位：完整档优先。两档都没装好返回 null。 */
    private fun installedTier(direction: TranslateDirection): TranslateTier? =
        TranslateDirections.installedTier(
            fullInstalled = modelStore.sourceFor(direction.fullModelId) != null,
            slimInstalled = modelStore.sourceFor(direction.modelId) != null,
        )

    fun status(from: String, to: String): TranslateStatus {
        val direction = TranslateDirections.direction(from, to)
            ?: return TranslateStatus(TranslateState.UNSUPPORTED)
        val tier = installedTier(direction)
        // 没装好时按正在下载的那档报大小（完整档在下载中就报完整档），
        // 否则按精简档（下载默认档）报。
        val fullSpec = modelStore.spec(direction.fullModelId)
        val spec = if (fullSpec != null &&
            modelStore.state(fullSpec) == ModelStore.State.DOWNLOADING
        ) {
            fullSpec
        } else {
            modelStore.spec(direction.modelId)
                ?: return TranslateStatus(TranslateState.UNSUPPORTED)
        }
        val sizeMb = ((spec.totalBytes + 512 * 1024) / (1024 * 1024)).toInt()
        if (tier != null) return TranslateStatus(TranslateState.READY, sizeMb)
        val state = translateStateFrom(modelStore.state(spec))
        val progress = lastDownloadProgress[spec.id]
            ?.takeIf { state == TranslateState.DOWNLOADING }
        return TranslateStatus(state, sizeMb, progress)
    }

    /** 触发下载（走设置页同一条 ModelStore.download，断点续传 + SHA-256
     *  校验）。默认下精简档（int8，约 114 MiB）；完整档在设置页单独下载，
     *  装好后引擎自动优先用它。返回 null = 已开始下载或已装好；否则返回
     *  给用户看的原因。注意 ModelStore 同一时间只跑一个下载（全局
     *  activeId）：语音模型正在下载时这里返回“稍后再试”，不排队。 */
    fun download(from: String, to: String): String? {
        val direction = TranslateDirections.direction(from, to) ?: return "离线翻译没有这个方向"
        if (installedTier(direction) != null) return null
        val spec = modelStore.spec(direction.modelId) ?: return "离线翻译没有这个方向"
        if (modelStore.sourceFor(direction.modelId) != null) return null
        val started = modelStore.download(spec, object : ModelStore.Listener {
            override fun onProgress(modelId: String, doneBytes: Long, totalBytes: Long) {
                if (totalBytes > 0) {
                    lastDownloadProgress[modelId] = ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                }
            }

            override fun onFinished(modelId: String, state: ModelStore.State, error: String?) {
                lastDownloadProgress.remove(modelId)
            }
        })
        if (!started) return "另一个模型正在下载，请稍后再试"
        return null
    }

    private val nextId = AtomicInteger(0)
    @Volatile private var newestId = -1
    /** 调用方只会取消自己最新的那一句（旧的已经被新的一句顶掉），记一个就够。 */
    @Volatile private var cancelledId = -1

    /** 翻译一句。返回请求 id（≥0）；方向不支持 / 模型未下载时
     *  立即回调 onError 并返回 -1。结果只经 listener 回调，不阻塞调用方。 */
    fun translate(text: String, from: String, to: String, listener: Listener): Int {
        val direction = TranslateDirections.direction(from, to)
        val failure = when {
            direction == null -> "离线翻译没有这个方向"
            installedTier(direction) == null -> "离线翻译模型还没下载"
            text.isBlank() -> "先输入要翻译的内容"
            else -> null
        }
        if (failure != null) {
            listener.onError(failure)
            return -1
        }
        val id = nextId.getAndIncrement()
        newestId = id
        worker.execute {
            // 翻译栏只要最后一句：排队时又来了新的一句，这句不翻了
            if (id != newestId || id == cancelledId) return@execute
            idleClose?.cancel(false)
            val result = runCatching { translateOnnx(direction!!, text) }
            idleClose = worker.schedule({ closeEngine() }, TranslateDirections.IDLE_CLOSE_MS, TimeUnit.MILLISECONDS)
            if (id == cancelledId) return@execute
            result.onSuccess { listener.onDone(it) }.onFailure { error ->
                Log.w(TAG, "offline translation failed", error)
                // 引擎出错就整个关掉，下次重开（模型文件被删、被换掉时）
                closeEngine()
                listener.onError("离线翻译出错：${error.message ?: error.javaClass.simpleName}")
            }
        }
        return id
    }

    fun cancel(id: Int) {
        if (id >= 0) cancelledId = id
    }

    /** 只在 [worker] 线程上调用。 */
    private fun translateOnnx(direction: TranslateDirection, text: String): String {
        val (encoder, decoder, tok) = engineFor(direction)
        val inputIds = tok.encode(text.trim()).let { ids ->
            if (ids.size > TranslateDirections.MAX_INPUT_TOKENS + 1) {
                // 截到上限再补 </s>（encode 已在末尾加了一个）。
                ids.copyOf(TranslateDirections.MAX_INPUT_TOKENS) +
                    MarianTokenizer.EOS_ID
            } else {
                ids
            }
        }
        val env = OrtEnvironment.getEnvironment()
        val hidden = runEncoder(env, encoder, inputIds)
        try {
            val outputIds = runDecoderGreedy(env, decoder, hidden, inputIds.size)
            return tok.decode(outputIds)
        } finally {
            hidden.close()
        }
    }

    /** 编码器一次前向：返回 last_hidden_state（调用方负责 close）。 */
    private fun runEncoder(
        env: OrtEnvironment,
        encoder: OrtSession,
        inputIds: IntArray,
    ): OnnxTensor {
        val ids = OnnxTensor.createTensor(env, arrayOf(inputIds.map { it.toLong() }.toLongArray()))
        val mask = OnnxTensor.createTensor(env, arrayOf(LongArray(inputIds.size) { 1L }))
        try {
            val output = encoder.run(mapOf("input_ids" to ids, "attention_mask" to mask))
            return output.get(0) as OnnxTensor
        } finally {
            ids.close()
            mask.close()
        }
    }

    /**
     * 解码器 greedy 自回归 + KV-cache。
     * merged 解码器的图输入（实测 onnx/decoder_model_merged_int8.onnx）：
     * input_ids [1, m] int64、encoder_hidden_states [1, n, 512] float32、
     * encoder_attention_mask [1, n] int64、past_key_values.{0..5}.{decoder,
     * encoder}.{key,value} [1, 8, past, 64] float32、use_cache_branch [1] bool；
     * 输出 logits [1, m, 65001] + present.*（下一轮的 past）。
     * 首步 use_cache_branch=false、past 全喂空张量（past 长 0）；之后
     * use_cache_branch=true、past 用上一轮的 present、input_ids 只喂上
     * 一步新出的一个 token。transformers.js 同款用法。
     * 尚未在真机上跑通过：如果首步空 past 被 ORT 拒掉（形状/分支），
     * 第一候选回退是每步都喂全序列 + use_cache_branch=false（不回灌
     * present，慢但一定能跑）——见汇报“仍需真机确认”。
     */
    private fun runDecoderGreedy(
        env: OrtEnvironment,
        decoder: OrtSession,
        hidden: OnnxTensor,
        encoderLength: Int,
    ): IntArray {
        val encoderMask = OnnxTensor.createTensor(
            env, arrayOf(LongArray(encoderLength) { 1L }),
        )
        var past: List<OnnxTensor> = emptyList()
        val generated = ArrayList<Int>()
        try {
            var decoderInput = longArrayOf(MarianTokenizer.DECODER_START_ID.toLong())
            repeat(TranslateDirections.MAX_OUTPUT_TOKENS) { step ->
                val inputs = LinkedHashMap<String, Any>()
                inputs["input_ids"] = OnnxTensor.createTensor(env, arrayOf(decoderInput))
                inputs["encoder_hidden_states"] = hidden
                inputs["encoder_attention_mask"] = encoderMask
                inputs["use_cache_branch"] = OnnxTensor.createTensor(env, booleanArrayOf(step > 0))
                val pastTensors = pastTensors(env, past)
                for ((name, tensor) in pastTensors) inputs[name] = tensor
                val output = decoder.run(inputs)
                try {
                    @Suppress("UNCHECKED_CAST")
                    val logits = (output.get(0).value as Array<Array<FloatArray>>)[0]
                    val lastLogits = logits[logits.size - 1]
                    var bestId = 0
                    var bestScore = Float.NEGATIVE_INFINITY
                    for (i in lastLogits.indices) {
                        if (lastLogits[i] > bestScore) {
                            bestScore = lastLogits[i]
                            bestId = i
                        }
                    }
                    // 本轮的 present.* 留给下一轮当 past；旧 past 用完即关。
                    val nextPast = (1 until output.size()).map { output.get(it) as OnnxTensor }
                    past.forEach { runCatching { it.close() } }
                    past = nextPast
                    if (bestId == MarianTokenizer.EOS_ID) return generated.toIntArray()
                    generated.add(bestId)
                    decoderInput = longArrayOf(bestId.toLong())
                } finally {
                    // input_ids 与本轮现造的 past 空张量是临时的；hidden 与
                    // encoderMask 是调用方/外层持有，不在这里关。
                    (inputs["input_ids"] as OnnxTensor).close()
                    (inputs["use_cache_branch"] as OnnxTensor).close()
                    if (step == 0) pastTensors.values.forEach { runCatching { it.close() } }
                    output.close()
                }
            }
            return generated.toIntArray()
        } finally {
            past.forEach { runCatching { it.close() } }
            encoderMask.close()
        }
    }

    /**
     * past 张量：已有 present 就原样回灌（名字不变：present.0.decoder.key
     * → past_key_values.0.decoder.key）；首步造 24 个空张量
     * （6 层 × decoder/encoder × key/value，past 长 0）。
     */
    private fun pastTensors(
        env: OrtEnvironment,
        past: List<OnnxTensor>,
    ): Map<String, OnnxTensor> {
        if (past.isNotEmpty()) {
            require(past.size == PAST_COUNT) { "past 张量数不对：${past.size}" }
            return PAST_NAMES.zip(past).toMap()
        }
        return PAST_NAMES.associateWith {
            OnnxTensor.createTensor(
                env,
                java.nio.FloatBuffer.allocate(0),
                longArrayOf(1, 8, 0, 64),
            )
        }
    }

    /** 只在 [worker] 线程上调用：按已装档位（完整档优先）开两个 session。 */
    private fun engineFor(direction: TranslateDirection): Triple<OrtSession, OrtSession, MarianTokenizer> {
        val tier = installedTier(direction) ?: error("翻译模型未安装")
        if (encoderSession != null && decoderSession != null &&
            engineDirection == direction && engineTier == tier
        ) {
            return Triple(encoderSession!!, decoderSession!!, tokenizer!!)
        }
        closeEngine()
        // 翻译模型只走下载副本（不进 APK assets）：sourceFor 返回
        // Directory(filesDir/models)，pathFor 拼出三个文件的绝对路径。
        // Assets 分支（理论上不会出现）没有文件系统路径，直接报错。
        val modelId = if (tier == TranslateTier.FULL) direction.fullModelId else direction.modelId
        val manifestPath: (String) -> String =
            if (tier == TranslateTier.FULL) direction::fullManifestPath else direction::manifestPath
        val source = modelStore.sourceFor(modelId)
            as? ModelSource.Directory ?: error("翻译模型未安装")
        fun path(name: String) = source.pathFor(manifestPath(name))
        val started = System.nanoTime()
        val env = OrtEnvironment.getEnvironment()
        val encoderFile = File(path(direction.encoder))
        val decoderFile = File(path(direction.decoder))
        val tokenizerFile = File(path(direction.tokenizer))
        val tok = MarianTokenizer.fromJson(tokenizerFile.readText())
        val encoder = sessionOptions().use { options ->
            env.createSession(encoderFile.absolutePath, options)
        }
        try {
            val decoder = sessionOptions().use { options ->
                env.createSession(decoderFile.absolutePath, options)
            }
            encoderSession = encoder
            decoderSession = decoder
            tokenizer = tok
            engineDirection = direction
            engineTier = tier
        } catch (error: Throwable) {
            runCatching { encoder.close() }
            throw error
        }
        Log.i(
            TAG,
            "loaded $modelId ($tier) in ${(System.nanoTime() - started) / 1_000_000} ms",
        )
        return Triple(encoderSession!!, decoderSession!!, tokenizer!!)
    }

    private fun sessionOptions(): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
        }

    private fun closeEngine() {
        runCatching { encoderSession?.close() }
        runCatching { decoderSession?.close() }
        encoderSession = null
        decoderSession = null
        tokenizer = null
        engineDirection = null
        engineTier = null
    }

    /** 关引擎 + 停 worker + 释放自带的 ModelStore。正在跑的下载会被取消。 */
    fun close() {
        worker.execute { closeEngine() }
        worker.shutdown()
        modelStore.release()
    }

    private companion object {
        const val TAG = "FeelimeTranslate"

        /** merged 解码器的 past/present 张量名（6 层 × 4 个），顺序与
         *  图输出一致（present.0.decoder.key … present.5.encoder.value）。 */
        val PAST_NAMES: List<String> = (0..5).flatMap { layer ->
            listOf(
                "past_key_values.$layer.decoder.key",
                "past_key_values.$layer.decoder.value",
                "past_key_values.$layer.encoder.key",
                "past_key_values.$layer.encoder.value",
            )
        }
        const val PAST_COUNT = 24
    }
}
