# DSH Go 产品与界面参考索引

2026-11-18 通过 GitHub 读取目录和相关源文件确认：DSH Go 前端使用 Flutter/Dart，位于 `app/lib/`；Android `MainActivity.kt` 继承 `FlutterActivity` 并连接手机控制等原生插件。不要误把其原生桥接文件当作 Compose UI。

本项目继续使用已有 Kotlin/Compose。以下文件用于理解产品信息结构、用户参与和会话归属，不复制源码或资源。

| 想研究的部分 | 已确认存在的入口 |
| --- | --- |
| 主聊天页面与当前会话 | [chat_screen.dart](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/lib/ui/chat/chat_screen.dart) |
| 会话详情与上下文/进度区域 | [session_detail_page.dart](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/lib/ui/session/session_detail_page.dart) |
| 会话状态和列表 | [sessions_state.dart](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/lib/state/sessions_state.dart) |
| 手机控制配置和任务控制 | [phone_control_screen.dart](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/lib/ui/phone/phone_control_screen.dart) |
| 用户确认回复的运行归属 | [phone_approval.dart](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/lib/phone/phone_approval.dart) |
| 模型 provider 页面 | [ui/providers](https://github.com/ccch1mneyyy/dsh-Go/tree/main/app/lib/ui/providers) |
| 设置与外观页面 | [ui/settings](https://github.com/ccch1mneyyy/dsh-Go/tree/main/app/lib/ui/settings) |
| 应用原生桥接 | [MainActivity.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/MainActivity.kt) |
| 用户对话、模型与手机控制指南 | [docs/guide/zh](https://github.com/ccch1mneyyy/dsh-Go/tree/main/docs/guide/zh) |

本次浏览确认会话详情文件包含 progress/context 等分区的说明，phone_control_screen 有 task 关联控制与确认模式入口，phone_approval 明确将确认回复约束到提出问题的 generation。下一 agent 应进一步阅读依赖和具体行为，不能仅因某个文件名存在就声称对应功能在本项目已经完成。

下一阶段值得借鉴的方向：同一会话内组织任务和结果、将当前任务/待处理问题放在醒目位置、保持设置与执行页面职责清楚、用户回答和确认绑定当前任务/运行、在不同尺寸下提供可理解的详情区域。

DSH Go 仓库整体标注 AGPL-3.0，个别文件有单独许可证声明。本文只是参考索引；若将来实际复用代码/资源，必须按具体文件和整体组合方式审查许可与署名义务。
