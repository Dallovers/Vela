# 0.2.3：对话体验与本机文本工作区

2026-11-19，继续使用 craft-anything 调查记录和 UI/UX 检查流程，保持本项目 Kotlin/Compose 独立实现。

## DSH 参考与差异

已有参考源码 work/reference/dsh：chat_screen.dart 的 _resend（约 830 行）展示在分支里重新发送的流程，sessions_state.dart 的 rename（约 852 行）刷新会话列表，session_detail_page.dart 区分轨迹/文件/进度/上下文，feature_catalog.dart 提供文件、模型等入口。此前的真机回复下可见复制/分享/朗读/分支/重试，本轮没有声称重新完整体验所有 DSH 页面。

本项目复制的是产品机制与常用交互模式，没有直接粘贴 DSH 源码/品牌资源。重新回答在新的纯文字分支执行，手机外部动作不提供一键重放；分支清除未来摘要，避免后续信息倒灌。切换聊天/手机助手须已空闲且所有请求完成或跳过，仅新消息采用新模式。

## 数据与生命周期

- SessionCodec schema 4 保存 folder/pinned，兼容旧版本默认空文件夹/不置顶；标题不影响 goal 和任务语义。
- ModelProfile 名字用于更新同名配置，最多 20 套；密钥仍单独 AES-GCM/Keystore 加密。读取损坏配置时保留原数据，写入失败不会当成成功。切换受全局运行状态限制，网络中的模型请求使用开始时的配置快照。
- ReplySpeech 使用系统 TTS，后台/切换对话/离开会话页时停止；支持停止和系统错误提示。实际音质和更多语音引擎不在本轮结论内。
- WorkspaceFiles 在应用私有 files/workspace 中工作，拒绝路径穿越和 AtomicFile 保留文件名，UTF-8 严格解码与 1 MB 上限；系统 SAF 只访问用户选择的导入/导出文件。导入副本不覆盖原文件，碰到同名工作区文件拒绝覆盖。
- 文件导入/导出/读取/编辑保存放在 IO 调度；新建空文件和本机小目录列表仍为同步小操作。页面内未保存草稿不保证系统杀进程后恢复。

## 复刻覆盖

本轮完成 P1 的回复操作/组织/配置/外观的一批能力，并开始 P2 文本文件切片；多工作区、文件作为附件给模型、终端/PTY/Linux、远程、扩展和其他平台继续。最新状态以 [功能 CSV](../roadmap/DSH_FEATURE_MATRIX.csv) 和 [验收报告](../../reports/acceptance/v0.2.3/验收报告.md) 为准。

Craft 静态证据和手动实机结果分开记录；不把包解析当作业务验收，也不把已有菜单算作终端/远程能力。继续工作先恢复 work/craft/dsh.cany 中的 plan/handoff，避免重做已完成的 APK 调查或测试发送。
