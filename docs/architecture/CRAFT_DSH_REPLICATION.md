# 使用 Craft Anything 推进 DSH 全产品复刻

2026-11-19。用户指定 https://github.com/storytold/craft-anything 的 skill；完整产品范围继续按 [43 项功能清单](../roadmap/DSH_FEATURE_MATRIX.csv) 与 [P1–P5 路线](../roadmap/DSH_FULL_PRODUCT_PLAN.md) 执行。

## 工具与来源

- 官方 release v0.6.1，Windows x64 portable；下载 SHA-256 `bd370fbaa2b44dbc13581db5ffd2bf66924ed40e433c7902212aa17ca781de54`，与官方 SHA256SUMS.txt 一致。
- CLI 位于 `tools/craft-anything/craft-anything-0.6.1-windows-x64-portable/craft-anything-cli.exe`。工具和分析数据在 E 盘；原生 setup 把 Codex 配置与 skill 安装到对应用户配置位置，并保留配置备份。
- 已读 skill：`%USERPROFILE%/.agents/skills/craft-anything/SKILL.md`；参考源码快照 `work/reference/craft-anything`，HEAD `6e11ce469f01e8c06fbffec6739d15bb3e3c03ad`。发布二进制与 main 快照分别记录，不能把 main HEAD 当作发布构建提交。
- 使用 Deep 参数读取用户要求研究的公开 DSH APK；本轮没有绕过登录或读取账户。skill 建议有源码先读源码，因此已保留 composer.dart / feature_catalog.dart / chat_screen.dart 等源码参考；工具补充 APK 元数据。
- 没有调用 rebuild 启动额外编码 agent；当前 agent 依据证据直接在既有 Compose 工程实现。不能把 CLI 安装成功当成全产品生成成功。

## 已有证据

调查工作区为 `work/craft/dsh.cany`，包含 brief、plan、findings、handoff、journal 与 evidence。每个 CLI 进程的活动 target 不自动保留，后续分析须显式传绝对 `--path`。

| 操作 | 证据 ID | 结论与边界 |
| --- | --- | --- |
| android_package_show | ev_520a5f3c51af5b1e6a58cdac6321f71681c3fe1cf5c01e6e60c83fc0a95fd030 | DSH Go 1.2.0 APK 包元数据；静态结构不证明页面行为 |
| android_flutter_identify | ev_9cfcad30e86e1439ebc4d8cb3bb68ab030609ab3b04ccfd4f61ebd52d5495631 | arm64-v8a 含 libflutter.so / libapp.so；未提供确定的 SDK 版本映射 |

APK 固定哈希：40c2d2612533f28fef2c0bb4acf3cb80d6d45bfede405c21e73dcce613aed718。完整证据 JSON 保留在本地验收 checks；本轮发现 fnd_1 已关联上述 ID。0.2.2 自身验证另见 [验收报告](../../reports/acceptance/v0.2.2/验收报告.md)，不把 DSH 静态证据当成本项目运行证据。

## 下一步的交付标准

1. P1 继续补消息朗读/分支/重试、文件夹、主题、多模型和手机确认/应用规则；每个入口对应真实行为。
2. P2 从受限工作区的文件导入、预览、编辑和导出开始，再接 PTY/Linux 生命周期和后台任务。需验证路径边界、取消、重启与失败。
3. P3 研究 DSH SSH/Relay 协议和配对认证，提供自己的主机隔离与连接生命周期；需要目标主机/账号的实际验收时才请求用户提供环境，不虚构已连接。
4. P4 分解技能/插件协议、团队/自动化、语音/媒体、机器人、桌宠/社区。远端服务、账号体系和第三方凭据不能靠 UI 外观代替。
5. P5 先完成 Android 回归，再实际构建与测试其他平台。各项在 CSV 分开标明“源码确认/入口可见/已实现/真机通过”。

研究、实现、检查、更新缺口是持续流程。已有 DSH 页面证据只覆盖此前实际体验范围；43 行不表示所有隐藏功能已穷尽。全产品完成条件是逐项真实实现和验收；本轮未满足这个完成条件。

DSH 原仓库 AGPL-3.0、部分文件 MIT。本轮保留独立实现，不把品牌素材和源码搬入应用；以后若直接集成其引擎，必须处理实际文件许可与分发要求。
