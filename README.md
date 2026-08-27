# IQ Code Android

IQ Code Android 是一款运行在 Android 手机上的原生 AI 编程 Agent。它将对话式代码助手、项目文件管理、终端、任务执行和 Android 沙箱整合到同一个应用中，让用户可以直接在移动设备上读取项目、修改代码、运行命令并验证结果。

## 主要功能

- 多轮编程对话与流式回复
- 读取、搜索、编辑和管理项目文件
- 内置 ARM64 Termux/Bionic 运行环境与原生终端
- Bash 命令执行、任务管理和计划审批
- Anthropic Messages、OpenAI Chat Completions、OpenAI Responses/Codex 等 API 协议
- API 配置、模型选择、推理强度和权限模式管理
- 会话保存、恢复、分支及上下文压缩
- MCP stdio 与 Streamable HTTP 工具接入
- 子 Agent、后台任务和实时执行进度
- 基于 BlackBox 的 IQ Sandbox，可安装、运行和调试独立 APK
- 深色、浅色及自定义界面主题

## 项目结构

- `app/src/main/java/com/termux/app/iqcode/`：Agent 核心、模型提供方、工具、会话、任务和 MCP
- `app/src/main/java/com/iqge/`：Android 应用界面、终端和系统集成
- `app/src/main/res/`：Android 资源
- `app/src/main/assets/bootstrap-aarch64.zip`：离线 ARM64 Termux 用户空间
- `app/src/main/jniLibs/arm64-v8a/libtermux.so`：原生 PTY 桥接库
- `app/libs/iqcode-termux-compat.jar`：Termux 安装及路径兼容二进制组件
- `Bcore/`：IQ Sandbox 使用的 BlackBox Android 运行层
- `black-reflection/`、`compiler/`：BlackBox 反射与注解处理模块

## 构建要求

- JDK 17 或 21
- Android SDK，包含 Compile SDK 35 和对应 Build Tools
- Android NDK
- Bash 环境
- ARM64 Android 设备作为运行目标

默认 SDK 路径为 `$HOME/android-sdk`，也可通过 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME` 指定。

## 构建 APK

```bash
chmod +x gradlew
./gradlew --no-daemon assembleRelease
```

构建产物位于：

```text
app/build/outputs/apk/release/IQCode-release.apk
```

## API 配置

应用本身不附带模型 API 凭据。安装后请在 API 设置中添加服务地址、API Key、模型名称及对应协议。密钥由 Android 安全存储组件保存，不应提交到源码仓库。

## 源码与二进制组件

Agent 逻辑、Android UI、工具系统、模型接入和沙箱集成代码以源码形式提供。Termux 前缀转换、安装兼容实现及原生 PTY 部分以预编译 JAR/SO 形式随项目分发，以便项目可以直接完成 Gradle 构建。

当前构建仅面向 `arm64-v8a`，最低 Android 版本为 Android 7.0（API 24）。

## 安全说明

IQ Code 可以执行终端命令、修改文件并控制沙箱应用。请仅连接可信的模型服务，审查高风险操作，并避免在不受信任的项目中启用跳过权限确认模式。

## 贡献

提交问题时请附上 Android 版本、设备架构、复现步骤及相关日志。代码改动应保持 `./gradlew --no-daemon assembleRelease` 构建通过，并避免提交 API Key、签名私钥或其他敏感信息。
