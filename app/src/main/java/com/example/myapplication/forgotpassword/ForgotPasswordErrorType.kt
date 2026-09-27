package com.example.myapplication.forgotpassword

sealed class ForgotPasswordErrorType(val messageResId: String) {
    data object EmptyEmail : ForgotPasswordErrorType("请输入邮箱")
    data object InvalidEmail : ForgotPasswordErrorType("邮箱格式不正确")
    data object NetworkError : ForgotPasswordErrorType("网络连接失败，请检查网络后重试")
    data object ServerError : ForgotPasswordErrorType("服务器异常，请稍后重试")
}