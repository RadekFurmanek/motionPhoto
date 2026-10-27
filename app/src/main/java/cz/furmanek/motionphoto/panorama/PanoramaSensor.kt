package cz.furmanek.motionphoto.panorama

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Heading and forward tilt of an upright phone. Prefers the game rotation vector (gyro and accelerometer): a
 * panorama needs only relative heading, and the magnetometer's pull toward north makes the heading jump indoors.
 */
class PanoramaSensor(context: Context) {

    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var listener: SensorEventListener? = null

    val available: Boolean get() = sensor != null

    /**
     * [onReading] gets heading and tilt in degrees and the device-to-world rotation matrix (reused between calls:
     * copy it to keep it) on the main thread, about 60 times a second.
     */
    fun start(onReading: (yawDeg: Float, pitchDeg: Float, rotation: FloatArray) -> Unit) {
        val sensor = sensor ?: return
        stop()
        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        val newListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                // Upright phone, camera looking forward: measure around the axis through the screen's top.
                SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
                SensorManager.getOrientation(remapped, orientation)
                onReading(
                    Math.toDegrees(orientation[0].toDouble()).toFloat(),
                    Math.toDegrees(orientation[1].toDouble()).toFloat(),
                    rotation,
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = newListener
        manager?.registerListener(newListener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        listener?.let { manager?.unregisterListener(it) }
        listener = null
    }
}
