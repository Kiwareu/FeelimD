package com.feelime.ime

/** 所有入口与计时回调都在 ASR worker 上执行，卸载与解码共享串行边界。 */
internal class AsrIdleUnload(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val unload: () -> Unit,
) {
    companion object {
        /** 常规模式：录音结束空闲 5 分钟卸载。 */
        const val DEFAULT_TIMEOUT_MS = 5 * 60_000L
        /** 省电模式：空闲 1 分钟卸载（3.74.9）。 */
        const val POWER_SAVING_TIMEOUT_MS = 60_000L
    }

    /** 当前空闲卸载超时；AsrEngine 按省电开关改写（改写即重新布防）。 */
    @Volatile var timeoutMs: Long = DEFAULT_TIMEOUT_MS

    private var enabled = false
    private var idle = false
    private var generation = 0L
    private var cancel: (() -> Unit)? = null

    fun setEnabled(on: Boolean) {
        if (enabled == on) return
        enabled = on
        arm()
    }

    fun setTimeout(timeout: Long) {
        if (timeoutMs == timeout) return
        timeoutMs = timeout
        arm()
    }

    fun becameActive() {
        idle = false
        arm()
    }

    fun becameIdle() {
        idle = true
        arm()
    }

    private fun arm() {
        cancel?.invoke()
        cancel = null
        val request = ++generation
        if (!enabled || !idle) return
        cancel = schedule(timeoutMs) {
            if (request == generation && enabled && idle) {
                cancel = null
                unload()
            }
        }
    }

    fun close() {
        idle = false
        arm()
    }
}
