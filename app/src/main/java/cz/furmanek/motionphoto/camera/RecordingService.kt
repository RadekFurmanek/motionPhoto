package cz.furmanek.motionphoto.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cz.furmanek.motionphoto.MainActivity
import cz.furmanek.motionphoto.MotionPhotoApp
import cz.furmanek.motionphoto.R

/**
 * Keeps the process in the foreground while a video records, so recording goes on with the screen off
 * or another app in front. The camera itself stays with [CameraSession]; this only holds the process.
 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val withAudio = intent.getBooleanExtra(EXTRA_WITH_AUDIO, false)
                ServiceCompat.startForeground(this, NOTIFICATION_ID, recordingNotification(this), foregroundServiceTypes(withAudio))
            }
            ACTION_STOP_RECORDING -> (application as MotionPhotoApp).cameraSession.stopRecording()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ACTION_START = "cz.furmanek.motionphoto.action.START_RECORDING_SERVICE"
        private const val ACTION_STOP_RECORDING = "cz.furmanek.motionphoto.action.STOP_RECORDING"
        private const val EXTRA_WITH_AUDIO = "with_audio"
        private const val CHANNEL_RECORDING = "recording"
        private const val CHANNEL_RESULTS = "recording_results"
        private const val NOTIFICATION_ID = 1
        private const val RESULT_NOTIFICATION_ID = 2

        fun start(context: Context, withAudio: Boolean) {
            ensureChannels(context)
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(ACTION_START).putExtra(EXTRA_WITH_AUDIO, withAudio),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }

        /** Tells the user about a recording that ended badly while the app was not on screen. */
        fun notifyResult(context: Context, message: String) {
            ensureChannels(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(message)
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                .build()
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) {
                try {
                    manager.notify(RESULT_NOTIFICATION_ID, notification)
                } catch (_: SecurityException) {
                    // POST_NOTIFICATIONS revoked between the check and the call.
                }
            }
        }

        private fun recordingNotification(context: Context): Notification {
            val stop = PendingIntent.getService(
                context,
                0,
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP_RECORDING),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_RECORDING)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.recording_notification_title))
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setContentIntent(openAppIntent(context))
                .addAction(0, context.getString(R.string.recording_stop), stop)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RECORDING,
                    context.getString(R.string.recording_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RESULTS,
                    context.getString(R.string.recording_results_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }
}
