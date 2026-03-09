package com.oceanguard.ai.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.GeneratedReport
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DataExporter — utilities for exporting detection session data and
 * AI-generated reports to shareable file formats.
 *
 * All output files are written to the application's internal cache
 * directory, which is accessible to other apps only via a
 * [FileProvider] URI that grants temporary read permission.
 *
 * FileProvider authority: `${context.packageName}.fileprovider`
 * Registered in AndroidManifest.xml with the `@xml/file_paths` resource.
 *
 * ## Usage
 * ```kotlin
 * val uri = DataExporter.exportToCSV(context, sessions)
 * if (uri != null) {
 *     DataExporter.shareFile(context, uri, "text/csv")
 * }
 * ```
 */
object DataExporter {

    private const val TAG = "DataExporter"

    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val FILE_TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    // -----------------------------------------------------------------------
    // CSV export
    // -----------------------------------------------------------------------

    /**
     * Serialises a list of [DetectionSession] objects into a CSV file and
     * returns a [FileProvider] URI for sharing.
     *
     * Each row in the CSV corresponds to a single debris item within a
     * session. Sessions with no detected debris produce a single summary row
     * with empty debris fields so the session timestamp and health score are
     * still captured.
     *
     * CSV columns:
     * `session_id, image_name, date, lat, lon, debris_type, material,
     *  confidence, health_score`
     *
     * @param context  Application or activity context for cache dir access.
     * @param sessions List of sessions to export. Order is preserved.
     * @return A `content://` [Uri] pointing to the generated CSV file, or
     *   `null` if the file could not be written.
     */
    fun exportToCSV(context: Context, sessions: List<DetectionSession>): Uri? {
        if (sessions.isEmpty()) {
            Log.w(TAG, "exportToCSV called with empty session list")
            return null
        }

        val timestamp = FILE_TIMESTAMP_FORMAT.format(Date())
        val fileName = "oceanguard_export_$timestamp.csv"

        return try {
            val file = createCacheFile(context, fileName)
            file.bufferedWriter().use { writer ->
                // Header row
                writer.write(
                    "session_id,image_name,date,lat,lon," +
                    "debris_type,material,confidence,health_score"
                )
                writer.newLine()

                // Data rows
                sessions.forEach { session ->
                    val imageName = Uri.parse(session.imageUri).lastPathSegment ?: session.imageUri
                    val formattedDate = DATE_FORMAT.format(session.timestamp)
                    val lat = session.location?.latitude?.let { String.format("%.6f", it) } ?: ""
                    val lon = session.location?.longitude?.let { String.format("%.6f", it) } ?: ""

                    if (session.debrisList.isEmpty()) {
                        // Session row with no debris
                        writer.write(
                            "${session.id}," +
                            "${escapeCsv(imageName)}," +
                            "${escapeCsv(formattedDate)}," +
                            "$lat,$lon," +
                            ",,," +    // debris_type, material, confidence empty
                            "${session.healthScore}"
                        )
                        writer.newLine()
                    } else {
                        // One row per debris item
                        session.debrisList.forEach { debris ->
                            writer.write(
                                "${session.id}," +
                                "${escapeCsv(imageName)}," +
                                "${escapeCsv(formattedDate)}," +
                                "$lat,$lon," +
                                "${debris.type.name}," +
                                "${debris.material.name}," +
                                "${String.format("%.4f", debris.confidence)}," +
                                "${session.healthScore}"
                            )
                            writer.newLine()
                        }
                    }
                }
            }

            Log.i(TAG, "CSV written: ${file.absolutePath} (${file.length()} bytes)")
            getFileProviderUri(context, file)

        } catch (e: IOException) {
            Log.e(TAG, "Failed to write CSV file", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during CSV export", e)
            null
        }
    }

    // -----------------------------------------------------------------------
    // Markdown export
    // -----------------------------------------------------------------------

    /**
     * Saves a pre-generated report string as a Markdown (.md) file and
     * returns a [FileProvider] URI for sharing.
     *
     * The file name includes the location name and a timestamp so multiple
     * reports do not overwrite each other in the cache directory.
     *
     * @param context       Application or activity context.
     * @param report        Markdown-formatted report string (e.g. from
     *   [com.oceanguard.ai.inference.ReportGenerator.generateReport]).
     * @param locationName  Human-readable survey location label used in the
     *   file name (e.g. "Mediterranean_Sea"). Spaces are replaced with
     *   underscores; characters invalid in file names are stripped.
     * @return A `content://` [Uri] pointing to the generated Markdown file,
     *   or `null` if the file could not be written.
     */
    fun exportToMarkdown(context: Context, report: String, locationName: String): Uri? {
        if (report.isBlank()) {
            Log.w(TAG, "exportToMarkdown called with blank report")
            return null
        }

        val timestamp = FILE_TIMESTAMP_FORMAT.format(Date())
        val safeLocation = sanitizeFileName(locationName).take(40).trimEnd('_')
        val fileName = "oceanguard_report_${safeLocation}_$timestamp.md"

        return try {
            val file = createCacheFile(context, fileName)
            file.writeText(report)

            Log.i(TAG, "Markdown written: ${file.absolutePath} (${file.length()} bytes)")
            getFileProviderUri(context, file)

        } catch (e: IOException) {
            Log.e(TAG, "Failed to write Markdown file", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during Markdown export", e)
            null
        }
    }

    // -----------------------------------------------------------------------
    // PDF export
    // -----------------------------------------------------------------------

    /**
     * Renders a professional PDF report with header, stats, body text,
     * and embedded session images, then returns a [FileProvider] URI.
     *
     * @param context  Application or activity context.
     * @param report   The [GeneratedReport] to render.
     * @param sessions Sessions used to generate the report (for images and stats).
     * @return A `content://` [Uri] pointing to the PDF, or `null` on failure.
     */
    suspend fun exportToPdf(
        context: Context,
        report: GeneratedReport,
        sessions: List<DetectionSession>,
    ): Uri? {
        val file = PdfReportExporter.exportReport(context, report, sessions)
            ?: return null
        return getFileProviderUri(context, file)
    }

    // -----------------------------------------------------------------------
    // Share intent
    // -----------------------------------------------------------------------

    /**
     * Launches the Android share sheet for the supplied [FileProvider] URI.
     *
     * The intent is started with [Intent.FLAG_ACTIVITY_NEW_TASK] so it can
     * be called safely from a non-Activity context (e.g. a repository or
     * ViewModel-invoked coroutine that holds an Application context).
     *
     * @param context  Application or activity context.
     * @param uri      A `content://` URI previously obtained from
     *   [exportToCSV] or [exportToMarkdown].
     * @param mimeType MIME type string for the file (e.g. `"text/csv"`,
     *   `"text/markdown"`).
     */
    fun shareFile(context: Context, uri: Uri, mimeType: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "Share OceanGuard export")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            context.startActivity(chooser)
            Log.i(TAG, "Share intent launched for URI: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch share intent", e)
        }
    }

    // -----------------------------------------------------------------------
    // Cache management
    // -----------------------------------------------------------------------

    /**
     * Deletes all OceanGuard export files from the cache directory to free
     * storage. Call this during a periodic cleanup or from a Settings action.
     *
     * @param context Application or activity context.
     * @return Number of files deleted.
     */
    fun clearExportCache(context: Context): Int {
        val cacheDir = exportCacheDir(context)
        if (!cacheDir.exists()) return 0

        val files = cacheDir.listFiles { file ->
            file.name.startsWith("oceanguard_")
        } ?: return 0

        var deleted = 0
        files.forEach { file ->
            if (file.delete()) deleted++
        }

        Log.i(TAG, "Cleared $deleted export file(s) from cache")
        return deleted
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Returns (or creates) the dedicated export subdirectory within the
     * application's cache directory.
     */
    private fun exportCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Creates a [File] in the export cache directory with the given name.
     * Any existing file with the same name is deleted first.
     */
    private fun createCacheFile(context: Context, fileName: String): File {
        val file = File(exportCacheDir(context), fileName)
        if (file.exists()) file.delete()
        file.createNewFile()
        return file
    }

    /**
     * Converts a [File] in the cache directory to a `content://` URI using
     * [FileProvider], granting read permission to receiving apps.
     *
     * @throws IllegalArgumentException if the file is not in the directory
     *   declared in the FileProvider `file_paths` XML resource.
     */
    private fun getFileProviderUri(context: Context, file: File): Uri? {
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "FileProvider URI failed for ${file.absolutePath}. " +
                "Ensure the exports directory is declared in res/xml/file_paths.xml", e)
            null
        }
    }

    /**
     * Escapes a string value for safe inclusion in a CSV cell.
     *
     * - If the value contains a comma, double-quote, or newline it is
     *   wrapped in double quotes.
     * - Internal double-quotes are escaped by doubling them (`""`).
     */
    private fun escapeCsv(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    /**
     * Replaces characters that are invalid in file names with underscores.
     */
    private fun sanitizeFileName(name: String): String {
        return name
            .replace(' ', '_')
            .replace(Regex("[^A-Za-z0-9_\\-]"), "_")
    }
}
