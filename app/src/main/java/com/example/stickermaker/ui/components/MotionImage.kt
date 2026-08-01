package com.example.stickermaker.ui.components

import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.withFrameMillis
import com.example.stickermaker.editor.MotionPlan
import com.example.stickermaker.editor.StickerMotion

/**
 * A square sticker [image], moving along [plan] (null: still). Steps through the same 25 fps
 * frames the saved file has, so the preview is exactly what WhatsApp will play. The clock is
 * read only while drawing: no recomposition per frame.
 */
@Composable
fun MotionImage(image: ImageBitmap, plan: MotionPlan?, contentDescription: String?, modifier: Modifier = Modifier) {
    val clock = remember { mutableLongStateOf(0L) }
    if (plan != null) {
        LaunchedEffect(plan) {
            val start = withFrameMillis { it }
            while (true) withFrameMillis { clock.longValue = it - start }
        }
    }
    Spacer(
        modifier
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .drawWithCache {
                val bitmap = image.asAndroidBitmap()
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                val matrix = Matrix()
                val fit = minOf(size.width / bitmap.width, size.height / bitmap.height)
                val left = (size.width - bitmap.width * fit) / 2
                val top = (size.height - bitmap.height * fit) / 2
                onDrawBehind {
                    if (plan == null) {
                        matrix.reset()
                    } else {
                        val frames = plan.motion.frameCount
                        val frame = (clock.longValue / StickerMotion.FRAME_MS % frames).toInt()
                        matrix.setValues(plan.frame(frame).values())
                    }
                    matrix.postScale(fit, fit)
                    matrix.postTranslate(left, top)
                    drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmap, matrix, paint) }
                }
            },
    )
}
