# Krkr M5：存读档开发交接与模拟器验收

更新日期：2026-10-04。分支：`refactor/krkr-direct-integration`。
基线为已验收 M4 `78bb0b5`，M5 开发准备提交 `4406529`。
M5.1–M5.4 的开发及自动化验证已完成，待用户模拟器验收。用户于 2026-10-04
要求先提交代码、后续再验收；本轮按此顺序提交，阶段签收仍待验收完成后记录。
格式与失败边界见 [存档契约](KRKR_M5_STORAGE_CONTRACT.md)。

## 交付

| 工作 | 行为 |
| --- | --- |
| M5.1 | 真实 KAG 私有槽位、sc/su、系统变量与配置；ONS 原目录保留。 |
| M5.2 | 散文件及压缩 XP3，两槽位、变量、图像、文字链接、宏、跨文件调用栈、BGM 播放/暂停恢复。 |
| M5.3 | 正常退出重启、保存后终止引擎、部分写入失败、损坏/错误 ID 拒绝，以及槽位菜单元数据重建。 |
| M5.4 | `KrkrSaveStore` 查询、占用、快照、恢复和清理；运行时互斥及目录恢复日志。管理界面属于 M7。 |

新增 Layer `stopTransition` 允许停止没有活动转场的层，活动转场创建仍不支持。
编号补丁 `0012` 在生成副本应用；固定上游快照不变。元数据修复为第一方宿主
启动钩子，样例保留完整固定 KAG 框架字节。

## 自动化结果

| 检查 | 最终结果 |
| --- | --- |
| Python | 105/105；保留 M4 基线，新增完整固定框架及写入配置/独立格式 ID 检查。 |
| JVM | 13/13：engine-api 5、engine-ons 4、Krkr 存档文件集/互斥/恢复/预算 4。 |
| 默认设备 | 32/32；包含保存完成后终止实际 Krkr PID、跨进程存档互斥、重启读取以及既有 ONS 回归。 |
| Krkr 隔离设备 | 40/40；包含两种介质的两槽位、系统变量/文字配置/已读记录、真正执行的跨文件 RETURN、音频状态及元数据中断/坏数据/错误 ID。 |
| 补充槽位复核 | 1/1，内部遍历散文件与 XP3；故意破坏当前宏后加载有效已保存宏，核对恢复后人物像素。 |
| 构建 / lint | 两 ABI Debug/Release 通过；lint 0 错误、7 警告。 |
| 原生存储 | ARM64 退出 0：19 个共享 XP3 向量、部分写入/替换失败、符号链接、原子替换、另一进程抢锁及管理恢复中断。 |
| 原生解码 | ARM64 退出 0；损坏 JPEG 的诊断为预期输出。 |
| 来源 / 卫生 / APK | 7,257 个固定文件、补丁摘要、仓库卫生、两 APK 的原生库/ABI/对齐通过；生产 APK 无 KAG/M5 测试资产。 |

Debug APK：`launcher-app/build/outputs/apk/debug/launcher-app-debug.apk`，SHA-256：
`7bf8e9e247785b58c22d29fbe7e0eee72cf6a067fc3f09993de104a1ab17f804`。
未签名 Release：`launcher-app/build/outputs/apk/release/launcher-app-release-unsigned.apk`，SHA-256：
`88851b9dfdc42f115496f8655c487e30d0bb7d886e8cc0bca525cdccb09b13ad`。
M5 样例为完整框架加原创资源，共 50 个条目；压缩 XP3 SHA-256：
`41eff8482e414a901b9547767afe90f74c817e9f80efbd88aec322393e79c192`。

证据在 `.agent-work/m5-development/`：`build-final.txt`、`build-default-final.txt`、
`build-isolated-final.txt`、`build-focus-final.txt`、`python-final.txt`、`default-final.txt`、
`isolated-final.txt`、`slots-focus-final.txt`、`native-storage-final.txt`、
`native-decoder-final.txt`、`sources-final.txt`、`hygiene-final.txt`、`apk-final.txt`、
`artifact-final.json`。APK、样例生成结果和设备日志不提交仓库。

过程中的失败日志保留用于定位：标准 `[load]` 与场景完成标记解决异步恢复期间
点到旧链接的问题；终止测试检查实际 PID，允许 Android 重建同名缓存进程。
另一轮受遗留 ONS 测试会话干扰，重置测试进程后两套完整回归均通过。
验证设备沿用 Medium_Phone，Android 16 / API 36，x86_64 + ARM64 转译，
SwiftShader、Vulkan 关闭；只使用模拟器。API 26 与 ARMv7 实际运行未验证。

## 完整模拟器验收

### 1. 构建和覆盖安装

在 Android Studio 打开项目及 Medium_Phone，设备序列号使用 `emulator-5554`。
PowerShell 从仓库根目录运行：

```powershell
$env:JAVA_HOME='C:\Users\80473\.jdks\jdk-17.0.20.1+1'
$adbPath='C:\Users\80473\AppData\Local\Android\Sdk\platform-tools\adb.exe'
.\gradlew.bat --no-daemon :launcher-app:assembleDebug :launcher-app:lintDebug
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
& $adbPath devices
& $adbPath -s emulator-5554 install -r launcher-app/build/outputs/apk/debug/launcher-app-debug.apk
if ($LASTEXITCODE -ne 0) { throw 'APK install failed' }
```

使用覆盖安装；不卸载、不清空应用数据，现有游戏库及 M4 样例可以继续使用。

### 2. 生成并导入独立 M5 游戏

```powershell
.\.venv\Scripts\python.exe scripts/create_krkr_m5_fixtures.py
if ($LASTEXITCODE -ne 0) { throw 'Fixture generation failed' }
& $adbPath -s emulator-5554 shell mkdir -p /sdcard/Download/TwinQuill-M5
& $adbPath -s emulator-5554 push .agent-work/m5-acceptance/games/m5-loose /sdcard/Download/TwinQuill-M5/
& $adbPath -s emulator-5554 push .agent-work/m5-acceptance/games/m5-compressed /sdcard/Download/TwinQuill-M5/
& $adbPath -s emulator-5554 push .agent-work/m5-acceptance/games/m5-other /sdcard/Download/TwinQuill-M5/
```

在 TwinQuill 点击导入目录，通过系统文件选择器选择 Download → TwinQuill-M5
下的各个游戏目录并“使用此文件夹”。分别导入三项；选择游戏根文件夹，不能选
外层 TwinQuill-M5。识别为 Krkr；`m5-compressed` 根目录入口为 `data.xp3`。
如重复验收，复用原游戏卡片以保持 `game.id` 和存档关联，不删除后重新导入。

### 3. 槽位 0：分支前保存与原地读取

先启动 `m5-loose`。应看到紫色标题菜单及 `System boots: 1`（重复运行时递增）。
点击 `NEW GAME`，显示深绿色车站、人物，文字为 `route NONE, coins 7`，BGM 播放。
点击 `SAVE SLOT 0`，看到 `Saved slot 0` 才算保存成功。
点击 `ALPHA`，背景变蓝，显示 `route ALPHA, coins 8` 和 Chapter checkpoint。
章内 BGM 暂停；点击 `LOAD SLOT 0`，恢复车站、NONE/7、人物及 BGM。
再次点击 `BETA`，背景变琥珀色，变量为 BETA/9，证明读档后的选择链接可继续使用。

选择链接由原 KAG 防止同位置连续误触；从 NEW GAME 进入 SAVE 时点击两项各自
文字中部即可。不要连续发送完全相同坐标的模拟点击。

### 4. 槽位 1：跨文件调用与退出重开

在 ALPHA 或 BETA 的 Chapter checkpoint 点击 `SAVE SLOT 1`，显示保存成功。
点击 `QUIT` 正常回到游戏库，结果应为 `NORMAL_EXIT`。
重开同一游戏，紫色菜单的 boots 增加；点击 `LOAD SLOT 1`。
应恢复刚保存的分支颜色及变量，章内 BGM 保持暂停。
点击 `RETURN`，显示 `Call stack restored correctly.`、原分支及 coins，BGM 继续播放。
点击 `LOAD SLOT 0`，应恢复早先的 NONE/7，证明两个槽位独立。
按 Home，等待 5 秒，从最近任务返回，画面和变量不变；不能返回游戏库或出现报错。
最后正常退出。

### 5. 保存后终止进程

重开 `m5-loose`，加载槽位 0，点击 BETA 并保存槽位 1，等待出现保存成功。
按 Home 后执行：

```powershell
& $adbPath -s emulator-5554 shell am force-stop io.github.twinquill
```

从模拟器桌面图标重新打开 TwinQuill，启动同一卡片并加载槽位 1。
应恢复琥珀色 BETA/9，RETURN 仍正确；槽位 0 保持 NONE/7。
这是终止应用进程，不清除数据。不要使用 `pm clear` 或卸载。

### 6. 压缩游戏、隔离与 ONS

对 `m5-compressed` 重复步骤 3–5，先选择 ALPHA；应恢复蓝色 ALPHA/8。
此前 `m5-loose` 的 BETA/9 不受影响。
启动 `m5-other`，其初始槽位为空；新建游戏并选择另一条路线，分别保存两个槽位。
再回到另外两个游戏，加载各自槽位，确认没有串档。
最后启动已有 ONS 游戏，读取原有存档、保存后重开；回到 M5 游戏还能读档。
ONS 现有数据只通过游戏内操作验证。

### 7. 自动化故障与全量回归

部分写入、损坏存档、错误格式 ID、元数据中断、后台进程互斥及恢复目录中断
由测试自己的临时存档注入，不手动破坏已有玩家文件。

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\gradlew.bat --no-daemon :engine-api:testDebugUnitTest :engine-ons:testDebugUnitTest :engine-krkr:testDebugUnitTest
```

默认设备测试（ONS、探测及生产启动协议）：

运行每套测试前先正常退出手工游戏。以下命令会终止残留测试进程，保留应用数据。

```powershell
.\gradlew.bat --no-daemon :launcher-app:assembleDebugAndroidTest -PtwinquillKrkrRuntimeInstrumentation=false
if ($LASTEXITCODE -ne 0) { throw 'Default test build failed' }
& $adbPath -s emulator-5554 install -r launcher-app/build/outputs/apk/androidTest/debug/launcher-app-debug-androidTest.apk
if ($LASTEXITCODE -ne 0) { throw 'Default test install failed' }
& $adbPath -s emulator-5554 shell am force-stop io.github.twinquill
& $adbPath -s emulator-5554 shell am instrument -w -r -e notClass 'io.github.twinquill.engine.krkr.KrkrRuntimeHostInstrumentedTest,io.github.twinquill.launcher.KrkrBrokerLifecycleInstrumentedTest' io.github.twinquill.test/androidx.test.runner.AndroidJUnitRunner
```

Krkr 隔离测试（显示、生命周期、存储、完整 KAG 及存档）：

```powershell
.\gradlew.bat --no-daemon :launcher-app:assembleDebugAndroidTest -PtwinquillKrkrRuntimeInstrumentation=true
if ($LASTEXITCODE -ne 0) { throw 'Krkr test build failed' }
& $adbPath -s emulator-5554 install -r launcher-app/build/outputs/apk/androidTest/debug/launcher-app-debug-androidTest.apk
if ($LASTEXITCODE -ne 0) { throw 'Krkr test install failed' }
& $adbPath -s emulator-5554 shell am force-stop io.github.twinquill
& $adbPath -s emulator-5554 shell am instrument -w -r -e class 'io.github.twinquill.engine.krkr.KrkrRuntimeHostInstrumentedTest,io.github.twinquill.launcher.KrkrBrokerLifecycleInstrumentedTest' io.github.twinquill.test/androidx.test.runner.AndroidJUnitRunner
```

切换时必须重新构建并安装测试 APK；不能只改 `-e class`。
每套命令中的 `am force-stop` 用于重置上一套测试的进程现场；遗留 ONS 会话
可能干扰测试任务的清理断言。
以最后的 `OK (...)`、零失败为准，不能用 `am instrument` 退出码代替。
避免使用会卸载测试包或目标应用的 connectedDebugAndroidTest。
源码/卫生/APK 和独立原生运行器命令沿用 [M3 交接](KRKR_M3_HANDOFF.md#5-自动化复核)。

## 签收与下一阶段

本轮先提交 M5 开发结果。上述流程完成、无误后记录用户验收；如有问题，补充修复提交。
下一阶段为 M6 媒体/转场/插件
兼容矩阵及必需实现；统一存档导出、恢复及清理界面在 M7。
当前结果只针对固定框架及自制样例，不扩大到任意商业游戏。
