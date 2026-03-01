# 02 - 内嵌 Linux 环境（proot + Debian）

## 概述

Android OpenClaw 最核心的能力是在 Android 设备上运行完整的 Linux 环境。这通过 **proot** 实现：

1. 应用首次启动时，从 GitHub Releases 下载预编译的 Debian rootfs 压缩包（`rootfs-aarch64.tar.xz`）
2. 解压到 app 的私有目录 `/data/data/com.openclaw.android/files/rootfs/`
3. 通过 proot 将 rootfs 作为虚拟根目录，进程看到标准 FHS 路径
4. 所有二进制执行均由 `ProotExecutor` 构建 proot 命令行并通过 `ProcessBuilder` 启动

**不需要 root 权限，不需要安装 Termux。**

## proot 工作原理

proot 是一个用户空间工具，通过 `ptrace` 拦截子进程的系统调用，透明地重映射文件路径：

```
进程认为自己读取:  /usr/bin/node
proot 拦截 syscall 并重映射为:  /data/data/com.openclaw.android/files/rootfs/usr/bin/node
```

关键参数：
- `--rootfs` — 指定 Debian rootfs 目录
- `--bind /dev --bind /proc --bind /sys` — 挂载 Android 的设备/进程/系统文件系统
- `--link2symlink` — 在不支持硬链接的文件系统上自动转为符号链接
- `-0` — 伪装为 root 用户（uid/gid 0），使 apt 等工具正常工作
- `--cwd` — 设置进程工作目录

## 文件系统布局

```
/data/data/com.openclaw.android/files/     ← OpenClawConstants.Paths.root
├── rootfs/                                ← Paths.rootfs (proot --rootfs)
│   ├── usr/                               ← 标准 FHS /usr
│   │   ├── bin/
│   │   │   ├── node                       ← Node.js 22+
│   │   │   ├── bash                       ← Bash shell
│   │   │   ├── npm                        ← npm 包管理器
│   │   │   ├── git, python3, ...          ← Debian 软件包
│   │   │   └── ...
│   │   └── lib/
│   │       └── node_modules/
│   │           └── openclaw/              ← OpenClaw Gateway
│   │               └── openclaw.mjs        ← Paths.hostOpenclawEntry
│   ├── etc/
│   │   └── resolv.conf                    ← DNS 配置 (8.8.8.8)
│   ├── root/                              ← $HOME (/root)
│   │   └── .openclaw/                     ← Agent 配置与数据
│   │       └── data/
│   ├── bin/ lib/ var/ tmp/ ...            ← 标准 Debian 目录
│   └── ...
│
├── proot-tmp/                             ← Paths.prootTmp (PROOT_TMP_DIR)
│
└── (app/lib/arm64-v8a/libproot.so)        ← proot 二进制（打包在 APK 中）
```

### Host 路径 vs Inner 路径

| 用途 | Host 路径 (Android 视角) | Inner 路径 (proot 内视角) |
|------|--------------------------|--------------------------|
| Node.js | `filesDir/rootfs/usr/bin/node` | `/usr/bin/node` |
| Bash | `filesDir/rootfs/usr/bin/bash` | `/usr/bin/bash` |
| OpenClaw | `filesDir/rootfs/usr/lib/node_modules/openclaw/openclaw.mjs` | `/usr/lib/node_modules/openclaw/openclaw.mjs` |
| Home | `filesDir/rootfs/root` | `/root` |

Host 路径用于文件存在性检查（`File.exists()`），Inner 路径用于传给 proot 内部进程。

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
│  rootfsInstaller.install(BuildConfig.ROOTFS_URL)             │
└─────────────────────────────────┬────────────────────────────┘
                                  │
                                  ▼
┌──────────────────────────────────────────────────────────────┐
│ RootfsInstaller.install()                                     │
│                                                              │
│  1. 检查是否已安装 (isInstalled)                              │
│     → 已安装且非强制: 直接返回 Installed                       │
│                                                              │
│  2. 下载阶段 → state = Downloading(progress)                 │
│     └── FileDownloader.download(url, cacheDir)               │
│         └── OkHttp GET → 写入临时文件 → rename                │
│         └── 回调 onProgress(bytesRead, totalBytes)            │
│                                                              │
│  3. 解压阶段 → state = Extracting                            │
│     └── extractRootfs(archive, rootfsDir)                    │
│         └── Apache Commons Compress (TarArchiveInputStream   │
│             + XZCompressorInputStream)                        │
│             ├── 目录 → mkdirs()                               │
│             ├── 符号链接 → Os.symlink()                       │
│             ├── 硬链接 → 转为相对符号链接                      │
│             └── 普通文件 → 写入 + applyPermissions()          │
│                                                              │
│  4. 配置阶段 → state = Configuring                           │
│     ├── 写入 /etc/resolv.conf (DNS: 8.8.8.8, 8.8.4.4, 1.1.1.1) │
│     └── 创建 proot-tmp 目录                                   │
│                                                              │
│  5. 验证阶段 → state = Verifying                             │
│     └── 检查 node, bash, openclaw.mjs 三个关键文件是否存在      │
│                                                              │
│  6. 清理 + 持久化                                            │
│     ├── 删除缓存的压缩包                                      │
│     ├── prefs.setRootfsInstalled(true)                       │
│     └── prefs.setRootfsVersion("0.1.0")                      │
│                                                              │
│  7. state = Installed                                        │
│                                                              │
│  异常: state = Error(message, cause)                         │
└──────────────────────────────────────────────────────────────┘
```

## 状态机

`RootfsState` 是一个密封接口，表示安装过程的每个阶段：

```
NotInstalled ──► Checking ──► Downloading(progress) ──► Extracting(progress)
                                                              │
                                                              ▼
                                                        Configuring
                                                              │
                                                              ▼
                                                         Verifying
                                                              │
                                                  ┌───────────┤
                                                  ▼           ▼
                                              Installed     Error
```

每个状态在 UI 中的表现：

| 状态 | UI 表现 |
|------|---------|
| `NotInstalled` | 显示 "Download & Install" 按钮 |
| `Downloading(0.65, 200MB, 310MB)` | 进度条 65%, "200MB / 310MB" |
| `Extracting` | 旋转加载指示器, "Extracting Debian rootfs..." |
| `Configuring` | 旋转加载指示器, "Configuring environment..." |
| `Verifying` | 旋转加载指示器, "Verifying installation..." |
| `Installed` | "Installation complete!" + Continue 按钮 |
| `Error("Network timeout")` | 错误信息 + Retry 按钮 |

## ProotExecutor

`ProotExecutor` 是构建 proot 命令行和启动进程的核心类。

### 命令构建

```
libproot.so \
  --rootfs=/data/.../files/rootfs \
  --bind=/dev:/dev \
  --bind=/proc:/proc \
  --bind=/sys:/sys \
  --bind=/storage:/storage \
  --cwd=/root \
  --link2symlink \
  -0 \
  <innerCommand>
```

Gateway 启动的完整内部命令（`innerCommand`）：

```
/usr/bin/node /usr/lib/node_modules/openclaw/openclaw.mjs gateway \
  --port 18789 --bind loopback --allow-unconfigured
```

注意事项：
- proot 二进制命名为 `libproot.so` 是为了让 Android 自动解压到 `nativeLibraryDir`
- `--bind=/dev:/dev` 格式表示"将 Android 的 /dev 挂载到 proot 内的 /dev"
- `--link2symlink` 解决 FAT/ext4 不支持硬链接时的兼容问题
- `-0` 使进程在 proot 内以 root 身份（uid/gid 0）运行

### 环境变量

`ProotExecutor.buildEnvironment()` 为 proot 宿主进程设置完整的环境：

```
HOME=/root
LANG=en_US.UTF-8
TERM=xterm-256color
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
TMPDIR=/data/.../files/proot-tmp
PROOT_TMP_DIR=/data/.../files/proot-tmp
PROOT_LOADER=/data/app/.../lib/arm64/libproot_loader.so
NODE_OPTIONS=--require=/root/.openclaw/node-preload.cjs
LD_LIBRARY_PATH=/data/app/.../lib/arm64:/data/data/.../files/lib
OPENCLAW_HOME=/root
OPENCLAW_DATA=/root/.openclaw/data
OPENCLAW_GATEWAY_PORT=18789
ANTHROPIC_API_KEY=<user设置的key>  # 各 provider 的 API key
...
```

其中 `NODE_OPTIONS` 注入的 preload 脚本修复了 proot 环境中 `os.networkInterfaces()` 会抛出 EACCES 的问题。这些变量通过 `ProcessBuilder.environment()` 注入到 proot 宿主进程，proot 会将它们传递给内部的 Debian 进程。

## 安装验证

`RootfsInstaller.verifyRootfs()` 检查三个关键文件是否存在（使用 Host 路径）：

| 二进制 | Host 路径 | Inner 路径 | 角色 |
|--------|-----------|-----------|------|
| `node` | `rootfs/usr/bin/node` | `/usr/bin/node` | Node.js 运行时 |
| `bash` | `rootfs/usr/bin/bash` | `/usr/bin/bash` | Shell |
| `openclaw` | `rootfs/usr/lib/node_modules/openclaw/openclaw.mjs` | `/usr/lib/node_modules/openclaw/openclaw.mjs` | Gateway 入口 |

任何一个缺失会抛出异常，`RootfsInstaller` 进入 `Error` 状态。

## Rootfs 构建指南

Rootfs 压缩包在 CI（ARM64 Linux 或 Docker + QEMU）中构建，使用 `scripts/build-rootfs.sh`：

```bash
#!/bin/bash
# 需要 root 权限和 debootstrap

# 1. 创建最小化 Debian bookworm 基础系统
debootstrap --arch=arm64 bookworm rootfs http://deb.debian.org/debian

# 2. chroot 进入安装所需软件
chroot rootfs /bin/bash -c "
  apt update && apt install -y nodejs npm git python3 make vim-tiny jq curl
  npm install -g openclaw@latest
  apt clean && rm -rf /var/lib/apt/lists/* /tmp/*
"

# 3. 打包为 tar.xz
tar -cJf rootfs-aarch64.tar.xz -C rootfs .

# 4. 上传到 GitHub Releases
gh release upload rootfs-v0.1.0 rootfs-aarch64.tar.xz
```

最终压缩包约 300MB（包含 Debian 基础系统 + Node.js + OpenClaw + 常用工具链）。

## proot 二进制分发

proot 静态编译的 aarch64 二进制通过 `scripts/fetch-proot.sh` 下载，放置于 `app/src/main/jniLibs/arm64-v8a/libproot.so`。打包进 APK 后位于 `nativeLibraryDir`，运行时通过 `context.applicationInfo.nativeLibraryDir + "/libproot.so"` 获取路径。

命名为 `.so` 是因为 Android 仅自动解压 `lib/` 目录下的 `.so` 文件到 `nativeLibraryDir`。
