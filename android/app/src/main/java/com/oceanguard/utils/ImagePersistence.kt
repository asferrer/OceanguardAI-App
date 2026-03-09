package com.oceanguard.ai.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Copies images from external content:// URIs to app-private internal storage
 * so they remain accessible after the app restarts.
 *
 * Content URIs from PhotoPicker (PickVisualMedia) and SAF (OpenDocument) may
 * lose read permissions when the granting component's lifecycle ends or the
 * process is killed. Copying to [Context.getFilesDir]/images/ guarantees
 * permanent access via a file:// URI owned by the app.
 */
object ImagePersistence {

    private const val TAG = "ImagePersistence"
    private const val IMAGES_DIR = "images"

    /**
     * Copy [sourceUri] to internal storage and return the local file URI string.
     *
     * If the source is already a file:// URI under the app's filesDir, it is
     * returned as-is (no redundant copy). Returns the original URI string
     * unchanged if the copy fails for any reason — the session is still saved
     * with whatever URI was available so the user doesn't lose the detection.
     */
    fun persistImage(context: Context, sourceUri: Uri): String {
        val uriString = sourceUri.toString()

        // Already an internal file — nothing to do
        if (uriString.startsWith("file://${context.filesDir.absolutePath}")) {
            return uriString
        }

        return try {
            val dir = File(context.filesDir, IMAGES_DIR).also { it.mkdirs() }
            val filename = "img_${System.currentTimeMillis()}_${uriString.hashCode().toUInt()}.jpg"
            val dest = File(dir, filename)

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            } ?: run {
                Log.w(TAG, "Could not open input stream for $sourceUri — keeping original URI")
                return uriString
            }

            val localUri = Uri.fromFile(dest).toString()
            Log.i(TAG, "Persisted image: $localUri (${dest.length() / 1024}KB)")
            localUri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist image from $sourceUri — keeping original URI", e)
            uriString
        }
    }
}
