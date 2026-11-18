package com.feelime.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** 工具栏左一按钮的自定义图标（3.76 原地更新）：单文件，存应用私有
 *  目录，不进备份（与用户字体 #70、背景图同口径——恢复后选择仍在，
 *  文件需重新上传；键盘侧无图时回落默认 F 标志）。
 *  上传图经 [decodeImport] 居中裁剪成正方形、缩到 192px 后存 PNG；
 *  键盘与设置页都经本地路由取图，不走 hello（base64 会撑大快照）。 */
internal object ToolbarIconStore {
    const val PREFIX = "/keyboard/toolbar-icon/"
    const val HOST = "feelime.local"
    const val TARGET_PX = 192
    const val MAX_IMPORT_BYTES = 8 * 1024 * 1024

    private const val VERSION_KEY = "toolbar_icon_version"

    fun iconFile(context: Context): File =
        File(context.filesDir, "toolbar-icon/icon.png")

    @Synchronized fun url(context: Context): String {
        val file = iconFile(context)
        if (!file.isFile) return ""
        val version = context.getSharedPreferences(KEYBOARD_PREFS_FILE, Context.MODE_PRIVATE)
            .getString(VERSION_KEY, null) ?: "${file.lastModified()}-${file.length()}"
        return "https://$HOST${PREFIX}$version.png"
    }

    @Synchronized fun response(context: Context, path: String): WebResourceResponse? {
        if (path != android.net.Uri.parse(url(context)).path) return null
        return runCatching {
            WebResourceResponse("image/png", null, 200, "OK",
                mapOf("Cache-Control" to "no-cache"), iconFile(context).inputStream())
        }.getOrNull()
    }

    @Synchronized fun install(context: Context, bytes: ByteArray) {
        val target = iconFile(context)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            temp.outputStream().use { it.write(bytes) }
            check(temp.renameTo(target))
            context.getSharedPreferences(KEYBOARD_PREFS_FILE, Context.MODE_PRIVATE).edit()
                .putString(VERSION_KEY, java.util.UUID.randomUUID().toString()).commit()
        } finally { temp.delete() }
    }

    @Synchronized fun delete(context: Context) {
        runCatching { iconFile(context).delete() }
        context.getSharedPreferences(KEYBOARD_PREFS_FILE, Context.MODE_PRIVATE).edit()
            .remove(VERSION_KEY).commit()
    }

    /** 居中裁剪成正方形再缩到 192px（按钮只画 16px，192px 供高分屏
     *  余量）。先读尺寸算采样，不先解完整原图；EXIF 朝向沿用浏览器
     *  口径（与 BackgroundImageStore.decodeImport 同源）。 */
    fun decodeImport(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty() && bytes.size <= MAX_IMPORT_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "image has no pixels" }
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        }.getOrDefault(1)
        val rotated = orientation in 5..8
        val width = if (rotated) bounds.outHeight else bounds.outWidth
        val height = if (rotated) bounds.outWidth else bounds.outHeight
        // 采样目标取裁剪边（短边），封顶 1024px——192px 输出 5 倍超采
        //  已足够，再大只白占解码预算（4000px 方图不采样会撞 12M 上限）。
        val edge = minOf(width, height)
        val sampleTarget = minOf(edge, 1024).coerceAtLeast(TARGET_PX)
        var sample = 1
        while (sample <= Int.MAX_VALUE / 2 &&
            (bounds.outWidth / sample > sampleTarget || bounds.outHeight / sample > sampleTarget)
        ) {
            val next = sample * 2
            if (maxOf(1, bounds.outWidth / next) < TARGET_PX ||
                maxOf(1, bounds.outHeight / next) < TARGET_PX
            ) break
            sample = next
        }
        val decodedWidth = (bounds.outWidth.toLong() + sample - 1) / sample
        val decodedHeight = (bounds.outHeight.toLong() + sample - 1) / sample
        require(maxOf(decodedWidth, decodedHeight) <= 8192 &&
            decodedWidth * decodedHeight <= 12_000_000) { "image exceeds decode pixel budget" }
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
        var oriented: Bitmap? = null
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(-90f); postScale(-1f, 1f) }
                    8 -> setRotate(-90f)
                }
            }
            oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val side = minOf(oriented.width, oriented.height)
            val left = (oriented.width - side) / 2
            val top = (oriented.height - side) / 2
            cropped = Bitmap.createBitmap(oriented, left, top, side, side)
            scaled = Bitmap.createScaledBitmap(cropped, TARGET_PX, TARGET_PX, true)
            return ByteArrayOutputStream().use { output ->
                check(scaled.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            scaled?.takeIf { it !== cropped && it !== oriented && it !== decoded }?.recycle()
            cropped?.takeIf { it !== oriented && it !== decoded }?.recycle()
            oriented?.takeIf { it !== decoded }?.recycle()
            decoded.recycle()
        }
    }
}
