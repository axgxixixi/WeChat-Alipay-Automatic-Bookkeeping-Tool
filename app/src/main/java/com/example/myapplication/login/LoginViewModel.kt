package com.example.myapplication.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.TokenManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenManager = TokenManager(application)

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onEmailChanged(email: String) {
        _uiState.update {
            it.copy(
                email = email,
                emailError = null,
                generalError = null
            )
        }
    }

    fun onPasswordChanged(password: String) {
        _uiState.update {
            it.copy(
                password = password,
                passwordError = null,
                generalError = null
            )
        }
    }

    fun onPasswordVisibilityToggle() {
        _uiState.update {
            it.copy(isPasswordVisible = !it.isPasswordVisible)
        }
    }

    fun onLogin() {
        val state = _uiState.value

        // Validate inputs
        var hasError = false

        if (state.email.isBlank()) {
            _uiState.update { it.copy(emailError = LoginErrorType.EmptyEmail.messageResId) }
            hasError = true
        } else if (!isValidEmail(state.email)) {
            _uiState.update { it.copy(emailError = LoginErrorType.InvalidEmail.messageResId) }
            hasError = true
        }

        if (state.password.isBlank()) {
            _uiState.update { it.copy(passwordError = LoginErrorType.EmptyPassword.messageResId) }
            hasError = true
        } else if (state.password.length < 6) {
            _uiState.update { it.copy(passwordError = LoginErrorType.ShortPassword.messageResId) }
            hasError = true
        }

        if (hasError) return

        // Start login
        _uiState.update {
            it.copy(isLoading = true, generalError = null)
        }

        viewModelScope.launch {
            // Simulate network request
            delay(1500)

            // Demo credentials check (for testing purposes only)
            if (state.email == "test@example.com" && state.password == "password") {
                tokenManager.saveToken("mock_token_" + System.currentTimeMillis())
                _uiState.update {
                    it.copy(isLoading = false, isLoginSuccess = true)
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        generalError = LoginErrorType.InvalidCredentials.messageResId
                    )
                }
            }
        }
    }

    fun onDismissError() {
        _uiState.update { it.copy(generalError = null) }
    }

    private fun isValidEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }
}