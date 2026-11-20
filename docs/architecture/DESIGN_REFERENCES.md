# 0.1.7 定位改进参考

用户建议参考的项目为 [ccch1mneyyy/dsh-Go](https://github.com/ccch1mneyyy/dsh-Go)。本次阅读其文档与实现，借鉴观察与操作绑定、目标编号、原生控件与本地 OCR 结合、操作前重新验证的设计，未复制其源码。该项目使用 AGPL-3.0，未来若引入源码须按其许可证处理。

- [ScreenFormat.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/a11y/ScreenFormat.kt)：屏幕编号、元素编号、目标指纹与执行前校验。
- [UiTree.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/a11y/UiTree.kt)：原生节点、屏幕像素矩形与密码字段处理。
- [LocalOcrVisual.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/a11y/LocalOcrVisual.kt)：同一次截图的本地文字识别，区分文字框与可点击控件。
- [CoordinateDispatch.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/a11y/CoordinateDispatch.kt)：一次动作一次分发。

本项目目前采用简化的 N/V 目标编号、文字/来源/角色/状态/位置匹配与键盘状态检查，并非 DSH Go 的完整实现。仍保留对无标签图标的截图坐标操作，因此无法保证任意 App 的全部操作准确。像素检查只判断截图是否发生明显变化，不能证明所选位置符合用户意图。

本地 OCR 使用 Google ML Kit 随 APK 打包的中文识别模型，不依赖运行时下载，支持识别中英文。参考 [ML Kit Android 文本识别](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)。OCR 在手机本地运行；当前屏幕图片和目标文字会随用户启动的 AI 任务发送到用户配置的模型服务。

DeepSeek 的动作请求采用指定函数工具调用，并按[官方工具调用说明](https://api-docs.deepseek.com/zh-cn/guides/tool_calls/)关闭思考模式。该传输格式不能替代本地校验；格式不符、多个调用和过期目标仍会拒绝执行。
