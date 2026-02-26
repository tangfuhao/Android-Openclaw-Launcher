# 09 - 开发指南

## 环境准备

### 必备工具

| 工具 | 版本要求 | 用途 |
|------|----------|------|
| Android Studio | Ladybug (2024.2+) | IDE |
| JDK | 17+ | 编译 |
| Android SDK | API 35 (compileSdk) | 编译 |
| Git | 任意 | 版本控制 |

### 可选工具

| 工具 | 用途 |
|------|------|
| Android 真机 (8GB+ RAM) | 端到端测试 |
| Termux (在测试设备上) | 手动验证 OpenClaw 能否运行 |
| scrcpy | 电脑投屏 + 调试 |

### 首次设置

```bash
# 克隆项目
git clone https://github.com/nicebug/android-openclaw.git
cd android-openclaw

# 构建（Gradle wrapper 会自动下载 Gradle 8.11.1）
./gradlew assembleDebug

# APK 输出位置
ls -la app/build/outputs/apk/debug/app-debug.apk
```

首次构建约需 1-2 分钟（下载依赖 + 编译），后续增量构建约 5-20 秒。

## 项目约定

### 包结构

新增功能时遵循现有的包分层：

```
com.openclaw.android.
├── core/       ← 常量、配置、基础工具（不依赖其他项目包）
├── data/       ← 数据模型、持久化（不依赖 UI/Service）
├── proot/      ← proot 执行器、Debian rootfs 管理（不依赖 UI）
├── gateway/    ← Gateway 协议（不依赖 UI/Service）
├── service/    ← Android 服务层（可依赖 proot/gateway）
├── di/         ← Hilt 模块配置
└── ui/         ← UI 层（可依赖所有其他层）
    ├── theme/
    ├── components/
    ├── navigation/
    ├── chat/
    ├── terminal/
    ├── settings/
    └── setup/
```

**依赖规则：** 上层可以依赖下层，但不能反向依赖。`core/` 和 `data/` 是最底层，不依赖其他项目代码。

### 命名约定

| 类型 | 命名规则 | 示例 |
|------|----------|------|
| Composable 函数 | PascalCase | `ChatScreen`, `MessageBubble` |
| ViewModel | `XxxViewModel` | `ChatViewModel` |
| 密封接口（状态） | `XxxState` | `GatewayState`, `RootfsState` |
| DI 模块 | `XxxModule` | `AppModule` |
| 常量对象 | `XxxConstants` | `OpenClawConstants` |
| 协议数据类 | 按协议命名 | `GatewayRequest`, `ChatEventPayload` |

### 状态管理约定

1. **ViewModel 暴露 StateFlow**，不暴露可变 MutableStateFlow
2. **UI 通过 `collectAsStateWithLifecycle()`** 收集 Flow
3. **一次性事件用 SharedFlow**（如 chat events），状态用 StateFlow
4. **所有异步操作在 ViewModel 的 `viewModelScope` 中启动**

## 常见开发任务

### 添加新的 Gateway 方法

1. 在 `GatewayProtocol.kt` 中添加请求/响应数据类：

```kotlin
@Serializable
data class MyNewParams(val foo: String, val bar: Int)
```

2. 在对应的 API 类（或新建 API 类）中添加方法：

```kotlin
class MyApi(private val gateway: GatewayClient) {
    suspend fun doSomething(foo: String): Result {
        val response = gateway.request("my.method", buildJsonObject {
            put("foo", foo)
        })
        if (!response.ok) throw MyException(response.error?.message ?: "Unknown error")
        return json.decodeFromJsonElement(Result.serializer(), response.payload!!)
    }
}
```

3. 在 ViewModel 中创建 API 实例并调用。

### 添加新的设置项

1. 在 `PreferencesManager` 中添加 key 和 Flow：

```kotlin
object Keys {
    val MY_SETTING = booleanPreferencesKey("my_setting")
}

val mySetting: Flow<Boolean> =
    context.dataStore.data.map { it[Keys.MY_SETTING] ?: false }

suspend fun setMySetting(value: Boolean) {
    context.dataStore.edit { it[Keys.MY_SETTING] = value }
}
```

2. 在 `SettingsViewModel` 中暴露为 StateFlow。
3. 在 `SettingsScreen` 中添加 UI 控件。

### 添加新的 UI 页面（Tab）

1. 在 `Screen` 枚举中添加路由：

```kotlin
MY_PAGE("my_page", "My Page", Icons.Default.Star),
```

2. 创建 `ui/mypage/` 包，添加 Screen + ViewModel。
3. 在 `MainScreen` 的 NavHost 中注册路由。
4. 在 `screens` 列表中添加新 Tab。

## 调试技巧

### 查看 Gateway 日志

Gateway 进程的 stdout/stderr 通过 `ProcessManager.logLines` 暴露。可以在代码中添加临时日志页面，或通过 adb 查看：

```bash
adb logcat -s "ProcessManager:*" "gateway-log-reader:*"
```

所有 Gateway 输出以 `[gateway]` 前缀记录在 Android logcat 中。

### 查看 WebSocket 通信

`GatewayClient` 的帧解析在 logcat 中有详细日志：

```bash
adb logcat -s "GatewayClient:*"
```

### 模拟 Rootfs 已安装

如果没有实际的 rootfs 包可以下载，可以手动创建占位文件来跳过安装：

```bash
adb shell
run-as com.openclaw.android.debug
mkdir -p files/rootfs/usr/bin files/rootfs/usr/lib/node_modules/openclaw/bin files/rootfs/root
touch files/rootfs/usr/bin/node files/rootfs/usr/bin/bash files/rootfs/usr/lib/node_modules/openclaw/bin/openclaw.js
chmod +x files/rootfs/usr/bin/node files/rootfs/usr/bin/bash
```

然后在 DataStore 中标记已安装（或修改 `isInstalled()` 暂时返回 true）。

### 在不启动 Gateway 的情况下开发 UI

可以在 `ChatViewModel` 中添加 mock 数据：

```kotlin
init {
    // 临时：添加测试消息
    _messages.value = listOf(
        ChatMessage("1", ChatMessage.Role.USER, "Hello"),
        ChatMessage("2", ChatMessage.Role.ASSISTANT, "Hi there! How can I help?"),
    )
}
```

## 构建变体

| 变体 | applicationIdSuffix | 特点 |
|------|---------------------|------|
| `debug` | `.debug` | 可调试、debug 签名 |
| `release` | (无) | R8 混淆、minify、shrink resources |

两个变体可以同时安装在同一设备上（不同的 applicationId）。

## 关键文件速查

| 需求 | 文件 |
|------|------|
| 修改 Gateway 地址/端口 | `core/OpenClawConstants.kt` |
| 修改 Rootfs 下载 URL | `app/build.gradle.kts` → `ROOTFS_URL` |
| 添加新的依赖 | `gradle/libs.versions.toml` |
| 修改 Android 权限 | `AndroidManifest.xml` |
| 修改主题色 | `ui/theme/Color.kt` |
| 修改 DI 注入 | `di/AppModule.kt` |
| 修改进程启动命令 | `service/ProcessManager.kt` → `startGateway()` |
| 修改环境变量 | `proot/ProotExecutor.kt` → `buildEnvironment()` |
| 修改 WebSocket 握手 | `gateway/GatewayClient.kt` → `performHandshake()` |
| 修改消息气泡样式 | `ui/chat/MessageBubble.kt` |
