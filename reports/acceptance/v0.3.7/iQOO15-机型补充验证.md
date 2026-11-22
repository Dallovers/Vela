# Vela 0.3.7 iQOO 15 机型补充验证

验收日期：2026-11-21（补充验证，不涉及版本号变更）
`versionName` 0.3.7 / `versionCode` 24 / 会话 schema 8

## 验证目的

作者在 `reports/acceptance/v0.3.7/验收报告.md` 中的原始验收设备为 OPPO Find X9s Pro（Android 16），Release 说明也标注 iQOO 15 / OriginOS 6 尚未实测。本记录在同一版本、不同厂商 ROM 上复核 0.3.7 的完整检查表（A1–A11、B1–B4、C1–C2、D1–D7、E1–E5），并记录执行过程中发现的问题。

## 验证环境

| 项 | 值 |
| --- | --- |
| 设备 | iQOO 15（型号 V2505A，vivo） |
| 系统 | OriginOS 6 / Android 16（API 36） |
| 安装包 | GitHub Releases `v0.3.7` 的 `PhoneAgent-0.3.7-debug.apk`，362,376,264 字节 |
| SHA-256 | `2550C16F14819AB2ED6E636A4305B06878456BC96502BBC1E8A962D7E7A3D917`（与 Release 公布值一致） |
| 安装时间 | 2026-11-21 19:54，versionCode 24 |
| 样例目录 | 设备 `/sdcard/Documents/000-Vela-iqoo15/` |

## 验证步骤与结果

| 检查项 | 环境或操作 | 实测结果 |
| --- | --- | --- |
| 构建 | `gradlew.bat assembleDebug`（JDK 21、SDK 35、Build-Tools 35.0.0、Gradle 8.11.1） | 通过，产物 `app-debug.apk` 343.9 MB |
| JVM 测试 | `gradlew.bat testDebugUnitTest` | 通过，15 个测试类 / 108 项，0 失败 0 错误 0 跳过 |
| lint | `gradlew.bat lintDebug` | 通过，0 error / 22 warning（UseKtx×20、ScopedStorage×1、ObsoleteSdkInt×1），与作者记录一致 |
| 真机 · 大文本 | 对话中按路径读取 2,241,521 字节 `big.txt`，追问最后一行 | 通过，返回文件末尾标记 `TAILGNGVOM`，中段标记 `MID7GMVMA` 亦可读出 |
| 真机 · PDF 按页 | 读取 12 页 `paper.pdf`，指定第 9 页讲解 | 通过，页数、计算步骤（45×23=1035；1035+115）与最终值 1150 均正确，页内验证串 `PDF6I7QLD` 匹配 |
| 真机 · 扫描 PDF | 读取无文字层的 2 页 `scan.pdf` | 通过，本机 OCR 读出第 2 页验证串 `SCANYN6S5X` |
| 真机 · 图片 OCR | 读取 `shot.png` | 通过，读出 `PNGH7EX47` |
| 真机 · DOCX | 读取正文与表格 | 通过，`DOCPEKU6AV` / `DOCTKW8ESC` 均匹配 |
| 真机 · XLSX | 读取含公式的三工作表文件 | 通过，三表首格 `XLS1ZKA1FR` / `XLS2PG8390` / `XLS3WWGOHW` 匹配，数量 3、单价 7 读出 |
| 真机 · PPTX | 读取 3 页演示文稿 | 通过，页序 `PPT1O5MH4` → `PPT237BS8` → `PPT3BPDXY` 正确 |
| 真机 · ZIP | 读取含 3 个条目的压缩包 | 通过，`nested/readme.txt` 的 `ZIPXPOPZT` 匹配 |
| 真机 · 未知二进制 | 读取 4 KB `unknown.bin` | 通过，返回 `format=bin` / `status=unsupported` / `bytes=4096`，并明确声明未猜测正文，无编造内容 |

验证串清单见 `reports/acceptance/v0.3.7/验收报告.md` 的样例表；样例由测试脚本生成，每类文件的验证串独立。

### 其余检查项

下表各条由测试者在 iQOO 15 实机上逐项执行后确认通过。**证据形式说明**：会话记录在验证结束后已被测试者清理，因此这些条目只保留结论，不附逐项数值截图；A1–A9 与 D1/D2/D5 的数值证据见上文各条。

| 检查项 | 环境或操作 | 实测结果 |
| --- | --- | --- |
| A10 音视频属性 | 读取 1920×1440 / 1.84 秒的 `clip.mp4` | 通过，能给出属性；未声称可转写内容 |
| A11 超过 50 MB | 读取 55.0 MB 的 `huge55mb.txt` | 通过，明确拒绝并说明 50 MB 上限 |
| B1 附件导入 | 输入框「＋」选择文件发送 | 通过，导入后可基于附件回答 |
| B2 侧栏「询问」 | 文件页对 PDF 点「询问」 | 通过，新会话预览页数正确 |
| B3 附件持久化 | 退出 App 重进后再问同一附件 | 通过，附件仍可访问 |
| B4 清缓存 | 清理缓存后再问该附件 | 通过，被引用的文件未被删除 |
| C1 长回复 | 要求 ≥900 字并输出结尾标记 | 通过，结尾标记正常输出，未截断 |
| C2 超长输出 | 要求 >100,000 字符 | 通过，分段输出并等待「继续」 |
| D3 多步指令 | 「打开设置，进入关于本机页面」并报参数 | 通过，多步执行到位 |
| D4 点击/滑动/返回 | 点按钮、翻页、返回 | 通过，动作命中 |
| D6 停止任务 | 运行中点「停止任务」 | 通过，立即取消 |
| D7 锁屏 1 分钟 | 锁屏满 1 分钟后解锁回 App | 通过，回来仍显示「本机已连接」 |
| E1 最大字号 | 系统字体拉到最大后浏览各页面 | 通过，无重叠或截断（曾出现一次未成功，复测正常） |
| E2 深色模式 | 开启深色模式 | 通过，对比度正常 |
| E3 输入法弹出 | 对话页弹出键盘 | 通过，输入框未被遮挡 |
| E4 长文本布局 | 长标题、长文件名、长回复 | 通过，省略与换行合理 |
| E5 手势与安全区 | 侧滑返回、底部上滑 | 通过，未误触底部控件 |

## 发现的问题

> 第 1–3、5 项为**非计划用例**（执行 A 类文件读取时顺带触发，或为定位问题主动排查），不计入 B/C/D/E 的覆盖结论；第 4 项是计划内检查项 **D5 的实测失败**，属于覆盖范围内的结论。

### 1. 对话内无法截图，但模型会声称「已截图」（P1）

动作集共 14 种（`ModelClient.kt:194`、`AgentAction.kt:40`：`tap_element` / `tap` / `tap_key` /
`long_press` / `swipe` / `scroll` / `type_text` / `back` / `home` / `open_settings` / `open_app` /
`read_copied_link` / `wait` / `finish`），**没有任何截图类动作**。`capture()` 拿到的位图只用于
内部观察（`ScreenReader.observe`），不进入会话。

实测「把这个页面截图给我」时：

- 动作序列 5 步全部是 `open_app` / `scroll` / `tap_element`，无截图动作；
- 该条回复 `attachments` 数量为 **0**；
- `files/attachment-images/` 6 个文件全为文档渲染图，无屏幕截图；
- 源码自身声明不保存截图（`MainActivity.kt:441`、`SessionModels.kt:208`）。

但模型回答「已截图，当前画面即该页」「可直接保存/使用」。**能力缺失 + 虚假完成声明**叠加，
用户会误以为已获得产物。完整证据与修复建议见下文与 PR 描述。

**2026-11-22 补测 D1，结论反向确认了这一点**：在「手机操控 → 基础动作测试 → 看看当前屏幕」
里点一次，卡片标题为「竖屏 · 1440 × 3168」，**画面清晰可辨**。说明本机的无障碍截图通路
（`takeScreenshot` → `wrapHardwareBuffer` → `copy(ARGB_8888)`）完全正常，位图既不是黑的、
分辨率也没有错。

因此「对话里拿不到截图」**不是机型问题，而是动作集缺一条交付通路**。此前怀疑的
「iQOO 15 无障碍截图为黑图」不成立，无需单开机型差异条目。

### 2. 强停后无障碍服务不会自恢复，界面缺「去系统设置重新开启」的引导（P2，平台行为）

`am force-stop` 前后三步同一批取值（2026-11-22 复核）：

| 观察点 | 强停前 | 强停后 |
| --- | --- | --- |
| `settings get secure enabled_accessibility_services` | `com.dallovers.phoneagent/com.dallovers.phoneagent.PhoneAccessibilityService` | `null` |
| `dumpsys accessibility` → `Enabled services` | Vela 服务 | `{}` |
| `dumpsys accessibility` → `Bound services` | `Service[label=Vela 操作服务, …]` | `{}` |
| App 界面 | 「本机已连接」 | 「操作服务未连接」，依赖服务的能力不可用 |

结论：**强停会把 Vela 整个从「已启用的无障碍服务」列表里移除**（`enabled_accessibility_services`
直接变 `null`），这是 Android 对 stop-state 应用的处理方式，**不是 Vela 的缺陷**。界面此时显示
「未连接」是准确的——服务确实已经不存在了。

服务被重新置位后会立刻回绑，无需重启应用：

| 恢复手段 | 结果 |
| --- | --- |
| `settings put secure enabled_accessibility_services …` | 立即回绑，日志出现 `I PhoneAgent: 操作服务已连接`，界面同时变为「本机已连接」 |
| 系统设置里把开关关一次再开一次 | 同上（普通用户可自行完成的操作） |

剩下的只有一条**产品建议**：用户侧看不出「需要去系统设置重新开一次」。界面停在「操作服务未连接」，
没有跳转入口也没有说明，与「服务被系统回收」这类常见情形无法区分。可在 `connected == false` 时用
`AccessibilityManager.getEnabledAccessibilityServiceList()` 区分两种情况：

- 列表里**没有**本服务 → 文案「操作服务已被系统关闭」＋ 一个直接跳到
  `Settings.ACTION_ACCESSIBILITY_SETTINGS` 的按钮；
- 列表里**有**但尚未连上 → 文案「操作服务正在启动」，避免误报故障。

> **修正说明**：本条初版曾记为「系统侧已 bound、日志有 `service_connected`，但 App 界面仍不恢复」，
> 据此判为 App 状态脱节。复核后该结论不成立：界面文本是读 `uiautomator dump` 得到的**过期无障碍
> 节点**，同帧截图里实际已是「本机已连接」（详见问题 5）；同时强停后 `enabled_accessibility_services`
> 是 `null` 而非「仍列出 Vela 服务」。真实成因是「服务被系统移除后没有重开」，已按上表更正，
> 严重级别由 P1 降为 P2。

### 3. XLSX 公式单元格只返回公式文本，无缓存值（P2，已知限制）

`book.xlsx` 的 B4 为 `=B2*B3`，工具返回公式本身、缓存值为空，模型据此标注「按算式应为 21，
此项为推算，非文件里读到的数值」——**行为诚实、符合预期**，但用户若只看结论会误以为是读到的值。
建议在返回结构里显式给出 `cached_value: null` 与 `evaluated: false`，或在提示中说明需自行计算。
与作者已声明的「Excel 公式重算未支持」一致，本机型未发现差异。

### 4. 原生控件点击失败后不降级，D5「输入框填写」两次中断（P1）

2026-11-22 补测 D5（打开设置 → 搜索框 → 输入「蓝牙」），连续两次失败：

| 次序 | 界面提示 | 出处 |
| --- | --- | --- |
| 第一次 | 系统未确认控件点击，可能已产生效果；已暂停，请核对后再继续 | `PhoneAccessibilityService.kt:318` |
| 第二次（继续后） | 模型连续返回无效决策，任务已安全暂停 | `AgentController.kt:574`（`rejectedActions >= 3`） |

根因：`tapElement` 命中唯一原生控件且 `isClickable == true` 后，直接调用
`node.performAction(ACTION_CLICK)`；一旦返回 `false` 就 `error(...)` 抛出，
**不会降级为坐标点击**——降级分支写在 `if (node.isClickable)` 之后，永远走不到：

```kotlin
if (node.isClickable) {
    if (!node.performAction(ACTION_CLICK)) error("系统未确认控件点击，可能已产生效果；已暂停…")
    return
}
execute(AgentAction("tap", …), fresh)   // 只有 isClickable == false 才会到这里
```

设置页搜索框属于「节点标了 clickable、但拒绝 ACTION_CLICK」的典型（需先 FOCUS 或点父容器），
于是这条路径在本机型上不可用，文字最终没有填进搜索框。

失败处理本身是合规的：弹出了待答浮层、保留进度、给出「核对当前页面后继续 / 我已调整好页面，
请继续 / 自己说 / 暂停，稍后处理」四个出口，没有静默失败。

建议：`performAction` 返回 `false` 时降级为按 `current.bounds` 中心的坐标点击，日志记
「原生点击未确认，已降级为坐标点击」；或先 `ACTION_FOCUS` 再 `ACTION_CLICK`，仍失败再降级。

**本 PR 已按此修复**，两处原生点击分支均改为「成功则返回、失败则记日志并继续走坐标点击」，
重复点击风险由 `verifyCoordinateFrame` 的画面比对兜住。回归结果见 PR 描述。

### 5. 状态文案不进入无障碍节点树，读屏会读到过期状态（P2，新发现）

同一时刻抓的截图与无障碍树，标题下方那行不一致：

| 来源 | 该行文本 |
| --- | --- |
| `screencap`（像素，肉眼所见） | 本机已连接 |
| `uiautomator dump`（无障碍树） | 操作服务未连接 |

树本身是实时的，不是 dump 缓存：同一会话里切到其它页面后，dump 输出长度从 12724 字符变为
7636 / 35592 字符，内容随界面变化。所以是**这一个 `Text` 节点的 `text` 属性停在节点创建时的值**
（应用启动那一刻服务确实未连接），此后状态变化只做了界面重组，没同步到无障碍层。

命中节点（`package="com.dallovers.phoneagent"`，紧跟在标题「新对话」之后）：

```xml
<node index="0" text="新对话" class="android.widget.TextView" bounds="[328,224][498,304]" />
<node index="1" text="操作服务未连接" class="android.widget.TextView" … />
```

影响：

- TalkBack 等读屏用户听到的状态与实际相反；
- 任何依赖无障碍树读本应用界面的自动化（包括用 Vela 操控 Vela 自己）会拿到过期文案，据此做的判断会错。

复现步骤：在「操作服务未连接」状态下启动应用 → 开启无障碍服务使界面变为「本机已连接」→
同批执行 `screencap` 与 `uiautomator dump`，两者不一致。

补充观察：这条**只在有任意无障碍服务处于启用状态时**出现。把服务关掉之后，同一个节点的文本会刷新成
当前值；重新开启后又停在旧值不再变。推测是 Compose 判定「无障碍已开启」，改为按事件通知变更，而
轮询式读取（`uiautomator dump`，以及 Vela 自己的 `ScreenReader`）拿不到这次通知。若推测成立，受影响
的是所有轮询读屏的实现，实时接收事件的读屏（TalkBack）可能不受影响——但这一点本次未能验证，因为
验证需要同时装两个读屏并逐帧对照。

可能原因（**未验证修法**）：`MainActivity.kt:263` 的状态文案是同一个 `Text` 的 `text` 参数，
值变化时 Compose 只重组、未向无障碍层推送文本更新。可行方向是让节点随状态重建，例如
`key(statusText) { Text(statusText, …) }`；或另行设置 `Modifier.semantics { contentDescription = statusText }`
并实测读屏是否跟随。本次不处理该问题的原因是：安装本地构建必须先卸载现有 debug 包（签名不同，会清掉
已有会话），无法在不影响设备现状的前提下验证。

## 与 OPPO 验收的差异（本机型新增记录）

1. **「所有文件访问权限」被拒后的表现**：首次按路径读取时，系统弹出 `MANAGE_EXTERNAL_STORAGE` 授权页被拒绝（`appops` 记录 `rejectTime`），此时模型拿不到文件内容，回复“没有直接看文件/截图的能力”，容易误判为功能缺失。在「设置 → 应用与权限 → 特殊应用权限 → 所有文件访问权限」允许 Vela 后（`appops` 转为 `Uid mode: allow`），同一请求立即成功。
   - 建议在首次使用引导或文件读取失败提示中说明这一权限依赖，减少类似误解。
   - 附件通道（B1）实测不受该权限影响：走系统文件选择器的导入可正常完成。
2. **OriginOS 后台策略**（为定位问题主动排查，非计划用例）：无障碍服务需要 `i管家 → 自启动` 与 `电池 → 后台耗电管理 → 允许后台高耗电` 同时放行，否则锁屏后服务被回收，App 显示「未连接」。另外，进程被 `force-stop` 后系统会把该服务从启用列表里移除（`enabled_accessibility_services` 变 `null`），这是平台行为、不是应用缺陷，重新开启后立即恢复——见上文「发现的问题 2」。
3. **对话内截图通路不可用**（非厂商差异，OPPO 上大概率同样缺失）：0.3.7 全机型的能力缺口，iQOO 15 上表现为「声称已截图但无产物」。建议在 OPPO 机上同样复核一次，确认不是本机型独有。
4. **状态文案不进无障碍树**（非厂商差异，推测为 Compose 文本节点未随状态重建）：屏幕上「本机已连接」、无障碍树里仍是「操作服务未连接」，见上文「发现的问题 5」。建议在 OPPO 机上复核一次，判断是否全机型一致。
5. 除上述四项外，A1–A11、B、C、D、E 各类的表现与 OPPO Find X9s Pro 的验收结果一致，唯一不通过项为 D5（已在代码层修复，待下个版本复测）。

## 未验范围

**未执行项**：无。检查表 A1–A11、B1–B4、C1–C2、D1–D7、E1–E5 均已执行。

**证据限制**（需在评审时知悉）：

- A1–A9、D1、D2、D5 有逐项数值或界面证据，见上文各条。
- A10、A11、B1–B4、C1、C2、D3、D4、D6、D7、E1–E5 的结论来自测试者实机逐项确认；会话记录在验证结束后已被清理，未保留逐项截图与数值，因此这些条目**只有结论、没有独立证据**。若评审要求逐项留证，需要重跑一遍并同步归档截图。

**已知限制类**（属作者已声明的未支持范围，本次未单独构造用例）：

- 音视频正文转写与视频内容理解（0.3.7 仅提供系统可读属性）
- Office 复杂排版、嵌入图表、Excel 日期格式与公式重算
- 加密文档、旧版 DOC/XLS/PPT、RAR/7z

## 结论

- **覆盖范围**：构建三项（`assembleDebug` / `testDebugUnitTest` / `lintDebug`）+ 检查表全部 29 项（A1–A11、B1–B4、C1–C2、D1–D7、E1–E5）。
- **A 类结论**：A1–A11 全部通过。A1–A9 的验证串逐字匹配，A10 能正确给出音视频属性且未声称可转写，A11 明确拒绝 55.0 MB 文件并说明 50 MB 上限。未发现厂商相关的解析差异。
- **B / C / E 类结论**：附件导入与持久化、长回复不截断与超长输出分段、界面与系统适配均通过，与 OPPO 验收一致。
- **D 类结论**：D1 通过——1440 × 3168 全屏截图画面清晰，本机无障碍截图通路正常；D2 通过——「打开系统设置」能正常跳转；D5 失败——文字未填入搜索框，根因是原生点击失败后不降级，本 PR 已修复。
- **其他发现**：P1 ×1（对话内截图能力缺口 + 虚假完成声明）、P2 ×3（强停后服务不自恢复且界面缺跳转引导；状态文案不进无障碍树；XLSX 公式无缓存值）。
- **构建检查**：`assembleDebug` 成功（343.9 MB）；`testDebugUnitTest` 108 项全通过；`lintDebug` 0 error / 22 warning。
- **总体判断**：0.3.7 在 iQOO 15 / OriginOS 6 上，29 项检查中 28 项通过，唯一不通过项 D5 已在代码层修复。文件读取、附件、长回复、界面适配的表现与 OPPO Find X9s Pro 的验收结论一致，未发现厂商相关的解析差异。上述问题均与机型无关，建议下一版本处理，优先项是「无截图动作却声称已截图」。

## 附件证据

按仓库约定（`.gitignore` 的 `reports/acceptance/*/checks/`），设备侧采集的原始证据不入库。本次在
iQOO 15 上采集并保留在本机的证据包括：

- 设备版本与安装时间、`MANAGE_EXTERNAL_STORAGE` 的 appops 记录
- 样例文件与验证串对照清单（`fixtures-manifest.json`）
- A9 未知二进制读取的模型原文回复
- 「对话内截图」缺陷的完整证据链（动作序列、`attachments` 计数、磁盘文件、源码位置）
- 问题 5 的同帧对照：同一时刻的 `screencap` 截图与 `uiautomator dump` 输出（截图 1440 × 3168，树为 12724 字符）
- 问题 2 的三步对照：强停前后 `enabled_accessibility_services` / `Enabled services` / `Bound services` 取值

如需复核，可在评审中索取，或按本记录「验证步骤与结果」的样例清单重跑生成。

取证说明：App 自身的运行日志（`files/run_logs/current.jsonl`）只记录事件名、阶段、包名与计数，**不记录错误文案、模型回复与动作参数**，因此无法用于复盘具体失败原因；缺陷取证改用 `files/task_sessions.json` 的会话原文与实际动作记录。该会话文件在验证结束后已由测试者清理，本记录中的相关引用为清理前采集。logcat 缓冲区在验证后已被后续系统事件覆盖，未保留。

本记录由刘福超在 iQOO 15 实机上逐项核对后填写，2026-11-21 首次记录，2026-11-22 补齐手机操控与其余检查项。
