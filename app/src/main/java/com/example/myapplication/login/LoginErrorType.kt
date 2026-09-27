package com.example.myapplication.login

sealed class LoginErrorType(val messageResId: String) {
    data object EmptyEmail : LoginErrorType("请输入邮箱")
    data object InvalidEmail : LoginErrorType("邮箱格式不正确")
    data object EmptyPassword : LoginErrorType("请输入密码")
    data object ShortPassword : LoginErrorType("密码至少需要6个字符")
    data object NetworkError : LoginErrorType("网络连接失败，请检查网络后重试")
    data object InvalidCredentials : LoginErrorType("邮箱或密码错误")
    data object ServerError : LoginErrorType("服务器异常，请稍后重试")
}