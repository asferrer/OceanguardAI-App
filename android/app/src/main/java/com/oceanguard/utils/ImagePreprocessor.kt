package com.oceanguard.ai.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * ImagePreprocessor - Image loading and preprocessing utilities
 *
 * Implements critical optimization: resizing images to 512px on long edge
 * This provides 90%+ latency reduction compared to full-resolution images
 * with minimal accuracy loss for debris detection tasks.
 */
class ImagePreprocessor(private val context: Context) {

    companion object {
        private const val TAG = "ImagePreprocessor"
        private const val DEFAULT_TARGET_SIZE = 512
        private const val QUALITY = 90  // JPEG quality for temporary storage
    }

    /**
     * Load and preprocess image from URI
     *
     * @param uri Source image URI
     * @param targetSize Target size for long edge (default 512px)
     * @return Preprocessed bitmap ready for inference
     */
    suspend fun loadAndPreprocess(
        uri: Uri,
        targetSize: Int = DEFAULT_TARGET_SIZE
    ): Bitmap = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Loading image from URI: $uri")

            // Load bitmap from URI
            val originalBitmap = loadBitmapFromUri(uri)
            Log.i(TAG, "Original size: ${originalBitmap.width}x${originalBitmap.height}")

            // Correct orientation if needed (from EXIF data)
            val orientedBitmap = correctOrientation(uri, originalBitmap)

            // Resize to target size if needed
            val processedBitmap = if (needsResize(orientedBitmap, targetSize)) {
                Log.i(TAG, "Resizing to target size: $targetSize")
                resize(orientedBitmap, targetSize)
            } else {
                Log.i(TAG, "No resize needed")
                orientedBitmap
            }

            Log.i(TAG, "Final size: ${processedBitmap.width}x${processedBitmap.height}")

            // Clean up intermediate bitmaps if different from final
            if (originalBitmap != processedBitmap) {
                originalBitmap.recycle()
            }
            if (orientedBitmap != processedBitmap && orientedBitmap != originalBitmap) {
                orientedBitmap.recycle()
            }

            processedBitmap

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load/preprocess image", e)
            throw ImageProcessingException("Image preprocessing failed: ${e.message}", e)
        }
    }

    /**
     * Load bitmap from URI (handles both content:// and file:// URIs)
     */
    private fun loadBitmapFromUri(uri: Uri): Bitmap {
        return when (uri.scheme) {
            "content" -> {
                // Use MediaStore for content URIs
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
            "file" -> {
                // Direct file access
                val path = uri.path ?: throw IOException("Invalid file URI")
                BitmapFactory.decodeFile(path)
                    ?: throw IOException("Failed to decode bitmap from file")
            }
            else -> {
                throw IllegalArgumentException("Unsupported URI scheme: ${uri.scheme}")
            }
        }
    }

    /**
     * Correct image orientation based on EXIF data
     */
    private fun correctOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return bitmap

            val exif = ExifInterface(inputStream)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            inputStream.close()

            return when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> flipBitmap(bitmap, horizontal = true)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> flipBitmap(bitmap, horizontal = false)
                else -> bitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read EXIF orientation, using original", e)
            return bitmap
        }
    }

    /**
     * Rotate bitmap by specified degrees
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
        if (rotated != bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    /**
     * Flip bitmap horizontally or vertically
     */
    private fun flipBitmap(bitmap: Bitmap, horizontal: Boolean): Bitmap {
        val matrix = Matrix().apply {
            if (horizontal) {
                postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            } else {
                postScale(1f, -1f, bitmap.width / 2f, bitmap.height / 2f)
            }
        }
        val flipped = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
        if (flipped != bitmap) {
            bitmap.recycle()
        }
        return flipped
    }

    /**
     * Check if bitmap needs resizing
     */
    private fun needsResize(bitmap: Bitmap, targetSize: Int): Boolean {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        return maxDim > targetSize
    }

    /**
     * Resize bitmap to target size while maintaining aspect ratio
     *
     * @param bitmap Source bitmap
     * @param targetSize Target size for long edge
     * @return Resized bitmap
     */
    fun resize(bitmap: Bitmap, targetSize: Int): Bitmap {
        val maxDim = maxOf(bitmap.width, bitmap.height)

        if (maxDim <= targetSize) {
            return bitmap  // No resize needed
        }

        val scale = targetSize.toFloat() / maxDim
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()

        Log.i(TAG, "Resizing from ${bitmap.width}x${bitmap.height} to ${newWidth}x${newHeight}")

        val resized = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)

        // Recycle original if different from resized
        if (resized != bitmap) {
            bitmap.recycle()
        }

        return resized
    }

    /**
     * Convert bitmap to RGB if needed (some models require RGB format)
     */
    fun ensureRGB(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.RGB_565 || bitmap.config == Bitmap.Config.ARGB_8888) {
            return bitmap
        }

        val rgbBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        if (rgbBitmap != bitmap) {
            bitmap.recycle()
        }
        return rgbBitmap
    }

    /**
     * Extract image metadata (for logging/debugging)
     */
    fun getImageMetadata(uri: Uri): ImageMetadata {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ImageMetadata()

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            // Try to get EXIF data
            val exifStream = context.contentResolver.openInputStream(uri)
            val exif = if (exifStream != null) {
                try {
                    ExifInterface(exifStream)
                } catch (e: Exception) {
                    null
                } finally {
                    exifStream.close()
                }
            } else null

            val latLongArray = FloatArray(2)
            @Suppress("DEPRECATION")
            val hasLocation = exif?.getLatLong(latLongArray) == true

            return ImageMetadata(
                width = options.outWidth,
                height = options.outHeight,
                mimeType = options.outMimeType,
                orientation = exif?.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                ),
                latitude = if (hasLocation) latLongArray[0].toDouble() else null,
                longitude = if (hasLocation) latLongArray[1].toDouble() else null,
                timestamp = exif?.getAttribute(ExifInterface.TAG_DATETIME)
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract metadata", e)
            return ImageMetadata()
        }
    }
}

/**
 * Image metadata extracted from file
 */
data class ImageMetadata(
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String? = null,
    val orientation: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timestamp: String? = null
)

/**
 * Exception thrown when image processing fails
 */
class ImageProcessingException(message: String, cause: Throwable? = null) : Exception(message, cause)
