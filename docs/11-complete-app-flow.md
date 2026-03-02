# 11 - 完整应用启动与运行流程

> **目标读者：** 首次接触本项目的开发者。阅读本文后，应能完整理解 Android OpenClaw 从安装到用户聊天的每一步发生了什么。

---

## 一、全局概念

Android OpenClaw 本质上是一个"把整台 Linux 服务器装进手机"的工程：

```
手机上运行的东西                    对应的常规服务器概念
──────────────────                  ────────────────────
proot + Debian rootfs          ←→   Linux 操作系统
Node.js (openclaw.mjs)         ←→   后端应用服务
ws://127.0.0.1:18789           ←→   本地 API 端点
Compose UI (Chat/Terminal)     ←→   前端客户端
Foreground Service + WakeLock  ←→   systemd 服务保活
```

所有这些都在一个 APK 内，不需要 root 权限，不需要安装 Termux。

---

## 二、首次安装与 Setup 向导

### 2.1 APK 安装后的状态

APK 解压后，Android 系统只提取以下文件到设备：

```
nativeLibraryDir/          ← 从 APK 的 jniLibs/ 解压
├── libproot.so            ← proot 可执行文件（伪装成 .so）
└── libproot_loader.so     ← proot 的动态链接辅助库

filesDir/ (初始为空)       ← app 私有数据目录，Debian rootfs 还不存在
```

**DataStore 中所有偏好项均为默认值（false / 空字符串）。**

### 2.2 用户点击图标 → Setup Wizard 显示

```
用户点击启动图标
        │
        ▼
MainActivity.onCreate()
  ├── enableEdgeToEdge()
  └── setContent { OpenClawTheme { MainScreen() } }
        │
        ▼
MainScreen 读取 PreferencesManager.isSetupCompleted
  └── false → 渲染 SetupWizardScreen
```

### 2.3 Setup 向导 5 步流程

```
┌─────────────────────────────────────────────────────────────────┐
│  Step 1: WELCOME                                                 │
│    UI: Logo + "Welcome to OpenClaw" + [Get Started]             │
│    逻辑: 无                                                      │
└──────────────────────────────┬──────────────────────────────────┘
                               │ 点击 Get Started
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  Step 2: DEVICE_CHECK                                            │
│    SetupViewModel.checkDevice() 检查三项：                       │
│    ├── RAM: ActivityManager.MemoryInfo.totalMem ≥ 4096MB？       │
│    ├── 存储: StatFs(Environment.getDataDirectory).available      │
│    │         ≥ 3072MB？                                          │
│    └── 网络: ConnectivityManager.NET_CAPABILITY_INTERNET？       │
│                                                                  │
│    全部通过 → [Continue]                                         │
│    有不通过 → [Continue Anyway]（允许强行继续）                   │
└──────────────────────────────┬──────────────────────────────────┘
                               │ 点击继续
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  Step 3: DOWNLOAD（核心步骤）                                     │
│                                                                  │
│  用户点击 [Download & Install]                                   │
│    └── SetupViewModel.startInstallation()                        │
│          └── rootfsInstaller.install(BuildConfig.ROOTFS_URL)     │
│                │                                                 │
│                ▼（RootfsInstaller 状态机驱动 UI）                 │
│                                                                  │
│  Checking ──► Downloading(0% → 100%)                            │
│    │            OkHttp 下载 rootfs-aarch64.tar.xz 到 cacheDir   │
│    │            UI 显示进度条 + "XXmb / 200MB"                   │
│    │                                                             │
│    ▼                                                             │
│  Extracting                                                       │
│    │   Apache Commons Compress 解压 .tar.xz                      │
│    │   ├── 目录 → mkdirs()                                       │
│    │   ├── 符号链接 → Os.symlink()                               │
│    │   ├── 硬链接 → 转相对符号链接                                │
│    │   └── 普通文件 → 写入 + chmod                               │
│    │   UI 显示旋转圈 + "Extracting..."                            │
│    │                                                             │
│    ▼                                                             │
│  Configuring                                                      │
│    │   ├── 写 /etc/resolv.conf (DNS: 8.8.8.8/8.8.4.4/1.1.1.1) │
│    │   └── 创建 proot-tmp/ 目录                                  │
│    │                                                             │
│    ▼                                                             │
│  Verifying                                                        │
│    │   检查 3 个关键二进制存在：                                   │
│    │   ├── rootfs/usr/bin/node        ← Node.js                  │
│    │   ├── rootfs/usr/bin/bash        ← Shell                    │
│    │   └── rootfs/usr/lib/node_modules/openclaw/openclaw.mjs     │
│    │                                                             │
│    ▼                                                             │
│  Installed                                                        │
│    ├── PreferencesManager.setRootfsInstalled(true)               │
│    ├── 删除缓存压缩包                                            │
│    └── UI 显示 "Installation complete!" + [Continue]             │
└──────────────────────────────┬──────────────────────────────────┘
                               │ 点击继续
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  Step 4: API_KEY                                                  │
│    FlowRow 展示 7 个 Provider 芯片：                             │
│    Anthropic / OpenAI / Google / OpenRouter /                    │
│    MiniMax (中国) / 智谱 GLM / Kimi                              │
│                                                                  │
│    用户选择 Provider → 输入 API Key → 选择模型                    │
│    点击 [Save & Continue]：                                      │
│      ├── PreferencesManager.setApiKey(provider, key)             │
│      ├── PreferencesManager.setSelectedModel(model)              │
│      └── OpenClawConfigWriter.writeConfig()                      │
│              ├── 写 rootfs/root/.openclaw/openclaw.json           │
│              │   {"env": {"ANTHROPIC_API_KEY": "..."}, ...}      │
│              └── 写 rootfs/root/.openclaw/agents/main/agent/     │
│                      auth-profiles.json                          │
│                                                                  │
│    或点击 [Skip for Now]（稍后在 Settings 配置）                  │
└──────────────────────────────┬──────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  Step 5: COMPLETE                                                 │
│    点击 [Start Chatting]：                                       │
│      └── PreferencesManager.setSetupCompleted(true)              │
│    MainScreen 检测到 isSetupCompleted=true → 跳转主界面           │
└─────────────────────────────────────────────────────────────────┘
```

---

## 三、正常启动流程（Setup 已完成）

### 3.1 时序图

```
用户点击图标
     │
     ▼
MainActivity.onCreate()
     │
     ▼
MainScreen 读取 isSetupCompleted = true
     │
     ▼
MainContent 渲染（3 Tab 底部导航：Chat / Terminal / Settings）
     │
     ▼ LaunchedEffect(Unit)
startForegroundService(OpenClawService.startIntent())
     │
     ├──────────────────────────────────┐
     │                                  │
     ▼                                  ▼
OpenClawService.onStartCommand         UI 线程继续渲染 Chat Tab
     │
     ├── startForeground(通知栏常驻)
     ├── acquireWakeLock(PARTIAL_WAKE_LOCK)
     │
     ├── processManager.startGateway()      ← 在 IO 协程启动
     │     │
     │     ├── configWriter.writeConfig()   ← 写入 openclaw.json（第1次）
     │     │
     │     ├── 构建 proot 命令行：
     │     │   [libproot.so,
     │     │    --rootfs=<filesDir>/rootfs,
     │     │    --bind=/dev:/dev, --bind=/proc:/proc,
     │     │    --bind=/sys:/sys, --bind=/storage:/storage,
     │     │    --cwd=/root, --link2symlink, -0,
     │     │    /usr/bin/node,
     │     │    /usr/lib/node_modules/openclaw/openclaw.mjs,
     │     │    gateway, --port, 18789,
     │     │    --bind, loopback, --allow-unconfigured]
     │     │
     │     ├── ProcessBuilder.start()
     │     │     └── 启动一个 "gateway-log-reader" daemon thread
     │     │         持续读取进程 stdout → logLines StateFlow
     │     │
     │     ├── delay(1000ms) 等待进程稳定
     │     │
     │     ├── 进程存活？
     │     │   ├── 否 → ProcessState.Error("exited with code X")
     │     │   └── 是 → delay(2000ms)
     │     │                │
     │     │                └── configWriter.writeConfig()  ← 第2次写入
     │     │                    （覆盖 Gateway 启动时重置的配置）
     │     │
     │     └── ProcessState.Running
     │
     ├── gatewayClient.connect()
     │     │
     │     │  WebSocket 连接到 ws://127.0.0.1:18789/
     │     │
     │     │  ←── event: connect.challenge {nonce, ts}
     │     │
     │     │  →── req: connect {
     │     │            minProtocol:3, maxProtocol:3,
     │     │            client:{id:"openclaw-android", mode:"cli"},
     │     │            role:"operator",
     │     │            scopes:[read, write, approvals, admin],
     │     │            auth:{token: <从 openclaw.json 读取>}
     │     │          }
     │     │
     │     │  ←── res: ok {protocol:3, policy:{tickIntervalMs:15000}}
     │     │
     │     └── GatewayState.Connected(3)
     │
     └── healthMonitor.start()
           每 15 秒检查 connectionState：
           Connected → markHealthy()
           Disconnected/Error > 60s → 触发 onUnhealthy → 重启 Gateway
```

### 3.2 启动完成后的稳态

```
手机内存中运行的对象/进程：

Android 进程: com.openclaw.android
  ├── OpenClawService (Foreground, WakeLock)
  │   ├── ProcessManager    (持有 Process 句柄)
  │   ├── GatewayClient     (WebSocket, 已连接)
  │   └── HealthMonitor     (15s 定时器)
  │
  └── MainActivity + Composable UI
      ├── ChatViewModel     (观察 GatewayClient 事件)
      ├── TerminalViewModel (可选 PTY 会话)
      └── SettingsViewModel (观察 ProcessManager 状态)

子进程（proot 内）:
  └── Node.js (openclaw.mjs gateway --port 18789)
      └── 监听 ws://127.0.0.1:18789

通知栏: "OpenClaw is running" (常驻，带停止按钮)
```

---

## 四、核心功能运行时流程

### 4.1 用户发送消息（含流式 AI 回复）

```
用户在输入框输入文字，点击发送
        │
        ▼
ChatScreen.onClick
        │
        ▼
ChatViewModel.sendMessage(text)
  ├── 1. 创建 UserMessage(status=SENDING) 加入 _messages
  │      → UI 立即显示用户气泡（乐观更新）
  │
  └── 2. viewModelScope.launch {
           chatApi.sendMessage(text)
             └── gateway.request("chat.send", {message:text, sessionKey:"main"})
                   ├── 生成 UUID → pendingRequests["uuid"] = CompletableDeferred
                   ├── ws.send(JSON)
                   └── deferred.await() ← 挂起等待响应

                 Gateway 收到请求 → 调用 LLM API
                 ← ws.receive(res: {ok:true, payload:{runId:"run-X"}})

                 deferred.complete(response)
                 └── UserMessage.status = SENT
         }

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
同时（LLM 流式生成，并行推送事件）：
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Gateway 推送 event: chat {state:"delta", runId:"run-X", message:{content:"你"}}
        │
        ▼
GatewayClient.onMessage → handleEvent → dispatchChatEvent
        │
        ▼
_chatEvents.emit(payload)    ← SharedFlow
        │
        ▼
ChatApi.observeChatEvents() → ChatEvent.Delta(runId, content)
        │
        ▼
ChatViewModel（init 时已订阅）
  └── streamingBuffers["run-X"].append("你")
      _messages 中新增 ASSISTANT 消息("你", isStreaming=true)
        │
        ▼
ChatScreen LazyColumn 重组 → MessageBubble(content="你", isStreaming=true)
  └── 显示文字 + 右下角旋转加载圈

（反复收到 delta 事件，内容逐字积累："你好" → "你好！" → ...）

Gateway 推送 event: chat {state:"final", runId:"run-X", message:{content:"你好！有什么可以帮您？"}}
        │
        ▼
ChatEvent.Final(runId, message)
        │
        ▼
ChatViewModel
  └── _messages 中 "run-X" 对应消息 isStreaming = false
      streamingBuffers.remove("run-X")
        │
        ▼
MessageBubble(content="你好！有什么可以帮您？", isStreaming=false)
  └── 旋转圈消失，显示完整文字
```

### 4.2 工具审批（Agent 执行敏感操作前请求确认）

```
AI Agent 需要执行敏感操作（如发送邮件、写文件）
        │
        ▼
Gateway 推送 event: exec.approval.requested
  {id:"apr-X", command:"send_email", commandArgv:["--to","user@example.com"], cwd:"/root"}
        │
        ▼
GatewayClient → dispatchApproval → _approvalRequests.emit(payload)
        │
        ▼
ApprovalApi.observeApprovalRequests() → ApprovalUiRequest
        │
        ▼
ChatViewModel._pendingApproval.value = request
        │
        ▼
ChatScreen: pendingApproval != null → 弹出 ApprovalDialog
  ┌─────────────────────────────────┐
  │ Tool Approval Required          │
  │ send_email                      │
  │ "发送邮件到 user@example.com"    │
  │                                 │
  │  [Deny]         [Approve]       │
  └─────────────────────────────────┘
        │
        ├── 点击 Approve → approvalApi.resolve(id, approved=true)
        │     └── gateway.request("exec.approval.resolve", {id, decision:"allow"})
        │
        └── 点击 Deny   → approvalApi.resolve(id, approved=false)
              └── gateway.request("exec.approval.resolve", {id, decision:"deny"})

Gateway 收到结果 → AI Agent 继续或中止操作
_pendingApproval.value = null → Dialog 消失
```

### 4.3 Terminal Tab（直接 Shell 访问）

```
用户切换到 Terminal Tab
        │
        ▼
TerminalScreen.AndroidView.factory { ctx ->
    TerminalView(ctx, null).apply {
        viewModel.attachView(this)
    }
}
        │
        ▼
TerminalViewModel.attachView(view)
  ├── createSession()（如果还没有会话）
  │     ├── prootCommand = prootExecutor.buildCommand(["/usr/bin/bash","--login"])
  │     │   = [libproot.so, --rootfs=.../rootfs, --bind=/dev:/dev, ..., -0,
  │     │      /usr/bin/bash, --login]
  │     │
  │     ├── envArray = buildEnvironment() 转为 ["KEY=VALUE", ...] 数组
  │     │
  │     └── TerminalSession(
  │           shellPath = ".../libproot.so",    ← proot 作为 shell 入口
  │           cwd = ".../files/",
  │           args = prootCommand.toTypedArray(),
  │           env = envArray,
  │           transcriptRows = 5000,
  │           client = sessionClient
  │         )
  │         └── JNI: createSubprocess() 创建 PTY + fork 子进程
  │
  ├── view.setTerminalViewClient(viewClient)
  ├── view.setTextSize(14)
  └── view.attachSession(session)
        └── TerminalView 开始渲染终端内容

用户在终端中输入（键盘 → TerminalView → PTY master FD → bash）
bash 输出（bash → PTY slave FD → TerminalEmulator → TerminalView.onScreenUpdated()）

终端会话与 Gateway 进程独立，Gateway 崩溃不影响终端
```

---

## 五、进程生命周期与异常恢复

### 5.1 Gateway 进程崩溃自动恢复

```
Node.js 进程意外退出（exitCode = 1）
        │
        ▼
"gateway-log-reader" daemon thread 检测到 readline 结束
        │
        ▼
ProcessState.Crashed(exitCode=1)
        │
        ▼
OpenClawService.observeProcessState() 收到 Crashed
  ├── gatewayClient.disconnect()       ← 断开 WebSocket
  └── processManager.restartWithBackoff()

restartWithBackoff():
  attempt = 1  → delay 2s  → startGateway()
  attempt = 2  → delay 4s  → startGateway()
  attempt = 3  → delay 8s  → startGateway()
  attempt = 4  → delay 16s → startGateway()
  attempt = 5  → delay 32s → startGateway()
  attempt > 5  → ProcessState.Error("crashed repeatedly")
                 通知栏更新为错误信息
                 用户需手动重启

重启成功 → ProcessState.Running → gatewayClient.connect() → 重新握手
```

### 5.2 HealthMonitor 兜底检查

```
每 15 秒执行：
  ├── GatewayState.Connected → lastHealthyTimestamp = now
  │
  ├── GatewayState.Disconnected / Error
  │   ├── (now - lastHealthyTimestamp) < 60s → 跳过（给自动重连时间）
  │   └── (now - lastHealthyTimestamp) ≥ 60s
  │         → onUnhealthy 回调
  │           ├── gatewayClient.disconnect()
  │           ├── processManager.restartWithBackoff()
  │           └── 重启成功后 gatewayClient.connect()
  │
  └── GatewayState.Connecting / Reconnecting → 跳过（等待中）
```

### 5.3 GatewayClient 自动重连（WebSocket 层）

```
WebSocket 连接断开（code ≠ 1000）
        │
        ▼
scheduleReconnect()
  ├── reconnectAttempt > 10 → GatewayState.Error（停止）
  └── 否则：
        GatewayState.Reconnecting
        delay = 3000ms * min(attempt, 5)
        └── connect() 重新发起 WebSocket 连接
```

### 5.4 用户主动关闭 App（按回退键 / 划掉后台卡片）

```
MainActivity 被销毁（onDestroy）
  └── Compose UI 卸载，ViewModel 清除

OpenClawService 继续运行（Foreground Service 不受影响）
  └── Gateway 进程、WebSocket 连接、HealthMonitor 全部保持

下次打开 App → MainActivity.onCreate() 重新挂载 UI
  └── 各 ViewModel 重新订阅 Service 中的 StateFlow/SharedFlow
      由于是 Singleton（Hilt），拿到的是同一个 GatewayClient / ProcessManager
      → UI 立即恢复到最新状态（无需重连，无需重启 Gateway）
```

### 5.5 设备重启（BootReceiver）

```
设备完成开机
        │
        ▼
系统广播 ACTION_BOOT_COMPLETED
        │
        ▼
BootReceiver.onReceive()
  ├── preferencesManager.isRootfsInstalledSync() = false → 跳过
  ├── preferencesManager.isBackgroundEnabledSync() = false → 跳过
  └── 均为 true →
        API 26+ : startForegroundService(OpenClawService)
        API < 26: startService(OpenClawService)
          └── 重复"正常启动流程"（第三节）
```

### 5.6 系统内存不足杀死进程（OOM Kill）

```
系统 OOM Killer 杀死 com.openclaw.android 进程
  └── 所有 Android 组件（Service、ViewModel）一起销毁
  └── Node.js 子进程也随之被杀死（父进程消亡）

用户再次打开 App / 通知栏点击：
  └── 重走"正常启动流程"，和冷启动无区别
      Setup 状态保留在 DataStore（持久化到磁盘）
```

---

## 六、设置修改后的配置生效流程

```
用户在 Settings 修改 API Key 或模型
        │
        ▼
SettingsViewModel.saveProviderConfig(provider, key)
  ├── preferencesManager.setApiKey(provider, key)
  └── launch(Dispatchers.IO) {
          configWriter.writeConfig()
            ├── 重写 rootfs/root/.openclaw/openclaw.json
            │   {"env": {"ANTHROPIC_API_KEY": "新key", ...}}
            └── 重写 auth-profiles.json
        }

Gateway 进程会定期重读配置文件，新 Key 在下次 LLM 调用时生效
（不需要重启 Gateway）
```

---

## 七、所有组件关系一张图

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Android 进程: com.openclaw.android                                      │
│                                                                         │
│  ┌──────────────┐    ┌───────────────────────────────────────────────┐  │
│  │  MainActivity│    │  OpenClawService (Foreground, WakeLock)       │  │
│  │              │    │                                               │  │
│  │  MainScreen  │    │  ┌──────────────────────────────────────────┐ │  │
│  │  ┌─────────┐ │    │  │ ProcessManager                           │ │  │
│  │  │Chat     ├─┼────┼─►│  ├── startGateway() / stopGateway()     │ │  │
│  │  │ViewModel│ │    │  │  ├── restartWithBackoff()                │ │  │
│  │  └─────────┘ │    │  │  ├── processState: StateFlow             │ │  │
│  │  ┌─────────┐ │    │  │  └── logLines: StateFlow                 │ │  │
│  │  │Terminal ├─┼─┐  │  └──────────────┬───────────────────────── ┘ │  │
│  │  │ViewModel│ │ │  │                 │ proot exec                  │  │
│  │  └─────────┘ │ │  │  ┌──────────────▼───────────────────────── ┐ │  │
│  │  ┌─────────┐ │ │  │  │ GatewayClient                           │ │  │
│  │  │Settings ├─┼─┼──┼─►│  ├── connect() / disconnect()           │ │  │
│  │  │ViewModel│ │ │  │  │  ├── request(method, params)             │ │  │
│  │  └─────────┘ │ │  │  │  ├── connectionState: StateFlow          │ │  │
│  └──────────────┘ │  │  │  ├── chatEvents: SharedFlow              │ │  │
│                   │  │  │  └── approvalRequests: SharedFlow        │ │  │
│  [Hilt Singletons]│  │  └──────────────────────────────────────── ┘ │  │
│  PreferencesManager  │  ┌──────────────────────────────────────── ┐ │  │
│  OpenClawConfigWriter│  │ HealthMonitor                            │ │  │
│  ProotExecutor    │  │  │  每 15s 检查 connectionState             │ │  │
│  RootfsInstaller  │  │  │  > 60s 不健康 → onUnhealthy             │ │  │
│  Paths            │  │  └────────────────────────────────────────  ┘ │  │
│                   │  └───────────────────────────────────────────────┘  │
│                   │                                                       │
│                   │  PTY (via Termux library)                             │
│                   └──────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  子进程: proot + Node.js (openclaw.mjs gateway)                  │   │
│  │                                                                  │   │
│  │  WebSocket Server: ws://127.0.0.1:18789/                         │   │
│  │  配置: rootfs/root/.openclaw/openclaw.json                        │   │
│  │  数据: rootfs/root/.openclaw/data/                               │   │
│  │                                     │ HTTPS                      │   │
│  │                                     ▼                            │   │
│  │                         Claude / GPT / Gemini / ...              │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 八、关键技术约束速查

| 约束 | 原因 | 影响 |
|------|------|------|
| `targetSdk = 28` | Android 10+ W^X 禁止执行 `/data/data/` 下二进制 | 仅能 sideload，不能上 Google Play |
| proot 而非 root | 不需要 root 权限的用户空间隔离 | 部分 syscall 不可用（如 `os.networkInterfaces`，需 preload 修复） |
| Node.js preload 脚本 | proot 内 `os.networkInterfaces()` 返回 EACCES | 每次 proot 执行时注入 `NODE_OPTIONS=--require=node-preload.cjs` |
| 两次写 openclaw.json | Gateway 启动时覆盖配置文件 | `ProcessManager` 在启动前后各写一次，确保用户配置不被覆盖 |
| `libproot.so` 命名 | Android 只自动解压 `jniLibs/*.so` 到 `nativeLibraryDir` | proot 可执行文件伪装成 .so 名称 |
| Foreground Service | Android 8+ 后台服务限制 | 带通知栏常驻，不可隐藏 |
| Phantom Process Killer | Android 12+ 限制子进程总数 ≤ 32 | Gateway 设计为单个 Node.js 进程，不派生过多子进程 |
