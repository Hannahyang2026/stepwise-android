# 一步 · Stepwise Android

一步是一个用 Kotlin 和 Jetpack Compose 编写的 Android App，将日历、待办、习惯和长期学习目标安排到每天。手动日程、待办和打卡支持离线使用。

- **近日和日程**　查看双日时间轴和按日期分组的事项，手动新增、编辑和完成待办，批量筛选 AI 生成的日程。
- **目标**　为习惯、倒计时和自学计划记录进度。
- **智能体**　使用自己的兼容 Chat Completions 接口进行聊天或生成可编辑的日程草稿，确认后写入日历。支持 Markdown 流式回复、Agent Skills 和远程 MCP。

## 安装

在 Android 8.0 或更新版本上下载并打开官方签名 APK。2.1.18 与 2.1.17 使用同一个签名，已在 Android 16 模拟器验证覆盖升级后会话和草稿可读回。 [下载 APK](https://github.com/Hannahyang2026/stepwise-android/releases/download/v2.1.18/stepwise-2.1.18.apk) · [2.1.18 更新说明](docs/release-2.1.18.md)

## 使用 AI

在「设置 → API 配置」填写服务商地址、模型和 API 密钥。发起 AI 请求时，相关输入和所用上下文会发送到你配置的服务商。API 密钥和 MCP 令牌加密保存在设备上。AI 费用由你配置的服务商账户结算。

## 从源码构建

准备 JDK 17、Android SDK 36 和 Build Tools 35.0.0。使用 Android Studio 打开仓库根目录，或设置 `JAVA_HOME` 和 `ANDROID_HOME` 后在根目录运行以下命令。

```sh
# 编译可调试安装包，运行单元测试并检查发布版。
bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintRelease
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，使用本机调试签名。覆盖官方版时请使用 Release 页提供的同签名 APK。

## 许可

一步源码使用 MIT 许可证。Gradle Wrapper 和其他第三方组件沿用其各自的许可。 [MIT](LICENSE) · [第三方许可](licenses/README.md)
