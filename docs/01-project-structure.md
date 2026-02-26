# 01 - 项目结构与构建系统

## 目录结构

```
android_openclaw/
├── build.gradle.kts              # Root 构建脚本（声明插件，不应用）
├── settings.gradle.kts           # 项目设置（仓库、模块声明）
├── gradle.properties             # Gradle JVM 参数与全局开关
├── gradle/
│   ├── libs.versions.toml        # 版本目录（所有依赖版本统一管理）
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties   # Gradle 8.11.1
├── gradlew / gradlew.bat        # Gradle Wrapper 脚本
├── .gitignore
├── README.md
├── docs/                         # 你正在阅读的文档目录
│
└── app/                          # 唯一的应用模块
    ├── build.gradle.kts          # App 模块构建配置
    ├── proguard-rules.pro        # R8 混淆规则
    └── src/main/
        ├── AndroidManifest.xml   # 组件声明、权限
        ├── res/                  # Android 资源文件
        │   ├── values/strings.xml
        │   ├── values/colors.xml
        │   ├── values/themes.xml
        │   ├── drawable/ic_notification.xml
        │   └── mipmap-anydpi-v26/ic_launcher.xml
        │
        └── kotlin/com/openclaw/android/
            ├── OpenClawApp.kt           # Application 入口
            ├── MainActivity.kt          # 唯一 Activity
            │
            ├── core/
            │   └── OpenClawConstants.kt # 所有常量 + 路径管理
            │
            ├── di/
            │   └── AppModule.kt         # Hilt 全局 DI 配置
            │
            ├── data/
            │   ├── ChatMessage.kt       # 消息数据模型
            │   └── PreferencesManager.kt # DataStore 偏好存储
            │
            ├── proot/
            │   ├── RootfsState.kt       # 安装状态（密封接口）
            │   ├── FileDownloader.kt    # HTTP 下载器
            │   ├── RootfsInstaller.kt   # Rootfs 安装协调器
            │   └── ProotExecutor.kt     # proot 命令构建与进程启动
            │
            ├── gateway/
            │   ├── GatewayState.kt      # 连接状态（密封接口）
            │   ├── GatewayProtocol.kt   # 协议 v3 所有数据类型
            │   ├── GatewayClient.kt     # WebSocket 客户端
            │   ├── ChatApi.kt           # 聊天 API 封装
            │   └── ApprovalApi.kt       # 工具审批 API 封装
            │
            ├── service/
            │   ├── OpenClawService.kt   # 前台服务
            │   ├── ProcessManager.kt    # Node.js 进程管理
            │   ├── HealthMonitor.kt     # 健康检查
            │   └── BootReceiver.kt      # 开机广播接收器
            │
            └── ui/
                ├── MainScreen.kt        # 主界面 + 导航
                ├── MainViewModel.kt     # 主界面 ViewModel
                ├── navigation/
                │   └── Screen.kt        # Tab 路由枚举
                ├── theme/
                │   ├── Color.kt         # 颜色定义
                │   ├── Type.kt          # 排版定义
                │   └── Theme.kt         # Material 3 主题
                ├── components/
                │   └── ConnectionStatusBar.kt  # 连接状态条
                ├── chat/
                │   ├── ChatScreen.kt    # 聊天界面
                │   ├── ChatViewModel.kt # 聊天状态管理
                │   └── MessageBubble.kt # 消息气泡组件
                ├── terminal/
                │   ├── TerminalScreen.kt     # 终端界面
                │   └── TerminalViewModel.kt  # 终端状态管理
                ├── settings/
                │   ├── SettingsScreen.kt     # 设置界面
                │   └── SettingsViewModel.kt  # 设置状态管理
                └── setup/
                    ├── SetupWizardScreen.kt  # 首次安装向导
                    └── SetupViewModel.kt     # 安装向导状态管理
```

## 构建配置详解

### `app/build.gradle.kts` 关键配置

```kotlin
android {
    namespace = "com.openclaw.android"
    compileSdk = 35              // 编译用最新 SDK
    
    defaultConfig {
        applicationId = "com.openclaw.android"
        minSdk = 24              // 支持 Android 7+（与 Termux 一致）
        targetSdk = 28           // 低于 29 以绕过 W^X 执行限制
        versionCode = 1
        versionName = "0.1.0"
    }
}
```

**为什么 minSdk = 24？**
与 Termux 官方保持一致，覆盖 99.5%+ 的活跃 Android 设备。

**为什么 targetSdk = 28？**
Android 10 (API 29) 起，如果 `targetSdkVersion >= 29`，系统将禁止在 `/data/data/<pkg>/` 下执行二进制文件（W^X 策略）。设为 28 可以在 Android 10+ 设备上执行 Node.js / bash 等预编译二进制。此策略仅适用于 sideload 分发。

### BuildConfig 编译时常量

| 字段 | 值 | 用途 |
|------|-----|------|
| `ROOTFS_URL` | GitHub Releases 下载地址 | Debian rootfs 压缩包 URL |
| `GATEWAY_HOST` | `127.0.0.1` | Gateway 绑定地址 |
| `GATEWAY_PORT` | `18789` | Gateway 端口 |

### 版本目录 (`gradle/libs.versions.toml`)

所有第三方依赖版本在此统一管理。引用方式：

```kotlin
// 在 build.gradle.kts 中
implementation(libs.compose.material3)     // 自动解析版本
implementation(libs.hilt.android)
ksp(libs.hilt.compiler)                    // KSP 注解处理
```

### 插件链

```
Android Application → Kotlin Android → Kotlin Compose → Kotlin Serialization → Hilt → KSP
```

每个插件的职责：
- **Android Application**: 标准 Android 应用构建
- **Kotlin Android**: Kotlin 编译支持
- **Kotlin Compose**: Compose 编译器插件（Kotlin 2.0+ 内置）
- **Kotlin Serialization**: `@Serializable` 编译时代码生成
- **Hilt**: Dagger Hilt 依赖注入框架
- **KSP**: Kotlin Symbol Processing（替代 kapt，更快）

## 构建命令

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建（需要签名配置）
./gradlew assembleRelease

# 清理
./gradlew clean

# 检查依赖更新
./gradlew dependencyUpdates
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

## AndroidManifest.xml 关键声明

### 权限

| 权限 | 必要性 | 用途 |
|------|--------|------|
| `INTERNET` | 必需 | 下载 Debian rootfs、LLM API 调用 |
| `ACCESS_NETWORK_STATE` | 必需 | 检测网络状态 |
| `FOREGROUND_SERVICE` | 必需 | 前台服务（保活 Gateway） |
| `WAKE_LOCK` | 必需 | 防止 CPU 休眠 |
| `RECEIVE_BOOT_COMPLETED` | 必需 | 开机自启动 |
| `POST_NOTIFICATIONS` | Android 13+ | 显示前台服务通知 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 可选 | 请求白名单免电池优化 |

### 组件

| 组件 | 类型 | 属性 |
|------|------|------|
| `MainActivity` | Activity | `singleTask`, launcher |
| `OpenClawService` | Service | not exported |
| `BootReceiver` | BroadcastReceiver | `BOOT_COMPLETED` filter |
