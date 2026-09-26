IQ Code Android

IQ Code Android 是一款运行在 Android 手机上的原生 AI 编程 Agent。

它将 AI 编程对话、项目文件管理、终端环境、任务执行以及 Android 应用沙箱整合到一个应用中，让你无需依赖电脑，也可以直接使用手机阅读项目、编写和修改代码、执行命令，并对修改后的程序进行构建和验证。

主要功能

- 多轮 AI 编程对话与流式回复
- 读取、搜索、创建、编辑和管理项目文件
- 内置 ARM64 Termux/Bionic 运行环境与原生终端
- Bash 命令执行
- 任务管理与计划审批
- 支持 Anthropic Messages API
- 支持 OpenAI Chat Completions API
- 支持 OpenAI Responses / Codex API
- API 地址、API Key、模型及推理强度配置
- 权限模式管理
- 会话保存、恢复与分支
- 上下文压缩
- MCP stdio 与 Streamable HTTP 工具接入
- 子 Agent
- 后台任务
- 实时任务执行进度
- 基于 BlackBox 的 IQ Sandbox
- 支持在沙箱中安装、运行和调试独立 APK
- 深色、浅色以及自定义界面主题

项目结构

app/src/main/java/com/termux/app/iqcode/

Agent 核心、模型提供方、工具、会话、任务和 MCP 等相关代码。

app/src/main/java/com/iqge/

Android 应用界面、终端以及系统集成相关代码。

app/src/main/res/

Android UI 与其他资源文件。

app/src/main/assets/bootstrap-aarch64.zip

离线 ARM64 Termux 用户空间。

app/src/main/jniLibs/arm64-v8a/libtermux.so

原生 PTY 桥接库。

app/libs/iqcode-termux-compat.jar

Termux 安装及路径兼容相关二进制组件。

Bcore/

IQ Sandbox 使用的 BlackBox Android 运行层。

black-reflection/
compiler/

BlackBox 反射以及注解处理相关模块。

构建要求

构建 IQ Code Android 需要：

- JDK 17 或 JDK 21
- Android SDK
- Android SDK Compile SDK 35
- 对应版本的 Android Build Tools
- Android NDK
- Bash 环境
- ARM64 Android 设备作为主要运行目标

默认 Android SDK 路径：

$HOME/android-sdk

也可以通过以下环境变量指定 SDK：

export ANDROID_SDK_ROOT=$HOME/android-sdk

或者：

export ANDROID_HOME=$HOME/android-sdk

构建 APK

首先确保 Gradle Wrapper 具有执行权限：

chmod +x gradlew

然后执行：

./gradlew --no-daemon assembleRelease

构建完成后，Release APK 位于：

app/build/outputs/apk/release/IQCode-release.apk

API 配置

IQ Code Android 本身不会预置模型服务的 API 凭据。

首次使用时，需要在应用的 API 设置中配置：

- API 服务地址
- API Key
- 模型名称
- API 协议

支持的协议包括：

- Anthropic Messages
- OpenAI Chat Completions
- OpenAI Responses / Codex

API Key 等敏感信息应妥善保管，不要将其提交到 Git 仓库。

源码与二进制组件

项目的主要 Agent 逻辑、Android UI、工具系统、模型接入以及 Sandbox 集成代码均以源码形式提供。

部分底层组件以预编译形式随项目分发，包括：

- Termux 前缀转换相关组件
- Termux 安装兼容实现
- 原生 PTY 支持
- ARM64 Termux 用户空间

这些组件用于保证项目能够直接完成 Gradle 构建并在 Android ARM64 环境中运行。

当前构建目标：

ABI: arm64-v8a
最低 Android 版本: Android 7.0 (API 24)

安全说明

IQ Code Android 具有执行终端命令、读取和修改项目文件以及运行沙箱应用等能力。

使用过程中请注意：

- 仅连接可信的 AI 模型服务
- 不要向未知模型服务提供敏感信息
- 执行高风险命令前仔细检查命令内容
- 不要在不可信项目中随意开启跳过权限确认的模式
- 不要将 API Key、Token、密码或签名私钥提交到仓库
- 使用第三方 MCP Server 前确认其来源和权限范围

AI Agent 生成的代码和命令也应该在执行前进行必要的检查。

贡献

欢迎提交 Issue、Pull Request 以及其他形式的贡献。

提交问题时，建议附带以下信息：

- Android 版本
- 设备型号
- CPU / ABI 架构
- IQ Code Android 版本
- 问题复现步骤
- 相关日志
- 必要的截图

提交代码前请尽量确认：

./gradlew --no-daemon assembleRelease

能够正常完成构建。

请不要在 Pull Request 或 Issue 中提交：

- API Key
- Access Token
- 密码
- 签名私钥
- 个人隐私数据
- 其他敏感信息

赞助

IQ Code Android 是一个持续开发中的开源项目。

如果你觉得这个项目对你有帮助，或者希望支持后续的功能开发，可以通过爱发电为项目提供支持。

你的每一份支持都会成为项目继续维护和完善的动力，也会帮助我们投入更多时间进行新功能开发、问题修复以及 Android AI 编程体验优化。

如果你愿意支持 IQ Code Android，可以前往：

爱发电：

https://afdian.com/a/IQ_ge

感谢所有关注项目、提交 Issue、贡献代码、Star 项目以及提供赞助的朋友 ❤️

License

本项目的具体许可证信息请以仓库中的 License 文件为准。