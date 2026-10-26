package cz.furmanek.motionphoto

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import cz.furmanek.motionphoto.ui.CameraPermissionGate
import cz.furmanek.motionphoto.ui.CameraScreen

class MainActivity : ComponentActivity() {

    private val session get() = (application as MotionPhotoApp).cameraSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CameraPermissionGate {
                    CameraScreen()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isShutterKey(keyCode)) {
            // Held keys repeat; only the first press shoots.
            if (event.repeatCount == 0) session.onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        isShutterKey(keyCode) || super.onKeyUp(keyCode, event)

    private fun isShutterKey(keyCode: Int): Boolean =
        session.volumeKeyShutter &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA)
}
