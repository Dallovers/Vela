# 0.1.9 界面设计与参考

2026-11-19。按用户要求参考 DSH Go 的页面结构，保持 Kotlin/Compose。

## 研究范围

查看 DSH Go 官方 README 中 f-phone-zh-light.webp、f-adaptive-zh-light.webp、f-steer-zh-light.webp，并阅读本地参考缓存中的 chat_screen.dart、session_detail_page.dart、phone_control_screen.dart、sessions_state.dart、phone_approval.dart。设备未安装 DSH Go，本轮是官方截图/源码交互分析，不能称为实际运行体验。

参考链接：
- https://github.com/ccch1mneyyy/dsh-Go
- https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/lib/ui/chat/chat_screen.dart
- https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/lib/ui/session/session_detail_page.dart
- https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/lib/ui/phone/phone_control_screen.dart
- https://github.com/Leonxlnx/taste-skill/blob/main/skills/taste-skill/SKILL.md
- https://github.com/elayadesign/ai-design-skills/blob/main/skills/landing-page-design/SKILL.md

用户提到的 taste skill 对应 Leonxlnx/taste-skill，AI Design Skills 对应 elayadesign/ai-design-skills。已阅读规则并仅将适用的层级、间距、内容真实性和交互反馈原则用于原生应用；它们的网页 hero、营销文案、浏览器脚本等不适用于本轮。没有全局安装或执行第三方脚本。DSH Go 只借鉴交互概念，未复制源码、图片、图标或品牌文字到应用。

## 设计方案

产品：会观察屏幕、逐步执行并能暂停询问的手机助手。用户的主要动作是在会话中安排任务，而非编辑一整页配置。

色板：底色 #F4F6FB，表面 #FEFEFF，正文 #202634，次级文字 #606A7D，主操作 #4965AC，浅蓝强调 #E5EBFB。中文使用系统原生字体与 Material type roles，尊重用户字号；正文 bodyLarge，说明 bodyMedium/bodySmall，页面标题 titleLarge，入口 headlineMedium。8/12/16/24 dp 间距；12/16 dp 圆角按层级使用。

结构：紧凑顶栏 → 会话标题/状态 → 对话、计划、上下文 → 独立滚动内容 → 底部固定输入与执行区。会话列表移入全高抽屉；模型、手机控制和日志经设置菜单进入。新会话先输入目标，逐行拆为计划，创建后显式开始。已有任务采用用户目标气泡和步骤时间线；待答问题优先显示，选项和自由文本仍归属对应问题。计划编辑进入独立对话框，不再让所有任务默认都是输入框。上下文独立成页。

手机控制使用分组行，保留真实连接状态，服务断开时提供系统设置入口。未新增 DSH 的确认模式或应用黑名单假控件。

## 真机修正

OPPO 大字体/1080×2344 下首轮 IME 使用默认窗口平移，导致标题栏被挤出；补 adjustResize 并限定安全区为顶部/水平，另处理导航栏与键盘。复测标题、输入框与提交按钮均可见，输入区贴合键盘上沿。

本轮没有调整手机字体、授权无障碍或启动真实外部任务。已有会话/通知截图存私人证据；公开材料只用新建的本地测试计划。

## 尚待完善

深色模式、平板常驻双栏、待答/运行全状态真机回归、会话搜索/筛选、逐会话持久输入草稿和更细的动效仍需后续实现。当前会话内容是结构化目标/任务/结果，没有伪造完整模型聊天历史。
