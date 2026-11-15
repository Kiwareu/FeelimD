package com.feelime.ime.update

/** Release signing identity; the matching private key is kept offline. */
object ReleaseKeys {
    const val KEY_ID = "feelime-fork-2026-11"
    val PUBLIC_KEY: ByteArray = hex("dac7f580baab67f82b7f3658f94e88a628f14f4bf6450d592081d1f49b096de5")

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
