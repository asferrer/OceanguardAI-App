package com.oceanguard.ai.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object VideoPersistence {
    private const val TAG = "VideoPersistence"
    private const val VIDEOS_DIR = "videos"

    fun persistVideo(context: Context, sourceUri: Uri): String {
        val uriString = sourceUri.toString()
        if (uriString.startsWith("file://${context.filesDir.absolutePath}")) {
            return uriString
        }
        return try {
            val dir = File(context.filesDir, VIDEOS_DIR).also { it.mkdirs() }
            val filename = "vid_${System.currentTimeMillis()}_${uriString.hashCode().toUInt()}.mp4"
            val dest = File(dir, filename)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output, bufferSize = 16384)
                }
            } ?: run {
                Log.w(TAG, "Could not open input stream for $sourceUri — keeping original URI")
                return uriString
            }
            val localUri = Uri.fromFile(dest).toString()
            Log.i(TAG, "Persisted video: $localUri (${"%.2f".format(dest.length() / 1_048_576.0)}MB)")
            localUri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist video from $sourceUri — keeping original URI", e)
            uriString
        }
    }
}
