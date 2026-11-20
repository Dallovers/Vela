# 参与 Vela 开发

登录 GitHub，在 [Dallovers/Vela](https://github.com/Dallovers/Vela) 点 **Code → Download ZIP** 下载源码。需要持续协作时，用 Git 克隆。

```powershell
git clone https://github.com/Dallovers/Vela.git
cd Vela
```

公开仓库可以直接下载。已有写权限的队友可推送自己的功能分支并开 PR。其他协作者在 GitHub 点 **Fork**，从自己的仓库提交 PR 到 `Dallovers/Vela:main`。

## 构建和安装

安装 JDK 21 和 Android Studio。用 SDK Manager 安装 Android SDK Platform 35、Build-Tools 35.0.0 和 Platform-Tools。用 Android Studio 打开克隆后的根目录即可同步项目。首次构建需要联网下载 Gradle 依赖和离线听写模型。模型文件由构建任务校验后打包。

在已配置 `JAVA_HOME` 和 `ANDROID_HOME` 的 PowerShell 中运行：

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

macOS 或 Linux 使用 `bash ./gradlew assembleDebug testDebugUnitTest lintDebug`。生成的 APK 在 `app/build/outputs/apk/debug/app-debug.apk`。把 APK 传到 Android 手机安装，也可以在手机开启 USB 调试并授权后运行 `adb install -r app/build/outputs/apk/debug/app-debug.apk`。若设备上的安装签名与本机的调试签名不同，Android 会拒绝覆盖。保留原安装数据时，先向队友取得同签名的测试包。

模型地址和 API Key 在 App 内配置。仓库里的历史报告可以帮助定位版本。历史 APK、设备截图与私有日志由原开发工作区保管。

## 升版本必须提交验收报告

- 在 `app/build.gradle.kts` 更新 `versionName` 和／或 `versionCode` 时，同一个 PR 必须新增或更新 `reports/acceptance/v<versionName>/验收报告.md`。升版本建议同步递增 `versionCode`。
- 按 [验收报告模板](reports/acceptance/验收报告模板.md) 记录改动、实际运行的检查、真机结果、未验范围和 APK SHA-256。跳过的检查写明原因。
- 更新 [验收索引](reports/acceptance/README.md)，把 PR 链到对应版本报告。等待 **Version acceptance report** 检查通过再合并。
- 提交前检查 diff。模型密钥、签名材料、私人录音、聊天截图、手机导出的原始日志、构建缓存和 APK 均留在本地。
