package app.lawnchair.nexuslauncher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lawnchair.nexus.news.NewsPictures
import app.lawnchair.nexus.news.sampleSizeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded news pictures, a few screens' worth, so scrolling back does not decode them again. */
private object NewsBitmaps {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun peek(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

/**
 * [bytes] decoded for a [boxWidthPx] by [boxHeightPx] box it will cover: as small as that allows, never
 * smaller than the box (see [sampleSizeFor]). Null if [bytes] are not a picture.
 */
private fun decode(bytes: ByteArray, boxWidthPx: Int, boxHeightPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, boxWidthPx, boxHeightPx)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/**
 * A news picture in a 16:9 box of [widthPx] drawn pixels, cropped to fill. The box is there from the
 * first frame, [modifier]'s background showing in it, so nothing moves when the picture arrives; the
 * picture fades in over it. The saved copy (memory, then disk) is shown first and the network is asked
 * only when there is none; reading and decoding happen off the main thread, so a list never waits on
 * a picture. [onMissing] is called when there is no picture to be had (offline and never saved), so
 * the caller can drop the box instead of leaving an empty one. [aiLabel] marks a picture the box drew
 * instead of the article's own.
 */
@Composable
fun NewsPicture(
    pictures: NewsPictures,
    path: String,
    widthPx: Int,
    modifier: Modifier = Modifier,
    aiLabel: Boolean = false,
    onMissing: () -> Unit = {},
) {
    val heightPx = widthPx * 9 / 16
    val key = remember(pictures, path, widthPx) { pictures.keyFor(path) + "@" + widthPx }
    val currentOnMissing by rememberUpdatedState(onMissing)
    val bitmap by produceState(NewsBitmaps.peek(key), key) {
        value = NewsBitmaps.peek(key)
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                pictures.bytes(path)?.let { decode(it, widthPx, heightPx) }?.also { NewsBitmaps.put(key, it) }
            }
            if (value == null) currentOnMissing()
        }
    }
    Box(modifier.aspectRatio(16f / 9f)) {
        Crossfade(targetState = bitmap, animationSpec = tween(180), label = "newsPicture") { shown ->
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (aiLabel && bitmap != null) AiLabel(Modifier.align(Alignment.BottomEnd).padding(8.dp))
    }
}

/** The small "AI" mark on a picture the box drew. Unobtrusive: dark glass with pale letters, on any picture. */
@Composable
private fun AiLabel(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        BasicText(
            "AI",
            style = TextStyle(
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
            ),
        )
    }
}
