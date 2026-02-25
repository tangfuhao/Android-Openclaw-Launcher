# 03 - 服务与进程生命周期

## 概述

OpenClaw Gateway 是一个长时运行的 Node.js 进程。为了在 Android 上保持它存活，我们使用：

- **Foreground Service** — 通知栏常驻，避免被系统杀死
- **Partial WakeLock** — 防止 CPU 进入深度睡眠
- **BootReceiver** — 设备重启后自动恢复服务
- **HealthMonitor** — 定期检查并自动重启崩溃的进程

## 组件关系

```
┌─────────────────────────────────────────────────────────┐
│ OpenClawService (Foreground Service)                     │
│                                                         │
│  onCreate()                                             │
│    └── [通知栏创建]                                       │
│                                                         │
│  onStartCommand(ACTION_START)                           │
│    ├── startForeground(notification)                    │
│    ├── acquireWakeLock()                                │
│    └── launchGateway()                                  │
│         ├── processManager.startGateway()               │
│         ├── gatewayClient.connect()                     │
│         ├── healthMonitor.start(scope, onUnhealthy)     │
│         └── observeProcessState()                       │
│                                                         │
│  onStartCommand(ACTION_STOP)                            │
│    └── shutdown()                                       │
│         ├── healthMonitor.stop()                        │
│         ├── gatewayClient.disconnect()                  │
│         ├── processManager.stopGateway()                │
│         ├── releaseWakeLock()                           │
│         └── stopSelf()                                  │
│                                                         │
│  [ProcessState 观察]                                     │
│    ├── Running → 更新通知 "OpenClaw is running"          │
│    ├── Crashed → gatewayClient.disconnect()             │
│    │             processManager.restartWithBackoff()     │
│    └── Error   → 更新通知显示错误信息                     │
└─────────────────────────────────────────────────────────┘
```

## ProcessManager 详解

### 职责

`ProcessManager` 是进程管理的核心，负责：
1. 启动 Node.js 子进程（`ProcessBuilder`）
2. 捕获 stdout/stderr 日志
3. 检测进程退出并报告状态
4. 指数退避重启
5. 为终端 Tab 创建 bash 会话

### 启动流程

```
startGateway()
  │
  ├── 检查是否已在运行 → 是 → 返回 true
  │
  ├── verifyInstallation() → MissingBinaries → 返回 false + Error 状态
  │
  ├── state = Starting
  │
  ├── 构建命令:
  │   [/data/.../usr/bin/node,
  │    /data/.../usr/lib/node_modules/openclaw/bin/openclaw.js,
  │    gateway,
  │    --port, 18789,
  │    --bind, 127.0.0.1]
  │
  ├── ProcessBuilder
  │   ├── .directory(home)              ← 工作目录 = $HOME
  │   ├── .redirectErrorStream(true)    ← stderr 合并到 stdout
  │   └── .environment() = buildEnvironment()  ← 完整 Linux 环境变量
  │
  ├── process.start()
  │
  ├── 启动日志读取线程 (daemon thread)
  │   └── 逐行读取 stdout → appendLog() + Log.d()
  │   └── 进程退出时 → state = Crashed(exitCode)
  │
  ├── delay(1000ms) 等待启动
  │
  ├── 检查进程是否存活
  │   ├── 已退出 → state = Error("exited immediately with code X")
  │   └── 存活 → restartCount = 0, state = Running
  │
  └── 返回 true/false
```

### 进程状态机

```
        startGateway()
            │
            ▼
Stopped ──► Starting ──► Running
   ▲                       │
   │                       │ (进程异常退出)
   │                       ▼
   │                    Crashed(exitCode)
   │                       │
   │        restartWithBackoff()
   │                       │
   │                       ▼
   │                 Restarting(attempt)
   │                       │
   │            ┌──────────┤
   │            │          │
   │            ▼          ▼
   │        Running    Error("max retries")
   │                       │
   └───────────────────────┘
        stopGateway()
```

### 指数退避重启

```
尝试次数    延迟时间
  1         2s    (2000 * 2^0)
  2         4s    (2000 * 2^1)
  3         8s    (2000 * 2^2)
  4        16s    (2000 * 2^3)
  5        32s    (2000 * 2^4，最大)
  6+       放弃，state = Error
```

重启成功后（进程持续运行一段时间），`resetRestartCount()` 将计数器归零。

### 日志缓冲

`logLines: StateFlow<List<String>>` 维护最近 500 行的滚动日志缓冲区，可用于调试界面或日志导出。

## HealthMonitor 详解

```
每 15 秒执行一次:
  │
  ├── 读取 GatewayClient.connectionState
  │
  ├── Connected → markHealthy() (记录时间戳)
  │
  ├── Disconnected / Error
  │   ├── 距上次健康 < 60s → 等待（可能是临时断连）
  │   └── 距上次健康 ≥ 60s → 触发 onUnhealthy 回调
  │       └── OpenClawService 中:
  │           ├── gatewayClient.disconnect()
  │           ├── processManager.restartWithBackoff()
  │           └── 如果恢复: gatewayClient.connect()
  │
  └── Connecting / Reconnecting → 不做动作，给它时间
```

## BootReceiver

设备开机时自动恢复服务的逻辑：

```
BOOT_COMPLETED 广播
  │
  ├── Bootstrap 未安装? → 跳过
  ├── 后台模式未启用? → 跳过
  │
  └── 启动 OpenClawService
      ├── API 26+: startForegroundService()
      └── API 25-: startService()
```

用户可以在 Settings 中关闭后台模式来禁用开机自启。

## 从 UI 启动/停止服务

```kotlin
// 启动
context.startService(OpenClawService.startIntent(context))

// 停止
context.startService(OpenClawService.stopIntent(context))
```

`OpenClawService` 通过 `onStartCommand` 的 Intent action 区分启动和停止：
- `ACTION_START` = `"com.openclaw.android.action.START"` → 启动流程
- `ACTION_STOP` = `"com.openclaw.android.action.STOP"` → 关闭流程

## Android 系统限制与对策

| 限制 | Android 版本 | 影响 | 对策 |
|------|-------------|------|------|
| W^X (Write XOR Execute) | 10+ (API 29) | 禁止执行 /data/data/ 下二进制 | `targetSdk = 28` |
| Phantom Process Killer | 12+ (API 31) | 全局子进程限 32 个 | Gateway 仅用单个 Node.js 进程 |
| Foreground Service Timeout | 15+ (API 35) | `dataSync` 类型限 6 小时 | 不使用 `dataSync` 类型 |
| Background Execution Limits | 8+ (API 26) | 后台服务限制 | Foreground Service + 通知 |
| Doze Mode | 6+ (API 23) | CPU 休眠 | Partial WakeLock |
| Battery Optimization | 6+ (API 23) | 系统可能杀死 app | 请求电池优化白名单 |

## 终端会话（Shell）

`ProcessManager.createShellSession()` 为终端 Tab 创建独立的 bash 会话：

```kotlin
val process = ProcessBuilder(paths.shellBinary.absolutePath, "--login")
    .directory(paths.home)           // 工作目录 = $HOME
    .redirectErrorStream(true)       // stderr → stdout
    .also { pb ->
        pb.environment().clear()
        pb.environment().putAll(environmentSetup.buildEnvironment())
    }
    .start()
```

这个 `Process` 对象被 `TerminalViewModel` 包装为 Termux 的 `TerminalSession`，后者管理 PTY（伪终端）和 VT100 终端仿真。
