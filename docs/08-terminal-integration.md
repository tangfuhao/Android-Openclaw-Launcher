# 08 - 终端集成（Termux 库嵌入）

## 概述

终端 Tab 提供对内嵌 Linux 环境的直接 shell 访问。它使用 Termux 的两个开源库：

| 库 | Maven 坐标 | 功能 |
|-----|-----------|------|
| **terminal-emulator** | `com.github.termux.termux-app:terminal-emulator:v0.118.1` | PTY 管理、VT100/xterm 终端仿真、进程生命周期 |
| **terminal-view** | `com.github.termux.termux-app:terminal-view:v0.118.1` | Android View 渲染终端内容、手势处理、文本选择 |

**重点：这两个库以 AAR/JAR 依赖编译进 APK，用户不需要安装 Termux 应用。**

## 架构

```
┌──────────────────────────────────────────────────────────┐
│ TerminalScreen (Composable)                               │
│                                                          │
│  ┌────────────────────────────────────────────────────┐  │
│  │ AndroidView {                                      │  │
│  │   TerminalView(context)  ← Termux terminal-view   │  │
│  │     .attachSession(session)                        │  │
│  │     .setTerminalViewClient(viewClient)             │  │
│  │ }                                                  │  │
│  └───────────────────┬────────────────────────────────┘  │
│                      │                                    │
│                      │ attachSession()                    │
│                      ▼                                    │
│  ┌────────────────────────────────────────────────────┐  │
│  │ TerminalSession  ← Termux terminal-emulator        │  │
│  │                                                    │  │
│  │   shellPath = /data/app/.../lib/arm64/libproot.so  │  │
│  │   cwd = /data/data/com.openclaw.android/files/     │  │
│  │   args = [libproot.so, --rootfs=..., --bind=...,   │  │
│  │           --cwd=/root, --link2symlink, -0,          │  │
│  │           /usr/bin/bash, --login]                  │  │
│  │   env = [HOME=/root, PATH=..., PROOT_LOADER=...,  │  │
│  │          NODE_OPTIONS=..., ANTHROPIC_API_KEY=...]  │  │
│  │   transcriptRows = 5000                            │  │
│  │                                                    │  │
│  │   ┌──────────────────────────────────┐             │  │
│  │   │ PTY (Pseudo-Terminal)             │             │  │
│  │   │                                  │             │  │
│  │   │  Master FD ◄──► Slave FD         │             │  │
│  │   │     ▲                 │           │             │  │
│  │   │     │                 ▼           │             │  │
│  │   │  TerminalEmulator   bash process  │             │  │
│  │   │  (VT100 解析)      (shell 进程)  │             │  │
│  │   └──────────────────────────────────┘             │  │
│  └────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

## 关键类和接口

### TerminalSession（来自 terminal-emulator）

`TerminalSession` 封装了一个完整的终端会话：

```java
// 构造函数
public TerminalSession(
    String shellPath,       // shell 二进制路径
    String cwd,             // 工作目录
    String[] args,          // 启动参数
    String[] env,           // 环境变量数组 ["KEY=VALUE", ...]
    Integer transcriptRows, // 回滚缓冲区行数
    TerminalSessionClient client  // 回调接口
)
```

内部机制：
1. 调用 JNI 函数 `createSubprocess()` 创建 PTY 和 fork shell 子进程
2. 启动三个线程：读取进程输出、写入进程输入、监控进程退出
3. 通过 `TerminalEmulator` 解析 VT100/xterm 控制序列
4. 维护屏幕缓冲区（`TerminalBuffer`）和样式状态

### TerminalSessionClient（需要实现）

```kotlin
interface TerminalSessionClient {
    fun onTextChanged(session)        // 屏幕内容变化 → 刷新 View
    fun onTitleChanged(session)       // 标题变化（如 cd 后目录变化）
    fun onSessionFinished(session)    // Shell 退出
    fun onCopyTextToClipboard(text)   // 终端请求复制到剪贴板
    fun onPasteTextFromClipboard()    // 终端请求粘贴
    fun onBell(session)               // 响铃字符 \a
    fun onColorsChanged(session)      // 颜色方案变化
    fun onTerminalCursorStateChange() // 光标闪烁状态
    fun getTerminalCursorStyle()      // 返回光标样式
    // + 日志方法 (logError, logWarn, logInfo, logDebug, logVerbose)
}
```

**本项目的实现要点：**

- `onTextChanged` → 调用 `terminalView.onScreenUpdated()` 触发重绘
- `onTitleChanged` → 更新 `_sessionTitle` StateFlow，Compose 标题栏响应变化
- `onCopyTextToClipboard` → 写入系统剪贴板 (`ClipboardManager`)
- `onPasteTextFromClipboard` → 从系统剪贴板读取，写入 `session.emulator.paste()`
- `getTerminalCursorStyle` → 返回 `TERMINAL_CURSOR_STYLE_UNDERLINE`

### TerminalView（来自 terminal-view）

`TerminalView` 是一个 Android `View`，负责渲染终端内容：

```java
// 核心方法
void setTerminalViewClient(TerminalViewClient client)  // 设置 UI 事件回调
boolean attachSession(TerminalSession session)          // 绑定终端会话
void setTextSize(int textSize)                          // 设置字号
void onScreenUpdated()                                  // 刷新显示
```

### TerminalViewClient（需要实现）

```kotlin
interface TerminalViewClient {
    fun onScale(scale: Float): Float          // 缩放手势 → 调整字号
    fun onSingleTapUp(event)                  // 点击 → 显示键盘
    fun shouldBackButtonBeMappedToEscape()     // 返回键 → ESC?
    fun shouldEnforceCharBasedInput()          // 字符输入模式
    fun isTerminalViewSelected()               // View 是否选中
    fun onKeyDown(keyCode, event, session)     // 按键事件
    fun onKeyUp(keyCode, event)                // 按键释放
    fun onLongPress(event)                     // 长按
    fun readControlKey() / readAltKey() / ...  // 修饰键状态
    fun onCodePoint(codePoint, ctrl, session)  // Unicode 输入
    fun onEmulatorSet()                        // 仿真器就绪
    // + 日志方法
}
```

**本项目的实现要点：**

- `onScale` → 根据缩放比例调整字号（范围 8-32sp），更新 `_fontSize` StateFlow
- `shouldEnforceCharBasedInput` → 返回 `true`（适合移动端输入法）
- `isTerminalViewSelected` → 返回 `true`（始终接受输入）
- 修饰键方法 → 全返回 `false`（不模拟物理键盘修饰键）

## Compose 集成

终端 View 通过 `AndroidView` 互操作嵌入 Compose：

```kotlin
AndroidView(
    factory = { ctx ->
        TerminalView(ctx, null).apply {
            isFocusable = true              // 接受焦点
            isFocusableInTouchMode = true   // 触摸获取焦点
            viewModel.attachView(this)      // 绑定 session + clients
        }
    },
    update = { view ->
        view.setTextSize(fontSize)          // 响应字号变化
    },
    modifier = Modifier.fillMaxSize(),
)
```

`DisposableEffect` 确保在 Composable 离开组合时调用 `viewModel.detachView()`。

## 会话创建流程

```
TerminalViewModel.createSession()
  │
  ├── 如果有旧会话 → finishIfRunning() 终止
  │
  ├── 构建环境变量数组
  │   env.entries.map { "${key}=${value}" }.toTypedArray()
  │
  ├── prootCommand = prootExecutor.buildCommand(["/usr/bin/bash", "--login"])
  │
  ├── envArray = prootExecutor.buildEnvironment()
  │             .entries.map { "${it.key}=${it.value}" }.toTypedArray()
  │
  ├── TerminalSession(
  │     shellPath = prootExecutor.prootBinaryPath,  // nativeLibraryDir/libproot.so
  │     cwd = paths.root.absolutePath,              // app 的 files/ 目录
  │     args = prootCommand.toTypedArray(),          // 完整 proot 命令行
  │     env = envArray,                              // proot 环境变量（含 API Keys）
  │     transcriptRows = 5000,
  │     client = sessionClient
  │   )
  │
  └── 返回 session （此时 PTY 已创建，proot+bash 已 fork）

TerminalViewModel.attachView(view)
  │
  ├── view.setTerminalViewClient(viewClient)
  ├── view.setTextSize(fontSize)
  └── view.attachSession(session)
      └── 此时 TerminalView 开始渲染终端内容
```

## 生命周期管理

| 事件 | 处理 |
|------|------|
| Tab 切换到终端 | `AndroidView.factory` 创建 View, `attachView()` |
| Tab 切换离开 | `DisposableEffect.onDispose` → `detachView()` |
| Tab 再次切换回 | 同一 ViewModel 实例，session 仍然活着，重新 `attachView()` |
| ViewModel 清除 | `onCleared()` → `session.finishIfRunning()` 终止 bash |

**注意：** bash 会话独立于 Gateway 进程。即使 Gateway 崩溃，终端仍然可用。
