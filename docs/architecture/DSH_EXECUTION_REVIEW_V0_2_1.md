# 0.2.1 执行引擎对照记录

2026-11-19。参考 DSH Go 提交 `95588b696d1f6981c711a1883fe8de3d93919dd0`，继续使用本项目 Kotlin/Compose，独立实现以下机制。原始参考源码保留在被 Git 忽略的 work/reference/dsh-engine/。此次是执行主链路审查，不代表已逐行读完或复刻整个产品。

| 参考模块 | 发现 | 本项目实现与边界 |
| --- | --- | --- |
| Actuator / MainScreenDriver | 原生控件点击、指纹复核；动作提交与业务结果分开 | 原生目标重新匹配包、窗口、viewId、语义和位置后 ACTION_CLICK；OCR 使用复核后的手势；原生失败不自动换坐标重试 |
| FocusInput / FocusCapturedInput33 / FocusReadback | 捕获原输入目标；输入一次并有限读回；结果未知不重试 | 截图记录编辑器身份，输入前比对；原生输入和输入连接均读回；不确定则暂停。尚非完整编辑器生命周期证明 |
| ScreenVerdict / ScreenDigest / collect | 无可读内容不等于未变化；列表遍历有上限 | 比较目标位置、键盘、画面采样；记住受阻方向；桌面可反向、最多 12 次；单任务 60 步；无可读目标不判定边界 |
| PhoneCore / SystemActions | 查应用与打开应用是独立系统能力 | 提供可启动应用目录和 open_app，不再必须盲翻桌面；仍需下一帧确认前台应用 |
| PhoneTaskDriver | 观察编号属于任务及生命周期；停止使旧动作失效 | 保留 generation + coroutine cancellation；任务切换重置模型工具历史；执行前再次检查取消和应用 |
| companion/prompt-context / session steer patch | 区分当前 owner 请求与历史上下文；明确更新归属 | 每帧重建 JSON 任务上下文；本任务最新答复优先，历史目标降为背景；“改做这件事”取消当前运行再启动新请求，“做完再处理”保留队列 |

## DeepSeek 协议

官方文档：[思考模式](https://api-docs.deepseek.com/guides/thinking_mode/)、[工具调用](https://api-docs.deepseek.com/guides/tool_calls/)、[视觉输入](https://api-docs.deepseek.com/guides/vision/)。2026-11-19 核实 deepseek-flash 支持图像和思考工具调用。

原实现硬编码 thinking disabled。现为默认开启，可在模型设置关闭；仅向官方 api.deepseek.com 发送 thinking / reasoning_effort。第三方兼容服务沿用其原协议，不假定也支持该参数。官方思考模式使用 tool_choice auto，原先强制工具与思考一起使用实测 HTTP 400；auto 后已完成只读屏与 open_app→再次观察→完成，以及微信多步工具调用。

任务内保留完整 assistant tool_calls + reasoning_content 和对应实际工具结果（无工具的 assistant 回合同样保留），只存在手机进程内存中，不显示或写日志。24 个完整交换或 100000 字符后整段重建观察检查点，避免截断半个工具交换；当前任务、历史结果摘要、近期执行结果和最新截图重新提供。停止、任务切换、恢复时重新建立，避免混入其他任务的推理。未提供流式思考展示。可配置的模型仍须支持视觉输入。

## 授权与许可

用户本轮明确授权向微信联系人测试联系人发送测试消息；该具体授权高于旧交接文档中禁止真实发送调试的默认约束。本次只发一条简短、明确标注的助手测试，不延伸为其他联系人或其他外部动作授权。私人微信截图仅保留在忽略目录，不作为公开比赛截图。

DSH 整体 AGPL-3.0，部分文件单独标注 MIT。本轮没有直接粘贴其代码/资源进入 app，参考的是机制与行为；不把本次优化称为全产品复刻完成。P1–P5 总路线继续有效。

## 审查源码

- [FocusInput.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/95588b696d1f6981c711a1883fe8de3d93919dd0/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/FocusInput.kt)，blob `6f471b0d10f6cf03c0395f03ec32890945584fa6`
- [VisualTargets.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/95588b696d1f6981c711a1883fe8de3d93919dd0/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/VisualTargets.kt)，blob `607d9ed7af5c30b906f825f313cf427d783b8d4a`
- [a11y/Actuator.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/95588b696d1f6981c711a1883fe8de3d93919dd0/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/a11y/Actuator.kt)，blob `444140b53efac20403abf5ddcf5eb22910bc0013`
- [a11y/FocusReadback.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/95588b696d1f6981c711a1883fe8de3d93919dd0/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/a11y/FocusReadback.kt)，blob `49a10286d51c4a9bc7c61435d00b7d285447a9fa`
- [a11y/CoordinateDispatch.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/95588b696d1f6981c711a1883fe8de3d93919dd0/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/a11y/CoordinateDispatch.kt)，blob `267edbba013d8bbfc90f1273f1a9e60be7c24c81`
- [a11y/ScreenVerdict.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/95588b696d1f6981c711a1883fe8de3d93919dd0/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/a11y/ScreenVerdict.kt)，blob `079df566f446a70a048858cf804e0dce5fc7d08f`
- [a11y/ScreenDigest.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/95588b696d1f6981c711a1883fe8de3d93919dd0/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/a11y/ScreenDigest.kt)，blob `29d44c2cd8c840aceab621545fe5292b37d647d5`
- [PhoneTaskDriver.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/95588b696d1f6981c711a1883fe8de3d93919dd0/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/PhoneTaskDriver.kt)，blob `c4698d6b1d38196cc813d46f4161862f488f0804`
- [SystemActions.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/95588b696d1f6981c711a1883fe8de3d93919dd0/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/SystemActions.kt)，blob `d98fd4d3dc5236c1344784db79bb48404384ed83`

另读同提交 PhoneCore.kt、MainScreenDriver.kt、FocusCapturedInput33.kt、runtime/bridge/dsh-go-bridge/lib/companion/prompt-context.js 与 runtime/patches/session-queue-steer-deletion.diff。DSH 部分模型会话实现由运行时依赖提供，此仓库补丁不等于依赖的完整源码。
