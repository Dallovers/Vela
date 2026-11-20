# Vela 协作约定

先读 [贡献指南](CONTRIBUTING.md)，再看 [验收索引](reports/acceptance/README.md) 获取当前版本与验证范围。

任何 `versionName` 或 `versionCode` 的变更，都要在同一个 PR 新增或更新 `reports/acceptance/v<versionName>/验收报告.md`。按模板写实测结果和没有跑到的范围，更新验收索引。检查通过后走 PR 合并到 `main`。

构建与检查命令见贡献指南。只提交源码、构建配置、合成测试数据和可公开文档。签名密钥、个人设备数据、真实账号内容与原始诊断日志保存在本地。
