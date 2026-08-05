package com.example.stickermaker.ui.components

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.stickermaker.LocalAppContainer
import com.example.stickermaker.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A local image file, decoded off the main thread at ≤ [maxPx]; a skeleton until ready.
 * [animated] plays an animated WebP (fitted, like [ContentScale.Fit]); the still first frame
 * shows while it loads, and stays when the system asks to reduce motion.
 */
@Composable
fun FileImage(
    file: File,
    modifier: Modifier = Modifier,
    maxPx: Int = 384,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    animated: Boolean = false,
) {
    if (animated && !LocalReduceMotion.current) {
        val drawable by produceState<AnimatedImageDrawable?>(null, file, maxPx) {
            value = withContext(Dispatchers.IO) { decodeAnimated(file, maxPx) }
        }
        drawable?.let {
            AnimatedDrawableImage(it, contentDescription, modifier)
            return
        }
    }
    StillFileImage(file, modifier, maxPx, contentScale, contentDescription)
}

@Composable
private fun StillFileImage(file: File, modifier: Modifier, maxPx: Int, contentScale: ContentScale, contentDescription: String?) {
    val cache = LocalAppContainer.current.thumbnails
    val key = remember(file, maxPx) { cache.key(file, maxPx) }
    val bitmap by produceState(cache.peek(file, maxPx), key) {
        if (value == null) value = withContext(Dispatchers.IO) { cache.load(file, maxPx) }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    if (image != null) {
        Image(image, contentDescription, modifier, contentScale = contentScale)
    } else {
        SkeletonBlock(modifier)
    }
}

/**
 * Plays [drawable] fitted in the box. It decodes frames on its own thread; each new frame
 * bumps [tick], which only the draw phase reads, so playing never recomposes.
 */
@Composable
private fun AnimatedDrawableImage(drawable: AnimatedImageDrawable, contentDescription: String?, modifier: Modifier) {
    val tick = remember { mutableIntStateOf(0) }
    // Drawables hold their callback weakly: keep it here for as long as this is shown.
    val callback = remember(drawable) {
        object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                tick.intValue++
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
    }
    DisposableEffect(drawable) {
        drawable.callback = callback
        drawable.start()
        onDispose {
            drawable.stop()
            drawable.callback = null
        }
    }
    Box(
        modifier
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .drawBehind {
                tick.intValue // redraw on every frame
                val w = drawable.intrinsicWidth.toFloat()
                val h = drawable.intrinsicHeight.toFloat()
                val scale = minOf(size.width / w, size.height / h)
                drawable.setBounds(0, 0, w.toInt(), h.toInt())
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    val save = native.save()
                    native.translate((size.width - w * scale) / 2, (size.height - h * scale) / 2)
                    native.scale(scale, scale)
                    drawable.draw(native)
                    native.restoreToCount(save)
                }
            },
    )
}

/** An animated WebP at ≤ [maxPx], or null if it isn't animated (or can't be read). */
private fun decodeAnimated(file: File, maxPx: Int): AnimatedImageDrawable? = runCatching {
    ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val longest = max(info.size.width, info.size.height)
        if (longest > maxPx) {
            val s = maxPx.toFloat() / longest
            decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
        }
    } as? AnimatedImageDrawable
}.onFailure { Log.w("FileImage", "Couldn't decode ${file.name}", it) }.getOrNull()
