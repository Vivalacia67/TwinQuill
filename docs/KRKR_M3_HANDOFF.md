# Krkr M3：资源系统、XP3、编码与私有写入交接

更新日期：2026-10-03。分支：`refactor/krkr-direct-integration`。
已验收 M2 提交：`db8f24b`。用户已于 2026-10-03 确认 M3 测试完成并授权继续 M4；
M3 已完成模拟器验收。支持范围和数据额度以 [存储契约](KRKR_M3_STORAGE_CONTRACT.md) 为准，
后续阶段见 [ROADMAP](ROADMAP.md)。

## 本阶段交付

| 任务 | 已实现行为 |
| --- | --- |
| M3.1 | 松散文件、SAF、XP3 共用资源流；子目录、中文/日文路径、搜索路径、成员枚举和有界缓存。SAF 权限撤销、非 seek 流和超限错误有实际回归。 |
| M3.2 | 标准未保护 XP3 的 raw/zlib 索引、续索引、多成员、混合多段和空文件；根松散优先、后登记归档覆盖；Java 探测与原生读取共享 19 个边界样例。 |
| M3.3 | 严格 Unicode 默认行为；根 twinquill-krkr.conf 可明确指定 CP932；BOM 优先，错误字节与错误配置拒绝，私有文件默认 UTF-8。 |
| M3.4 | 只读 System.dataPath 映射到每游戏 krkr/ 子目录；文本、字节及默认文本对象序列化原子提交。实际部分写入、序列化失败与替换失败保留旧数据。 |

主要实现位于 `engine-krkr/src/main/cpp/krkr_resource.*`、
`krkr_resource_backend.cpp`、`krkr_xp3.cpp`、
`krkr_game_text.*`、`krkr_private_storage.*` 和 TJS 会话。
Java 会话统一 Android 私有目录别名，生产 broker 与 runtime 绑定相同存档目录；
拒绝基目录/游戏目录符号链接指向另一游戏。ONS 保留原有父目录布局及只读 VFS 行为。

新增上游补丁 `0008-tjs-text-stream-abort.patch` 只作用于生成副本，
使序列化异常丢弃文本缓冲。固定上游源码未改动，没有扩大上游翻译单元清单，
继续使用已审计 zlib 并保留最终链接检查。

## 自动化验证与产物

原 M3 证据位于忽略目录 `.agent-work/m3-storage/`；
验收反馈修复的构建、入口回归及最终 APK 证据位于 `.agent-work/m3-home-fix/`。
最终结果：

| 检查 | 结果 |
| --- | --- |
| Python | 98/98；包括夹具重现、二进制检出、来源、补丁和源码闭包。 |
| 默认设备测试 | 29/29；包括 ONS 既有协议/媒体/持久化、XP3 探测、生产 SAF broker，以及松散/压缩 Krkr 和 ONS 的真实启动任务后台重入。 |
| Krkr 隔离设备测试 | 30/30；保留 M2 显示/生命周期覆盖，新增松散/压缩像素一致性、补丁、CP932、写入失败、重启隔离、符号链接和权限撤销。 |
| JVM | engine-api 5 项、engine-ons 4 项，共 9/9。 |
| Debug / Release | arm64-v8a、armeabi-v7a 构建及原生链接门槛通过。 |
| lint | 0 错误、7 警告；没有关闭或降低检查。 |
| 原生存储测试 | ARM64 执行 19 个归档样例及实际部分写入/替换失败、路径、缓存、编码、隔离断言，退出 0。 |
| 原生图像解码 | 同一模拟器 ARM64 转译执行，退出 0；损坏图像诊断为预期输出。 |
| 来源、卫生、APK | 7,257 个固定文件及补丁摘要、仓库卫生、diff 空白、两个 APK 的库清单/ABI/对齐通过。 |

环境：JDK 17，SDK 36，NDK 28.2.13676358，CMake 3.22.1。
设备仅 `emulator-5554` / `Medium_Phone`，Android 16 / API 36，
x86_64 + ARM64 转译，SwiftShader，Vulkan 关闭。
ARMv7 只有构建证据；真机及 API 26 运行未验证。

Debug APK：`launcher-app/build/outputs/apk/debug/launcher-app-debug.apk`，SHA-256：
`5558dced87ffbd5d15704413ebd90fdeeb1e6004cf59a9fac122d271190a0c49`。
未签名 Release APK：`launcher-app/build/outputs/apk/release/launcher-app-release-unsigned.apk`，SHA-256：
`3b92061cf159dea97254e581fa4611d691b017329c185587698a8e4fc16f739e`。
APK 和设备日志不提交仓库。

证据：`build-final.txt`、`python-final.txt`、`default-standalone-final.txt`、
`default-final-results.xml`、`krkr-standalone-final.txt`、`krkr-final-results.xml`、
`native-final.txt`、`decoder-build.txt`、`decoder-test.txt`、`sources-final.txt`、
`hygiene-final.txt`、`apk-check.txt`、`artifact-check.txt`。构建与模拟器测试分别执行。
曾合并 Release/lint 与设备测试，两个既有显示/时序用例超时；保留失败 XML
`krkr-under-build-load-results.xml`，最终结论使用独立设备回归结果。

## 验收反馈：后台返回与重复应用入口

步骤 2 曾返回游戏列表，之后启动任何 Krkr 场景都返回 INVALID_REQUEST。
实际任务栈及日志显示：通过无 MAIN/LAUNCHER 的 adb Intent 打开根页面后，
系统启动器重入会新建 MainActivity，盖住仍存活的 Krkr 会话；再次启动被单会话检查拒绝。
MainActivity 现会结束非根位置的 MAIN/LAUNCHER 重复入口，让原游戏恢复，
并保护提前结束路径上的 repository 生命周期。

新增 LauncherTaskResumeInstrumentedTest 使用真实 MainActivity、EngineRouter、
生产 broker 和系统启动器 Intent。旧 APK 在该测试中稳定失败，任务顶为重复 MainActivity；
修复后验证松散/压缩场景绿色状态及 PID 保持、最近任务返回、退出后重新启动，
并验证 ONS 原进程继续运行。此修复未修改引擎或原生存储代码；原生结果沿用上述 M3 证据。
本轮使用覆盖安装和直接 instrumentation，保留现有游戏库、目录授权与存档。
结束后确认五个 M3 游戏库条目及原有两个游戏存档目录仍在，清理的仅为本轮测试创建的目录。
证据：`baseline-system-entry.txt`、`baseline-system-activities.txt`、
`default-final.txt`、`build-final.txt`、`build-test-pid-wait.txt`、
`python-final.txt`、`apk-check.txt`、`sources-final.txt`、`hygiene-final.txt`、
`data-preservation.txt`、`final-library-ui.xml`，均位于 `.agent-work/m3-home-fix/`。
用户已完成重新验收；后台返回和测试配置反馈均已关闭。

### 隔离测试配置反馈

仅将 am instrument 的 `-e notClass` 改成 `-e class`，仍安装默认测试 APK 时，
Krkr 测试会在主进程运行。设备日志明确报出预期 `io.github.twinquill:krkr`、
实际 `io.github.twinquill`，后续 native 会话无法交给另一进程的 Activity。
JUnit 可同时记录测试主体及清理阶段的异常，因此出现 30 项测试、58 次失败。

Krkr 两个测试类现统一在 setUp 检查目标进程，配置错误时立即提示重新构建和安装，
并避免进入原生会话及其清理逻辑。步骤 5 给出两套完整命令，切换时必须重新安装测试 APK。
本轮证据位于 `.agent-work/m3-instrumentation-process/`：设备日志、已安装错误 APK 的 manifest、
两个测试类的错误配置前置检查及正确配置的隔离回归；目标应用 APK 与上一节 SHA-256 相同。
重新构建安装后，隔离设备回归 30/30、Python 98/98 通过。
证据：`reported-logcat.txt`、`reported-test-manifest.txt`、`wrong-process-guard.txt`、
`build-krkr.txt`、`krkr-test-manifest.txt`、`krkr-final.txt`、`python-final.txt`。

## 完整模拟器验收流程

### 1. 确认设备、安装和复制场景

在 Android Studio 启动已配置的 Medium_Phone。仓库根目录 PowerShell 执行：

```powershell
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adbPath devices
& $adbPath -s emulator-5554 install -r launcher-app/build/outputs/apk/debug/launcher-app-debug.apk
& $adbPath -s emulator-5554 shell mkdir -p /sdcard/Download/TwinQuill-M3
& $adbPath -s emulator-5554 push tests/fixtures/krkr-m3/m3-loose /sdcard/Download/TwinQuill-M3/
& $adbPath -s emulator-5554 push tests/fixtures/krkr-m3/m3-compressed /sdcard/Download/TwinQuill-M3/
& $adbPath -s emulator-5554 push tests/fixtures/krkr-m3/m3-patches /sdcard/Download/TwinQuill-M3/
& $adbPath -s emulator-5554 push tests/fixtures/krkr-m3/m3-cp932 /sdcard/Download/TwinQuill-M3/
& $adbPath -s emulator-5554 push tests/fixtures/krkr-m3/m3-save /sdcard/Download/TwinQuill-M3/
& $adbPath -s emulator-5554 shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n io.github.twinquill/.launcher.MainActivity
```

开发结束时已将上述 APK 安装到当前模拟器，并复制这五个目录，当前停在游戏库。
这五个目录是自制测试资源，无需手写脚本或下载游戏。
从游戏库添加目录，在系统选择器进入 `Download/TwinQuill-M3/<场景>`，
点击“使用此文件夹 / USE THIS FOLDER”并允许授权。分别添加五个场景，
授权每个场景自身目录，随后启动 Kirikiri 引擎。

### 2. 验证松散与压缩 XP3 场景一致

先启动 m3-loose，再启动 m3-compressed。两者都应出现已验收 M2 的 320×200 场景：
深蓝灰背景、左上蓝白 PNG 棋盘、右侧绿色 JPEG、底部黄色“TwinQuill 中文”、
中部半透明暗红矩形及右侧约每 500 ms 蓝/黄切换的徽标。周围黑边正常。

每个场景点击中部矩形，应变为绿色，徽标继续切换。按 Home，等待至少 3 秒，
从最近任务返回：图片、中文、绿色状态和 Timer 恢复，无 Script Error。
再按 Home，等待 3 秒，通过应用图标返回，结果相同。两个返回路径各重复两次，
不能出现遮挡游戏的新游戏列表。压缩场景只有 data.xp3；没有松散图片或脚本可供回退。

执行 Enter 正常退出：

```powershell
& $adbPath -s emulator-5554 shell input keyevent 66
```

重新启动应恢复初始红色，之后用模拟器 Back 正常退出。各重复三次。

### 3. 验证补丁与编码

启动 m3-patches：应为绿色画面 `#228844`。
脚本先断言 patch.xp3 覆盖 data.xp3、松散 loose.txt 覆盖归档，
再检查日文成员、显式搜索路径和子目录枚举；任一失败会返回 Script Error。
Enter 或 Back 应正常退出。

启动 m3-cp932：应为紫色画面 `#663399`。
根配置明确启用 CP932，归档内 startup 及日本語.tjs 都按该编码读取。
正常显示证明文本解码及日文成员查找通过；Enter 或 Back 正常退出。
错误配置、错误字节和 Unicode BOM 优先级由自动化验证，不需手工破坏场景。

### 4. 验证私有落盘与重启

首次启动新添加的 m3-save 条目，应为蓝色 `#3366cc`；正常退出后，
启动**同一游戏库条目**应变为绿色 `#22aa66`。
脚本每次增加并写回计数，另保存/读回含中文、日文的字典。Home 返回保持画面；
退出后再次启动仍绿色，不应重置或出现 Script Error。

可进一步验证进程重启：先正常退出，在 PowerShell 执行后手动重开 TwinQuill，
再启动同一 m3-save 条目：

```powershell
& $adbPath -s emulator-5554 shell am force-stop io.github.twinquill
& $adbPath -s emulator-5554 shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n io.github.twinquill/.launcher.MainActivity
& $adbPath -s emulator-5554 shell run-as io.github.twinquill ls -R files/saves
```

画面仍绿色。输出找到该条目游戏 ID 下的 `krkr/checks/`，应有
counter.tjs、last-good.tjs、state.tjs。使用实际 ID 读取：

```powershell
& $adbPath -s emulator-5554 shell run-as io.github.twinquill cat files/saves/<实际game-id>/krkr/checks/counter.tjs
& $adbPath -s emulator-5554 shell run-as io.github.twinquill cat files/saves/<实际game-id>/krkr/checks/last-good.tjs
```

尖括号是需要替换的占位符。计数应递增，last-good.tjs 始终为 GOOD。
也可在 Android Studio Device Explorer 的应用私有 files/saves 中查看。
原游戏目录内不能新增这些文件。两游戏 ID 隔离、ONS 原布局和失败保留旧文件
由下一步实际设备/原生回归验证；不要求现有游戏库支持存档管理界面。

### 5. 自动化边界与 ONS 回归

从仓库根目录执行：

```powershell
python -m unittest discover -s tests -v
python scripts/check_repository_hygiene.py
python scripts/verify_source_snapshots.py
.\gradlew.bat --no-daemon :launcher-app:assembleDebug :launcher-app:assembleRelease :launcher-app:lintDebug
.\gradlew.bat --no-daemon :engine-api:testDebugUnitTest :engine-ons:testDebugUnitTest
.\gradlew.bat --no-daemon :launcher-app:connectedDebugAndroidTest
.\gradlew.bat --no-daemon :launcher-app:connectedDebugAndroidTest -PtwinquillKrkrRuntimeInstrumentation=true
python scripts/check_apk_native_libraries.py launcher-app/build/outputs/apk/debug/launcher-app-debug.apk launcher-app/build/outputs/apk/release/launcher-app-release-unsigned.apk
```

预期 Python 98、默认设备 29、隔离设备 30、JVM 9 项，无失败。
上述 Gradle connected 测试会卸载目标 APK；它们应在手工验收之前完成，或完成后重做步骤 1。
卸载会删除应用私有数据，不能用卸载前后的计数判断持久化。

已有验收数据时，默认设备回归改用以下覆盖安装与直接运行方式。先正常退出游戏，
测试期间不操作模拟器；这些命令保留应用数据。

```powershell
.\gradlew.bat --no-daemon :launcher-app:assembleDebugAndroidTest -PtwinquillKrkrRuntimeInstrumentation=false
& $adbPath -s emulator-5554 install -r launcher-app/build/outputs/apk/debug/launcher-app-debug.apk
& $adbPath -s emulator-5554 install -r launcher-app/build/outputs/apk/androidTest/debug/launcher-app-debug-androidTest.apk
& $adbPath -s emulator-5554 shell am instrument -w -r -e notClass 'io.github.twinquill.engine.krkr.KrkrRuntimeHostInstrumentedTest,io.github.twinquill.launcher.KrkrBrokerLifecycleInstrumentedTest' io.github.twinquill.test/androidx.test.runner.AndroidJUnitRunner
```

查看结尾应为 `OK (29 tests)`；am instrument 的退出码不能代替测试结果。

Krkr 隔离回归必须完整执行以下三步：带参数构建测试 APK、覆盖安装该 APK、运行指定类。
参数在构建时写入 manifest，`-e class` 只选择用例，不能改变目标进程。

```powershell
.\gradlew.bat --no-daemon :launcher-app:assembleDebugAndroidTest -PtwinquillKrkrRuntimeInstrumentation=true
if ($LASTEXITCODE -ne 0) { throw 'Krkr test APK build failed' }
& $adbPath -s emulator-5554 install -r launcher-app/build/outputs/apk/androidTest/debug/launcher-app-debug-androidTest.apk
if ($LASTEXITCODE -ne 0) { throw 'Krkr test APK install failed' }
& $adbPath -s emulator-5554 shell am instrument -w -r -e class 'io.github.twinquill.engine.krkr.KrkrRuntimeHostInstrumentedTest,io.github.twinquill.launcher.KrkrBrokerLifecycleInstrumentedTest' io.github.twinquill.test/androidx.test.runner.AndroidJUnitRunner
```

预期结尾为 `OK (30 tests)`。两种测试 APK 共用文件名和包名；每次切换测试类型，
必须按相应命令重新构建并覆盖安装。不能直接复用刚跑完另一组测试的 APK。

独立存储测试使用当前 Debug CMake 输出下的 ARM64 可执行文件：

```powershell
python scripts/run_krkr_storage_test.py --adb "$adbPath" --binary engine-krkr/build/intermediates/cxx/Debug/<当前hash>/obj/arm64-v8a/twinquill_krkr_storage_test --serial emulator-5554
```

当前 hash 可在 `engine-krkr/.cxx/Debug/` 查看；不要盲用旧目录。
Debug 构建会生成该测试；运行器只清理其 UUID 测试目录。
图像 decoder 构建及运行继续按 [M0 门槛](KRKR_M0_HANDOFF.md) 执行。
在已有 ONS 验收场景检查启动、正常退出及既有存读档，结果应与本分支基线一致。

### 6. 记录签收

记录 Debug APK SHA-256、模拟器/API、步骤 2–4 结果及步骤 5 测试结果。
全部一致后反馈“验收通过”，再按用户授权提交 M3 并继续 M4。
有异常时保留实际场景名称、步骤和日志：

```powershell
& $adbPath -s emulator-5554 logcat -v time 'TwinQuill/Krkr:I' 'TwinQuill/KrkrActivity:I' 'TwinQuill/KrkrRuntime:I' '*:S'
```

## 支持边界与下一阶段

M3 是资源及持久写入基础；尚未接通完整 KAG 游戏框架、场景槽位存读档、
更多图像/媒体或插件。分别按 M4、M5、M6 推进。

标准 XP3 必须从第 0 字节开始，不支持专用解密/过滤器、EXE 前缀或其他压缩方法。
可 seek 介质最多 4 GiB，非 seek SAF 临时缓存 128 MiB，压缩段各 32 MiB，
索引合计 16 MiB，文本解码 8 MiB；完整额度见存储契约。
探测只验证元数据，损坏的后续资源在实际读取时报告失败。
Provider 自身阻塞的 Binder/IO 没有硬超时；不把脚本执行预算描述为任意 IO 的保证。
