package com.example.myapplication.forgotpassword

data class ForgotPasswordUiState(
    val email: String = "",
    val isLoading: Boolean = false,
    val emailError: String? = null,
    val generalError: String? = null,
    val isEmailSent: Boolean = false
)