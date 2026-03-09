package com.oceanguard.ai.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.LocationSource
import kotlinx.coroutines.tasks.await

/**
 * LocationProvider — one-shot wrapper around [FusedLocationProviderClient].
 *
 * Retrieves the last known device location without setting up a continuous
 * location update stream. This keeps the API surface simple for the OceanGuard
 * use-case where location is only needed at the moment a photo is captured.
 *
 * ## Permission handling
 * The caller is responsible for requesting location permissions before calling
 * [getLastKnownLocation]. If neither [Manifest.permission.ACCESS_FINE_LOCATION]
 * nor [Manifest.permission.ACCESS_COARSE_LOCATION] is granted at call time,
 * the function returns `null` immediately without throwing.
 *
 * ## Accuracy note
 * `lastLocation` may return `null` when:
 *   - The device has never obtained a fix since the last reboot.
 *   - All location providers are disabled in Settings.
 *   - The app is running in the background and was recently denied access.
 * In all such cases this function also returns `null`.
 *
 * @param context Application or Activity context used to build the
 *   [FusedLocationProviderClient] and to check runtime permissions.
 */
class LocationProvider(private val context: Context) {

    companion object {
        private const val TAG = "LocationProvider"
    }

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Returns the last known device location as a [Location] data class, or
     * `null` if the location permission has not been granted or if the fused
     * provider has no cached fix available.
     *
     * This is a suspend function that awaits the [com.google.android.gms.tasks.Task]
     * returned by [FusedLocationProviderClient.lastLocation] using
     * [kotlinx.coroutines.tasks.await]. Call it from a coroutine scope; it does
     * not block the calling thread.
     *
     * @return [Location] with [LocationSource.GPS] when a fix is available,
     *   or `null` when the permission is denied or no fix is cached.
     */
    suspend fun getLastKnownLocation(): Location? {
        // Guard: check at least coarse permission before calling the API.
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Location permission not granted — returning null")
            return null
        }

        return try {
            // lastLocation completes immediately if a cached fix exists;
            // it resolves to null if no fix is available.
            val androidLocation: android.location.Location? = fusedClient.lastLocation.await()

            if (androidLocation == null) {
                Log.d(TAG, "FusedLocationProvider returned null — no cached fix available")
                return null
            }

            Log.d(
                TAG,
                "Location obtained: lat=${androidLocation.latitude}, " +
                    "lon=${androidLocation.longitude}, " +
                    "accuracy=${androidLocation.accuracy}m"
            )

            Location(
                latitude = androidLocation.latitude,
                longitude = androidLocation.longitude,
                accuracy = androidLocation.accuracy,
                source = LocationSource.GPS,
            )
        } catch (exception: SecurityException) {
            // Should not occur after the permission check above, but guard
            // defensively against race conditions with permission revocation.
            Log.e(TAG, "SecurityException accessing location — permission was revoked?", exception)
            null
        } catch (exception: Exception) {
            Log.e(TAG, "Unexpected error while retrieving last known location", exception)
            null
        }
    }
}
