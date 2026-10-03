# Krkr M4：KAG 框架与 Android 接口审计

更新日期：2026-10-04。分支：`refactor/krkr-direct-integration`。
本文件记录 M4 自制样例的实际支持范围；验收步骤与最终验证结果见
[M4 交接](KRKR_M4_HANDOFF.md)。M2 的显示预算及 M3 的存储契约继续适用。

## 框架、启动链与来源

核心仍为 Kirikiroid2 `d1c2b1259423542c893e0b65eaeb46c848848f2b`；
KAG3 固定于 `1f3ab309106d210e3169bbbe0fb4e066ae463b42`，版本
`3.32 stable rev. 2`，39 文件快照保留在 `vendor/kag3-1f3ab309/`。
许可、文件校验和补丁摘要见 `third_party/sources.toml`，没有修改固定快照。

游戏提供框架。会话发现 `system/Initialize.tjs` 后注册 Android KAG 适配器，
执行真实 `startup.tjs → Initialize.tjs → Config.tjs → 框架类 → KAGWindow → first.ks`。
Utils、History、BGM、SE、Movie、Conductor、Animation、Graphic、Message、Menus、
DefaultMover 和 MainWindow 的完整脚本均实际加载；Movie 类加载不代表视频可播放。
预处理变量 `kirikiriz=0` 选择内置 Kirikiri 2 原生类路径；不加载外部 DLL。
生产 APK 只嵌入第一方适配脚本与字体，不打包测试游戏或 KAG 框架副本。

样例仅覆盖游戏的 `Config.tjs` 和 `first.ks`，另加 `chapter.ks` 与原创资源。
其他框架文件保持快照字节。配置选择 640×480、普通横排 Noto 字体、alpha 图层、
一个立绘层、一个消息层、一个 SE 缓冲、零视频槽，关闭桌面菜单和自动记录。
`readOnlyMode=true` 暂不启用 KAG 游戏状态落盘；M3 私有写入能力保留，槽位存读档属于 M5。

## 上游源码闭包与补丁

`TVP_HOST_SOURCES` 从四个增加为五个：TimerIntf、WindowIntf、LayerIntf、EventIntf
和 `utils/KAGParser.cpp`。显式列举文件；不纳入完整桌面、Cocos 场景或媒体闭包。
新增第一方 `krkr_kag_host.*`、`krkr_kag_audio.*` 和 `KrkrPcmAudio.java`。
依赖仍使用已审计的图像、字体、zlib 和 TJS 子集；原生最终链接门槛继续执行。

| 补丁 | 实际作用 |
| --- | --- |
| 0009-android-kag-parser | 在生成副本编译原生 KAGParser，连接资源流、表达式与日志；标签扫描、跳转、属性、宏展开循环检查执行预算，并限制宏、调用和条件栈。 |
| 0010-android-kag-bindings | 扩展 Window/Layer/Font 的选定绑定；添加对象所有权、位图操作、真实脚本命中判定、菜单树及旧版 KAG 所需属性。 |
| 0011-tjs-call-depth-budget | 在 TJS 函数入口限制嵌套深度，递归溢出变成脚本错误，避免原生栈崩溃。 |

补丁只应用于构建生成副本。源码回归逐个执行 `git apply --check` 和应用，
同时校验补丁 SHA-256；构建继续校验动态库与 Ninja 链接闭包。

## 已接入的接口与标签

| 接口 | M4 实际行为与边界 |
| --- | --- |
| KAGParser / Conductor | 使用上游原生解析器和完整框架调度；宏参数、标签、条件、表达式、跨文件 call/return、jump 及内嵌 TJS。不是另写简化场景解释器。 |
| System / Debug | 实际 tick、键状态、eventDisabled、连续事件队列、私有 dataPath、日志及脚本异常。桌面尺寸为逻辑 640×480；screenWidth/Height 为实际 Surface。exePath 为 ./，明确指向游戏根；无启动参数；单 VM 注册表落实应用锁。inform 抛出可定位错误。 |
| Window / MenuItem | 实际保留并释放 KAG 管理的对象，处理关闭、层输入及尺寸。菜单是有界逻辑对象树，支持 caption/checked/enabled 等；桌面 popup 明确拒绝，没有 Android 菜单 UI。边框、位置、hint/cursor 等只作逻辑元数据。 |
| Layer / Font | PNG/JPEG、普通横排字形、alpha/opaque 绘制、copyRect/operateRect/assignImages、颜色矩形、8 位 province、绝对顺序及模态命中。实际调用脚本 onHitTest，文字页可穿透非活动图层，选择链接可响应触摸。 |
| Timer / AsyncTrigger | 在脚本 worker 调度框架推进、页面等待、声音状态和连续事件；暂停期间不推进，恢复继续同一 VM。 |
| Storages / Scripts | 继承 M3 只读 SAF、松散、XP3、Unicode/CP932 和私有序列化。根 XP3 内的虚拟目录可枚举，并映射 KAG 的 system/scenario/image 等搜索路径。 |
| WaveSoundBuffer / BGM / SE | AudioTrack 实际播放 PCM16 WAV；open/play/stop、整段循环、音量、手动暂停、实际播放位置及结束状态回调。Home 暂停播放头，返回继续；退出释放全部音轨。 |

端到端样例实际覆盖：`macro/endmacro`、`emb`、`image`、`nowait`、`r`、`p`、
`cm`、`link/endlink`、`s`、`eval`、`jump`、`call/return`、`iscript/endscript`、
`playbgm/stopbgm`、`playse`、`wait` 和 `close`。背景使用直接切换。
标签能被解析不等于其全部参数、插件或媒体后端已受支持。

## 额度与明确限制

- KAG 启动预算 20 秒、单次回调 10 秒、清理 2 秒；普通 TJS 启动/回调仍为
  5/2 秒。TJS 调用、KAG 宏/场景调用/条件嵌套最多 128 层，场景缓存最多 8 项。
  脚本和原生解析循环有预算检查；Provider 的 Binder/IO 阻塞没有硬超时保证。
- 沿用最多 64 个图层、单边 2048 像素、图像/显示面积各 8M 像素、图层深度 32
  等 M2 限额。Window 管理对象最多 256；每个菜单最多 256 子项。
- 沿用 M3 四个归档索引缓存；同次查找用弱引用复用已刷新索引，后续查找仍刷新
  权限和元数据。合并目录最多 4096 项，根归档最多 32、搜索路径最多 128。
  SAF 同次查找共享有界目录快照，作用域结束立即清除；详见 M3 存储契约。
  框架的 graphicCacheLimit 配置不提供可调图像缓存；使用固定显示额度。
- PCM16 限 mono/stereo、8–48 kHz、有效 RIFF/WAVE 与对齐采样；最多 16 音轨，
  总 PCM 缓冲 8 MiB。仅整段循环；不提供 WAV cue/smpl 标记语义、压缩音频或流式解码。
- 非零 pan、音量 fade、音频 seek、VideoOverlay 创建、非恒等 gamma、特殊字体样式
  和未支持的混合/转场明确报错。普通 PCM 没有标签；stopFade 在未启用 fade 时无待取消任务。
- 桌面菜单、复杂字形/竖排、动画资源、TLG、视频、插件、音频焦点、游戏槽位存读档
  和任意商业游戏兼容均不属于本轮证据。分别按 M5/M6/M8 补齐。

窗口先释放所管理的框架对象，再释放残留图层、Timer、连续事件、解析器缓存和 VM；
最终释放所有音轨并清除当前 KAG 注册指针。错误场景和正常退出后均可再次启动游戏。
测试资源由仓库生成器重现，不提交商业素材、原生二进制或 APK。
