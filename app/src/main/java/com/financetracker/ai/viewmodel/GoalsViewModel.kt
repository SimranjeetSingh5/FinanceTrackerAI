package com.financetracker.ai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.ai.FinanceApp
import com.financetracker.ai.data.Goal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GoalsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as FinanceApp).repository

    private val _goals = MutableStateFlow<List<Goal>>(emptyList())
    val goals: StateFlow<List<Goal>> = _goals.asStateFlow()

    init {
        viewModelScope.launch { repo.allGoals.collect { _goals.value = it } }
    }

    fun addGoal(name: String, target: Double, targetDate: Long?) {
        viewModelScope.launch { repo.addGoal(Goal(name = name, targetAmount = target, targetDate = targetDate)) }
    }

    fun contribute(goal: Goal, amount: Double) {
        viewModelScope.launch { repo.contributeToGoal(goal, amount) }
    }

    fun deleteGoal(goal: Goal) {
        viewModelScope.launch { repo.deleteGoal(goal) }
    }
}
