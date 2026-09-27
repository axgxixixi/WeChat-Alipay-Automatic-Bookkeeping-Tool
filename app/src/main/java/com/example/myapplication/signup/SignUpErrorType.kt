package com.example.myapplication.signup

sealed class SignUpErrorType(val messageResId: String) {
    data object EmptyEmail : SignUpErrorType("请输入邮箱")
    data object InvalidEmail : SignUpErrorType("邮箱格式不正确")
    data object EmptyPassword : SignUpErrorType("请输入密码")
    data object ShortPassword : SignUpErrorType("密码至少需要8个字符")
    data object WeakPassword : SignUpErrorType("密码需包含字母和数字")
    data object EmptyConfirmPassword : SignUpErrorType("请确认密码")
    data object PasswordMismatch : SignUpErrorType("两次输入的密码不一致")
    data object EmptyVerificationCode : SignUpErrorType("请输入验证码")
    data object InvalidVerificationCode : SignUpErrorType("验证码格式不正确")
    data object NetworkError : SignUpErrorType("网络连接失败，请检查网络后重试")
    data object EmailTaken : SignUpErrorType("该邮箱已被注册")
    data object ServerError : SignUpErrorType("服务器异常，请稍后重试")
}