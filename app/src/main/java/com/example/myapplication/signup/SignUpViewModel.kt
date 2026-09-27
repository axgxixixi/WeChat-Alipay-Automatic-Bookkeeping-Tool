package com.example.myapplication.signup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SignUpViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SignUpUiState())
    val uiState: StateFlow<SignUpUiState> = _uiState.asStateFlow()

    fun onEmailChanged(email: String) {
        _uiState.update { it.copy(email = email, emailError = null, generalError = null) }
    }

    fun onPasswordChanged(password: String) {
        _uiState.update { it.copy(password = password, passwordError = null, generalError = null) }
    }

    fun onConfirmPasswordChanged(confirmPassword: String) {
        _uiState.update {
            it.copy(confirmPassword = confirmPassword, confirmPasswordError = null, generalError = null)
        }
    }

    fun onVerificationCodeChanged(code: String) {
        // 只允许数字输入，最多 6 位
        if (code.all { it.isDigit() } && code.length <= 6) {
            _uiState.update { it.copy(verificationCode = code, verificationCodeError = null, generalError = null) }
        }
    }

    fun onPasswordVisibilityToggle() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun onConfirmPasswordVisibilityToggle() {
        _uiState.update { it.copy(isConfirmPasswordVisible = !it.isConfirmPasswordVisible) }
    }

    fun onSendVerificationCode() {
        val email = _uiState.value.email

        // 先校验邮箱
        if (email.isBlank()) {
            _uiState.update { it.copy(emailError = SignUpErrorType.EmptyEmail.messageResId) }
            return
        }
        if (!isValidEmail(email)) {
            _uiState.update { it.copy(emailError = SignUpErrorType.InvalidEmail.messageResId) }
            return
        }

        // 模拟发送验证码
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            delay(1500)
            _uiState.update { it.copy(isLoading = false, isCodeSent = true, countdown = 60) }
            startCountdown()
        }
    }

    private fun startCountdown() {
        viewModelScope.launch {
            while (_uiState.value.countdown > 0) {
                delay(1000)
                _uiState.update { it.copy(countdown = it.countdown - 1) }
            }
        }
    }

    fun onSignUp() {
        val state = _uiState.value
        var hasError = false

        if (state.email.isBlank()) {
            _uiState.update { it.copy(emailError = SignUpErrorType.EmptyEmail.messageResId) }
            hasError = true
        } else if (!isValidEmail(state.email)) {
            _uiState.update { it.copy(emailError = SignUpErrorType.InvalidEmail.messageResId) }
            hasError = true
        }

        if (state.password.isBlank()) {
            _uiState.update { it.copy(passwordError = SignUpErrorType.EmptyPassword.messageResId) }
            hasError = true
        } else if (state.password.length < 8) {
            _uiState.update { it.copy(passwordError = SignUpErrorType.ShortPassword.messageResId) }
            hasError = true
        } else if (!hasLetterAndDigit(state.password)) {
            _uiState.update { it.copy(passwordError = SignUpErrorType.WeakPassword.messageResId) }
            hasError = true
        }

        if (state.confirmPassword.isBlank()) {
            _uiState.update { it.copy(confirmPasswordError = SignUpErrorType.EmptyConfirmPassword.messageResId) }
            hasError = true
        } else if (state.password != state.confirmPassword) {
            _uiState.update { it.copy(confirmPasswordError = SignUpErrorType.PasswordMismatch.messageResId) }
            hasError = true
        }

        // 验证码校验
        if (state.verificationCode.isBlank()) {
            _uiState.update { it.copy(verificationCodeError = SignUpErrorType.EmptyVerificationCode.messageResId) }
            hasError = true
        } else if (state.verificationCode.length != 6) {
            _uiState.update { it.copy(verificationCodeError = SignUpErrorType.InvalidVerificationCode.messageResId) }
            hasError = true
        }

        if (hasError) return

        _uiState.update { it.copy(isLoading = true, generalError = null) }

        viewModelScope.launch {
            delay(1500)

            // Mock: reject test@example.com as already taken
            if (state.email == "test@example.com") {
                _uiState.update {
                    it.copy(isLoading = false, generalError = SignUpErrorType.EmailTaken.messageResId)
                }
            } else {
                _uiState.update {
                    it.copy(isLoading = false, isSignUpSuccess = true)
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

    private fun hasLetterAndDigit(value: String): Boolean {
        val hasLetter = value.any { it.isLetter() }
        val hasDigit = value.any { it.isDigit() }
        return hasLetter && hasDigit
    }
}