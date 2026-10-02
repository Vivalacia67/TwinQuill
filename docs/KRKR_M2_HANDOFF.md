# Krkr M2 交接与模拟器验收：TVP 基础显示

更新日期：2026-10-03。分支：`refactor/krkr-direct-integration`。
**M2 已于 2026-10-03 完成用户模拟器验收。**
前置 M1 已验收（`6f42083`）；路线图文档已提交（`0f2d821`）。
本交接记录与 M2 代码、夹具一并提交，作为 M3 开发前的已验收基线。

## 本阶段交付

| 能力 | 实现与边界 |
| --- | --- |
| 来源审计 | 固定官方 KAG3 3.32 stable rev. 2 的 39 文件快照；仅用于审计，框架执行属于 M4。 |
| 启动时序 | Broker 准备会话；真实 surface 就绪后在 worker 注册 TVP、执行一次 startup。 |
| 标准显示 | 固定上游 Window/Layer/Font TJS 绑定与 Android 基础后端；真实 PNG/JPEG、中文文字、图层合成、透明度、顺序和裁剪。 |
| 交互与关闭 | 等比居中输出及坐标转换；窗口/图层触摸、左键、常用键盘事件、focusedLayer 和 onCloseQuery。 |
| 定时/异步 | 上游 Timer/AsyncTrigger 绑定；缓存、取消、优先级和下一 tick 重入派发。后台暂停，恢复不补发积累的定时事件。 |
| 状态与恢复 | CPU 图层和 VM 状态跨 Home、surface/GL context、Activity 重建保留；GL 纹理按新 context 重建。 |
| 有界退出 | startup 5 秒，回调/事件批次 2 秒，终结清理 2 秒；取消绕过脚本 catch，自引用对象释放，旧会话清理等待最多 5 秒。 |

主要代码：`krkr_tjs_session.*`、`krkr_tjs_execution.*`、`krkr_tvp_events.*`、
`krkr_tvp_visual.*`、`krkr_display_frame.h`、`krkr_cocos_runtime.*` 和 Java
会话/renderer。TJS 只在 worker 执行；GL 使用不可变 RGBA 帧，UI 提交事件。

新增上游翻译单元明确列为 TimerIntf、WindowIntf、LayerIntf、EventIntf。
补丁 0003–0007 只作用于生成副本。Window/Layer/Font 使用实际 Android 基础
后端，未引入完整桌面 Layer/RenderManager；其他成员明确报错，支持清单和
依赖依据见 [接口审计](KRKR_M2_INTERFACE_AUDIT.md)。

## 验证结果与产物

最终日志/XML 保存在忽略目录 `.agent-work/m2-visual/`，不提交 APK 或设备日志。
第一批宿主/Timer 的历史证据仍在 `.agent-work/m2-development/`；本节结果取代
第一批的 APK 和测试数量。

| 检查 | 结果 |
| --- | --- |
| Python 回归 | 95/95 通过，含来源、补丁应用、显式 TVP 闭包和标准夹具检查；签收后的提交检查新增 Git 二进制夹具字节保持回归。 |
| 默认设备测试 | 22/22 通过，含 ONS 协议、媒体、持久化及只读 SAF 标准 Krkr 显示/Timer/关闭。 |
| JVM 测试 | 9/9 通过：engine-api 5 项，engine-ons 4 项。 |
| Krkr 隔离测试 | 23/23 通过：22 项运行时/会话，1 项 broker 重建。 |
| Debug / Release | 两个 ARM ABI 构建通过；原生最终链接门槛保持启用并通过。 |
| lint | 0 错误、7 警告，没有修改检查级别。 |
| 独立图像解码 | ARM64 原生 decoder 在同一模拟器转译执行，退出 0；损坏 PNG/JPEG 的错误日志为预期测试输出。 |
| 来源/仓库/APK | 7,257 个固定文件校验、仓库卫生、diff 空白、两个 APK 原生库/ABI/对齐检查通过。 |

设备：`emulator-5554` / `Medium_Phone`，Android 16 / API 36，x86_64 +
ARM64 转译，SwiftShader，Vulkan 关闭。ARMv7 只有构建证据；未新增真机或
API 26 运行证据，本轮签收仅要求模拟器。

Debug APK：`launcher-app/build/outputs/apk/debug/launcher-app-debug.apk`，SHA-256：
`faa35d0bd77186a6f665e623d0e4e97edbe275220141e4896208ba50ecbe7a65`。
未签名 Release APK：`launcher-app/build/outputs/apk/release/launcher-app-release-unsigned.apk`，SHA-256：
`8ea2e4f764c2500b47540687da2695cf0083ab558372949575ed3646526e118f`。

证据文件：`krkr-final-validation.txt`、`krkr-final-results.xml`；
`default-final-validation.txt`、`default-final-results.xml`；`python-final.txt`、
`sources-final.txt`、`hygiene-final.txt`、`apk-check.txt`。
字体及 OFL 在两份 APK 中核对通过，测试夹具与 KAG 审计快照未混入主 APK。
补丁空白整理后的生成源码逐字节一致，见 `generated-byte-equality.txt`；独立
解码构建/运行见 `decoder-build.txt`、`decoder-test.txt`。
开发者另从实际游戏库导入 visual，经系统 SAF 授权检查图片/中文/透明度、
点击变绿、Home/最近任务返回保留绿色及 Enter 正常退出。截图
`visual-saf.png`、`visual-saf-green.png`、`visual-saf-home-return.png` 与
`manual-saf-logcat.txt` 保存于同一证据目录，作为开发自检证据。
新增设备覆盖验证实际颜色/透明度/文字像素、Timer 可见变化、局部触摸坐标、
键盘标志、黑边忽略、关闭 veto、Home 与 Activity 重建、AsyncTrigger 缓存/
取消/优先级/重入、矩形溢出、层数/深度/字体失效及错误后恢复。

## 完整模拟器验收流程

### 1. 安装并复制夹具

启动 Android Studio 中已配置的 `Medium_Phone`，在仓库根目录执行。
以下命令假定设备序号为 `emulator-5554`；先用 devices 确认。

```powershell
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adbPath devices
& $adbPath -s emulator-5554 install -r launcher-app/build/outputs/apk/debug/launcher-app-debug.apk
& $adbPath -s emulator-5554 shell mkdir -p /sdcard/Download/TwinQuill-M2
& $adbPath -s emulator-5554 push tests/fixtures/krkr-m2/visual /sdcard/Download/TwinQuill-M2/
& $adbPath -s emulator-5554 push tests/fixtures/krkr-m2/timer /sdcard/Download/TwinQuill-M2/
& $adbPath -s emulator-5554 push tests/fixtures/krkr-m2/startup-deadline /sdcard/Download/TwinQuill-M2/
& $adbPath -s emulator-5554 push tests/fixtures/krkr-m2/finalizer-loop /sdcard/Download/TwinQuill-M2/
& $adbPath -s emulator-5554 shell am start -n io.github.twinquill/.launcher.MainActivity
```

`visual` 包含 startup.tjs、checker.png、sample.jpg；都是自制资源，可直接使用。
无需手工输入脚本或下载游戏。字体已打入 APK。其他三个目录各含一个脚本。
本次开发结束时，四个目录已复制到模拟器，最终 Debug APK 已安装；visual
已添加到游戏库并完成开发自检，当前停在游戏库。可直接启动现有 visual 条目。

### 2. 经 SAF 添加标准场景

在 TwinQuill 游戏库点击添加目录，在系统选择器进入
`Download/TwinQuill-M2/visual`，点击“使用此文件夹 / USE THIS FOLDER”，
允许授权。选择 Kirikiri 引擎后启动。授权的是 visual 目录，不是其父目录。

### 3. 检查图片、文字、透明度与 Timer

游戏逻辑尺寸 320×200，等比居中，周围黑边属于正常显示。
首次读取资源和字体时等待几秒，再检查稳定画面。
应看到深蓝灰背景，左上蓝白 PNG 棋盘、其右边绿色 JPEG，底部黄色
`TwinQuill 中文`，中部半透明暗红矩形，以及右侧约每 500 毫秒蓝/黄交替的徽标。
中文不能变成方框，图片不能缺失，徽标应持续切换。

本脚本只通过标准 Window/Layer/Font/Timer 绘制，不调用 TwinQuillHost 绘图。
点击中部暗红矩形，必须变成不透明绿色；徽标继续切换。点击周围黑边不会
改变该状态、退出或显示 Script Error。

### 4. 后台恢复与状态保留

矩形变绿后按模拟器 Home，等待至少 3 秒，从最近任务返回 TwinQuill。
图片与中文仍正确，矩形仍绿，徽标恢复交替；不能重置成红色、黑屏或报错。
重复两次。脚本本身检查后台 Timer 没有继续推进，错误会返回 Script Error。

Activity/GL 重建和“startup 只执行一次”由下一步隔离回归实际执行并断言；
无需开启“不保留活动”，该选项会触发宿主主动结束会话的另一条路径。

### 5. 关闭、重新启动及自动重建验证

场景运行时执行 `& $adbPath -s emulator-5554 shell input keyevent 66`
（Enter），应通过 Window.close 正常返回游戏库。重新启动 visual：矩形恢复
初始半透明红色，图片/文字/徽标正常。再按模拟器 Back，正常返回。
重复启动/退出三次，不应出现 Script Error 或无法重新启动。

运行以下隔离测试，预期 `23 tests`、0 failed、BUILD SUCCESSFUL；
它包含真实 Home、Activity.recreate、图层状态/像素和 broker 重建验证：

```powershell
.\gradlew.bat --no-daemon :launcher-app:connectedDebugAndroidTest -PtwinquillKrkrRuntimeInstrumentation=true
```

设备测试完成会卸载目标 APK；继续手工验收前重新执行步骤 1 的 install 命令。

### 6. 错误与清理恢复

用步骤 2 的方法分别添加 startup-deadline、finalizer-loop 和 timer。
startup-deadline 启动后约 5 秒返回游戏库并显示 Script Error，这是预期结果；
紧接着启动 visual 必须正常。finalizer-loop 启动为绿色测试方块，按 Back
正常退出，立即再次启动或启动 visual，不能卡死或报错。timer 是保留的
调度夹具：蓝黄交替，首次点击变绿停止计时，再次点击正常退出。

### 7. ONS 与公共检查

默认设备测试必须通过，并在已有 ONS 验收目录重复启动、正常退出和存读档。
完整命令如下，JDK/SDK/NDK 使用仓库指定版本：

```powershell
python -m unittest discover -s tests -v
python scripts/check_repository_hygiene.py
python scripts/verify_source_snapshots.py
.\gradlew.bat --no-daemon :launcher-app:assembleDebug :launcher-app:assembleRelease :launcher-app:lintDebug
.\gradlew.bat --no-daemon :engine-api:testDebugUnitTest :engine-ons:testDebugUnitTest
.\gradlew.bat --no-daemon :launcher-app:connectedDebugAndroidTest
python scripts/check_apk_native_libraries.py launcher-app/build/outputs/apk/debug/launcher-app-debug.apk launcher-app/build/outputs/apk/release/launcher-app-release-unsigned.apk
```

### 8. 记录签收结果

用户于 2026-10-03 确认：“已验收完成，提交代码后继续M3”。据此签收上述
模拟器流程及 M2 基础显示范围，授权提交 M2 并开始 M3。签收使用本节所列
Debug APK 和 Medium_Phone，不扩展为完整 KAG、存档或未经验证设备的兼容声明。
后续复测记录 APK 哈希、模拟器/API、步骤 3–6 和自动化结果；有异常时保存
错误页面及以下日志。

```powershell
& $adbPath -s emulator-5554 logcat -v time 'TwinQuill/Krkr:I' 'TwinQuill/KrkrRuntime:I' '*:S'
```

## 后续阶段边界

M2 支持基础 opaque/alpha、PNG/JPEG、固定字体和列出的事件；未支持方法/
属性明确报错。完整 KAG、菜单、序列化和框架必需对象属于 M4；通用资源路径、
压缩 XP3 与私有写入属于 M3；实际 Krkr 存读档属于 M5；更多格式、混合、
转场和媒体属于 M6。不能用本阶段自制场景证明任意商业游戏兼容。

执行预算检查 TJS opcode/lexer，不是任意 Provider I/O 或第三方原生函数的
硬超时。完整支持成员和资源上限见接口审计；总体范围见 [ROADMAP](ROADMAP.md)。
