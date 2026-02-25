# 02 - 内嵌 Linux 环境（Bootstrap）

## 概述

Android OpenClaw 最核心的能力是在 Android 设备上运行完整的 Linux 二进制程序（Node.js, bash 等）。这通过以下机制实现：

1. 应用首次启动时，从 GitHub Releases 下载预编译的 Linux 文件系统压缩包（bootstrap）
2. 解压到 app 的私有目录 `/data/data/com.openclaw.android/files/usr/`
3. 通过 `ProcessBuilder` 直接启动这些二进制文件

**不需要 root 权限，不需要安装 Termux。**

## 文件系统布局

```
/data/data/com.openclaw.android/files/     ← OpenClawConstants.Paths.root
├── usr/                                    ← Paths.prefix ($PREFIX)
│   ├── bin/                                ← Paths.bin
│   │   ├── node                            ← Node.js 22+ 二进制
│   │   ├── bash                            ← Bash shell
│   │   ├── npm                             ← npm 包管理器
│   │   └── ...                             ← 其他 Linux 工具
│   ├── lib/                                ← Paths.lib
│   │   ├── libc.so → ...                   ← 动态链接库
│   │   └── node_modules/
│   │       └── openclaw/                   ← OpenClaw Gateway
│   │           └── bin/openclaw.js         ← Paths.openclawEntry
│   ├── libexec/                            ← 辅助可执行文件
│   └── tmp/                                ← Paths.tmp ($TMPDIR)
│
└── home/                                   ← Paths.home ($HOME)
    ├── .profile                            ← Shell 启动脚本
    └── .openclaw/                          ← Paths.openclawConfig
        └── data/                           ← Paths.openclawData
```

## 安装流程

```
┌──────────────────────────────────────────────────────────────┐
│ SetupWizardScreen (UI)                                       │
│                                                              │
│  [Welcome] → [DeviceCheck] → [Download] → [ApiKey] → [Done] │
└─────────────────────────────────┬────────────────────────────┘
                                  │ startInstallation()
                                  ▼
┌──────────────────────────────────────────────────────────────┐
│ SetupViewModel                                                │
│                                                              │
│  bootstrapInstaller.install(BuildConfig.BOOTSTRAP_URL)       │
└─────────────────────────────────┬────────────────────────────┘
                                  │
                                  ▼
┌──────────────────────────────────────────────────────────────┐
│ BootstrapInstaller.install()                                  │
│                                                              │
│  1. 检查是否已安装 (isInstalled)                              │
│     → 已安装且非强制: 直接返回 Installed                       │
│                                                              │
│  2. 下载阶段 → state = Downloading(progress)                 │
│     └── BootstrapDownloader.download(url, cacheDir)          │
│         └── OkHttp GET → 写入临时文件 → rename                │
│         └── 回调 onProgress(bytesRead, totalBytes)            │
│                                                              │
│  3. 解压阶段 → state = Extracting                            │
│     └── extractTarGz(archive, prefix)                        │
│         ├── 优先使用 /system/bin/tar（系统自带，更快）          │
│         └── 回退到 Java GZIPInputStream + 自实现 tar 解析      │
│                                                              │
│  4. 配置阶段 → state = Configuring                           │
│     ├── setExecutablePermissions(prefix)                     │
│     │   └── 递归遍历 bin/ lib/ libexec/                       │
│     │       └── file.setExecutable(true, false)              │
│     └── paths.ensureDirectories()                            │
│                                                              │
│  5. 清理 + 持久化                                            │
│     ├── 删除缓存的压缩包                                      │
│     ├── prefs.setBootstrapInstalled(true)                    │
│     └── prefs.setBootstrapVersion("0.1.0")                   │
│                                                              │
│  6. state = Installed                                        │
│                                                              │
│  异常: state = Error(message, cause)                         │
└──────────────────────────────────────────────────────────────┘
```

## 状态机

`BootstrapState` 是一个密封接口，表示安装过程的每个阶段：

```
NotInstalled ──► Checking ──► Downloading(progress) ──► Extracting(progress)
                                                              │
                                                              ▼
                                                        Configuring
                                                              │
                                              ┌───────────────┤
                                              ▼               ▼
                                          Installed        Error
```

每个状态在 UI 中的表现：

| 状态 | UI 表现 |
|------|---------|
| `NotInstalled` | 显示 "Download & Install" 按钮 |
| `Downloading(0.65, 200MB, 310MB)` | 进度条 65%, "200MB / 310MB" |
| `Extracting` | 旋转加载指示器, "Extracting files..." |
| `Configuring` | 旋转加载指示器, "Configuring environment..." |
| `Installed` | "Installation complete!" + Continue 按钮 |
| `Error("Network timeout")` | 错误信息 + Retry 按钮 |

## 环境变量

`EnvironmentSetup.buildEnvironment()` 为每个子进程构建完整的环境变量映射：

```
HOME=/data/data/com.openclaw.android/files/home
PREFIX=/data/data/com.openclaw.android/files/usr
TMPDIR=/data/data/com.openclaw.android/files/usr/tmp
LANG=en_US.UTF-8
TERM=xterm-256color
PATH=/data/data/.../files/usr/bin:/system/bin:/system/xbin
LD_LIBRARY_PATH=/data/data/.../files/usr/lib
OPENCLAW_HOME=/data/data/.../files/home/.openclaw
OPENCLAW_DATA=/data/data/.../files/home/.openclaw/data
OPENCLAW_GATEWAY_PORT=18789
NODE_PATH=/data/data/.../files/usr/lib/node_modules
```

这些变量通过 `ProcessBuilder.environment()` 注入到 Node.js 进程和 bash 会话中。

## 安装验证

`EnvironmentSetup.verifyInstallation()` 检查三个关键二进制是否存在：

| 二进制 | 路径 | 角色 |
|--------|------|------|
| `node` | `$PREFIX/bin/node` | Node.js 运行时 |
| `bash` | `$PREFIX/bin/bash` | Shell（终端 Tab 用） |
| `openclaw` | `$PREFIX/lib/node_modules/openclaw/bin/openclaw.js` | Gateway 入口 |

任何一个缺失会返回 `MissingBinaries(names)`，`ProcessManager` 收到后拒绝启动。

## Tar 解压器实现细节

内置了一个最小化的 POSIX tar 解析器（`BootstrapInstaller.extractTarStream`），处理三种文件类型：

| 类型标志 | 含义 | 处理方式 |
|----------|------|----------|
| `'0'` / `'\0'` | 普通文件 | 创建文件并写入内容 |
| `'5'` / `'D'` | 目录 | `mkdirs()` |
| `'2'` | 符号链接 | `ln -sf linkName target` |

每条 tar 记录包含 512 字节头部，数据段按 512 字节对齐填充。

## Bootstrap 构建指南

Bootstrap 压缩包需要在 ARM64 Linux 环境中构建（可以用 Termux 或 Docker + QEMU）：

```bash
#!/bin/bash
# 在 Termux 或 ARM64 容器中执行

# 1. 安装 Node.js
pkg install nodejs-lts

# 2. 全局安装 OpenClaw
npm install -g openclaw@latest

# 3. 打包 $PREFIX 为 tar.gz
cd $PREFIX/..
tar czf bootstrap-aarch64.tar.gz usr/

# 4. 上传到 GitHub Releases
gh release upload bootstrap-v0.1.0 bootstrap-aarch64.tar.gz
```

最终压缩包大小约 300MB（包含 Node.js + npm + OpenClaw + 基础工具链）。
