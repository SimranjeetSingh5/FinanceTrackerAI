package com.financetracker.ai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.ai.FinanceApp
import com.financetracker.ai.data.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.*

data class CategorySlice(val category: Category, val amount: Double)
data class MonthPoint(val label: String, val income: Double, val expense: Double)

class AnalyticsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as FinanceApp).repository

    private val _breakdown = MutableStateFlow<List<CategorySlice>>(emptyList())
    val breakdown: StateFlow<List<CategorySlice>> = _breakdown.asStateFlow()

    private val _trend = MutableStateFlow<List<MonthPoint>>(emptyList())
    val trend: StateFlow<List<MonthPoint>> = _trend.asStateFlow()

    init {
        loadCurrentMonthBreakdown()
        loadSixMonthTrend()
    }

    fun loadCurrentMonthBreakdown() {
        viewModelScope.launch {
            val cal = Calendar.getInstance()
            cal.set(Calendar.DAY_OF_MONTH, 1); cal.set(Calendar.HOUR_OF_DAY, 0)
            val start = cal.timeInMillis
            cal.add(Calendar.MONTH, 1)
            val end = cal.timeInMillis - 1

            val totals = repo.categoryBreakdown(start, end)
            val categoryList = repo.allCategories.first()
            val categories = categoryList.associateBy { it.id }

            _breakdown.value = totals.mapNotNull { t ->
                categories[t.categoryId]?.let { CategorySlice(it, t.total) }
            }
        }
    }

    fun loadSixMonthTrend() {
        viewModelScope.launch {
            val points = mutableListOf<MonthPoint>()
            val cal = Calendar.getInstance()
            val labelFormat = java.text.SimpleDateFormat("MMM", Locale.getDefault())

            for (i in 5 downTo 0) {
                val monthCal = cal.clone() as Calendar
                monthCal.add(Calendar.MONTH, -i)
                monthCal.set(Calendar.DAY_OF_MONTH, 1); monthCal.set(Calendar.HOUR_OF_DAY, 0)
                val start = monthCal.timeInMillis
                monthCal.add(Calendar.MONTH, 1)
                val end = monthCal.timeInMillis - 1
                val (income, expense) = repo.totalsFor(start, end)
                points.add(MonthPoint(labelFormat.format(Date(start)), income, expense))
            }
            _trend.value = points
        }
    }
}
