# Android OpenClaw - 技术文档索引

本目录包含 Android OpenClaw 项目的完整技术文档，面向开发者编写。每份文档覆盖一个独立的技术面。

## 文档清单

| 编号 | 文档 | 一句话描述 | 适合阅读时机 |
|------|------|-----------|-------------|
| **00** | [系统架构总览](00-architecture-overview.md) | 五层架构、技术栈、数据流全景 | **最先阅读** |
| **01** | [项目结构与构建系统](01-project-structure.md) | 目录结构、Gradle 配置、AndroidManifest | 搭环境时阅读 |
| **02** | [内嵌 Linux 环境](02-bootstrap-linux-environment.md) | Bootstrap 下载/解压/验证的完整流程 | 理解核心机制时阅读 |
| **03** | [服务与进程生命周期](03-service-process-lifecycle.md) | Foreground Service、进程管理、健康检查 | 理解后台运行时阅读 |
| **04** | [Gateway WebSocket 协议](04-gateway-protocol.md) | 协议 v3 帧格式、握手、方法、事件 | 对接 OpenClaw 时阅读 |
| **05** | [UI 架构与状态管理](05-ui-architecture.md) | Compose 导航、ViewModel、消息气泡设计 | 开发 UI 时阅读 |
| **06** | [依赖注入](06-dependency-injection.md) | Hilt 配置、依赖图、扩展指南 | 添加新组件时阅读 |
| **07** | [数据流与通信链路](07-data-flow.md) | 五条核心链路的详细数据流跟踪 | 调试问题时阅读 |
| **08** | [终端集成](08-terminal-integration.md) | Termux 库嵌入、PTY 会话、Compose 集成 | 修改终端功能时阅读 |
| **09** | [开发指南](09-development-guide.md) | 环境搭建、约定、常见任务、调试技巧 | **新成员入门阅读** |
| **10** | [API 参考](10-api-reference.md) | 所有类/接口/方法的速查表 | 编码时随时查阅 |

## 推荐阅读顺序

**新接手项目：** 00 → 09 → 01 → 按需阅读其他

**理解核心机制：** 00 → 02 → 03 → 04 → 07

**开发 UI 功能：** 05 → 06 → 09

**调试问题：** 07 → 03 → 04

## 项目统计

| 指标 | 数值 |
|------|------|
| Kotlin 源文件 | 35 |
| 总代码行数 | ~3,500 |
| Debug APK 大小 | ~18 MB |
| 编译时间（增量） | 5-20 秒 |
| 最低 Android 版本 | 7.0 (API 24) |
