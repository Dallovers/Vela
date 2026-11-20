# 开发辅助脚本

所有脚本在 `E:\AndroidPhoneAgent\scripts`，根目录按 `Path(__file__).resolve().parents[1]` 获取。下载包仍在 `work/downloads`，仓库描述 XML 在 `work/repository.xml`。

| 脚本 | 用途 |
| --- | --- |
| `setup_toolchain.py` | 下载/安装本项目 JDK、Gradle、Android 命令行工具 |
| `download_sdk_ranges.py` | Android 命令行工具的分段下载辅助 |
| `install_sdk_packages.py` | SDK platform/build tools 的安装辅助 |
| `prefetch_lint.py` | 补充 lint Kotlin 编译器下载 |
| `check_deepseek.py` | 从 stdin 读取临时密钥，检查模型列表；不要把密钥写在命令行中 |
| `test_deepseek_vision.py` | 测试一张人工蓝色图片的模型输入能力 |
| `organize_project_files.py` | 本次一次性文件归档，已执行；发现迁移清单后拒绝再次执行 |
| `check_project_organization.py` | 只读检查归档哈希、文档链接和 0.1.7 测试证据完整性 |

日常构建优先使用根目录 `build.ps1`。不要为验证归档操作重复下载 SDK 或重新询问用户密钥。
