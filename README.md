# 记账助手

一个 **半自动记账** 的 Android 应用：通过监听系统通知，自动捕获微信 / 支付宝的支付与收款记录，解析出金额和类型后存入本地数据库。

**不需要手动输入，支付完成后账单自动出现在 App 里。**

---

## 为什么需要它

微信和支付宝的账单分散在两个 App 里，想统一查看只能靠手动导入或手动记。本工具的思路是：

> 每次支付，微信 / 支付宝都会弹出通知 —— 既然通知里有金额，那就在通知到达时把它截下来。

Android 提供了 `NotificationListenerService`，允许应用在**用户授权后**读取通知内容。本工具就建立在这个机制之上。

---

## 工作原理

### 整体流程

```
① 用户在微信/支付宝完成支付
              │
              ▼
② 系统弹出支付通知
   "微信支付  已支付¥0.82"
              │
              ▼
③ 系统回调 NotificationService.onNotificationPosted(通知对象)
   （运行在系统 binder 线程上）
              │
              ▼
④ 包名过滤
   com.tencent.mm          → 微信
   com.eg.android.AlipayGphone → 支付宝
   （大小写不敏感匹配，其余通知直接丢弃）
              │
              ▼
⑤ 提取通知文本
   android.title / android.text / android.bigText / android.subText
              │
              ▼
⑥ TransactionParser.parse() 解析
   ├─ 金额：正则提取
   ├─ 类型：关键词判定（收入 / 支出）
   └─ 描述：商户名或交易内容
              │
              ▼
⑦ 异步写入 SQLite（IO 线程）
              │
              ▼
⑧ 发射变更信号 → 界面自动刷新
```

### 步骤详解

#### ① ~ ② 通知的产生

微信 / 支付宝在支付成功、收款到账时会发送系统通知。这是**被动等待**的 —— App 不主动查询，只在通知到达时被动接收。

#### ③ 通知监听

`NotificationService` 继承自系统的 `NotificationListenerService`：

```kotlin
class NotificationService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        try {
            processNotification(sbn)
        } catch (t: Throwable) {
            Log.e(TAG, "处理通知时异常", t)
        }
    }
}
```

> ⚠️ **关键点**：`onNotificationPosted()` 运行在**系统的 binder 线程**上。
> 绝不能用 `runBlocking` 阻塞它 —— 这会卡住整个系统的通知分发，触发 ANR，
> 反而让进程更容易被系统杀死。所有耗时操作必须异步执行。

#### ④ 包名过滤

只处理微信和支付宝的通知，其余一律丢弃：

```kotlin
if (!packageName.contains("tencent.mm", ignoreCase = true) &&
    !packageName.contains("alipay", ignoreCase = true)) {
    return
}
```

> 💡 这里的 `ignoreCase = true` 是踩坑换来的。支付宝包名是
> `com.eg.android.AlipayGphone`，其中 `A` 是**大写** —— 而 Kotlin 的
> `String.contains()` 默认**大小写敏感**，漏掉这个参数会导致支付宝的通知
> 全部被静默丢弃，且没有任何报错。详见 [REVIEW.md](./REVIEW.md)。

#### ⑤ ~ ⑥ 文本解析

通知内容散落在多个 extras 字段里，需要逐个尝试并拼接：

```kotlin
val fullText = listOf(title, text, bigText, subText)
    .filter { it.isNotBlank() }
    .joinToString(" ")
```

解析器 `TransactionParser` 按包名分流到两套独立逻辑：

**微信** —— 格式固定，单个正则即可：

```
通知格式：title="微信支付"  text="已支付¥0.82"
正则：    [¥￥]\s*(\d+\.\d{2})       → 0.82
```

**支付宝** —— 格式多变，按优先级依次尝试 4 种正则：

| 优先级 | 正则 | 匹配样例 |
|--------|------|----------|
| 1 | `[¥￥]\s*(\d+\.\d{2})` | `¥0.01` |
| 2 | `(\d+\.\d{2})\s*元` | `0.01元` |
| 3 | `(\d+\.\d{2})` | `0.01` |
| 4 | `[¥￥]?\s*([\d０-９]+[\.．][\d０-９]{2})` | 全角 `０．０１` |

第 4 条是兜底，配合 `normalizeDigits()` 把全角数字转半角，防止支付宝在某些机型上
输出全角字符导致解析失败。

**类型判定**用的是关键词表，命中即返回：

```kotlin
// 收入关键词
"到账" "收款" "收到" "转入" "入账" "退款" "红包" ...
// 支出关键词
"支出" "付款" "消费" "支付" "转账" "还款" "扣款" ...
```

两者都没命中时，默认判定为**支出**（`TYPE_EXPENSE`）。

#### ⑦ 异步入库

解析成功后，写入操作丢到协程作用域异步执行，不阻塞 binder 线程：

```kotlin
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

scope.launch {
    try {
        DatabaseHelper.getInstance(this@NotificationService).insert(transaction)
    } catch (e: Exception) {
        Log.e(TAG, "存入数据库失败", e)
    }
}
```

#### ⑧ 响应式刷新

数据库层用 `MutableSharedFlow` 作为变更信号，实现"写入即刷新"：

```
insert() / deleteById() 成功
        │
        ▼
changeSignal.tryEmit(Unit)              ← MutableSharedFlow(replay = 1)
        │
        ▼
查询 Flow 收到信号 → 重新查库 → emit 新数据
        │
        ▼
Compose UI collectAsState() → 自动重组
```

```kotlin
fun getAllTransactions(): Flow<List<Transaction>> = changeSignal
    .onStart { emit(Unit) }              // 首次订阅立即查一次
    .flatMapLatest {
        flow { /* 查询 SQLite */ }.flowOn(Dispatchers.IO)
    }
```

**效果**：新增一笔账单后，首页和记录页自动更新 —— 不需要下拉刷新，不需要切页面，不需要定时轮询。

---

## 为何必须手动授权

本工具需要的权限**无法通过普通弹窗申请**，必须用户手动去系统设置开启。这是 Android 的安全设计：

| 权限 | 申请方式 | 用户操作路径 |
|------|----------|--------------|
| **通知读取权限** | ❌ 无法弹窗申请 | 设置 → 通知 → 通知访问权限 → 找到「记账助手」→ 开启 |
| `POST_NOTIFICATIONS` | ✅ 运行时弹窗 | 自动弹出 |
| 电池优化豁免 | ⚠️ 跳转系统设置 | 设置 → 电池 → 选择「无限制」 |

> ⚠️ **常见误区**：App 里弹出的「允许通知」和「通知读取权限」是**两回事**。
> 前者是让本 App 能发通知，后者才是让本 App 能读别人的通知。只开前者，记账不会生效。

---

## 后台保活机制

### 为什么需要

`NotificationListenerService` 由系统绑定，但 **App 进程**在国产 ROM（ColorOS / MIUI / EMUI）上会被冻结或杀死 —— **进程一死，通知回调就断了**，记账静默失效。

### 三重保障

```
┌─────────────────────────────────────────────────┐
│ 1. 前台服务 (KeepAliveService)                   │
│    常驻通知 + START_STICKY                       │
│    → 把进程钉在前台, 系统不再轻易冻结             │
├─────────────────────────────────────────────────┤
│ 2. 开机自启 (BootReceiver)                       │
│    监听 BOOT_COMPLETED                           │
│    → 重启后自动拉起保活服务                       │
├─────────────────────────────────────────────────┤
│ 3. 断线重连 (requestRebind)                      │
│    onListenerDisconnected() → 延迟 3s 重新绑定    │
│    → 监听被系统解除后自动恢复                     │
└─────────────────────────────────────────────────┘
```

**前台服务类型的选择**：Android 14+ 强制要求声明 `foregroundServiceType`。这里用 `specialUse` 而非 `dataSync`，原因是 **Android 15+ 禁止从 `BOOT_COMPLETED` 启动 `dataSync` 等类型的前台服务，但允许 `specialUse`** —— 选错类型会导致开机自启直接失败。

```xml
<service
    android:name=".notification.KeepAliveService"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="保持通知监听服务存活, 以自动记录微信/支付宝账单" />
</service>
```

> ⚠️ **代价**：Android 8+ 前台服务必须显示常驻通知，无法隐藏。
> 同时 Google Play 对 `specialUse` 类型有审核要求，上架前需在 Play Console 说明用途。

---

## 技术栈

| 项目 | 版本 / 方案 |
|------|------------|
| 语言 | Kotlin 2.2.10 |
| 构建 | Gradle 9.6.0 / AGP 9.4.0 |
| UI | Jetpack Compose + Material 3（Compose BOM 2026.02.01） |
| 架构 | MVVM（ViewModel + StateFlow） |
| 数据库 | SQLite（`SQLiteOpenHelper` + 索引） |
| 数据刷新 | Kotlin Coroutines `Flow` + `MutableSharedFlow` |
| 安全存储 | DataStore Preferences + AES-256/GCM（Android Keystore） |
| 最低版本 | API 24（Android 7.0） |
| 目标版本 | API 37 |

---

## 项目结构

```
com.example.myapplication/
├── MainActivity.kt                 # 入口、页面路由、权限引导
├── data/
│   ├── DatabaseHelper.kt           # SQLite 操作 + 响应式刷新信号
│   └── TokenManager.kt             # 加密 Token 存储
├── model/
│   └── Transaction.kt              # 交易模型 + 月度汇总
├── notification/
│   ├── NotificationService.kt      # 通知监听（核心入口）
│   ├── TransactionParser.kt        # 微信/支付宝通知解析
│   ├── KeepAliveService.kt         # 前台保活服务
│   └── BootReceiver.kt             # 开机自启
├── login/ signup/ forgotpassword/  # 认证页面
└── ui/
    ├── home/                       # 首页：月度概况 + 最近记录
    ├── records/                    # 全部记录列表
    ├── profile/                    # 我的：权限状态 + 设置
    ├── navigation/                 # 底部导航栏
    └── theme/                      # Material 3 主题
```

---

## 构建与运行

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 安装到已连接设备
./gradlew installDebug

# 运行单元测试
./gradlew test
```

**首次运行配置**：

1. 安装后打开 App
2. 在引导弹窗中点击「去设置」，开启**通知读取权限**
3. 返回 App，再按引导**关闭电池优化**（国产 ROM 还需手动关闭自启动限制、后台冻结）
4. 确认通知栏出现「记账助手正在后台运行」的常驻通知
5. 用微信 / 支付宝完成一笔小额支付，观察记录是否自动出现

---

## 已知限制

| 限制 | 说明 |
|------|------|
| 依赖通知格式 | 微信/支付宝若修改通知文案，正则可能失效 |
| 无法解析无通知的交易 | 部分静默扣款、自动续费不弹通知，无法捕获 |
| 认证为模拟实现 | 登录 / 注册 / 找回密码目前是本地 Mock，未接入真实后端 |
| 保活非 100% | 前台服务能大幅提升存活率，但国产 ROM 仍可能强杀 |
| 无通知去重 | `notification_id` 字段已预留但尚未启用去重逻辑 |

完整的问题清单与改进路线图见 [REVIEW.md](./REVIEW.md)。

---

## 相关文档

| 文档 | 内容 |
|------|------|
| [PROJECT.md](./PROJECT.md) | 项目详细说明（数据结构、页面设计、扩展指南） |
| [REVIEW.md](./REVIEW.md) | 代码审查报告（问题清单、优化建议、改进路线图） |