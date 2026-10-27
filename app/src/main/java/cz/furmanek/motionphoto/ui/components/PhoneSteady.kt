package cz.furmanek.motionphoto.ui.components

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cz.furmanek.motionphoto.portrait.Steadiness
import kotlin.math.sqrt

/** Whether the phone is held still, from the gyroscope; always true on a phone without one. */
@Composable
fun rememberPhoneSteady(): State<Boolean> {
    val context = LocalContext.current
    val steady = remember { mutableStateOf(true) }
    DisposableEffect(context) {
        val manager = context.getSystemService(SensorManager::class.java)
        val gyro = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val tracker = Steadiness()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                steady.value = tracker.update(sqrt(x * x + y * y + z * z), SystemClock.elapsedRealtime())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (gyro != null) manager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            manager?.unregisterListener(listener)
            steady.value = true
        }
    }
    return steady
}
