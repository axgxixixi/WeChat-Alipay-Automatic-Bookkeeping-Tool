package com.example.myapplication.signup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SignUpScreen(
    viewModel: SignUpViewModel = viewModel(),
    onSignUpSuccess: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()

    if (state.isSignUpSuccess) {
        onSignUpSuccess()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "创建账号",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(28.dp))

            // General error banner
            AnimatedVisibility(
                visible = state.generalError != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                state.generalError?.let { error ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Email field
            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChanged,
                label = { Text("邮箱") },
                isError = state.emailError != null,
                supportingText = state.emailError?.let { error ->
                    { Text(text = error, color = MaterialTheme.colorScheme.error) }
                },
                singleLine = true,
                enabled = !state.isLoading,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Verification code field
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = state.verificationCode,
                        onValueChange = viewModel::onVerificationCodeChanged,
                        label = { Text("验证码") },
                        isError = state.verificationCodeError != null,
                        singleLine = true,
                        enabled = !state.isLoading,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.small
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = viewModel::onSendVerificationCode,
                        enabled = !state.isLoading && state.countdown == 0 && state.email.isNotBlank(),
                        modifier = Modifier.height(56.dp),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.primary,
                            disabledContentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            text = if (state.countdown > 0) "${state.countdown}s后重新发送" else "发送验证码",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                // 错误提示放在 Row 外部，不影响输入框与按钮的对齐
                state.verificationCodeError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Password field
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChanged,
                label = { Text("密码") },
                isError = state.passwordError != null,
                supportingText = state.passwordError?.let { error ->
                    { Text(text = error, color = MaterialTheme.colorScheme.error) }
                },
                singleLine = true,
                enabled = !state.isLoading,
                visualTransformation = if (state.isPasswordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = viewModel::onPasswordVisibilityToggle) {
                        Icon(
                            imageVector = if (state.isPasswordVisible) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = if (state.isPasswordVisible) "隐藏密码" else "显示密码"
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Confirm password field
            OutlinedTextField(
                value = state.confirmPassword,
                onValueChange = viewModel::onConfirmPasswordChanged,
                label = { Text("确认密码") },
                isError = state.confirmPasswordError != null,
                supportingText = state.confirmPasswordError?.let { error ->
                    { Text(text = error, color = MaterialTheme.colorScheme.error) }
                },
                singleLine = true,
                enabled = !state.isLoading,
                visualTransformation = if (state.isConfirmPasswordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = viewModel::onConfirmPasswordVisibilityToggle) {
                        Icon(
                            imageVector = if (state.isConfirmPasswordVisible) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = if (state.isConfirmPasswordVisible) "隐藏密码" else "显示密码"
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { viewModel.onSignUp() }
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Sign up button
            Button(
                onClick = viewModel::onSignUp,
                enabled = !state.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = MaterialTheme.shapes.small
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(text = "创建账号", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Login link
            Row {
                Text(
                    text = "已有账号？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "登录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(
                        enabled = !state.isLoading,
                        onClick = onNavigateToLogin
                    )
                )
            }

            Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.height(48.dp))
            }
            // 返回按钮（放在 Column 之后，确保不被遮挡）
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回"
                )
            }
        }
    }
}