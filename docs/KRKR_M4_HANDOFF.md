# Krkr M4：KAG 最小游戏开发交接与模拟器验收

更新日期：2026-10-04。分支：`refactor/krkr-direct-integration`。
M3 已验收并提交为 `abcd6fc`。M4.1–M4.4 已完成，用户于 2026-10-04 确认模拟器验收通过、无报错。
M4 代码与签收记录已提交为 `78bb0b5`；下一轮入口见 [M5 开发计划](KRKR_M5_PLAN.md)。
支持范围见 [M4 接口审计](KRKR_M4_INTERFACE_AUDIT.md)，后续任务见 [ROADMAP](ROADMAP.md)。

## 本阶段交付

| 任务 | 行为与证据 |
| --- | --- |
| M4.1 | 固定 KAG3 的完整启动、配置与框架类实际执行，原生 KAGParser 与 Conductor 调度 first.ks。游戏提供框架，生产 APK 不带测试游戏。 |
| M4.2 | 普通中英文字、页面点击等待、背景/立绘、直接切换、两条选择路线、标签跳转、跨文件调用返回、宏和内嵌 TJS。 |
| M4.3 | 框架所需 Window/Layer/Font、对象释放、系统键状态/连续事件、逻辑菜单、资源搜索和真实 PCM16 WAV 音频。Home 后声音与脚本暂停，返回保持现场。 |
| M4.4 | 原创车站场景，同时以散文件和压缩 XP3 运行；缺资源、脚本异常、正常结束后可重新启动。真实生产 SAF broker 的成功/错误结果有覆盖。 |

构建新增的上游翻译单元只有 KAGParser.cpp；补丁 0009–0011 在生成副本应用，
固定上游快照、许可和链接检查保留。XP3 虚拟目录及搜索路径映射继承 M3 的
只读访问、权限刷新和资源优先级。KAG 双分隔符私有路径规范化仍拒绝遍历。
窗口释放框架对象后清理残留图层、Timer、解析器缓存、VM 和音轨；递归和原生
解析循环有预算检查，错误后不会遗留一份占用单会话注册表的游戏。

## 2026-10-04：真实 SAF 初始化超时修复

用户反馈 `m4-loose` 长时间显示宿主蓝色方块，随后 `SCRIPT ERROR`。日志确认
完整 KAG 初始化的文件查找超过 20 秒；早先快速测试 Provider 未覆盖真实目录延迟。
修复包含游戏根限定路径 `System.exePath="./"`、物理搜索目录仅校验类型/权限、
同次 SAF 查找内共享有界目录快照。快照在返回或异常时清除；新查找重新查询，
保持精确大小写优先、歧义拒绝、文件变化和撤权检测。

首次 Conductor 回调还会连续加载图片、PCM 和字体后绘制整页。KAG 回调预算
调整为 10 秒；初始化 20 秒、清理 2 秒及普通 TJS 的 5/2 秒预算保留。
40 ms 子目录查询延迟的完整路线、快照刷新/超额回退/撤权、KAG 回调死循环
均有回归覆盖。预算和死循环检查继续生效；Provider Binder/IO 仍没有硬超时保证。

已从用户现有游戏卡片通过 `com.android.externalstorage.documents` 启动真实
Download 目录：散文件绿色开场、Home 返回、ALPHA；压缩 XP3 绿色开场、BETA
及 Home 返回均正常，两个结尾在游戏库显示 `NORMAL_EXIT`。音频用实际
AudioTrack 播放头测试确认；截图只记录画面。现有目录和授权可以直接复用。
日志、截图和 `manual-real-provider.json` 在 `.agent-work/m4-saf-startup/`。

## 验证环境与结果

| 检查 | 最终结果 |
| --- | --- |
| Python | 103/103；完整框架字节、压缩 XP3、原创 PCM、来源/补丁/源码闭包等回归。 |
| Krkr 隔离设备 | 37/37；完整 KAG、SAF 散文件/XP3 两路线、声音/Home、错误恢复、PCM 结束及执行预算，补充慢 Provider 与目录快照刷新/额度回退/撤权。 |
| 默认设备 | 30/30；ONS 媒体/持久化、生产 SAF broker、完整 KAG 成功/错误结果、探测及真实应用入口。 |
| JVM | engine-api 5、engine-ons 4，共 9 项通过（未修改模块复用 Gradle 有效结果）。 |
| 构建 | arm64-v8a、armeabi-v7a 的 Debug/Release 及原生链接检查通过。 |
| lint | 0 错误、7 警告，没有关闭或降低检查。 |
| 原生存储 | 19 个共享 XP3 样例、seek/缓存/编码/原子写入及新增虚拟目录/搜索/撤权断言，ARM64 退出 0。 |
| 原生解码 | ARM64 图像测试退出 0；损坏 JPEG 的诊断为预期输出。 |
| 来源 / 卫生 / APK | 7,257 个固定文件、补丁摘要、仓库卫生、两个 APK 的原生库/ABI/对齐通过；生产 APK 无测试游戏/框架资产。 |

Debug APK：`launcher-app/build/outputs/apk/debug/launcher-app-debug.apk`，SHA-256：
`4bca79b6234c1ce28ecee5fd48ecd598ef6ade7e5d3b2fe3b49db4088347c5e4`。
未签名 Release（提交前复核的当前本地构建产物）：
`launcher-app/build/outputs/apk/release/launcher-app-release-unsigned.apk`，SHA-256：
`0cde8411134274cf571ba6b23dca8ea3f306c116e5ee0743bdc3f32bdea6e58b`。
此前验收构建的 Release 摘要为
`0c38057314507e9b7a5b75d41d000eeeeb83633d130b94c9fbc2afbc291100e8`，
保留在 `artifact-final.json`；用户实际验收的 Debug 摘要保持不变。
完整自制 XP3 SHA-256：
`74e734a9d88bc3dc2b677803a3bf927c5292924654b6b52785f41d71a5e0f404`。

最新修复证据在 `.agent-work/m4-saf-startup/`：`build-final.txt`、`build-isolated-final.txt`、
`build-default-final.txt`、`python-final.txt`、`krkr-final.txt`、`default-final.txt`、`regressions-final.txt`、
`native-storage-final.txt`、`native-decoder-final.txt`、`sources-final.txt`、
`hygiene-final.txt`、`apk-final.txt`、`artifact-final.json`、`data-preservation.json`、`manual-real-provider.json`。
音频断言使用实际 AudioTrack 播放头，退出检查音轨数为零；不是仅检查声音对象创建成功。

提交前再次通过源码快照检查（7,257 项）、仓库卫生检查（7,501 个已跟踪/暂存路径）
及当前两个 APK 的原生库门槛。自有代码空白检查通过；框架配置保留上游空白格式，
补丁保留统一差异格式要求的空行上下文标记。


环境：JDK 17、SDK 36、NDK 28.2.13676358、CMake 3.22.1。
设备仅 emulator-5554 / Medium_Phone，Android 16 / API 36，x86_64 + ARM64 转译，
SwiftShader、Vulkan 关闭。ARMv7 有构建证据；真机及 API 26 运行未验证。
构建与完整模拟器回归分开执行。APK、日志和生成游戏留在忽略目录，不提交仓库。
本轮没有卸载/清空应用；在设备上核对 39 个原有存档目录与 m3-save 计数文件仍在，
游戏库保留本轮反馈时已有的四个 M4 条目。仅记录存在性结果，不导出私有数据库或存档内容。
当前模拟器已打开游戏库，默认
测试 APK 保持默认进程配置；要运行隔离回归须按步骤 5 重新构建并覆盖安装。

## 完整模拟器验收流程

### 1. 构建、覆盖安装与生成样例

在 Android Studio 启动 Medium_Phone；确认模拟器媒体音量和电脑音量可听。
仓库根目录 PowerShell 执行以下命令。已有 JDK 17 配置可沿用；如果当前终端
python 仍指向 Store，改用 `.\.venv\Scripts\python.exe` 执行 Python 命令。

```powershell
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adbPath devices
.\gradlew.bat --no-daemon :launcher-app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Debug build failed' }
& $adbPath -s emulator-5554 install -r launcher-app/build/outputs/apk/debug/launcher-app-debug.apk
if ($LASTEXITCODE -ne 0) { throw 'APK install failed' }
python scripts/create_krkr_m4_fixtures.py
if ($LASTEXITCODE -ne 0) { throw 'Fixture generation failed' }
& $adbPath -s emulator-5554 shell mkdir -p /sdcard/Download/TwinQuill-M4
foreach ($scene in 'm4-loose','m4-compressed','m4-missing','m4-script-error') {
    & $adbPath -s emulator-5554 push ".agent-work/m4-acceptance/games/$scene" /sdcard/Download/TwinQuill-M4/
    if ($LASTEXITCODE -ne 0) { throw "Push failed: $scene" }
}
& $adbPath -s emulator-5554 shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n io.github.twinquill/.launcher.MainActivity
```

生成器会复制完整固定框架，覆盖游戏配置和场景，生成原创 PNG/WAV 与标准 XP3；
共 49 项资源，清单为 `.agent-work/m4-acceptance/games/M4-FIXTURES.json`。
只覆盖已知场景路径，保留其他本地文件。无需手写 startup.tjs 或下载商业游戏。
`m4-broker` 仅供自动化协议测试，手工验收导入上述四个目录。

本轮已将上述最终 Debug APK 覆盖安装到当前模拟器，并复制四个场景至 Download。
已导入的四个场景可直接启动复验；尚未导入时继续下面的操作。需要重现构建时再执行上面的命令。

在游戏库添加目录，从系统选择器进入 `Download/TwinQuill-M4/<场景>`，
点击“使用此文件夹 / USE THIS FOLDER”并允许授权；每次授权场景自身目录。
普通场景包含 startup.tjs，压缩场景仅有 data.xp3 和许可说明，应识别为 Krkr。

### 2. 散文件开场、页面等待和后台返回

启动 m4-loose，等待框架初始化。应出现 640×480 游戏画面：深绿色车站背景
`#285040`、右上原创蓝衣向导、下部深色消息框、白色中英文字和黄色页面等待标记。
文字含 `TwinQuill M4 - Station`、`游戏演出 / KAG3 on Android` 与
`Tap to choose your route.`，应听到循环电子音。画面外黑边正常。
初始化期间可能短暂显示宿主蓝色方块；看到完整绿色场景后再点击或检查后台恢复。

先不要点击页面，按模拟器 Home，等待至少 3 秒：后台期间声音停止。
从最近任务返回，仍是同一文字页，声音恢复，无 Script Error，不能回到游戏列表。
再按 Home，等待 3 秒，通过 TwinQuill 应用图标返回；结果相同。
开场不会重新执行，向导和文字状态不丢失。

点击消息框中的普通文字处，应出现 `Choose a route:`，以及
`ALPHA - Blue platform`、`BETA - Amber platform` 两个链接；不会自动跳过选择。
可在选择页再做一次 Home/返回，两个选项仍可点击。

### 3. 两条分支、跨文件返回和正常结束

点击 ALPHA 链接，背景切成蓝色 `#2040b0`，向导保留；消息框应出现
`The guide shows you the platform.`、`Route ALPHA selected.` 和 `Tap to finish.`。
循环音停止，播放一次较高的短提示音；出现黄色页面等待标记后，点击文字处，
游戏正常结束回到游戏库，无报错且不残留声音。

重新启动同一条目，回到绿色开场，再点击页面并选择 BETA。
这次应为橙色背景 `#b06020`，文字为 `Route BETA selected.`，结束过程相同。
重新启动重新选择是本阶段预期行为；跨进程保存剧情位置属于 M5。

本场景在分支后执行跨文件 call/return、宏与内嵌 TJS 校验；返回没有正确运行会
直接抛出脚本错误。因此完成上述结尾同时验证调用返回链。

### 4. 压缩 XP3、错误恢复和既有引擎

启动 m4-compressed，重复步骤 2–3，分别走 ALPHA 和 BETA；画面、声音、页面等待、
后台恢复及结束结果应与散文件一致。该目录没有松散框架/图像/声音可供回退。

依次启动 m4-missing 和 m4-script-error，两者应结束并显示 Script Error；
前者缺少图片，后者是主动抛出的原创脚本异常。这两次错误是预期结果。
每次错误后立即启动 m4-loose，应正常进入绿色开场并完成分支，不能出现
INVALID_REQUEST、黑屏或上一局残留音频。用模拟器 Back 退出运行中的正常游戏
也应回到列表，随后能再次启动。

复查已验收的 m3-save：同一条目计数继续保留，不应重置。再运行既有 ONS
验收场景，检查启动、交互、正常退出与已有存档；双引擎交替启动保持可用。
本轮安装和测试均使用覆盖安装，保留游戏库、SAF 授权和应用私有存档。

### 5. 自动化复核

先正常退出游戏。构建结束后再执行设备测试，期间不操作模拟器。
以下采用直接 instrumentation，避免 Gradle connected 测试卸载应用并删除验收数据。
两类测试 APK 文件名相同，切换测试类型时必须重新构建并覆盖安装。

```powershell
python -m unittest discover -s tests -v
python scripts/check_repository_hygiene.py
python scripts/verify_source_snapshots.py
.\gradlew.bat --no-daemon :launcher-app:assembleDebug :launcher-app:assembleRelease :launcher-app:lintDebug :engine-api:testDebugUnitTest :engine-ons:testDebugUnitTest
python scripts/check_apk_native_libraries.py launcher-app/build/outputs/apk/debug/launcher-app-debug.apk launcher-app/build/outputs/apk/release/launcher-app-release-unsigned.apk
```

默认设备回归（ONS、探测、生产 broker 和真实应用入口）：

```powershell
.\gradlew.bat --no-daemon :launcher-app:assembleDebugAndroidTest -PtwinquillKrkrRuntimeInstrumentation=false
if ($LASTEXITCODE -ne 0) { throw 'Default test APK build failed' }
& $adbPath -s emulator-5554 install -r launcher-app/build/outputs/apk/debug/launcher-app-debug.apk
& $adbPath -s emulator-5554 install -r launcher-app/build/outputs/apk/androidTest/debug/launcher-app-debug-androidTest.apk
if ($LASTEXITCODE -ne 0) { throw 'Default test APK install failed' }
& $adbPath -s emulator-5554 shell am instrument -w -r -e notClass 'io.github.twinquill.engine.krkr.KrkrRuntimeHostInstrumentedTest,io.github.twinquill.launcher.KrkrBrokerLifecycleInstrumentedTest' io.github.twinquill.test/androidx.test.runner.AndroidJUnitRunner
```

Krkr 隔离设备回归（显示、输入、生命周期、存储及完整 KAG）：

```powershell
.\gradlew.bat --no-daemon :launcher-app:assembleDebugAndroidTest -PtwinquillKrkrRuntimeInstrumentation=true
if ($LASTEXITCODE -ne 0) { throw 'Krkr test APK build failed' }
& $adbPath -s emulator-5554 install -r launcher-app/build/outputs/apk/androidTest/debug/launcher-app-debug-androidTest.apk
if ($LASTEXITCODE -ne 0) { throw 'Krkr test APK install failed' }
& $adbPath -s emulator-5554 shell am instrument -w -r -e class 'io.github.twinquill.engine.krkr.KrkrRuntimeHostInstrumentedTest,io.github.twinquill.launcher.KrkrBrokerLifecycleInstrumentedTest' io.github.twinquill.test/androidx.test.runner.AndroidJUnitRunner
```

预期 Python 103、JVM 9、默认设备 30、Krkr 隔离设备 37 项无失败。
不能仅更换 `-e class`：隔离参数在构建时写入 manifest。
查看最终 `OK (...)` 和失败数，不能用 am instrument 的退出码代替测试结果。
原生存储和图像解码运行器见 [M3 交接](KRKR_M3_HANDOFF.md) 与
[M0 门槛](KRKR_M0_HANDOFF.md)，只清理各自测试临时目录。

### 6. 签收记录与下一阶段

用户于 2026-10-04 反馈“验收完成，无报错”，M4 按上述模拟器范围签收。
本次验收对应的 Debug APK SHA-256 为
`4bca79b6234c1ce28ecee5fd48ecd598ef6ade7e5d3b2fe3b49db4088347c5e4`，
自动化结果与环境见前文。保存已验收代码基线后，下一阶段准备 M5 的实际 KAG
存档路径、槽位保存及跨进程恢复；按 ROADMAP 分任务开发和验收。

再次复核发现异常时，记录场景名和具体步骤，并保留日志：

```powershell
& $adbPath -s emulator-5554 logcat -v time 'TwinQuill/Krkr:I' 'TwinQuill/KrkrActivity:I' 'TwinQuill/KrkrRuntime:I' '*:S'
```

M4 只声明固定配置、自制 KAG 样例的运行能力。游戏槽位存读档属于 M5；复杂
转场、竖排、压缩音频、视频、插件与音频焦点属于 M6；游戏及存档管理界面属于 M7。
没有商业游戏或全部 KAG 参数的兼容证据，支持边界以接口审计为准。
