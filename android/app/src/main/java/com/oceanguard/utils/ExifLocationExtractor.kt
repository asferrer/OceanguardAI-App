package com.oceanguard.ai.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.LocationSource

/**
 * Extracts GPS coordinates from image EXIF metadata.
 *
 * On Android 10+ (API 29), `MediaStore` content URIs redact GPS tags unless the
 * app holds `ACCESS_MEDIA_LOCATION` and requests the original file via
 * [MediaStore.setRequireOriginal]. This extractor handles both cases transparently.
 */
object ExifLocationExtractor {

    private const val TAG = "ExifLocationExtractor"

    /**
     * Extract GPS location from the EXIF metadata of an image.
     *
     * @param context Application context for ContentResolver access.
     * @param uri Content URI of the image.
     * @return [Location] with [LocationSource.EXIF] if GPS data is found, null otherwise.
     */
    fun extract(context: Context, uri: Uri): Location? {
        // On API 29+ MediaStore URIs, request the original (un-redacted) file
        val resolvedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            isMediaStoreUri(uri)
        ) {
            try {
                MediaStore.setRequireOriginal(uri)
            } catch (_: Exception) {
                uri
            }
        } else {
            uri
        }

        return readExifLocation(context, resolvedUri)
            ?: readExifLocation(context, uri) // retry with original URI as fallback
    }

    private fun readExifLocation(context: Context, uri: Uri): Location? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                val latLong = exif.latLong ?: return null

                Log.d(TAG, "EXIF GPS found: lat=${latLong[0]}, lon=${latLong[1]} from $uri")
                Location(
                    latitude = latLong[0],
                    longitude = latLong[1],
                    source = LocationSource.EXIF,
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to read EXIF from $uri: ${e.message}")
            null
        }
    }

    private fun isMediaStoreUri(uri: Uri): Boolean {
        val authority = uri.authority ?: return false
        return authority == "media" ||
            authority == MediaStore.AUTHORITY ||
            authority.contains("com.google.android.apps.photos")
    }
}
