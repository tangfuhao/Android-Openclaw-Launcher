# 13 - proot/Android 沙盒环境约束分析与 TODO：告知 OpenClaw 真实运行环境

> **文档背景：** OpenClaw 运行于 proot 模拟的 Linux 沙盒之中。这个沙盒对外表现为标准 Debian 环境，但底层受到 Android 内核、SELinux 和 Linux Capability 机制的严格限制。本文档系统梳理这些限制的根因、已知失败域，并记录"如何将环境约束信息传达给 OpenClaw Agent"这一未完成工作的 TODO。

---

## 一、根因分析：为什么 proot 沙盒不等于真正的 Linux

### 架构层次

```
OpenClaw / Node.js / AI Agent 执行的 shell 命令
         │
         ▼
   proot（用户空间）
   ├── 通过 ptrace 拦截子进程 syscall
   ├── 透明重映射文件路径（/usr/bin → filesDir/rootfs/usr/bin）
   ├── -0 标志：在 proot 层伪造 uid/gid = 0（假 root）
   └── 无法建立真实 namespace（网络、PID、挂载等）
         │
         ▼  ← proot 无法影响这一层
   Android Linux Kernel（真实内核）
   ├── SELinux（Enforcing 模式）：对 app UID 强制访问控制
   ├── Seccomp Filter：过滤部分危险 syscall
   └── Linux Capability 机制：CAP_NET_RAW / CAP_SYS_ADMIN 等均不可获取
```

**关键结论：** proot 的 `-0`（假 root）只在 proot 自身的拦截层生效，对 Android 内核、SELinux、Capability 完全透明。内核看到的始终是 app 真实的 UID，受完整的 Android 权限体系约束。

### `os.networkInterfaces()` 的 EACCES 为何必然发生

这是目前项目中唯一已实施修复的案例，其调用链为：

```
os.networkInterfaces()
  → libuv uv_interface_addresses()
  → glibc getifaddrs()
  → 读取 /proc/net/if_inet6 等文件
  → SELinux 策略（Android 10+ 引入）：app UID 无权读 /proc/net/
  → EACCES
```

这是 Android 10 有意引入的隐私保护变更，防止 app 指纹识别网络拓扑。proot 无路绕过。

当前修复方式：在 `ProotExecutor.ensureNodePreload()` 中写入 monkey-patch 脚本，通过 `NODE_OPTIONS=--require=` 在 Node.js 启动前注入，将 `os.networkInterfaces()` 的异常捕获并返回一个最小化的 loopback 假值。

---

## 二、失败域枚举

### 2.1 必然失败（任何设备任何 Android 版本）

| 操作 / 工具 | 失败原因 | 错误码 |
|------------|---------|-------|
| 加载内核模块（`insmod`、`modprobe`） | 需要 `CAP_SYS_ADMIN` | EPERM |
| `mount()` / `umount()` | 需要 `CAP_SYS_ADMIN` | EPERM |
| 写入 `/proc/sys/`（sysctl 调整） | 需要 `CAP_SYS_ADMIN` | EPERM |
| 读取 `/proc/net/`（`netstat`、`ss` 的实现依赖） | SELinux 拒绝 app UID 访问 | EACCES |
| 原始套接字（`nmap -sS`、`tcpdump`、传统 `ping`） | 需要 `CAP_NET_RAW` | EPERM |
| 修改网络接口/路由（`ip addr add`、`iptables`） | 需要 `CAP_NET_ADMIN` | EPERM |
| 绑定 1024 以下端口 | 需要 `CAP_NET_BIND_SERVICE` | EACCES |
| Docker / Podman / 任意容器 | 需要真实内核 namespace + capability | — |
| `strace` / `gdb` 调试（在 proot 内嵌套 ptrace） | Android 限制 ptrace 嵌套 | EPERM |
| setuid 可执行文件产生真实权限提升 | 内核不识别 proot 的假 root | 无效果 |

### 2.2 依赖 Android 版本或厂商定制，结果不确定

| 操作 | 风险 |
|------|------|
| 大量并发子进程 | Android 12+ Phantom Process Killer 限制子进程总数 ≤ 32 |
| SysV IPC（共享内存、信号量） | 与 Android 系统共享同一内核 IPC 命名空间，可能冲突或被清理 |
| `inotify` 监听 `/proc`、`/sys` | 行为不可预期 |
| PostgreSQL、MySQL 等数据库服务器 | 部分初始化依赖 sysctl 调整（如 `vm.overcommit_memory`），可能失败 |
| `/dev/` 设备访问 | 虽然 `--bind=/dev:/dev`，但 Android SELinux 限制大量设备节点的访问权限 |
| 厂商定制 SELinux（小米/华为等） | 可能比 AOSP 更严格，限制域更广 |

### 2.3 完全正常工作

| 类别 | 具体内容 |
|------|---------|
| 常规网络 | HTTP/HTTPS 请求、TCP/UDP socket、WebSocket |
| 包管理 | `apt install`（proot 假 root 足以应付） |
| 主流语言运行时 | Python、Node.js、Ruby、Go、Java（无 JVM 沙盒限制时） |
| 开发工具 | gcc/clang 编译、git、make、cmake |
| 文件操作 | 完整读写能力（在 rootfs 目录内） |
| 轻量数据库 | SQLite、LevelDB、文件型 MongoDB |
| Web 服务器 | Nginx、Express 等（端口需 > 1024） |
| DNS 解析 | rootfs 中已预配 `/etc/resolv.conf`（8.8.8.8） |
| 脚本与自动化 | bash/python 脚本、cron（若在前台运行）、任务队列 |

---

## 三、已实施的 Workaround

| 问题 | 解决方案 | 实现位置 |
|------|---------|---------|
| `os.networkInterfaces()` 在 proot 内 EACCES | Node.js preload 脚本：try/catch 包裹，失败返回 loopback 假值 | `ProotExecutor.ensureNodePreload()` |
| `libtalloc.so.2` 符号链接缺失 | 在 `lib/` 目录创建版本化软链接 | `ProotExecutor.ensureTallocSymlink()` |
| proot 二进制无法被 Android 自动解压 | 将 proot 可执行文件命名为 `libproot.so` 打包进 jniLibs | `build.gradle.kts` |
| W^X 限制禁止执行 `/data/data/` 下二进制 | `targetSdk = 28`（兼容模式） | `build.gradle.kts` |
| Android 12+ Phantom Process Killer | Gateway 设计为单个 Node.js 进程，不派生大量子进程 | 架构设计 |

---

## 四、TODO：向 OpenClaw Agent 传达真实运行环境

### 背景与动机

OpenClaw Agent 当前运行在一个"看起来像标准 Debian Linux"的环境里，但它对以下事实一无所知：

1. 自己实际运行于 Android 设备上的 proot 沙盒，而非裸机 Linux
2. 一系列操作（见第二节）会以权限错误失败，且无法通过 `sudo` 或安装软件解决
3. 存储空间来自 Android app 私有目录，有配额限制
4. 网络接口信息不可靠（已被 mock 为仅有 loopback）
5. 不能运行容器、不能调试器附加、不能修改内核参数

这导致 Agent 可能：
- 尝试执行必然失败的命令，浪费 token 和时间
- 在失败后反复重试或走弯路（如尝试用 `sudo` 获取权限）
- 给出对真实 Linux 环境有效但在此环境无效的建议
- 无法向用户解释为什么某个操作不可行

### TODO 列表

#### TODO-1：研究 OpenClaw 的 System Prompt 注入机制
**优先级：** 高  
**背景：** 需要先了解 OpenClaw Gateway 如何向 LLM 注入系统提示或环境上下文。可能的注入点：
- Gateway 的 `system` 字段或初始化消息
- `openclaw.json` 配置中的环境描述字段
- Agent 启动时传入的上下文参数

**目标：** 找到一个可靠的接口，让 Android 侧能向 OpenClaw 注入一段描述当前环境约束的文本。

---

#### TODO-2：起草环境约束 System Prompt 文本
**优先级：** 中（待 TODO-1 完成后进行）  
**草稿方向：**

```
你当前运行于 Android 设备的 proot 沙盒环境（非裸机 Linux）。
这意味着以下操作将会失败，且无法通过任何方式绕过：

【不可用操作】
- 容器（Docker/Podman）：不支持
- 内核模块（insmod/modprobe）：EPERM
- 网络抓包（tcpdump/wireshark）：EPERM（无 CAP_NET_RAW）
- 端口扫描原始套接字模式（nmap -sS）：EPERM
- 修改网络接口/路由（iptables/ip link）：EPERM
- 绑定 1024 以下端口：EACCES
- 读取 /proc/net/ 下的网络统计文件：EACCES
- sysctl 写入：EPERM
- strace/gdb 附加调试：EPERM

【可正常使用】
- HTTP/HTTPS 网络请求、TCP/UDP socket
- apt 包管理、常规编译工具
- Python/Node.js/Go 等语言运行时
- 文件读写（在 /root 及 /tmp 下）
- 运行 Web 服务（端口需 > 1024）

【注意】
- os.networkInterfaces() 返回的是模拟数据（仅 loopback），不反映真实网络接口
- 如果你遇到 EPERM 或 EACCES 并且重试无效，请直接告知用户这是沙盒限制
```

---

#### TODO-3：评估动态注入 vs 静态配置的实现路径
**优先级：** 中  
**需要调研：**
- OpenClaw 是否支持在 `openclaw.json` 里配置 system prompt 扩展字段？
- 是否可以通过 Gateway WebSocket 协议的初始化帧传入环境描述？
- 是否需要修改 OpenClaw 本身（在 `openclaw.mjs` 里增加 Android 环境感知逻辑）？
- Android 侧（`OpenClawConfigWriter`）是否需要在写配置时动态注入设备信息（Android 版本、设备型号）？

---

#### TODO-4：测试 Agent 在约束下的行为
**优先级：** 低（待前三项完成后）  
**测试用例：**
- Agent 尝试运行 Docker → 是否给出正确解释而非反复重试？
- Agent 尝试 `ping -c1 8.8.8.8` → 大多数情况下会失败（ICMP raw socket），是否能降级到 `curl` 测试连通性？
- Agent 尝试 `netstat -tlnp` → `/proc/net/` 不可读，是否给出替代方案？
- Agent 尝试绑定 80 端口 → 是否主动建议使用 8080？

---

## 五、已知 Workaround 的潜在改进点

下列问题不是 TODO 的核心，但在代码质量层面值得关注：

| 问题 | 当前状态 | 建议 |
|------|---------|------|
| preload 脚本升级后不更新 | `ensureNodePreload()` 仅判断文件是否存在 | 改为比对内容 hash 或写入版本文件 |
| `NODE_OPTIONS` 影响全部 Node.js 进程 | 包括用户在终端手动运行的 node/npm | 可接受，记录即可 |
| fallback 的 MAC 地址全零 | `00:00:00:00:00:00` 若被用于 UUID v1 生成会产生冲突 | 当前 gateway 无此依赖，低优先级 |

---

*本文档由开发者在分析 proot/Android 沙盒约束时编写，作为未来工作的起点。*
