# 06 - 依赖注入（Hilt）

## 概述

项目使用 Dagger Hilt 进行依赖注入，所有单例在 `AppModule` 中声明，通过 KSP（Kotlin Symbol Processing）进行编译时代码生成。

## Hilt 入口点

### Application

```kotlin
@HiltAndroidApp
class OpenClawApp : Application()
```

这是 Hilt 的根入口，编译时生成 `Hilt_OpenClawApp`，负责初始化全局依赖图。

### Activity

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity()
```

标记后，所有 Hilt 管理的依赖可以在该 Activity 及其子 Composable 中使用。

### Service

```kotlin
@AndroidEntryPoint
class OpenClawService : Service() {
    @Inject lateinit var processManager: ProcessManager
    @Inject lateinit var gatewayClient: GatewayClient
    @Inject lateinit var healthMonitor: HealthMonitor
}
```

Service 使用字段注入（`@Inject lateinit var`）而非构造函数注入，因为 Android 框架负责实例化。

### BroadcastReceiver

```kotlin
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var preferencesManager: PreferencesManager
}
```

## AppModule（全局单例注册）

```
AppModule (@Module @InstallIn(SingletonComponent))
│
├── providePaths(@ApplicationContext) ──► Paths               [Singleton]
├── provideOkHttpClient()            ──► OkHttpClient         [Singleton]
├── providePreferencesManager()      ──► PreferencesManager   [Singleton]
├── provideEnvironmentSetup(Paths)   ──► EnvironmentSetup     [Singleton]
├── provideBootstrapDownloader(Http) ──► BootstrapDownloader  [Singleton]
├── provideBootstrapInstaller(...)   ──► BootstrapInstaller   [Singleton]
├── provideProcessManager(...)       ──► ProcessManager       [Singleton]
├── provideGatewayClient(Http)       ──► GatewayClient        [Singleton]
└── provideHealthMonitor(Gateway)    ──► HealthMonitor        [Singleton]
```

### 依赖关系图

```
@ApplicationContext ──┬──► Paths
                     │      │
                     │      ├──► EnvironmentSetup ──► ProcessManager
                     │      │                              ▲
                     │      ├──► BootstrapInstaller ────────┘
                     │      │       ▲
                     │      │       │
                     ├──────┘       │
                     │              │
                     └──► PreferencesManager ──► BootstrapInstaller
                     
OkHttpClient ──┬──► BootstrapDownloader ──► BootstrapInstaller
               │
               └──► GatewayClient ──► HealthMonitor
```

## ViewModel 注入

所有 ViewModel 使用 `@HiltViewModel` + 构造函数 `@Inject`：

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val gatewayClient: GatewayClient,  // 从 AppModule 注入
) : ViewModel()
```

在 Composable 中通过 `hiltViewModel()` 获取：

```kotlin
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    // ...
}
```

### ViewModel 依赖表

| ViewModel | 注入的依赖 |
|-----------|-----------|
| `MainViewModel` | `PreferencesManager` |
| `ChatViewModel` | `GatewayClient` |
| `TerminalViewModel` | `@ApplicationContext`, `BootstrapInstaller`, `Paths`, `EnvironmentSetup` |
| `SetupViewModel` | `@ApplicationContext`, `BootstrapInstaller`, `PreferencesManager` |
| `SettingsViewModel` | `PreferencesManager`, `ProcessManager` |

## 作用域说明

| 作用域 | 生命周期 | 使用场景 |
|--------|----------|----------|
| `@Singleton` | Application 生命周期 | 所有 AppModule 中的 `@Provides` |
| `@HiltViewModel` | ViewModel 生命周期（跟随 NavBackStackEntry） | 所有 ViewModel |
| `@AndroidEntryPoint` | 对应 Android 组件的生命周期 | Activity, Service, Receiver |

**关键点：** `GatewayClient`, `ProcessManager` 等是全局单例，意味着在 Service 中注入的实例和在 ViewModel 中注入的实例是 **同一个对象**。这确保了：

- `OpenClawService` 启动 Gateway 进程 → `ProcessManager.processState` 更新
- `SettingsViewModel` 观察 `ProcessManager.processState` → UI 实时反映状态
- `OpenClawService` 连接 WebSocket → `GatewayClient.connectionState` 更新
- `ChatViewModel` 观察 `GatewayClient` 的事件流 → 聊天界面收到消息

## 新增组件的步骤

### 新增一个全局单例

1. 创建类（无需 `@Inject` 注解）
2. 在 `AppModule` 中添加 `@Provides @Singleton` 方法
3. 在需要的地方通过构造函数或字段注入使用

### 新增一个 ViewModel

1. 创建类继承 `ViewModel`
2. 添加 `@HiltViewModel` 注解
3. 构造函数添加 `@Inject constructor(...)`
4. 在 Composable 中用 `hiltViewModel()` 获取

### 新增一个页面

1. 创建 ViewModel（如上）
2. 创建 `@Composable` 函数，参数默认值为 `hiltViewModel()`
3. 在 `MainScreen` 的 `NavHost` 中添加 `composable(route) { YourScreen() }`
4. 在 `Screen` 枚举中添加路由定义
