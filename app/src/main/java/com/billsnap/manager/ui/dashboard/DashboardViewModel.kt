package com.billsnap.manager.ui.dashboard

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.billsnap.manager.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class DashboardViewModel(private val repository: BillRepository) : ViewModel() {

    // --- Backwards compatible models for existing UI components ---
    data class MonthDetail(
        val monthLabel: String,
        val year: Int,
        val totalCount: Int,
        val paidCount: Int,
        val unpaidCount: Int,
        val bills: List<BillEntity>
    )

    private val _selectedMonthDetail = MutableLiveData<MonthDetail?>()
    val selectedMonthDetail: LiveData<MonthDetail?> = _selectedMonthDetail

    private val _currentDateLabel = MutableLiveData<String>()
    val currentDateLabel: LiveData<String> = _currentDateLabel

    // Resolving creator filtering. If role == worker, pass their UID. Else null.
    // For now, defaulting to null (global shop view)
    private val currentWorkerId: String? = null

    // ─── 1. Reactive Dashboard Counts (Top row metrics) ───
    val dashboardCounts: LiveData<DashboardCounts> = repository.getDashboardCounts(System.currentTimeMillis(), currentWorkerId)
        .flowOn(Dispatchers.IO)
        .asLiveData()

    // ─── 2. Global Financial Summary (Total Billed / Paid / Outstanding) ───
    val globalFinancialSummary: LiveData<GlobalFinancialSummary> = repository.getGlobalFinancialSummary(System.currentTimeMillis(), currentWorkerId)
        .flowOn(Dispatchers.IO)
        .asLiveData()

    // ─── 3. Current Month Financial Summary ───
    val currentMonthSummary: LiveData<GlobalFinancialSummary> = flow {
        val monthCal = Calendar.getInstance()
        monthCal.set(Calendar.DAY_OF_MONTH, 1)
        monthCal.set(Calendar.HOUR_OF_DAY, 0)
        monthCal.set(Calendar.MINUTE, 0)
        monthCal.set(Calendar.SECOND, 0)
        monthCal.set(Calendar.MILLISECOND, 0)
        val startEpoch = monthCal.timeInMillis
        val endEpoch = System.currentTimeMillis()

        emitAll(repository.getMonthlyFinancialSummary(startEpoch, endEpoch, currentWorkerId))
    }.flowOn(Dispatchers.IO).asLiveData()

    // ─── 4. Payment Status Distribution (Donut Chart) ───
    val statusDistribution: LiveData<List<StatusDistribution>> = repository.getPaymentStatusDistribution(currentWorkerId)
        .flowOn(Dispatchers.IO)
        .asLiveData()

    // ─── 5. Top Customers By Paid & Outstanding ───
    val topCustomersByPaid: LiveData<List<CustomerContribution>> = repository.getTopCustomersByPaid(5, currentWorkerId)
        .flowOn(Dispatchers.IO)
        .asLiveData()

    val topCustomersByOutstanding: LiveData<List<CustomerContribution>> = repository.getTopCustomersByOutstanding(5, currentWorkerId)
        .flowOn(Dispatchers.IO)
        .asLiveData()

    // ─── 6. Monthly Trends (Chunking for BarChart backwards compatibility) ───
    val monthlyData: LiveData<List<BarChartView.BarData>> = repository.getAllBillsFlow(currentWorkerId)
        .map { allBills ->
            val now = System.currentTimeMillis()
            val monthFmt = SimpleDateFormat("MMM", Locale.getDefault())
            val cal = Calendar.getInstance()

            val monthKeys = mutableListOf<Triple<String, Int, Int>>()
            for (i in 5 downTo 0) {
                cal.timeInMillis = now
                cal.add(Calendar.MONTH, -i)
                val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
                monthKeys.add(Triple(key, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH)))
            }

            val monthTotal = mutableMapOf<String, Double>()
            val monthPaid = mutableMapOf<String, Double>()
            monthKeys.forEach { (k, _, _) -> monthTotal[k] = 0.0; monthPaid[k] = 0.0 }

            for (bill in allBills) {
                cal.timeInMillis = bill.timestamp
                val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
                if (monthTotal.containsKey(key)) {
                    monthTotal[key] = (monthTotal[key] ?: 0.0) + (bill.totalAmount ?: 0.0)
                    monthPaid[key] = (monthPaid[key] ?: 0.0) + bill.paidAmount
                }
            }

            monthKeys.map { (key, year, month) ->
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.MONTH, month)
                BarChartView.BarData(
                    label = monthFmt.format(cal.time),
                    value = (monthTotal[key] ?: 0.0).toFloat(),
                    paidValue = (monthPaid[key] ?: 0.0).toFloat(),
                    year = year,
                    month = month
                )
            }
        }
        .flowOn(Dispatchers.IO)
        .asLiveData()


    init {
        _currentDateLabel.value = SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date())
    }

    fun loadMonthBills(year: Int, month: Int) {
        viewModelScope.launch {
            val cal = Calendar.getInstance()
            cal.set(year, month, 1, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startDate = cal.timeInMillis
            cal.add(Calendar.MONTH, 1)
            val endDate = cal.timeInMillis - 1

            val bills = repository.getBillsByDateRangeSync(startDate, endDate)
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, month)
            val monthLabel = SimpleDateFormat("MMMM", Locale.getDefault()).format(cal.time)
            val paidCount = bills.count { it.paymentStatus == "Paid" }

            _selectedMonthDetail.value = MonthDetail(
                monthLabel = monthLabel,
                year = year,
                totalCount = bills.size,
                paidCount = paidCount,
                unpaidCount = bills.size - paidCount,
                bills = bills
            )
        }
    }

    fun clearMonthSelection() {
        _selectedMonthDetail.value = null
    }
}
