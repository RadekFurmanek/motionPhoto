package cz.furmanek.motionphoto.ui.components

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.furmanek.motionphoto.R
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val THUMBNAIL_PX = 144

/** Round thumbnail of the newest capture; empty circle when there is none or it was deleted. */
@Composable
fun LastCaptureThumbnail(uri: Uri?, onClick: (Uri) -> Unit, modifier: Modifier = Modifier) {
    val resolver = LocalContext.current.contentResolver
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = uri?.let {
            withContext(Dispatchers.IO) {
                try {
                    resolver.loadThumbnail(it, Size(THUMBNAIL_PX, THUMBNAIL_PX), null)
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
            }
        }
    }
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.DarkGray)
            .border(2.dp, Color.White, CircleShape)
            .clickable(enabled = uri != null && bitmap != null, onClickLabel = stringResource(R.string.last_capture)) {
                uri?.let(onClick)
            },
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.last_capture),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
