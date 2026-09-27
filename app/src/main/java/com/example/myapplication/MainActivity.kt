package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.data.DatabaseHelper
import com.example.myapplication.forgotpassword.ForgotPasswordScreen
import com.example.myapplication.login.LoginScreen
import com.example.myapplication.notification.KeepAliveService
import com.example.myapplication.signup.SignUpScreen
import com.example.myapplication.ui.home.HomeScreen
import com.example.myapplication.ui.home.HomeViewModel
import com.example.myapplication.ui.navigation.BottomNavBar
import com.example.myapplication.ui.navigation.BottomTab
import com.example.myapplication.ui.profile.ProfileScreen
import com.example.myapplication.ui.records.RecordsScreen
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val NOTIFICATION_LISTENER_SETTINGS =
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"

        fun isNotificationListenerEnabled(context: Context, packageName: String): Boolean {
            val enabledListeners = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                ) ?: ""
            } catch (e: Exception) {
                ""
            }
            return enabledListeners.contains(packageName)
        }

        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }
    }

    // 请求 POST_NOTIFICATIONS 运行时权限（Android 13+）
    private val postNotificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "POST_NOTIFICATIONS: ${if (granted) "已授予" else "已拒绝"}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android 13+ 需要运行时请求 POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            postNotificationsLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MyApplicationTheme {
                val db = remember { DatabaseHelper.getInstance(this) }

                // 弹窗状态
                var showNotificationGuide by remember { mutableStateOf(false) }
                var showBatteryGuide by remember { mutableStateOf(false) }

                // 每次回到前台都重新检查权限
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                LaunchedEffect(lifecycle) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        // 检查通知读取权限
                        if (!isNotificationListenerEnabled(this@MainActivity, packageName)) {
                            showNotificationGuide = true
                            Log.w(TAG, "通知读取权限未开启")
                        } else {
                            showNotificationGuide = false
                            Log.d(TAG, "通知读取权限已开启")
                            // 监听已开启 → 拉起前台保活服务, 防止后台被 ROM 冻结
                            KeepAliveService.start(this@MainActivity)
                        }

                        // 检查电池优化（独立于通知权限）
                        if (!isIgnoringBatteryOptimizations(this@MainActivity)) {
                            showBatteryGuide = true
                            Log.w(TAG, "系统电池优化未关闭")
                        } else {
                            showBatteryGuide = false
                            Log.d(TAG, "系统电池优化已关闭")
                        }
                    }
                }

                // 弹窗显示：通知权限优先，搞定后再显示电池优化
                if (showNotificationGuide) {
                    NotificationAccessGuideDialog(
                        onOpenSettings = {
                            showNotificationGuide = false
                            openNotificationListenerSettings()
                        },
                        onDismiss = {
                            showNotificationGuide = false
                        }
                    )
                } else if (showBatteryGuide) {
                    BatteryOptimizationGuideDialog(
                        onOpenSettings = {
                            showBatteryGuide = false
                            openAppBatterySettings()
                        },
                        onDismiss = {
                            showBatteryGuide = false
                        }
                    )
                }

                var authScreen by remember { mutableStateOf<Screen?>(null) }
                var currentTab by remember { mutableStateOf(BottomTab.HOME) }

                if (authScreen != null) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        when (authScreen) {
                            Screen.LOGIN -> LoginScreen(
                                onLoginSuccess = { authScreen = null },
                                onNavigateToSignUp = { authScreen = Screen.SIGN_UP },
                                onNavigateToForgotPassword = { authScreen = Screen.FORGOT_PASSWORD },
                                onBack = {
                                    authScreen = null
                                    currentTab = BottomTab.PROFILE
                                }
                            )
                            Screen.SIGN_UP -> SignUpScreen(
                                onSignUpSuccess = { authScreen = null },
                                onNavigateToLogin = { authScreen = Screen.LOGIN },
                                onBack = {
                                    authScreen = null
                                    currentTab = BottomTab.PROFILE
                                }
                            )
                            Screen.FORGOT_PASSWORD -> ForgotPasswordScreen(
                                onNavigateToLogin = { authScreen = Screen.LOGIN },
                                onBack = {
                                    authScreen = null
                                    currentTab = BottomTab.PROFILE
                                }
                            )
                            else -> {}
                        }
                    }
                } else {
                    Scaffold(
                        bottomBar = {
                            BottomNavBar(
                                currentTab = currentTab,
                                onTabSelected = { currentTab = it }
                            )
                        }
                    ) { innerPadding ->
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            when (currentTab) {
                                BottomTab.HOME -> {
                                    val factory = remember {
                                        object : ViewModelProvider.Factory {
                                            @Suppress("UNCHECKED_CAST")
                                            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                                return HomeViewModel(db) as T
                                            }
                                        }
                                    }
                                    val homeViewModel: HomeViewModel = viewModel(factory = factory)
                                    HomeScreen(viewModel = homeViewModel)
                                }
                                BottomTab.RECORDS -> RecordsScreen(db = db)
                                BottomTab.PROFILE -> ProfileScreen(
                                    isLoggedIn = false,
                                    onNavigateToLogin = { authScreen = Screen.LOGIN }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openNotificationListenerSettings() {
        try {
            startActivity(Intent(NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openAppBatterySettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法打开应用详情", e)
        }
    }
}

// ===== 弹窗组件 =====

@Composable
private fun NotificationAccessGuideDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val appName = stringResource(R.string.app_name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要通知读取权限") },
        text = {
            Text(
                "要自动记录微信和支付宝的账单，请开启通知读取权限：\n\n" +
                "1. 点击下方「去设置」\n" +
                "2. 在列表中找到「$appName」\n" +
                "3. 开启开关\n\n" +
                "⚠️ 注意：不是「允许通知」弹窗，需要去「通知访问权限」里手动开启。"
            )
        },
        confirmButton = {
            Button(onClick = onOpenSettings) {
                Text("去设置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后")
            }
        }
    )
}

@Composable
private fun BatteryOptimizationGuideDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val mfr = Build.MANUFACTURER.lowercase()
    val isOppo = mfr in listOf("oppo", "oneplus", "realme")
    val isXiaomi = mfr == "xiaomi"
    val isHuawei = mfr in listOf("huawei", "honor")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要关闭电池优化") },
        text = {
            Text(
                buildString {
                    appendLine("系统电池优化当前未关闭, 后台运行可能被限制。")
                    appendLine()
                    appendLine("⚠️ 注意:「系统电池优化」与厂商自己的「自启动 / 后台冻结」是两套独立开关。")
                    appendLine("系统接口只能检测到前者, 因此即使显示「已关闭」, 也可能仍被 ROM 冻结。")
                    appendLine("请按下面的引导把所有相关开关都关掉。")
                    appendLine()
                    append(getBatteryGuideText(isOppo, isXiaomi, isHuawei))
                }
            )
        },
        confirmButton = {
            Button(onClick = onOpenSettings) {
                Text("去设置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后")
            }
        }
    )
}

private fun getBatteryGuideText(isOppo: Boolean, isXiaomi: Boolean, isHuawei: Boolean): String {
    if (isOppo) {
        return buildString {
            appendLine("ColorOS 会拦截后台通知监听，需要关闭电池优化：")
            appendLine()
            appendLine("① 点击「去设置」进入应用详情页")
            appendLine("→ 耗电管理 → 选择「无限制」")
            appendLine()
            appendLine("② 关闭后台冻结")
            appendLine("「设置 → 电池 → 更多电池设置 → 耗电保护」")
            appendLine("→ 关闭「后台冻结」和「深度睡眠」")
            appendLine()
            appendLine("③ 开启自启动")
            appendLine("「设置 → 应用管理 → 应用列表」→ 本 App → 自启动")
            appendLine()
            appendLine("④ 锁定后台任务")
            appendLine("最近任务 → 下拉 App 卡片 → 🔒 锁定")
        }.trimEnd()
    }
    if (isXiaomi) {
        return buildString {
            appendLine("小米会拦截后台通知监听，需要关闭电池优化：")
            appendLine()
            appendLine("① 点击「去设置」进入应用详情页")
            appendLine("→ 省电策略 → 选择「无限制」")
            appendLine()
            appendLine("② 开启自启动")
            appendLine("「设置 → 应用 → 应用管理 → 自启动 → 开启」")
        }.trimEnd()
    }
    if (isHuawei) {
        return buildString {
            appendLine("华为会拦截后台通知监听，需要关闭电池优化：")
            appendLine()
            appendLine("① 点击「去设置」进入应用详情页")
            appendLine("→ 耗电 → 选择「无限制」")
            appendLine()
            appendLine("② 关闭自动管理")
            appendLine("「设置 → 应用 → 应用启动管理 → 关闭自动管理」")
            appendLine("→ 开启所有开关")
        }.trimEnd()
    }
    return buildString {
        appendLine("Android 系统会限制后台 App 活动，需要关闭电池优化：")
        appendLine()
        appendLine("点击「去设置」进入应用详情页")
        appendLine("→ 电池/耗电 → 选择「无限制」")
    }.trimEnd()
}

private enum class Screen {
    LOGIN, SIGN_UP, FORGOT_PASSWORD
}