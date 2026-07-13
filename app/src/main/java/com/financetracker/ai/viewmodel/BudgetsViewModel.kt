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
import java.text.SimpleDateFormat
import java.util.*

data class CategoryBudgetProgress(
    val category: Category,
    val limit: Double,
    val spent: Double
) {
    val pct: Float get() = if (limit > 0) (spent / limit).toFloat().coerceIn(0f, 2f) else 0f
    val isOverBudget: Boolean get() = spent > limit
}

class BudgetsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as FinanceApp).repository

    private val _progress = MutableStateFlow<List<CategoryBudgetProgress>>(emptyList())
    val progress: StateFlow<List<CategoryBudgetProgress>> = _progress.asStateFlow()

    val currentMonthYear: String = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val cal = Calendar.getInstance()
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            val start = cal.timeInMillis
            cal.add(Calendar.MONTH, 1)
            val end = cal.timeInMillis - 1

            val catList = repo.allCategories.first()
            val totals = repo.categoryBreakdown(start, end).associateBy { it.categoryId }

            _progress.value = catList
                .filter { it.monthlyBudget != null }
                .map { cat ->
                    CategoryBudgetProgress(
                        category = cat,
                        limit = cat.monthlyBudget ?: 0.0,
                        spent = totals[cat.id]?.total ?: 0.0
                    )
                }
        }
    }

    fun setBudget(category: Category, limit: Double) {
        viewModelScope.launch {
            repo.updateCategoryBudget(category, limit)
            refresh()
        }
    }
}
