package com.oceanguard.ai.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.oceanguard.ai.inference.DetectionResult
import java.io.File
import java.io.FileOutputStream

/**
 * Renders bounding box overlays onto a Bitmap using Android Canvas.
 *
 * Used to produce annotated images that are saved alongside detection sessions
 * so the history view shows the detections without needing to re-render overlays.
 *
 * Visual style: corner brackets (L-shaped) with semi-transparent fill and
 * pill-shaped label chips. Mirrors the live Compose overlay style but without
 * glow or animation (static JPEG output).
 *
 * Colour mapping mirrors [com.oceanguard.ai.ui.components.BoundingBoxOverlay].
 */
object BitmapAnnotator {

    private const val TAG = "BitmapAnnotator"

    // ARGB colour palette matching the Compose theme colours
    private val CLASS_COLOR_MAP: Map<String, Int> = mapOf(
        "bottle"         to 0xFFE91E63.toInt(),   // MaterialPlastic
        "plastic_debris" to 0xFFE91E63.toInt(),
        "mask"           to 0xFFE91E63.toInt(),
        "glove"          to 0xFF3F51B5.toInt(),   // MaterialFabric
        "can"            to 0xFF9E9E9E.toInt(),   // MaterialMetal
        "metal_debris"   to 0xFF9E9E9E.toInt(),
        "fishing_net"    to 0xFFFF6F00.toInt(),   // MaterialFishingNet
        "fabric_debris"  to 0xFF3F51B5.toInt(),   // MaterialFabric
        "tire"           to 0xFF795548.toInt(),   // MaterialRubber
        "glass_debris"   to 0xFF00BCD4.toInt(),   // MaterialGlass
    )

    private const val DEFAULT_COLOR = 0xFF607D8B.toInt() // MaterialOther

    private fun colorForClass(className: String): Int =
        CLASS_COLOR_MAP[className.lowercase()] ?: DEFAULT_COLOR

    /**
     * Draw L-shaped corner brackets on an Android Canvas.
     *
     * Each corner gets two perpendicular lines with rounded caps.
     * Arm length is proportional to the shorter box dimension, clamped
     * to [6px, 80px] to handle both small and high-resolution images.
     */
    private fun drawCornerBrackets(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paint: Paint,
        bracketFraction: Float = 0.15f,
    ) {
        val boxW = right - left
        val boxH = bottom - top
        val arm = (minOf(boxW, boxH) * bracketFraction).coerceIn(6f, 80f)

        // Top-left
        canvas.drawLine(left, top, left + arm, top, paint)
        canvas.drawLine(left, top, left, top + arm, paint)
        // Top-right
        canvas.drawLine(right, top, right - arm, top, paint)
        canvas.drawLine(right, top, right, top + arm, paint)
        // Bottom-left
        canvas.drawLine(left, bottom, left + arm, bottom, paint)
        canvas.drawLine(left, bottom, left, bottom - arm, paint)
        // Bottom-right
        canvas.drawLine(right, bottom, right - arm, bottom, paint)
        canvas.drawLine(right, bottom, right, bottom - arm, paint)
    }

    /**
     * Render bounding boxes onto a copy of the source bitmap.
     *
     * Detection coordinates are expected to be normalized [0,1].
     * The returned bitmap is a mutable copy — the original is not modified.
     *
     * Visual style per detection:
     * 1. Semi-transparent fill (10% alpha) inside the box area
     * 2. Corner brackets with rounded caps (no glow — static image)
     * 3. Pill-shaped label chip with thin white border
     *
     * @param source     The original image bitmap.
     * @param detections RT-DETR detections with normalized coordinates.
     * @return A new bitmap with bounding boxes and labels painted on it.
     */
    fun annotate(source: Bitmap, detections: List<DetectionResult>): Bitmap {
        val annotated = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)

        val w = annotated.width.toFloat()
        val h = annotated.height.toFloat()

        // Scale stroke and text to image size (3dp equivalent at ~160dpi baseline)
        val strokeWidth = (w * 0.005f).coerceIn(2f, 8f)
        val textSize = (w * 0.028f).coerceIn(14f, 48f)
        val padding = textSize * 0.3f

        val bracketPaint = Paint().apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }

        val fillPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val bgPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val borderPaint = Paint().apply {
            style = Paint.Style.STROKE
            this.strokeWidth = 1f
            color = android.graphics.Color.argb(51, 255, 255, 255)  // 20% white
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = android.graphics.Color.WHITE
            this.textSize = textSize
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }

        for (det in detections) {
            val color = colorForClass(det.className)

            // Scale normalized [0,1] coordinates to pixel coordinates
            val left   = det.x1 * w
            val top    = det.y1 * h
            val right  = det.x2 * w
            val bottom = det.y2 * h

            // 1. Semi-transparent fill (10% alpha of detection color)
            fillPaint.color = (color and 0x00FFFFFF) or 0x1A000000.toInt()
            canvas.drawRect(left, top, right, bottom, fillPaint)

            // 2. Corner brackets (no glow for static images)
            bracketPaint.color = color
            drawCornerBrackets(canvas, left, top, right, bottom, bracketPaint)

            // 3. Pill-shaped label chip
            val label = "${det.className} ${(det.confidence * 100).toInt()}%"
            val labelWidth = textPaint.measureText(label)
            val chipHeight = textSize + padding * 2
            val chipRadius = chipHeight / 2f

            val chipTop = (top - chipHeight - strokeWidth).coerceAtLeast(0f)
            val chipBottom = chipTop + chipHeight
            val chipRight = (left + labelWidth + padding * 2).coerceAtMost(w)
            val chipRect = RectF(left, chipTop, chipRight, chipBottom)

            // Pill background at 85% alpha
            bgPaint.color = (color and 0x00FFFFFF) or 0xD9000000.toInt()
            canvas.drawRoundRect(chipRect, chipRadius, chipRadius, bgPaint)

            // White pill border
            canvas.drawRoundRect(chipRect, chipRadius, chipRadius, borderPaint)

            // Label text
            canvas.drawText(label, left + padding, chipBottom - padding, textPaint)
        }

        return annotated
    }

    /**
     * Annotate an image and save the result as JPEG to internal storage.
     *
     * @param context    Application context for file access.
     * @param imageUri   URI of the source image.
     * @param detections RT-DETR detections with normalized [0,1] coordinates.
     * @param quality    JPEG compression quality (0-100). Default 85.
     * @return URI string of the saved annotated image, or null on failure.
     */
    fun annotateAndSave(
        context: Context,
        imageUri: Uri,
        detections: List<DetectionResult>,
        quality: Int = 85,
    ): String? {
        if (detections.isEmpty()) return null

        return try {
            // Load the source bitmap
            val inputStream = context.contentResolver.openInputStream(imageUri)
                ?: return null
            val decoded = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (decoded == null) {
                Log.e(TAG, "Failed to decode bitmap from $imageUri")
                return null
            }

            // Correct EXIF orientation so annotations align with the actual image
            val source = correctOrientation(context, imageUri, decoded)

            // Render annotations
            val annotated = annotate(source, detections)
            source.recycle()

            // Save to internal storage
            val dir = File(context.filesDir, "annotated").also { it.mkdirs() }
            val filename = "annotated_${System.currentTimeMillis()}.jpg"
            val file = File(dir, filename)

            FileOutputStream(file).use { out ->
                annotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            annotated.recycle()

            val uri = Uri.fromFile(file).toString()
            Log.i(TAG, "Saved annotated image: $uri (${file.length() / 1024}KB)")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to annotate and save image", e)
            null
        }
    }

    /**
     * Read EXIF orientation from [imageUri] and rotate/flip [bitmap] accordingly.
     * Returns the original bitmap unchanged if no correction is needed.
     */
    private fun correctOrientation(context: Context, imageUri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val exif = when (imageUri.scheme) {
                "content" -> {
                    val stream = context.contentResolver.openInputStream(imageUri) ?: return bitmap
                    val e = ExifInterface(stream)
                    stream.close()
                    e
                }
                "file" -> ExifInterface(imageUri.path!!)
                else -> return bitmap
            }

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> return bitmap
            }

            val corrected = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true,
            )
            if (corrected !== bitmap) bitmap.recycle()
            corrected
        } catch (e: Exception) {
            Log.w(TAG, "EXIF orientation correction failed, using original", e)
            bitmap
        }
    }
}
