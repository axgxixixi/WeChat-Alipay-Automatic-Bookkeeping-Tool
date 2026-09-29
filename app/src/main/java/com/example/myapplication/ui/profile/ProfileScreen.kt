package com.example.myapplication.ui.profile

import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.example.myapplication.notification.AutoStartDestination
import com.example.myapplication.notification.AutoStartLauncher

@Composable
fun ProfileScreen(
    importViewModel: ImportViewModel,
    isLoggedIn: Boolean = false,
    onNavigateToLogin: () -> Unit = {}
) {
    val context = LocalContext.current

    val importState by importViewModel.uiState.collectAsState()

    // 文件选择器注册在 ProfileScreen(而不是 sheet 内部): sheet 在导入过程中会被移除,
    // 若注册在里面, 选择器返回时回调已经不存在了。
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        importViewModel.onFilePicked(uri)
    }

    // 点选来源 → pendingType 被置上 → 这里拉起系统文件选择器。
    // 回调返回后 pendingType 会被清空, 因此改选另一家能再次触发。
    LaunchedEffect(importState.pendingType) {
        importState.pendingType?.let { type -> filePicker.launch(type.mimeTypes) }
    }

    // 权限状态（每次回到前台重新检查）
    var isListenerEnabled by remember { mutableStateOf(false) }
    var isIgnoringBattery by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            isListenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)

            isIgnoringBattery = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) true
            else {
                val pm = context.getSystemService(PowerManager::class.java)
                pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "我的",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 登录/用户卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                if (isLoggedIn) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Text(
                            text = "用户",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "test@example.com",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "登录以同步数据",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(
                            onClick = onNavigateToLogin,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "登录 / 注册",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 通知监听设置
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = MaterialTheme.shapes.small,
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                // 通知监听状态
                NotificationPermissionItem(
                    title = "通知读取权限",
                    status = if (isListenerEnabled) "已开启" else "未开启",
                    statusColor = if (isListenerEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                NotificationPermissionItem(
                    title = "系统电池优化",
                    status = if (isIgnoringBattery) "已关闭" else "未关闭",
                    statusColor = if (isIgnoringBattery) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    // 原来这里内联了一份「应用详情页」intent, 现统一走 AutoStartLauncher
                    onClick = { AutoStartLauncher.openAppDetails(context) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                // 自启动: Android 读不到该状态, 所以这里不显示"已开启/未开启",
                // 只给出「去开启」的动作暗示, 并在下方说明里讲清无法检测。
                NotificationPermissionItem(
                    title = "自启动管理",
                    status = "去开启",
                    statusColor = MaterialTheme.colorScheme.primary,
                    onClick = {
                        val destination = AutoStartLauncher.openAutoStart(context)
                        autoStartFallbackMessage(destination)?.let {
                            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(
                    title = "导入账单",
                    subtitle = "从微信 / 支付宝导出的账单文件导入",
                    onClick = { importViewModel.showSheet() }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(title = "数据导出")
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(title = "关于")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 说明: 厂商自己的开关系统接口读不到, 这里必须讲清楚 ——
            // 否则用户会以为「自启动管理」点一下就已经帮他把开关打开了。
            Text(
                text = "说明：「系统电池优化」只代表 Android 标准设置。" +
                    "部分手机（小米 / 华为 / OPPO / vivo 等）另有独立的「自启动」「后台冻结」开关，" +
                    "Android 没有提供查询或申请该权限的接口，因此本应用无法检测它是否已开启 —— " +
                    "上方「自启动管理」只负责跳到系统页面，请跳过去后自行确认已允许本应用自启动，" +
                    "否则后台监听可能仍会失效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // ===== 导入账单：选择面板 + 结果 / 错误弹窗 =====

    if (importState.sheetVisible) {
        ImportBillSheet(
            isWorking = importState.isWorking,
            onSelect = { type -> importViewModel.preparePick(type) },
            onDismiss = { importViewModel.dismissSheet() }
        )
    }

    importState.result?.let { result ->
        AlertDialog(
            onDismissRequest = { importViewModel.dismissResult() },
            title = { Text("导入完成") },
            text = { Text(result.message()) },
            confirmButton = {
                Button(onClick = { importViewModel.dismissResult() }) {
                    Text("好的")
                }
            }
        )
    }

    importState.error?.let { error ->
        AlertDialog(
            onDismissRequest = { importViewModel.dismissError() },
            title = { Text("导入失败") },
            text = { Text(error.userMessage) },
            confirmButton = {
                Button(onClick = { importViewModel.dismissError() }) {
                    Text("好的")
                }
            }
        )
    }
}

@Composable
private fun NotificationPermissionItem(
    title: String,
    status: String,
    statusColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = statusColor,
            modifier = Modifier.padding(end = 8.dp)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 没跳到厂商自启动页时给用户一句如实的交代。
 *
 * 返回 null 表示跳转成功、无需提示 —— 不能让用户以为自己到了正确的地方。
 */
private fun autoStartFallbackMessage(destination: AutoStartDestination): String? =
    when (destination) {
        AutoStartDestination.AUTO_START_PAGE -> null

        AutoStartDestination.APP_DETAILS ->
            "没找到本机系统的自启动页面，已跳到应用详情页，" +
                "请在这里手动查找「自启动」或「后台运行」开关"

        AutoStartDestination.SYSTEM_SETTINGS ->
            "没找到自启动页面，已跳到系统设置，" +
                "请手动查找「自启动」或「后台运行」开关"

        AutoStartDestination.FAILED ->
            "无法打开系统设置，请手动到「设置 → 应用管理 → 本应用」里允许自启动"
    }

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}