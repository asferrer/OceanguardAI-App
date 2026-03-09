package com.oceanguard.ai.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Saves annotated detection images to the device's public gallery
 * via [MediaStore], making them visible in the Photos app.
 */
object ImageGallerySaver {

    private const val TAG = "ImageGallerySaver"

    /**
     * Copy an image from [sourceUri] (internal file:// or content://) to the
     * public Pictures/OceanGuard directory.
     *
     * @return The public gallery [Uri] on success, or `null` on failure.
     */
    fun saveToGallery(context: Context, sourceUri: String, displayName: String? = null): Uri? {
        return try {
            val uri = Uri.parse(sourceUri)
            val resolver = context.contentResolver

            val filename = displayName
                ?: "OceanGuard_${System.currentTimeMillis()}.jpg"

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OceanGuard")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val galleryUri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues,
            ) ?: run {
                Log.e(TAG, "MediaStore insert returned null")
                return null
            }

            // Copy bytes from source to gallery
            val inputStream = when (uri.scheme) {
                "file" -> File(uri.path!!).inputStream()
                else -> resolver.openInputStream(uri)
            }

            inputStream?.use { input ->
                resolver.openOutputStream(galleryUri)?.use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            } ?: run {
                Log.e(TAG, "Could not open input stream for $sourceUri")
                resolver.delete(galleryUri, null, null)
                return null
            }

            // Mark as complete on Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val update = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(galleryUri, update, null, null)
            }

            Log.i(TAG, "Image saved to gallery: $galleryUri")
            galleryUri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image to gallery", e)
            null
        }
    }
}
