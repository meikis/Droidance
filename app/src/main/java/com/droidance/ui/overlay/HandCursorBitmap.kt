// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color

/**
 * 机器人小手指针的位图素材（`robot_hand_pointer.png`）。沿用原小手的黑底素材加载方式，
 * 把接近纯黑的背景转成透明，
 * 再按目标尺寸缩放，解码结果按尺寸缓存。
 *
 * 主屏由 sidecar 合成位图、虚拟屏预览层由 View 直接绘制，两处共用这里的同一张图，长相一致。
 */
internal object HandCursorBitmap {
    const val ASSET = "robot_hand_pointer.png"
    private const val BACKGROUND_KEY_THRESHOLD = 18

    private val cache = HashMap<Int, Bitmap>()

    /** 返回 [sizePx] 见方的小手位图；同一尺寸只解码一次。 */
    @Synchronized
    fun of(context: Context, sizePx: Int): Bitmap {
        val size = sizePx.coerceAtLeast(1)
        cache[size]?.takeIf { !it.isRecycled }?.let { return it }
        val rendered = render(context, size)
        cache[size] = rendered
        return rendered
    }

    private fun render(context: Context, size: Int): Bitmap {
        val original = context.assets.open(ASSET).use(BitmapFactory::decodeStream)
            ?: error("$ASSET decode failed")
        val pixels = IntArray(original.width * original.height)
        original.getPixels(pixels, 0, original.width, 0, 0, original.width, original.height)
        pixels.indices.forEach { index ->
            val color = pixels[index]
            if (Color.red(color) < BACKGROUND_KEY_THRESHOLD &&
                Color.green(color) < BACKGROUND_KEY_THRESHOLD &&
                Color.blue(color) < BACKGROUND_KEY_THRESHOLD
            ) {
                pixels[index] = Color.TRANSPARENT
            }
        }
        val transparent = Bitmap.createBitmap(
            original.width,
            original.height,
            Bitmap.Config.ARGB_8888
        ).apply {
            setPixels(pixels, 0, original.width, 0, 0, original.width, original.height)
        }
        original.recycle()
        val scaled = Bitmap.createScaledBitmap(transparent, size, size, true)
        if (scaled != transparent) transparent.recycle()
        return scaled
    }
}
