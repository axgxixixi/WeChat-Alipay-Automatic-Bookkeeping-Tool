package com.example.myapplication.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.DatabaseHelper
import com.example.myapplication.model.MonthlySummary
import com.example.myapplication.model.Transaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

data class HomeUiState(
    val monthlySummary: MonthlySummary = MonthlySummary(),
    val recentTransactions: List<Transaction> = emptyList(),
    val isLoading: Boolean = true
)

class HomeViewModel(private val db: DatabaseHelper) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val monthStart = cal.timeInMillis

        cal.add(Calendar.MONTH, 1)
        val monthEnd = cal.timeInMillis

        viewModelScope.launch {
            db.getMonthlySummary(monthStart, monthEnd).collect { summary ->
                _uiState.update { it.copy(monthlySummary = summary) }
            }
        }

        viewModelScope.launch {
            db.getRecentTransactions(5).collect { transactions ->
                _uiState.update {
                    it.copy(recentTransactions = transactions, isLoading = false)
                }
            }
        }
    }
}