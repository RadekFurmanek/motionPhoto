package cz.furmanek.motionphoto.camera

/**
 * Runs [check], which may build the segmenter and ask Play services about it. Anything it throws (a missing
 * class surfaces as a [LinkageError]) means the custom portrait is unavailable and is handed to [onFailure],
 * so a broken ML Kit costs one mode instead of the whole camera.
 */
fun portraitAvailable(onFailure: (Throwable) -> Unit, check: () -> Boolean): Boolean =
    try {
        check()
    } catch (e: Exception) {
        onFailure(e)
        false
    } catch (e: LinkageError) {
        onFailure(e)
        false
    }
