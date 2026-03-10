package com.oceanguard.ai.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream

/**
 * Low-level layout engine on top of [PdfDocument].
 *
 * Tracks a Y cursor, handles automatic page breaks, and provides
 * drawing primitives (title, paragraph, image, table, separator).
 * A4 at 72 dpi = 595 x 842 pt.
 */
internal class PdfPageCanvas(private val outputFile: File) {

    companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN = 40
        val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
    }

    private val document = PdfDocument()
    private var pageNumber = 0
    private var cursorY = MARGIN
    private var currentPage: PdfDocument.Page? = null
    private var currentCanvas: Canvas? = null
    private val pageFooters = mutableListOf<Pair<PdfDocument.Page, Int>>()

    init {
        startNewPage()
    }

    // -- Styles ---------------------------------------------------------------

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 18f
        color = Color.WHITE
    }

    private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 14f
        color = Color.parseColor("#0D47A1")
    }

    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textSize = 10f
        color = Color.parseColor("#212121")
    }

    private val boldBodyPaint = TextPaint(bodyPaint).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val h3Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 12f
        color = Color.parseColor("#1565C0")
    }

    private val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        textSize = 8.5f
        color = Color.parseColor("#616161")
    }

    private val headerBgColor = Color.parseColor("#0D47A1")
    private val separatorColor = Color.parseColor("#BDBDBD")
    private val tableBorderColor = Color.parseColor("#9E9E9E")
    private val tableHeaderBg = Color.parseColor("#E3F2FD")

    // -- Page management ------------------------------------------------------

    private fun startNewPage() {
        finishCurrentPage()
        pageNumber++
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        currentPage = document.startPage(pageInfo)
        currentCanvas = currentPage!!.canvas
        cursorY = MARGIN
    }

    private fun finishCurrentPage() {
        val page = currentPage ?: return
        // Draw page number footer
        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8f
            color = Color.GRAY
            textAlign = Paint.Align.CENTER
        }
        currentCanvas?.drawText(
            pageNumber.toString(),
            (PAGE_WIDTH / 2).toFloat(),
            (PAGE_HEIGHT - 15).toFloat(),
            footerPaint,
        )
        document.finishPage(page)
        currentPage = null
        currentCanvas = null
    }

    fun ensureSpace(height: Int) {
        if (cursorY + height > PAGE_HEIGHT - MARGIN - 20) {
            startNewPage()
        }
    }

    // -- Drawing primitives ---------------------------------------------------

    fun drawHeaderBand(title: String, subtitle: String? = null) {
        val bandHeight = if (subtitle != null) 60 else 44
        ensureSpace(bandHeight)
        val canvas = currentCanvas ?: return

        val bgPaint = Paint().apply { color = headerBgColor; style = Paint.Style.FILL }
        canvas.drawRect(0f, cursorY.toFloat(), PAGE_WIDTH.toFloat(), (cursorY + bandHeight).toFloat(), bgPaint)

        canvas.drawText(title, MARGIN.toFloat(), (cursorY + 28).toFloat(), titlePaint)
        if (subtitle != null) {
            val subPaint = TextPaint(captionPaint).apply { color = Color.parseColor("#BBDEFB") }
            canvas.drawText(subtitle, MARGIN.toFloat(), (cursorY + 46).toFloat(), subPaint)
        }
        cursorY += bandHeight + 12
    }

    fun drawTitle(text: String) {
        drawStaticLayout(text, titlePaint.apply { color = Color.parseColor("#0D47A1") }, Layout.Alignment.ALIGN_NORMAL, 8)
    }

    fun drawSubtitle(text: String) {
        ensureSpace(24)
        cursorY += 6
        drawStaticLayout(text, subtitlePaint, Layout.Alignment.ALIGN_NORMAL, 6)
    }

    fun drawH3(text: String) {
        ensureSpace(20)
        cursorY += 4
        drawStaticLayout(text, h3Paint, Layout.Alignment.ALIGN_NORMAL, 4)
    }

    fun drawParagraph(text: String) {
        drawStaticLayout(text, bodyPaint, Layout.Alignment.ALIGN_NORMAL, 4)
    }

    fun drawBoldParagraph(text: String) {
        drawStaticLayout(text, boldBodyPaint, Layout.Alignment.ALIGN_NORMAL, 4)
    }

    fun drawCaption(text: String) {
        drawStaticLayout(text, captionPaint, Layout.Alignment.ALIGN_CENTER, 4)
    }

    fun drawBlockquote(text: String) {
        ensureSpace(20)
        val canvas = currentCanvas ?: return
        val barPaint = Paint().apply { color = Color.parseColor("#1976D2"); style = Paint.Style.FILL }
        val bgPaint = Paint().apply { color = Color.parseColor("#E3F2FD"); style = Paint.Style.FILL }

        val indent = 12
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, captionPaint, CONTENT_WIDTH - indent - 8)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1f)
            .build()
        val height = layout.height + 12

        ensureSpace(height)
        canvas.drawRect(
            MARGIN.toFloat(), cursorY.toFloat(),
            (MARGIN + CONTENT_WIDTH).toFloat(), (cursorY + height).toFloat(), bgPaint,
        )
        canvas.drawRect(
            MARGIN.toFloat(), cursorY.toFloat(),
            (MARGIN + 3).toFloat(), (cursorY + height).toFloat(), barPaint,
        )
        canvas.save()
        canvas.translate((MARGIN + indent).toFloat(), (cursorY + 6).toFloat())
        layout.draw(canvas)
        canvas.restore()
        cursorY += height + 4
    }

    fun drawBulletItem(text: String) {
        val indent = 14
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, bodyPaint, CONTENT_WIDTH - indent)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1f)
            .build()
        val height = layout.height
        ensureSpace(height + 4)
        val canvas = currentCanvas ?: return

        val bulletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0D47A1"); style = Paint.Style.FILL
        }
        canvas.drawCircle(
            (MARGIN + 5).toFloat(), (cursorY + 6).toFloat(), 2.2f, bulletPaint,
        )
        canvas.save()
        canvas.translate((MARGIN + indent).toFloat(), cursorY.toFloat())
        layout.draw(canvas)
        canvas.restore()
        cursorY += height + 4
    }

    fun drawNumberedItem(number: String, text: String) {
        val indent = 18
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, bodyPaint, CONTENT_WIDTH - indent)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1f)
            .build()
        val height = layout.height
        ensureSpace(height + 4)
        val canvas = currentCanvas ?: return

        val numPaint = TextPaint(boldBodyPaint).apply { color = Color.parseColor("#0D47A1") }
        canvas.drawText("$number.", MARGIN.toFloat(), (cursorY + 10).toFloat(), numPaint)
        canvas.save()
        canvas.translate((MARGIN + indent).toFloat(), cursorY.toFloat())
        layout.draw(canvas)
        canvas.restore()
        cursorY += height + 4
    }

    fun drawSeparator() {
        ensureSpace(12)
        val canvas = currentCanvas ?: return
        val paint = Paint().apply { color = separatorColor; strokeWidth = 0.8f }
        cursorY += 6
        canvas.drawLine(MARGIN.toFloat(), cursorY.toFloat(), (PAGE_WIDTH - MARGIN).toFloat(), cursorY.toFloat(), paint)
        cursorY += 6
    }

    fun drawImage(bitmap: Bitmap, maxWidth: Int = CONTENT_WIDTH, maxHeight: Int = 260) {
        val scale = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height, 1f)
        val drawWidth = (bitmap.width * scale).toInt()
        val drawHeight = (bitmap.height * scale).toInt()

        ensureSpace(drawHeight + 8)
        val canvas = currentCanvas ?: return

        val left = MARGIN + (CONTENT_WIDTH - drawWidth) / 2
        val dest = Rect(left, cursorY, left + drawWidth, cursorY + drawHeight)
        canvas.drawBitmap(bitmap, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
        cursorY += drawHeight + 8
    }

    fun drawTable(headers: List<String>, rows: List<List<String>>) {
        val colCount = headers.size
        if (colCount == 0) return

        val canvas = currentCanvas ?: return
        val cellPadH = 4
        val cellPadV = 4
        val colWidth = CONTENT_WIDTH / colCount
        val cellTextWidth = colWidth - cellPadH * 2
        val borderPaint = Paint().apply { color = tableBorderColor; style = Paint.Style.STROKE; strokeWidth = 0.5f }
        val headerBgPaint = Paint().apply { color = tableHeaderBg; style = Paint.Style.FILL }
        val altRowBgPaint = Paint().apply { color = Color.parseColor("#F5F5F5"); style = Paint.Style.FILL }
        val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.parseColor("#212121") }
        val headerTextPaint = TextPaint(cellPaint).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }

        // Measure header row height
        val headerHeight = measureRowHeight(headers, headerTextPaint, cellTextWidth, cellPadV)
        ensureSpace(headerHeight + 20)
        val tableStartY = cursorY

        // Draw header background
        currentCanvas?.drawRect(
            MARGIN.toFloat(), cursorY.toFloat(),
            (MARGIN + CONTENT_WIDTH).toFloat(), (cursorY + headerHeight).toFloat(),
            headerBgPaint,
        )
        drawRowCells(headers, headerTextPaint, colWidth, cellPadH, cellPadV, headerHeight)
        cursorY += headerHeight

        // Data rows
        for ((rowIdx, row) in rows.withIndex()) {
            val rowH = measureRowHeight(row, cellPaint, cellTextWidth, cellPadV)
            if (cursorY + rowH > PAGE_HEIGHT - MARGIN - 20) {
                // Draw borders for current page portion before break
                drawTableBorders(borderPaint, colCount, colWidth, tableStartY, cursorY)
                startNewPage()
            }
            // Alternate row background
            if (rowIdx % 2 == 1) {
                currentCanvas?.drawRect(
                    MARGIN.toFloat(), cursorY.toFloat(),
                    (MARGIN + CONTENT_WIDTH).toFloat(), (cursorY + rowH).toFloat(),
                    altRowBgPaint,
                )
            }
            val padded = row + List((colCount - row.size).coerceAtLeast(0)) { "" }
            drawRowCells(padded, cellPaint, colWidth, cellPadH, cellPadV, rowH)
            // Row separator
            currentCanvas?.drawLine(
                MARGIN.toFloat(), (cursorY + rowH).toFloat(),
                (MARGIN + CONTENT_WIDTH).toFloat(), (cursorY + rowH).toFloat(),
                borderPaint,
            )
            cursorY += rowH
        }

        // Outer + column borders
        drawTableBorders(borderPaint, colCount, colWidth, tableStartY, cursorY)
        cursorY += 8
    }

    private fun measureRowHeight(
        cells: List<String>,
        paint: TextPaint,
        cellTextWidth: Int,
        padV: Int,
    ): Int {
        var maxH = 0
        for (cell in cells) {
            val layout = StaticLayout.Builder
                .obtain(cell, 0, cell.length, paint, cellTextWidth.coerceAtLeast(20))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(1f, 1f)
                .build()
            maxH = maxOf(maxH, layout.height)
        }
        return maxH + padV * 2
    }

    private fun drawRowCells(
        cells: List<String>,
        paint: TextPaint,
        colWidth: Int,
        padH: Int,
        padV: Int,
        rowHeight: Int,
    ) {
        val canvas = currentCanvas ?: return
        val cellTextWidth = colWidth - padH * 2
        for ((i, cell) in cells.withIndex()) {
            val layout = StaticLayout.Builder
                .obtain(cell, 0, cell.length, paint, cellTextWidth.coerceAtLeast(20))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(1f, 1f)
                .build()
            canvas.save()
            canvas.translate(
                (MARGIN + i * colWidth + padH).toFloat(),
                (cursorY + padV).toFloat(),
            )
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawTableBorders(
        borderPaint: Paint,
        colCount: Int,
        colWidth: Int,
        startY: Int,
        endY: Int,
    ) {
        val canvas = currentCanvas ?: return
        canvas.drawRect(
            MARGIN.toFloat(), startY.toFloat(),
            (MARGIN + CONTENT_WIDTH).toFloat(), endY.toFloat(),
            borderPaint,
        )
        for (i in 1 until colCount) {
            val x = MARGIN + i * colWidth
            canvas.drawLine(x.toFloat(), startY.toFloat(), x.toFloat(), endY.toFloat(), borderPaint)
        }
    }

    fun addSpacing(pts: Int) {
        cursorY += pts
        if (cursorY > PAGE_HEIGHT - MARGIN) startNewPage()
    }

    // -- StaticLayout helper --------------------------------------------------

    private fun drawStaticLayout(
        text: String,
        paint: TextPaint,
        alignment: Layout.Alignment,
        bottomSpacing: Int,
    ) {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, CONTENT_WIDTH)
            .setAlignment(alignment)
            .setLineSpacing(2f, 1f)
            .build()

        val height = layout.height
        ensureSpace(height + bottomSpacing)
        val canvas = currentCanvas ?: return

        canvas.save()
        canvas.translate(MARGIN.toFloat(), cursorY.toFloat())
        layout.draw(canvas)
        canvas.restore()
        cursorY += height + bottomSpacing
    }

    // -- Finish ---------------------------------------------------------------

    fun finish(): File {
        finishCurrentPage()
        FileOutputStream(outputFile).use { document.writeTo(it) }
        document.close()
        return outputFile
    }
}
