package com.example.stickermaker.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.example.stickermaker.LocalAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A local image file, decoded off the main thread at ≤ [maxPx]; a skeleton until ready. */
@Composable
fun FileImage(
    file: File,
    modifier: Modifier = Modifier,
    maxPx: Int = 384,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
) {
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
