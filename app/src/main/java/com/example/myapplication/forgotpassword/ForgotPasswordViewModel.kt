package com.example.myapplication.forgotpassword

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ForgotPasswordViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ForgotPasswordUiState())
    val uiState: StateFlow<ForgotPasswordUiState> = _uiState.asStateFlow()

    fun onEmailChanged(email: String) {
        _uiState.update { it.copy(email = email, emailError = null, generalError = null) }
    }

    fun onSendResetLink() {
        val state = _uiState.value

        if (state.email.isBlank()) {
            _uiState.update { it.copy(emailError = ForgotPasswordErrorType.EmptyEmail.messageResId) }
            return
        }
        if (!isValidEmail(state.email)) {
            _uiState.update { it.copy(emailError = ForgotPasswordErrorType.InvalidEmail.messageResId) }
            return
        }

        _uiState.update { it.copy(isLoading = true, generalError = null) }

        viewModelScope.launch {
            delay(1500)
            _uiState.update { it.copy(isLoading = false, isEmailSent = true) }
        }
    }

    fun onDismissError() {
        _uiState.update { it.copy(generalError = null) }
    }

    private fun isValidEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }
}