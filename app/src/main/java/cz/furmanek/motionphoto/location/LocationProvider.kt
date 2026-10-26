package cz.furmanek.motionphoto.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import android.os.CancellationSignal
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Location for geotagging captures, without Google Play services. */
class LocationProvider(private val context: Context) {

    fun hasPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** A fresh fix if one arrives within [CURRENT_FIX_TIMEOUT_MS], else the newest last-known one; null without permission. */
    @SuppressLint("MissingPermission")
    suspend fun currentOrNull(): Location? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = PROVIDERS.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        val provider = providers.firstOrNull() ?: return null
        val fresh = withTimeoutOrNull(CURRENT_FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(context),
                ) { continuation.resume(it) }
            }
        }
        return fresh ?: providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }

    private companion object {
        const val CURRENT_FIX_TIMEOUT_MS = 2_000L
        val PROVIDERS = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }
}
