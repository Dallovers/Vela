# 项目资料索引

当前开发版：Vela 0.3.6；历史验收基线为 0.1.7。工作区：`E:\AndroidPhoneAgent`。

最新入口：[0.3.6 后台文件读取验收](../reports/acceptance/v0.3.6/验收报告.md)。历史入口：[0.3.5 待答浮层验收](../reports/acceptance/v0.3.5/验收报告.md)。语音听写：[0.3.4 语音听写验收](../reports/acceptance/v0.3.4/验收报告.md)。文件工作区：[0.3.3 文件工作区验收](../reports/acceptance/v0.3.3/验收报告.md)。历史入口：[0.3.1 品牌更新](../reports/acceptance/v0.3.1/验收报告.md)、[图标与生成提示词](design/branding/CONCEPT.md)。执行能力参考：[0.3.0 验收](../reports/acceptance/v0.3.0/验收报告.md)、[工作区与中断恢复](architecture/WORKSPACES_RECOVERY_V0_3_0.md)。历史入口：[0.2.1 验收报告](../reports/acceptance/v0.2.1/验收报告.md)、[DSH 执行源码对照](architecture/DSH_EXECUTION_REVIEW_V0_2_1.md)、[完整产品分阶段路线](roadmap/DSH_FULL_PRODUCT_PLAN.md)、[43 类功能对照表](roadmap/DSH_FEATURE_MATRIX.csv)、[0.2.0 设计与状态](architecture/DESIGN_V0_2_0.md)。

## 从这里开始

1. 新 agent 先读 handoff/CURRENT_STATE.md，确认工程、设备和已验证范围。
2. 阅读 [docs/roadmap/OPTIMIZATION_PLAN.md](roadmap/OPTIMIZATION_PLAN.md)，理解后续产品方向与分阶段开发边界。
3. 将 handoff/NEXT_AGENT_PROMPT.md 作为新 agent 的完整启动提示词。
4. 评估当前版本时读 [reports/acceptance/README.md](../reports/acceptance/README.md) 及 [reports/acceptance/v0.1.7/验收报告.md](../reports/acceptance/v0.1.7/验收报告.md)。

## 目录结构

| 路径 | 用途 |
| --- | --- |
| `app/` | Android Kotlin/Compose 应用源码和自动化测试 |
| `artifacts/apk/current/` | 当前已验收安装包 |
| `artifacts/apk/archive/` | 旧版本 APK，按版本留档 |
| `cache/`、`tools/` | E 盘构建工具和缓存，不纳入比赛材料 |
| `scripts/` | 工具链设置、下载、检查及文件归档脚本 |
| `docs/planning/` | 最初项目计划等历史规划 |
| `docs/architecture/` | DSH Go 参考分析与当前架构约定 |
| `docs/roadmap/` | 接下来要实现的功能和验收顺序 |
| `docs/presentation/` | 比赛汇报简介、演示流程和答辩问题 |
| `handoff/` | 给新 agent 的项目状态与启动提示词 |
| `reports/acceptance/v0.1.7/` | 单独的 0.1.7 验收报告和可分享的练习证据 |
| `reports/acceptance/history/` | 0.1.0 至 0.1.7 累积验证历史 |
| `reports/acceptance/evidence/private/` | 设备原始采集记录和截图，可能包含个人内容，不放进比赛材料 |
| `reports/builds/` | 版本构建日志和诊断信息 |
| `work/` | 可再生成的临时下载、SDK 安装索引、构建临时文件和缓存辅助文件 |

## 归档说明

2026-11-18 将历史文件从 `PROJECT_PLAN.md`、`artifacts/`、`work/` 按主题归档。迁移前后 SHA-256 一致的清单为 FILE_MIGRATION_2026-11-18.csv。原始证据和构建诊断默认由 `.gitignore` 排除；不要将私人聊天截图提交到公开比赛仓库。

## 常用命令

```powershell
cd E:\AndroidPhoneAgent
.\build.ps1 -Tasks @('assembleDebug','testDebugUnitTest','lintDebug')
```

最新 APK：`E:\AndroidPhoneAgent\artifacts\apk\current\PhoneAgent-0.2.1-debug.apk`。


## 2026-11-18 0.1.8 更新

当前开发版 0.1.8 已增加持久会话、任务计划、询问/答复恢复。0.1.7 APK 保留在 artifacts/apk/archive，当前包为 artifacts/apk/current/PhoneAgent-0.1.8-debug.apk。新版本验收报告位于 reports/acceptance/v0.1.8/验收报告.md，实现边界见 docs/architecture/SESSION_V0_1_8.md。已建立仅本地 Git 仓库。旧版描述保留为历史基线。


归档更正：首次同版本构建覆盖了原 0.1.7 APK；原源码与验收证据保留，独立重建包为 artifacts/apk/archive/PhoneAgent-0.1.7-rebuilt-debug.apk，并非原验收哈希。详情见 reports/acceptance/v0.1.8/验收报告.md。


2026-11-19：当前 0.1.9 为界面改版，APK 在 artifacts/apk/current；布局改动和验证边界见 docs/architecture/DESIGN_V0_1_9.md、reports/acceptance/v0.1.9/验收报告.md。无障碍服务本轮未连接，仅验 UI，不声称复验设备执行。

- [Craft Anything 复刻记录](architecture/CRAFT_DSH_REPLICATION.md)
- [0.2.2 验收](../reports/acceptance/v0.2.2/验收报告.md)

- [0.2.3 架构](architecture/DESIGN_V0_2_3.md) · [0.2.3 验收](../reports/acceptance/v0.2.3/验收报告.md)

- [0.2.4 当前页面答复设计](architecture/QUESTION_OVERLAY_V0_2_4.md) · [验收报告](../reports/acceptance/v0.2.4/验收报告.md)
