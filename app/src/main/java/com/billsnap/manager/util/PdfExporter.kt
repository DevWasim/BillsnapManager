package com.billsnap.manager.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.billsnap.manager.data.BillEntity
import com.billsnap.manager.data.CustomerEntity
import com.billsnap.manager.ui.dashboard.DashboardViewModel
import com.billsnap.manager.util.CurrencyManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility class for generating PDF reports from bill records.
 * Uses Android's built-in PdfDocument API (no external library).
 */
object PdfExporter {

    private const val PAGE_WIDTH = 595   // A4 width in points
    private const val PAGE_HEIGHT = 842  // A4 height in points
    private const val MARGIN = 40f
    private const val THUMB_SIZE = 60f
    private const val LINE_HEIGHT = 18f

    private val titlePaint = Paint().apply {
        textSize = 20f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = 0xFF212121.toInt()
    }
    private val headerPaint = Paint().apply {
        textSize = 14f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = 0xFF424242.toInt()
    }
    private val bodyPaint = Paint().apply {
        textSize = 12f; color = 0xFF616161.toInt()
    }
    private val statusPaidPaint = Paint().apply {
        textSize = 12f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = 0xFF4CAF50.toInt()
    }
    private val statusUnpaidPaint = Paint().apply {
        textSize = 12f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = 0xFFF44336.toInt()
    }
    private val statusOverduePaint = Paint().apply {
        textSize = 12f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = 0xFFFF9800.toInt()
    }
    private val linePaint = Paint().apply { color = 0xFFE0E0E0.toInt(); strokeWidth = 1f }
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy  hh:mm a", Locale.getDefault())

    // ---- Existing: Bills Report ----

    fun generatePdf(context: Context, bills: List<BillEntity>): File {
        val document = PdfDocument()

        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
        var page = document.startPage(pageInfo)
        var canvas = page.canvas
        var y = MARGIN

        // Title
        canvas.drawText("BillSnap Manager — Report", MARGIN, y + 20f, titlePaint)
        canvas.drawText("Generated: ${dateFormat.format(Date())}", MARGIN, y + 38f, bodyPaint)
        y += 60f
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 15f

        for (bill in bills) {
            if (y + 110f > PAGE_HEIGHT - MARGIN) {
                document.finishPage(page)
                pageNum++
                pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
                page = document.startPage(pageInfo)
                canvas = page.canvas
                y = MARGIN
            }

            drawThumbnail(canvas, bill.imagePath, MARGIN, y, THUMB_SIZE)

            val textX = MARGIN + THUMB_SIZE + 12f
            canvas.drawText(bill.customName, textX, y + 14f, headerPaint)

            val notes = bill.notes.ifEmpty { "—" }
            val truncatedNotes = if (notes.length > 60) notes.take(57) + "..." else notes
            canvas.drawText("Notes: $truncatedNotes", textX, y + 30f, bodyPaint)
            canvas.drawText("Date: ${dateFormat.format(Date(bill.timestamp))}", textX, y + 46f, bodyPaint)

            val sPaint = when (bill.paymentStatus) {
                "Paid" -> statusPaidPaint
                "Partial" -> statusOverduePaint
                "Overdue" -> statusOverduePaint
                else -> statusUnpaidPaint
            }
            canvas.drawText("Status: ${bill.paymentStatus}", textX, y + 62f, sPaint)

            var extraY = 78f
            // Show payment amounts if totalAmount > 0
            val total = bill.totalAmount ?: 0.0
            if (total > 0) {
                val totStr = CurrencyManager.formatCompact(total)
                val paidStr = CurrencyManager.formatCompact(bill.paidAmount)
                val remStr = CurrencyManager.formatCompact(bill.remainingAmount)
                canvas.drawText("Total: $totStr  |  Paid: $paidStr  |  Remaining: $remStr", textX, y + extraY, bodyPaint)
                extraY += 16f
            }

            // Show paid timestamp if available
            if (bill.paidTimestamp != null) {
                canvas.drawText("Paid: ${dateFormat.format(Date(bill.paidTimestamp))}", textX, y + extraY, bodyPaint)
                extraY += 16f
            }
            // Show reminder if available
            if (bill.reminderDatetime != null) {
                canvas.drawText("Reminder: ${dateFormat.format(Date(bill.reminderDatetime))}", textX, y + extraY, bodyPaint)
            }

            y += THUMB_SIZE + 30f
            canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
            y += 12f
        }

        document.finishPage(page)

        val pdfDir = File(context.cacheDir, "exports")
        if (!pdfDir.exists()) pdfDir.mkdirs()
        val pdfFile = File(pdfDir, "BillSnap_Report_${System.currentTimeMillis()}.pdf")
        FileOutputStream(pdfFile).use { document.writeTo(it) }
        document.close()

        return pdfFile
    }

    // ---- Dashboard: Full Summary ----

    fun generateDashboardSummaryPdf(
        context: Context,
        stats: com.billsnap.manager.data.DashboardCounts,
        bills: List<BillEntity>,
        customers: List<CustomerEntity>
    ): File {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        var y = MARGIN

        canvas.drawText("Dashboard Summary", MARGIN, y + 20f, titlePaint)
        canvas.drawText("Generated: ${dateFormat.format(Date())}", MARGIN, y + 38f, bodyPaint)
        y += 60f
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 25f

        // Stats table
        val statRows = listOf(
            "Total Bills" to stats.totalBills.toString(),
            "Paid" to stats.paidCount.toString(),
            "Unpaid" to stats.unpaidCount.toString(),
            "Overdue" to stats.overdueCount.toString(),
            "Total Customers" to customers.size.toString()
        )

        for ((label, value) in statRows) {
            canvas.drawText(label, MARGIN, y, headerPaint)
            canvas.drawText(value, MARGIN + 200f, y, titlePaint)
            y += LINE_HEIGHT + 6f
        }

        y += 10f
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 20f

        // Monthly breakdown
        canvas.drawText("Monthly Overview (Last 6 Months)", MARGIN, y, headerPaint)
        y += 20f

        val monthFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        val cal = java.util.Calendar.getInstance()
        val monthCounts = mutableMapOf<String, Int>()
        for (i in 5 downTo 0) {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(java.util.Calendar.MONTH, -i)
            val key = "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.MONTH)}"
            monthCounts[key] = 0
        }
        for (bill in bills) {
            cal.timeInMillis = bill.timestamp
            val key = "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.MONTH)}"
            if (monthCounts.containsKey(key)) {
                monthCounts[key] = (monthCounts[key] ?: 0) + 1
            }
        }
        for (i in 5 downTo 0) {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(java.util.Calendar.MONTH, -i)
            val key = "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.MONTH)}"
            val label = monthFormat.format(cal.time)
            val count = monthCounts[key] ?: 0
            canvas.drawText("$label: $count bills", MARGIN + 20f, y, bodyPaint)
            y += LINE_HEIGHT
        }

        document.finishPage(page)
        return savePdf(context, document, "Dashboard_Summary")
    }

    // ---- Dashboard: Bills Breakdown ----

    fun generateBillsBreakdownPdf(context: Context, bills: List<BillEntity>): File {
        val document = PdfDocument()
        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
        var page = document.startPage(pageInfo)
        var canvas = page.canvas
        var y = MARGIN

        canvas.drawText("Bills Breakdown", MARGIN, y + 20f, titlePaint)
        canvas.drawText("Generated: ${dateFormat.format(Date())}", MARGIN, y + 38f, bodyPaint)
        y += 60f
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 20f

        val groups = mapOf(
            "Paid" to bills.filter { it.paymentStatus == "Paid" },
            "Unpaid" to bills.filter { it.paymentStatus == "Unpaid" },
            "Overdue" to bills.filter { it.paymentStatus != "Paid" && it.reminderDatetime != null && it.reminderDatetime <= System.currentTimeMillis() }
        )

        for ((status, list) in groups) {
            if (y + 30f > PAGE_HEIGHT - MARGIN) {
                document.finishPage(page); pageNum++
                pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
                page = document.startPage(pageInfo); canvas = page.canvas; y = MARGIN
            }

            val sPaint = when (status) {
                "Paid" -> statusPaidPaint
                "Overdue" -> statusOverduePaint
                else -> statusUnpaidPaint
            }
            canvas.drawText("$status (${list.size})", MARGIN, y, Paint().apply {
                textSize = 16f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                color = sPaint.color
            })
            y += 20f

            for (bill in list) {
                if (y + 30f > PAGE_HEIGHT - MARGIN) {
                    document.finishPage(page); pageNum++
                    pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
                    page = document.startPage(pageInfo); canvas = page.canvas; y = MARGIN
                }
                canvas.drawText("• ${bill.customName}", MARGIN + 20f, y, bodyPaint)
                canvas.drawText(dateFormat.format(Date(bill.timestamp)), MARGIN + 250f, y, bodyPaint)
                y += LINE_HEIGHT
            }
            y += 12f
        }

        document.finishPage(page)
        return savePdf(context, document, "Bills_Breakdown")
    }

    // ---- Dashboard: Customers Summary ----

    fun generateCustomersSummaryPdf(
        context: Context,
        customers: List<CustomerEntity>,
        bills: List<BillEntity>
    ): File {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        var y = MARGIN

        canvas.drawText("Customers Summary", MARGIN, y + 20f, titlePaint)
        canvas.drawText("Generated: ${dateFormat.format(Date())}", MARGIN, y + 38f, bodyPaint)
        y += 60f
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 20f

        // Column headers
        canvas.drawText("Customer", MARGIN, y, headerPaint)
        canvas.drawText("Total", MARGIN + 250f, y, headerPaint)
        canvas.drawText("Unpaid", MARGIN + 320f, y, headerPaint)
        y += 20f
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 15f

        for (customer in customers) {
            val customerBills = bills.filter { it.customerId == customer.customerId }
            val unpaidCount = customerBills.count { it.paymentStatus != "Paid" }

            canvas.drawText(customer.name, MARGIN, y, bodyPaint)
            canvas.drawText(customerBills.size.toString(), MARGIN + 260f, y, bodyPaint)
            val uPaint = if (unpaidCount > 0) statusUnpaidPaint else bodyPaint
            canvas.drawText(unpaidCount.toString(), MARGIN + 340f, y, uPaint)
            y += LINE_HEIGHT + 4f
        }

        document.finishPage(page)
        return savePdf(context, document, "Customers_Summary")
    }

    // ---- Dashboard: Payments Report ----

    fun generatePaymentsReportPdf(context: Context, bills: List<BillEntity>): File {
        val document = PdfDocument()
        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
        var page = document.startPage(pageInfo)
        var canvas = page.canvas
        var y = MARGIN

        canvas.drawText("Payments Report", MARGIN, y + 20f, titlePaint)
        canvas.drawText("Generated: ${dateFormat.format(Date())}", MARGIN, y + 38f, bodyPaint)
        y += 60f
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 20f

        // Column headers
        canvas.drawText("Bill Name", MARGIN, y, headerPaint)
        canvas.drawText("Status", MARGIN + 220f, y, headerPaint)
        canvas.drawText("Paid Date", MARGIN + 310f, y, headerPaint)
        y += 20f
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 15f

        for (bill in bills) {
            if (y + 25f > PAGE_HEIGHT - MARGIN) {
                document.finishPage(page); pageNum++
                pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
                page = document.startPage(pageInfo); canvas = page.canvas; y = MARGIN
            }

            val name = if (bill.customName.length > 25) bill.customName.take(22) + "..." else bill.customName
            canvas.drawText(name, MARGIN, y, bodyPaint)

            val sPaint = when (bill.paymentStatus) {
                "Paid" -> statusPaidPaint
                "Overdue" -> statusOverduePaint
                else -> statusUnpaidPaint
            }
            canvas.drawText(bill.paymentStatus, MARGIN + 220f, y, sPaint)

            val paidDate = if (bill.paidTimestamp != null) dateFormat.format(Date(bill.paidTimestamp)) else "—"
            canvas.drawText(paidDate, MARGIN + 310f, y, bodyPaint)
            y += LINE_HEIGHT + 4f
        }

        document.finishPage(page)
        return savePdf(context, document, "Payments_Report")
    }

    // ---- Shared helpers ----

    private fun savePdf(context: Context, document: PdfDocument, name: String): File {
        val pdfDir = File(context.cacheDir, "exports")
        if (!pdfDir.exists()) pdfDir.mkdirs()
        val pdfFile = File(pdfDir, "BillSnap_${name}_${System.currentTimeMillis()}.pdf")
        FileOutputStream(pdfFile).use { document.writeTo(it) }
        document.close()
        return pdfFile
    }

    private fun drawThumbnail(canvas: Canvas, imagePath: String, x: Float, y: Float, size: Float) {
        try {
            val file = File(imagePath)
            if (!file.exists()) return

            val options = BitmapFactory.Options().apply { inSampleSize = 8 }
            val bitmap = BitmapFactory.decodeFile(imagePath, options) ?: return
            val scaled = Bitmap.createScaledBitmap(bitmap, size.toInt(), size.toInt(), true)
            canvas.drawBitmap(scaled, x, y, null)
            scaled.recycle()
            bitmap.recycle()
        } catch (_: Exception) { /* Skip thumbnail if decode fails */ }
    }

    fun sharePdf(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share PDF Report"))
    }
}
