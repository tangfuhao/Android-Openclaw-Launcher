# 12 - Google Play 兼容性改造：技术可行性分析与 TODO

> **文档背景：** 当前项目以 `targetSdk = 28` 绕过 Android 10+ 的 W^X 执行限制，导致只能 sideload 分发，无法上架 Google Play。本文档记录了对"既满足技术目标又符合 Google Play 要求"的替代方案分析，供后续开发作为起点。

---

## 一、核心矛盾

### 当前决策

```
targetSdk = 28
```

**原因：** Android 10+（API 29）通过 SELinux 策略引入 W^X（Write XOR Execute）限制，禁止对 `/data/data/<pkg>/` 目录下文件的 `exec()` 和带 `PROT_EXEC` 的 `mmap()` 调用。`targetSdk = 28` 使 App 运行在兼容模式，SELinux 放行这些调用。

**受限的具体文件：**
- `filesDir/rootfs/usr/bin/node`（Node.js 运行时）
- `filesDir/rootfs/usr/bin/bash`（Shell）
- `filesDir/rootfs/usr/lib/node_modules/...`（OpenClaw Gateway）
- 用户通过 `apt install` 动态安装的所有 Debian 二进制

**不受限的文件（已豁免）：**
- `nativeLibraryDir/libproot.so`（在 `/data/app/` 下，不受 W^X 约束）

**Play 要求：** Google Play 自 2024 年 8 月起，新 App 必须 `targetSdk ≥ 34`。

---

## 二、方案概述

### 方案 A：memfd_create + fexecve（推荐）

**技术原理：**

不直接 `exec()` 磁盘文件，而是先将二进制读入内存，通过 `memfd_create()` 创建匿名内存文件描述符，再通过 `fexecve(fd, ...)` 执行。内核对 memfd 不施加路径级 W^X 限制。

```
当前路径（被 W^X 阻断）:
  proot 拦截 exec("/usr/bin/node")
    → 翻译为 host 路径 .../filesDir/rootfs/usr/bin/node
    → 系统 exec() 该文件 ← SELinux DENIED

方案 A 路径:
  proot 拦截 exec("/usr/bin/node")
    → 翻译为 host 路径 .../filesDir/rootfs/usr/bin/node
    → read() 文件内容到内存 buffer
    → memfd_create("node", MFD_CLOEXEC)  → fd
    → write() buffer 到 fd
    → fexecve(fd, argv, envp)  ← 内核不检查路径，通过 ✓
```

**对沙盒环境的影响：**

| proot 沙盒能力 | 是否受影响 |
|---------------|----------|
| 路径重映射（`open/stat/readdir` 等 syscall 拦截） | ❌ 完全不受影响 |
| AI Agent 看到虚拟的 `/usr/bin/`、`/etc/` 等路径 | ❌ 完全不受影响 |
| `apt install` 新工具后可执行（走同样的 memfd 机制） | ❌ 完全不受影响 |
| proot 持续 ptrace 子进程（bash、python、git 等） | ❌ 完全不受影响 |
| 网络访问 | ❌ 完全不受影响 |
| 文件读写、`-0` 伪装 root uid | ❌ 完全不受影响 |

**结论：AI Agent 在 proot + Debian 沙盒中的完整体验不变。**

**额外复杂度——动态链接库问题：**

`exec()` 本身仅是第一个障碍。ELF 二进制执行后，动态链接器（`ld.so`）还需要 `mmap(PROT_EXEC)` 加载 rootfs 内的 `.so` 依赖文件，这同样受 W^X 限制：

```
fexecve(node_memfd) → 成功，node 进程启动
  ↓
动态链接器尝试加载:
  .../filesDir/rootfs/lib/aarch64/libc.so.6  ← mmap(PROT_EXEC) 仍被拦截
  .../filesDir/rootfs/usr/lib/libssl.so.3    ← 同上
```

**解决动态链接问题的两条路：**

1. **静态链接**（推荐）：将 Node.js 编译为静态链接二进制（Termux 即如此，使用 musl libc），消除对 rootfs `.so` 的依赖，只需处理 `exec()` 这一步。
2. **自定义 ELF 加载器**：实现一个通过 memfd 加载 `.so` 的自定义动态链接器（复杂度极高，不推荐作为首选）。

**实现要点：**

- 需要修改 proot 源码（或维护 fork），在 proot 的 exec 系统调用拦截路径中，当目标文件在 `/data/data/` 下时，改用 memfd 路径执行
- 同时构建静态链接的 Node.js aarch64 二进制（参考 Termux 的 Node.js 包配置）
- Debian rootfs 中 bash、python、git 等工具若已是静态链接则直接受益，否则需逐一处理

**参考实现：**
- Termux 的 Node.js 包（静态链接 + 自定义 exec 路径）
- [proot 源码](https://github.com/proot-me/proot)（重点关注 `src/execve/` 目录）
- [zygisk-next](https://github.com/Dr-TSNG/ZygiskNext) 中的 memfd exec 实现

---

### 方案 B：Play Asset Delivery（install-time 资产包）

**技术原理：**

Google Play 的 [Play Asset Delivery](https://developer.android.com/guide/playcore/asset-delivery) 支持最大 2GB 的资产包。`install-time` 类型的资产包在 App 安装时同步分发，存储在 `/data/app/<pkg>/` 路径下（与 `nativeLibraryDir` 同级），**该路径不受 W^X 限制**，可直接 `exec()`。

```
当前: filesDir/rootfs/  →  /data/data/<pkg>/files/rootfs/  → W^X 禁止
改为: AssetPack/rootfs/ →  /data/app/<pkg>/.../rootfs/     → 可 exec ✓
```

**关键限制：**

> **⚠️ 此方案与"AI Agent 动态安装工具"能力存在根本冲突。**

- Asset Pack 目录对 App 是**只读**的，无法写入
- `apt install` 安装的新工具只能落在 `filesDir`（仍受 W^X 限制）
- 如果产品不需要动态 apt，此方案改动量最小

**适用场景：** 预置工具链固化、不支持用户动态 apt 的产品形态。

---

### 方案 C：Node.js 编译为 libnode.so（部分方案）

**技术原理：**

Node.js 官方支持以动态库（`libnode`）形式嵌入宿主进程。将 `libnode.so` 打包进 APK `jniLibs/`，通过 JNI 在 Android 进程内直接运行 Node.js，完全绕过 exec 限制。

```
当前: proot exec /usr/bin/node openclaw.mjs
改为: JNI → System.loadLibrary("node") → 在 Android 进程内跑 openclaw.mjs
```

**关键限制：**

- 仅解决 Node.js（Gateway 进程）的 exec 问题
- Debian 工具链（bash、python、git）的 exec 问题仍然存在，AI Agent 调用这些工具时仍被 W^X 拦截
- 需要配合方案 A 使用才能覆盖完整场景

**参考：** [nodejs-mobile-android](https://github.com/nicktindall/nodejs-mobile-android)（注：原项目已停止维护）

---

### 方案 D：Android 虚拟化框架（AVF）—— 未来方向

Android 13+ 引入 [Android Virtualization Framework](https://source.android.com/docs/core/virtualization)（pKVM），可在 Protected VM 中运行完整 Linux，从根本上绕过 W^X 限制。

- 目前仅支持特定硬件（Pixel 6+、部分旗舰机型）
- minSdk 不得低于 33
- Developer API 仍处于预览阶段，生产可用性待定

---

## 三、方案对比矩阵

| 方案 | targetSdk 可升至 34+ | 保留 apt 动态安装 | 保留完整 Debian 沙盒 | Play 合规 | 改动范围 |
|------|:-------------------:|:----------------:|:-------------------:|:--------:|:------:|
| **A: memfd exec** | ✅ | ✅ | ✅ | ✅ | 高（需 fork proot + 静态 Node.js） |
| **B: Play Asset Delivery** | ✅ | ❌ | ⚠️ 预置工具可用 | ✅ | 中（无 native 改动） |
| **C: libnode.so** | ✅（仅 Node） | ❌ | ⚠️ 部分 | ✅ | 高（需 Node.js 定制构建） |
| **A + C 组合** | ✅ | ✅ | ✅ | ✅ | 极高 |
| **D: AVF** | ✅ | ✅ | ✅ | ✅ | 极高，硬件受限 |

---

## 四、推荐路径与 TODO

### 优先级 P0：技术可行性验证（最小化 PoC）

> **目标：** 在一台 Android 10+ 设备上，以 `targetSdk = 34` 成功通过 proot 执行 `filesDir` 里的 Node.js 二进制。

- [ ] **PoC-1：memfd exec 验证**
  - 写一个最小 JNI 模块，调用 `memfd_create()` + `fexecve()` 执行 `filesDir` 里的一个简单 ELF（如 `busybox echo`）
  - 在 `targetSdk = 34` 的 App 中验证 exec 成功
  - 验证设备：Android 10 / 12 / 14 各一台（覆盖不同内核版本）

- [ ] **PoC-2：静态链接 Node.js 构建**
  - 基于 Termux 的 Node.js 构建配置，编译 aarch64 静态链接的 `node` 二进制
  - 确认静态 node 二进制在通过 memfd exec 后能正常加载 `.mjs` 脚本
  - 目标：`node openclaw.mjs` 启动 WebSocket Server 并接受连接

- [ ] **PoC-3：proot + memfd 联调**
  - 在 proot 的 exec 拦截路径中插入 memfd 分支
  - 以 `targetSdk = 34` 启动完整的 proot 沙盒 + Node.js Gateway
  - 验证 WebSocket 握手和 Chat 功能端到端可用

### 优先级 P1：proot 修改与 CI 集成

- [ ] Fork proot，在 `src/execve/` 中实现 memfd exec 路径
  - 检测时机：`execve()` 的目标路径前缀匹配 `/data/data/`
  - Fallback：若 `memfd_create` 不可用（内核 < 3.17），降级到原有路径（此时 exec 会失败，给用户明确错误提示）
- [ ] 将修改后的 proot 纳入项目 CI，自动编译 `libproot.so`（aarch64）
- [ ] 更新 `scripts/fetch-proot.sh` 指向新的 proot fork

### 优先级 P2：Rootfs 重构

- [ ] 修改 `scripts/build-rootfs.sh`，将 Node.js 替换为静态链接版本
- [ ] 评估 bash、python3、git 等工具是否需要静态链接
  - 实测方式：在 `targetSdk = 34` 下通过 memfd proot 执行，观察 `dlopen` 失败日志
  - 若动态链接问题集中在少数核心工具，可逐一替换为静态版本
- [ ] 更新 rootfs 构建 CI（`.github/workflows/build-rootfs.yml`）

### 优先级 P3：App 层改动

- [ ] 将 `app/build.gradle.kts` 中 `targetSdk` 从 `28` 提升至 `34`
- [ ] 审查并修复 `targetSdk = 34` 带来的其他兼容性问题（通知权限、后台限制等）
- [ ] 移除注释 `// Intentionally below 29 to bypass W^X exec restriction for sideload`，更新为新机制说明

### 优先级 P4：Google Play 上架准备

- [ ] 申请 Google Play 开发者账号（如尚未申请）
- [ ] 评估是否需要 Play Asset Delivery 分发 rootfs（替代运行时下载）
- [ ] 确认 App 内容符合 Google Play 政策（AI Agent 工具使用场景）
- [ ] 准备隐私政策（API Key 存储、网络访问说明）

---

## 五、关键技术参考

| 资源 | 用途 |
|------|------|
| [proot 源码 - execve 拦截](https://github.com/proot-me/proot/tree/master/src/execve) | 实现 memfd exec 的改动入口 |
| [Termux Node.js 包配置](https://github.com/termux/termux-packages/tree/master/packages/nodejs) | 静态链接 Node.js 构建参考 |
| [memfd_create(2) man page](https://man7.org/linux/man-pages/man2/memfd_create.2.html) | memfd API 文档 |
| [fexecve(3) man page](https://man7.org/linux/man-pages/man3/fexecve.3.html) | fexecve API 文档 |
| [Play Asset Delivery 文档](https://developer.android.com/guide/playcore/asset-delivery) | 方案 B 参考 |
| [Android W^X 限制说明](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission) | 官方限制说明 |

---

## 六、已知风险与注意事项

1. **memfd_create 内核版本依赖**：`memfd_create` 要求 Linux 内核 ≥ 3.17。minSdk=24（Android 7）理论上对应内核 3.18+，但部分 OEM 定制内核可能版本更低。需在 PoC 阶段实测覆盖率。

2. **Phantom Process Killer（Android 12+）**：已知限制，项目中已有设计（Gateway 设计为单进程）。提升 targetSdk 不改变此限制，无需额外处理。

3. **Foreground Service 通知权限（Android 13+）**：`targetSdk ≥ 33` 时，发送通知需要 `POST_NOTIFICATIONS` 运行时权限。需在 SetupWizard 中添加权限请求步骤。

4. **proot 维护负担**：Fork proot 意味着需要自行跟进上游安全修复。建议将 fork 改动保持最小化，便于 rebase。

5. **Google Play 审核**：含有"执行下载的二进制"能力的 App 在 Play 审核中可能受到额外关注。需准备充分的产品描述说明其用途（本地 AI Agent Runtime）。
