package com.example.myapplication.signup

data class SignUpUiState(
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val verificationCode: String = "",
    val isPasswordVisible: Boolean = false,
    val isConfirmPasswordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val isCodeSent: Boolean = false,
    val countdown: Int = 0,
    val emailError: String? = null,
    val passwordError: String? = null,
    val confirmPasswordError: String? = null,
    val verificationCodeError: String? = null,
    val generalError: String? = null,
    val isSignUpSuccess: Boolean = false
)