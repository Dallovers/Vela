# 0.2.4：在当前任务页面直接回答

2026-11-19。针对用户要求，不再将“AI 等你回答”仅处理为返回原 App 的提醒。

## DSH 参考

固定提交 95588b696d1f6981c711a1883fe8de3d93919dd0 的 [a11y/ControlOverlay.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/95588b696d1f6981c711a1883fe8de3d93919dd0/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/a11y/ControlOverlay.kt)。本地参考 work/reference/dsh-engine/a11y/ControlOverlay.kt，SHA-256 1cf327e44a57c5db250ef286e604a6d7c1d5e427a29ad6ffa4f4e90e11d5fe63。

源码说明任务控制在胶囊、展开卡片与收起形态间切换，确认/交接有独立卡片，编辑与截图隔离，任务归属和窗口清理受生命周期约束。MainScreenDriver 的编辑期间阻止自动执行机制也属于对照。这里是源码观察，没有声称本轮在 DSH 真机上新验过所有交接表单。

本项目继续 Kotlin/Compose 主界面，待答层使用原生 View + TYPE_ACCESSIBILITY_OVERLAY 独立实现同类行为，未复制 DSH 代码/品牌资源。不是复刻 DSH 全部悬浮层、拖动、动画、确认策略和后台屏幕。

## 实现位置和状态边界

- QuestionOverlay.kt：身份绑定 sessionId/questionId；问题、选项、解释、自由文本、收起/展开；原生输入法焦点；草稿保存；锁屏隐藏；触摸延迟门槛和过期控制校验。显示失败每个问题抑制重复尝试，避免错误日志造成状态刷新循环。
- PhoneAccessibilityService.kt：订阅 AgentState 显示/移除浮层，服务重连恢复待答，屏幕熄灭/解锁广播与配置变化清理；执行器拒绝待答期间动作，截图拒绝残留待答浮层。停止按钮和待答层的生命周期分开，运行 finally 不误删问题。
- AgentController.answerQuestion：先持久消费答复，失败不恢复；再清理浮层/草稿/通知，延迟重新观察。自动恢复为独立可取消 Job，stop 会取消。切换界面只更新状态，不启动 MainActivity。
- SessionModels.withAnswer：原任务必须仍为 NEEDS_USER，问题 ID 和会话必须匹配；空白/超长/重复/过期答复不消费。
- PracticeActivity 的跨应用只读练习构造同样的持久待答状态；恢复走同一个 runSession/capture，之后完成本地只读任务。它不调用模型、不点击外部应用，用于验证窗口和恢复路径。

没有新增普通悬浮权限，也没有改变用户已授予的无障碍设置。Android 的服务授权和系统窗口限制仍适用。

## 验证

[0.2.4 验收报告](../../reports/acceptance/v0.2.4/验收报告.md)区分候选/最终包、固定问题与模型行为、已验证及未验证分支。最终设备原页面为 com.android.settings，恢复截图 keyboard=false，待答浮层已移除；未在等待时自动操作，也没有额外外部发送。

完整产品范围继续按 CSV 与 P1–P5 记录；本轮优先解决在执行界面回答和恢复的闭环。
