package com.billsnap.manager.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.drawToBitmap
import androidx.core.view.isVisible
import com.billsnap.manager.R
import com.billsnap.manager.data.PaymentMethodInfo
import com.billsnap.manager.data.PaymentMethodType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import coil.imageLoader
import java.util.Locale

/**
 * Generates professional payment summary images (1080×1920) from app data
 * and launches share intents (WhatsApp-first with generic fallback).
 *
 * All bitmap generation runs on [Dispatchers.Default] to avoid UI thread blocking.
 * Bitmaps are recycled after saving to prevent OOM.
 * No storage permission required — uses app cache directory.
 */
class ShareCardGenerator(private val context: Context) {

    companion object {
        private const val CARD_WIDTH = 1080
        private const val CARD_HEIGHT = 1920
        private const val SHARE_DIR = "share_cards"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
    }

    // ═══════════════════════════════════════════════════
    // PUBLIC DATA CLASSES
    // ═══════════════════════════════════════════════════

    data class BillShareData(
        val storeName: String,
        val storeLogoPath: String?,
        val customerName: String,
        val invoiceId: String,
        val invoiceDate: String?,
        val dueDate: String?,
        val todayDate: String,
        val originalAmount: Double,
        val paidAmount: Double,
        val remainingAmount: Double,
        val paymentStatus: String,
        val isOverdue: Boolean,
        val overdueDays: Int,
        val onTimeCount: Int,
        val totalBillCount: Int,
        val paymentMethods: List<PaymentMethodInfo>,
        val currencySymbol: String = "Rs."
    )

    data class ProfileShareData(
        val storeName: String,
        val storeLogoPath: String?,
        val customerName: String,
        val customerImagePath: String?,
        val totalBills: Int,
        val totalPaidAmount: Double,
        val totalOutstandingAmount: Double,
        val onTimePercentage: Int,
        val recentBills: List<RecentBillInfo>,
        val overdueBillCount: Int,
        val paymentMethods: List<PaymentMethodInfo>,
        val currencySymbol: String = "Rs."
    )

    data class RecentBillInfo(
        val invoiceId: String,
        val amount: Double,
        val status: String
    )

    // ═══════════════════════════════════════════════════
    // PUBLIC API — Generate + Share
    // ═══════════════════════════════════════════════════

    /**
     * Generates a bill share card bitmap, saves to cache, and returns the content URI.
     * Must be called from a coroutine. Returns null on failure.
     */
    suspend fun generateBillShareCard(data: BillShareData): Uri? = withContext(Dispatchers.Default) {
        try {
            val view = inflateBillCard(data)
            val bitmap = renderViewToBitmap(view)
            val uri = saveBitmapToCache(bitmap, "bill_share_${System.currentTimeMillis()}.png")
            bitmap.recycle()
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Generates a profile summary share card bitmap, saves to cache, and returns the content URI.
     * Must be called from a coroutine. Returns null on failure.
     */
    suspend fun generateProfileShareCard(data: ProfileShareData): Uri? = withContext(Dispatchers.Default) {
        try {
            val view = inflateStatementCard(data)
            val bitmap = renderViewToBitmap(view)
            val uri = saveBitmapToCache(bitmap, "statement_${System.currentTimeMillis()}.jpg", useJpeg = true, quality = 92)
            bitmap.recycle()
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Attempts to share via WhatsApp first. Falls back to generic chooser if not installed.
     */
    fun shareWithFallback(uri: Uri, message: String) {
        if (!shareViaWhatsApp(uri, message)) {
            shareGeneric(uri, message)
        }
    }

    /**
     * Share image via WhatsApp with prefilled text.
     * @return true if WhatsApp intent was launched, false if WhatsApp not available.
     */
    fun shareViaWhatsApp(uri: Uri, message: String): Boolean {
        return try {
            val pm = context.packageManager
            pm.getLaunchIntentForPackage(WHATSAPP_PACKAGE) ?: return false

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                setPackage(WHATSAPP_PACKAGE)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, message)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Share image via generic Android share chooser.
     */
    fun shareGeneric(uri: Uri, message: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, message)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.share_bill))
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    // ═══════════════════════════════════════════════════
    // INTERNAL — Bill Card Inflation
    // ═══════════════════════════════════════════════════

    private fun inflateBillCard(data: BillShareData): View {
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.layout_share_card_bill, null, false)

        val formatter = getAmountFormatter(data.currencySymbol)

        // Header
        root.findViewById<TextView>(R.id.tvStoreName).text = data.storeName.ifBlank { context.getString(R.string.app_name) }
        root.findViewById<TextView>(R.id.tvFooterStoreName).text = data.storeName.ifBlank { context.getString(R.string.app_name) }

        // Store logo
        val ivLogo = root.findViewById<ImageView>(R.id.ivStoreLogo)
        if (data.storeLogoPath != null) {
            val logoFile = File(data.storeLogoPath)
            if (logoFile.exists()) {
                ivLogo.setImageURI(Uri.fromFile(logoFile))
                ivLogo.visibility = View.VISIBLE
            }
        }

        // Status tag
        val tvStatusTag = root.findViewById<TextView>(R.id.tvStatusTag)
        when {
            data.paymentStatus.equals("Paid", true) -> {
                tvStatusTag.text = context.getString(R.string.payment_received)
                tvStatusTag.setBackgroundResource(R.drawable.bg_share_indicator_paid)
            }
            else -> {
                tvStatusTag.text = context.getString(R.string.payment_reminder)
                tvStatusTag.setBackgroundResource(R.drawable.bg_share_tag)
            }
        }

        // Customer info
        root.findViewById<TextView>(R.id.tvCustomerName).text = data.customerName.ifBlank { "—" }
        root.findViewById<TextView>(R.id.tvInvoiceId).text = data.invoiceId.ifBlank { "—" }
        root.findViewById<TextView>(R.id.tvInvoiceDate).text = data.invoiceDate ?: "—"
        root.findViewById<TextView>(R.id.tvTodayDate).text = data.todayDate

        // Due date
        val layoutDueDate = root.findViewById<LinearLayout>(R.id.layoutDueDate)
        if (data.dueDate != null) {
            root.findViewById<TextView>(R.id.tvDueDate).text = data.dueDate
            layoutDueDate.visibility = View.VISIBLE
        } else {
            layoutDueDate.visibility = View.GONE
        }

        // Amounts
        root.findViewById<TextView>(R.id.tvOriginalAmount).text = formatter(data.originalAmount)
        root.findViewById<TextView>(R.id.tvPaidAmount).text = formatter(data.paidAmount)
        val tvRemaining = root.findViewById<TextView>(R.id.tvRemainingAmount)
        tvRemaining.text = formatter(data.remainingAmount)

        // Overdue / Paid indicator
        val layoutIndicator = root.findViewById<LinearLayout>(R.id.layoutOverdueIndicator)
        val viewBar = root.findViewById<View>(R.id.viewIndicatorBar)
        val tvLabel = root.findViewById<TextView>(R.id.tvOverdueLabel)

        when {
            data.paymentStatus.equals("Paid", true) -> {
                layoutIndicator.visibility = View.VISIBLE
                layoutIndicator.setBackgroundResource(R.drawable.bg_share_indicator_paid)
                tvLabel.text = context.getString(R.string.payment_completed)
                // Use primary color for paid indicator bar
                viewBar.setBackgroundColor(resolveThemeColor(com.google.android.material.R.attr.colorPrimary))
            }
            data.isOverdue && data.overdueDays > 0 -> {
                layoutIndicator.visibility = View.VISIBLE
                layoutIndicator.setBackgroundResource(R.drawable.bg_share_indicator)
                tvLabel.text = context.getString(R.string.overdue_by_days, data.overdueDays)
                viewBar.setBackgroundColor(resolveThemeColor(com.google.android.material.R.attr.colorError))
            }
            else -> {
                layoutIndicator.visibility = View.GONE
            }
        }

        // Timeline
        val layoutTimeline = root.findViewById<LinearLayout>(R.id.layoutTimeline)
        if (data.invoiceDate != null && data.dueDate != null) {
            layoutTimeline.visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.tvTimelineInvoice).text = data.invoiceDate
            root.findViewById<TextView>(R.id.tvTimelineDue).text = data.dueDate
            root.findViewById<TextView>(R.id.tvTimelineToday).text = context.getString(R.string.today_date_label)
        } else {
            layoutTimeline.visibility = View.GONE
        }

        // Mini analytics
        val tvAnalytics = root.findViewById<TextView>(R.id.tvMiniAnalytics)
        if (data.totalBillCount > 0) {
            tvAnalytics.visibility = View.VISIBLE
            tvAnalytics.text = context.getString(R.string.on_time_stats, data.onTimeCount, data.totalBillCount)
        } else {
            tvAnalytics.visibility = View.GONE
        }

        // Payment methods
        bindPaymentMethods(root, data.paymentMethods)

        return root
    }

    // ═══════════════════════════════════════════════════
    // INTERNAL — V4 Strict Statement Card Inflation
    // ═══════════════════════════════════════════════════

    private suspend fun inflateStatementCard(data: ProfileShareData): View {
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.layout_share_statement_card, null, false)

        val formatter = getAmountFormatter(data.currencySymbol)
        val storeName = data.storeName.ifBlank { context.getString(R.string.app_name) }
        val isAllCleared = data.totalOutstandingAmount <= 0.0

        // ── HEADER ──
        root.findViewById<TextView>(R.id.tvStoreName).text = storeName
        root.findViewById<TextView>(R.id.tvFooterStore).text = storeName

        // Store logo (Coil sync load for offscreen)
        val ivLogo = root.findViewById<ImageView>(R.id.ivStoreLogo)
        if (data.storeLogoPath != null) {
            val logoFile = java.io.File(data.storeLogoPath)
            if (logoFile.exists()) {
                val request = coil.request.ImageRequest.Builder(context)
                    .data(logoFile)
                    .transformations(coil.transform.CircleCropTransformation())
                    .build()
                val result = context.imageLoader.execute(request)
                if (result is coil.request.SuccessResult) {
                    ivLogo.setImageDrawable(result.drawable)
                    ivLogo.visibility = View.VISIBLE
                }
            }
        }

        // Statement chip — "Payment Completed" when all cleared
        val tvChip = root.findViewById<TextView>(R.id.tvStatementChip)
        if (isAllCleared) {
            tvChip.text = context.getString(R.string.payment_completed_chip)
        }

        // Customer avatar or initial fallback
        val ivAvatar = root.findViewById<ImageView>(R.id.ivCustomerAvatar)
        val tvInitial = root.findViewById<TextView>(R.id.tvInitialBadge)
        if (data.customerImagePath != null) {
            val avatarFile = java.io.File(data.customerImagePath)
            if (avatarFile.exists()) {
                val request = coil.request.ImageRequest.Builder(context)
                    .data(avatarFile)
                    .transformations(coil.transform.CircleCropTransformation())
                    .build()
                val result = context.imageLoader.execute(request)
                if (result is coil.request.SuccessResult) {
                    ivAvatar.setImageDrawable(result.drawable)
                    ivAvatar.visibility = View.VISIBLE
                    tvInitial.visibility = View.GONE
                } else {
                    tvInitial.text = getInitials(data.customerName)
                }
            } else {
                tvInitial.text = getInitials(data.customerName)
            }
        } else {
            tvInitial.text = getInitials(data.customerName)
        }

        // ── FINANCIAL GRID ──
        root.findViewById<TextView>(R.id.tvCustomerName).text = data.customerName
        root.findViewById<TextView>(R.id.tvTotalBills).text = data.totalBills.toString()
        root.findViewById<TextView>(R.id.tvPaidAmount).text = formatter(data.totalPaidAmount)
        root.findViewById<TextView>(R.id.tvOnTime).text = "${data.onTimePercentage}%"

        // Remaining balance: red by default (from XML); green + check when cleared
        val tvRemaining = root.findViewById<TextView>(R.id.tvRemainingBalance)
        val ivClearedCheck = root.findViewById<ImageView>(R.id.ivClearedCheck)
        if (isAllCleared) {
            tvRemaining.text = formatter(0.0)
            tvRemaining.setTextColor(ContextCompat.getColor(context, R.color.share_paid_green))
            ivClearedCheck.visibility = View.VISIBLE
        } else {
            tvRemaining.text = formatter(data.totalOutstandingAmount)
            ivClearedCheck.visibility = View.GONE
        }

        // ── PENDING INVOICES (only where remaining > 0, i.e. NOT Paid) ──
        val pendingBills = data.recentBills.filter { !it.status.equals("Paid", true) }
        val displayBills = pendingBills.take(3)
        val totalPendingCount = pendingBills.size

        val lblPending = root.findViewById<TextView>(R.id.lblPending)
        if (isAllCleared || pendingBills.isEmpty()) {
            lblPending.visibility = View.GONE
        } else {
            lblPending.visibility = View.VISIBLE
        }

        val invoiceRows = listOf(
            InvoiceRowIds(R.id.rowInv1, R.id.tvInv1Id, R.id.tvInv1Amt, R.id.tvInv1Status),
            InvoiceRowIds(R.id.rowInv2, R.id.tvInv2Id, R.id.tvInv2Amt, R.id.tvInv2Status),
            InvoiceRowIds(R.id.rowInv3, R.id.tvInv3Id, R.id.tvInv3Amt, R.id.tvInv3Status)
        )

        for (i in invoiceRows.indices) {
            val row = invoiceRows[i]
            val layout = root.findViewById<ConstraintLayout>(row.layoutId)
            if (!isAllCleared && i < displayBills.size) {
                val bill = displayBills[i]
                layout.visibility = View.VISIBLE
                root.findViewById<TextView>(row.idTextId).text = bill.invoiceId
                root.findViewById<TextView>(row.amountTextId).text = formatter(bill.amount)

                val tvStatus = root.findViewById<TextView>(row.statusTextId)
                tvStatus.text = bill.status
                applyStatusChip(tvStatus, bill.status)
            } else {
                layout.visibility = View.GONE
            }
        }

        // "+X more pending invoices"
        val tvMore = root.findViewById<TextView>(R.id.tvMorePending)
        if (!isAllCleared && totalPendingCount > 3) {
            tvMore.visibility = View.VISIBLE
            tvMore.text = context.getString(R.string.more_pending_invoices, totalPendingCount - 3)
        } else {
            tvMore.visibility = View.GONE
        }

        // ── OVERDUE WARNING ──
        val layoutOverdue = root.findViewById<ConstraintLayout>(R.id.layoutOverdueWarning)
        if (data.overdueBillCount > 0) {
            layoutOverdue.visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.tvOverdueText).text =
                if (data.overdueBillCount == 1) "1 invoice overdue"
                else context.getString(R.string.invoices_overdue, data.overdueBillCount)
        } else {
            layoutOverdue.visibility = View.GONE
        }

        // ── PAYMENT METHODS ──
        bindPaymentCards(root, data.paymentMethods)

        return root
    }

    /**
     * Applies hardcoded status chip background. NO Paid status shown in pending.
     * Partial/Unpaid → #3F0B0B, Overdue → #7F1D1D
     */
    private fun applyStatusChip(tv: TextView, status: String) {
        when {
            status.equals("Overdue", true) -> {
                tv.setBackgroundResource(R.drawable.bg_share_chip_overdue)
            }
            else -> { // Unpaid, Partial
                tv.setBackgroundResource(R.drawable.bg_share_chip_unpaid)
            }
        }
    }

    /**
     * Helper data class for invoice row view IDs.
     */
    private data class InvoiceRowIds(
        val layoutId: Int,
        val idTextId: Int,
        val amountTextId: Int,
        val statusTextId: Int
    )

    /**
     * Extracts up to 2 initials from a customer name.
     */
    private fun getInitials(name: String): String {
        return name.trim()
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifEmpty { "?" }
    }

    private fun bindPaymentCards(root: View, methods: List<PaymentMethodInfo>) {
        val lblPay = root.findViewById<TextView>(R.id.lblPayMethods)
        val container = root.findViewById<LinearLayout>(R.id.containerPaymentMethods)

        android.util.Log.d("ShareCard", "PaymentMethods count: ${methods.size}")

        if (methods.isEmpty()) {
            lblPay.visibility = View.GONE
            container.visibility = View.GONE
            return
        }

        lblPay.visibility = View.VISIBLE
        container.visibility = View.VISIBLE
        container.removeAllViews()

        val inflater = LayoutInflater.from(root.context)
        methods.forEach { method ->
            val view = inflater.inflate(R.layout.item_payment_method, container, false)
            
            val iconRes = when (method.type) {
                PaymentMethodType.BANK -> R.drawable.ic_bank
                PaymentMethodType.EASYPAISA, PaymentMethodType.JAZZCASH -> R.drawable.ic_mobile_payment
            }
            view.findViewById<ImageView>(R.id.ivPaymentIcon).setImageResource(iconRes)
            view.findViewById<TextView>(R.id.methodName).text = method.type.displayName
            view.findViewById<TextView>(R.id.methodNumber).text = method.accountNumber
            
            container.addView(view)
        }
    }

    // ═══════════════════════════════════════════════════
    // INTERNAL — Shared Helpers
    // ═══════════════════════════════════════════════════

    /**
     * Binds up to 3 payment methods dynamically.
     */
    private fun bindPaymentMethods(root: View, methods: List<PaymentMethodInfo>) {
        val container = root.findViewById<LinearLayout>(R.id.layoutPaymentMethods)
        
        android.util.Log.d("ShareCard", "PaymentMethods count (Bill Share): ${methods.size}")

        if (methods.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        
        container.visibility = View.VISIBLE
        
        // Remove children (other than header text)
        val childCount = container.childCount
        if (childCount > 1) {
            container.removeViews(1, childCount - 1)
        }

        val inflater = LayoutInflater.from(root.context)
        methods.forEach { method ->
            val view = inflater.inflate(R.layout.item_payment_method, container, false)
            
            val iconRes = when (method.type) {
                PaymentMethodType.BANK -> R.drawable.ic_bank
                PaymentMethodType.EASYPAISA, PaymentMethodType.JAZZCASH -> R.drawable.ic_mobile_payment
            }
            view.findViewById<ImageView>(R.id.ivPaymentIcon).setImageResource(iconRes)
            view.findViewById<TextView>(R.id.methodName).text = method.type.displayName
            view.findViewById<TextView>(R.id.methodNumber).text = method.accountNumber
            
            container.addView(view)
        }
    }

    /**
     * Measures, lays out, and renders a view hierarchy to a Bitmap.
     * The view is sized to exactly [CARD_WIDTH] x [CARD_HEIGHT] pixels.
     */
    private fun renderViewToBitmap(view: View): Bitmap {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(CARD_WIDTH, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(CARD_HEIGHT, View.MeasureSpec.AT_MOST)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view.drawToBitmap(Bitmap.Config.ARGB_8888)
    }

    /**
     * Saves bitmap to cache directory and returns a FileProvider content URI.
     * Old share card files are cleaned up before saving.
     */
    private fun saveBitmapToCache(bitmap: Bitmap, filename: String, useJpeg: Boolean = false, quality: Int = 100): Uri? {
        val shareDir = java.io.File(context.cacheDir, SHARE_DIR)
        if (!shareDir.exists()) shareDir.mkdirs()

        cleanupOldFiles(shareDir, maxKeep = 5)

        val file = java.io.File(shareDir, filename)
        val format = if (useJpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
        java.io.FileOutputStream(file).use { out ->
            bitmap.compress(format, quality, out)
            out.flush()
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * Removes old share card files to prevent cache bloat.
     */
    private fun cleanupOldFiles(dir: File, maxKeep: Int) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        if (files.size > maxKeep) {
            files.drop(maxKeep).forEach { it.delete() }
        }
    }

    /**
     * Returns a currency formatting function.
     */
    private fun getAmountFormatter(currencySymbol: String): (Double) -> String {
        val nf = java.text.NumberFormat.getNumberInstance(java.util.Locale("en", "PK")).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }
        return { amount -> "$currencySymbol ${nf.format(amount)}" }
    }

    /**
     * Resolves a theme color attribute value.
     */
    private fun resolveThemeColor(attrResId: Int): Int {
        val typedArray = context.obtainStyledAttributes(intArrayOf(attrResId))
        val color = typedArray.getColor(0, 0)
        typedArray.recycle()
        return color
    }
}
