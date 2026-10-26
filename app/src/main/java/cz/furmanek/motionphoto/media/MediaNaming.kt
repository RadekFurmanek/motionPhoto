package cz.furmanek.motionphoto.media

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class MediaKind(val suffix: String, val mimeType: String) {
    MOTION_PHOTO(".MP.jpg", "image/jpeg"),
    PHOTO(".jpg", "image/jpeg"),
    VIDEO(".mp4", "video/mp4"),
}

/** A capture as saved: where it is and what it turned out to be. */
data class SavedMedia(val uri: android.net.Uri, val kind: MediaKind)

object MediaNaming {
    // Pixel phones stamp PXL_ names in UTC; local time stays in DATE_TAKEN and EXIF.
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)

    fun fileName(kind: MediaKind, capturedAtMillis: Long): String =
        "PXL_" + FORMAT.format(Instant.ofEpochMilli(capturedAtMillis)) + kind.suffix
}
