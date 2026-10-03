# Krkr M2：接口、源码与宿主审计

更新：2026-10-03。分支：`refactor/krkr-direct-integration`。
M2 基础显示已于 2026-10-03 通过用户模拟器验收；**尚未执行完整 KAG 框架**。

本文件保留 M2 签收时的接口范围；M3 后续存储/编码/序列化能力见
[存储契约](KRKR_M3_STORAGE_CONTRACT.md) 与 [M3 交接](KRKR_M3_HANDOFF.md)。

## 固定的框架和源码

- 主要核心保持 Kirikiroid2 `d1c2b1259423542c893e0b65eaeb46c848848f2b`。
- 首批框架选用官方 [krkrz/kag3](https://github.com/krkrz/kag3)，固定
  `1f3ab309106d210e3169bbbe0fb4e066ae463b42`，脚本自报
  `3.32 stable rev. 2`，该分发版本使用 UTF-8，适配过 KrkrZ。
  完整 39 文件源码快照在 `vendor/kag3-1f3ab309/`，来源、Git tree、
  `git archive` SHA-256、许可和文件校验已记录在 `third_party/`。
- 框架 README 及脚本头声明允许修改和分发，保留原文；该快照不包含
  原生二进制。M2 用它核对接口，M4 才接入完整执行链。
- 已锁定的 `vendor/krkrz/` 不作为另一套完整运行时一起链接；目前只沿用
  已审计的 Oniguruma 依赖来源。若后续移植其中某个模块，逐文件列明理由、
  类型/ABI 差异和测试，避免混用两套引擎的全局状态。

## 框架要求与阶段归属

下表来自固定快照的启动和类定义，不是完整兼容性声明。M2 使用自制标准
TJS 图像/文字场景；框架启动要求在 M4 继续闭合。

| 接口 | 框架中的依据和主要要求 | 安排 |
| --- | --- | --- |
| Window | `MainWindow.tjs` 的 KAGWindow；`setInnerSize`、`visible`、`caption`、`add`、主图层、关闭和输入事件 | M2 基础宿主；菜单/更多桌面属性在 M4 审定 |
| Layer / Font | `KAGLayer.tjs`、`MessageLayer.tjs`；父子层、尺寸/位置、透明度、顺序、`loadImages`、`drawText`、字体度量、图层输入 | M2 PNG/JPEG、字体、基础绘制；转场/更多格式在 M6 |
| Timer | `Conductor.tjs`、`MainWindow.tjs`；`new Timer(callback, '')`、`interval`、`enabled`、`onTimer` 事件 | M2 已接入上游绑定及 Android 后端 |
| AsyncTrigger / 连续事件 | Conductor 的单次触发、关闭流程和移动器 | M2 已接入 AsyncTrigger；全局连续事件留待 M4 |
| Scripts / Storages | `startup.tjs` → Initialize；execStorage、搜索路径、归档优先级、路径查询 | M1 有相对脚本读；通用存储和搜索在 M3 |
| KAGParser | Conductor 继承 KAGParser，处理脚本、标签、宏、跳转和条件 | M4；不能以源码文件存在视为已接入 |
| System / Debug / 序列化 | 标题、环境、异常处理、tick、数据路径、日志、字典/数组存储 | tick/日志已有；M3 写入、M4 启动要求、M5 存档 |
| MenuItem / Plugins | `Menus.tjs`；startup 的 `@if(kirikiriz)` 分支要求 `menu.dll`、`KAGParser.dll` | M4 审定内置注册和 Android 交互；不加载外部 DLL |
| WaveSoundBuffer / VideoOverlay | BGM、SE 和 Movie 的原生父类，部分对象在窗口构造时创建 | M4 必需音频；M6 视频与格式扩展 |

M4 必须明确预处理变量 `kirikiriz` 的取值及对应接口契约；不能只屏蔽插件
调用或忽略初始化错误后声称框架兼容。样例使用自制文字、分支、图片和声音，
不导入商业游戏资源。

## 翻译单元与依赖边界

`TVP_HOST_SOURCES` 明确加入以下四个固定上游翻译单元，生成副本应用补丁
0003–0007 后编译；源快照不变。既有最终链接检查继续执行，没有新增系统库、
未经审计的第三方库或预编译库。

| 编译单元 | 实际纳入范围 |
| --- | --- |
| `utils/TimerIntf.cpp` | 上游 Timer 绑定和基础实例；Android 调度半部在 `tvp/TimerImpl.h`、`krkr_tvp_events.cpp`。 |
| `visual/WindowIntf.cpp` | 上游 TJS Window 成员定义；选择 Android 基础窗口实例，排除桌面 Window 实现。 |
| `visual/LayerIntf.cpp` | 上游 TJS Layer/Font 成员定义；选择 Android 位图/字体实例，排除原始 Layer 算法和 FontSystem。 |
| `base/EventIntf.cpp` | 上游 AsyncTrigger 绑定/基础实例；排除桌面全局事件循环，使用会话 worker 队列。 |

基础视觉后端在 `krkr_tvp_visual.*`，不是完整上游 Layer/RenderManager 的移植。
补丁 0006 使用 `TWINQUILL_ANDROID_BASIC_TVP` 保留选定绑定，并让其余成员
抛出带方法/属性名的错误；矩形参数先检查再相加。未用空实现掩盖缺失功能。
PNG/JPEG 复用已审计 Cocos `CCImage` 和 libpng/libjpeg，文字使用既有 FreeType
与 Noto Sans CJK SC 2.004 字体；字体和 OFL 随 APK 打包。

以下完整桌面闭包已经检查，**没有加入编译白名单**：

| 上游位置 | 职责及 Android 适配点 |
| --- | --- |
| `visual/WindowIntf.cpp`、`visual/win32/WindowImpl.cpp` | 原生 Window 绑定、窗口列表与事件；替换平台窗口工厂，保留上游对象语义 |
| `visual/LayerIntf.cpp`、`visual/win32/LayerImpl.cpp`、`visual/LayerManager.cpp` | 原生 Layer/Font、图层树、命中和输入；逐项闭合绘制、事件与错误符号 |
| `visual/LayerBitmapIntf.cpp`、`visual/win32/LayerBitmapImpl.cpp`、`visual/ComplexRect.cpp` | 位图操作、裁剪和脏区域；限制尺寸、内存和写操作 |
| `visual/RenderManager.*`、`visual/win32/BasicDrawDevice.cpp`、`DrawDevice.cpp` | 实际合成和显示；需要独立后端审计，不能用 M1 方块替代 |
| `visual/GraphicsLoaderIntf.cpp`、`FontImpl.cpp`、`FreeTypeFontRasterizer.cpp`、`FontSystem.cpp` | 图片、字形和资源读取；优先复用已固定的 PNG/JPEG/FreeType/字体 |
| `base/EventIntf.cpp`、平台 EventImpl、TickCount、ThreadIntf、消息模块 | 窗口事件与全局服务；仅会话 worker 执行脚本，Android UI/GL 不执行 TJS |

直接把上述文件整体加入当前 CMake 会越过已有闭包。具体证据：

- `RenderManager.cpp` 直接包含 `libswscale/swscale.h`、`opencv2/opencv.hpp`、
  `xxhash/xxhash.h` 和 `lz4/lz4.h`；这些实现没有进入当前 Krkr 链接白名单。
- `MainScene.cpp` 的 `TVPCreateAndAddWindow` 拉入完整 Cocos 场景、UI、控制器和
  Kirikiroid2 菜单；当前只有受控的 Cocos 图像/数学子集。
- `WindowImpl` 的 `iWindowLayer` 是宿主适配点；需明确游戏尺寸、输出帧和输入
  坐标关系，并逐项处理支持的窗口操作。不得用空实现冒充受支持行为。

当前以实际 Android 基础后端闭合所选接口。M4/M6 按框架和格式需求继续移植；
每次扩充显式清单、来源/许可及链接门槛。

## 当前基础显示契约

- **Window：** 单窗口；`setInnerSize/setSize`、宽高、visible、caption、
  primaryLayer、focusedLayer、close。caption 保存在脚本对象，宿主无桌面标题栏。
  `close` 遵循 `onCloseQuery`；Android Back 使用宿主取消流程正常退出。
- **Layer：** 主层/子层、位置/尺寸与独立 image 尺寸/偏移、可见/启用、同窗口
  reparent、顺序/front/back、0–255 opacity、opaque/alpha、clip、holdAlpha、
  `fillRect/loadImages/drawText/getMainPixel/setMainPixel/update`、字体和度量。
  子层在父层矩形内裁剪，父层透明度应用于整组合成结果。update 调用 onPaint；
  普通像素修改直接标脏。图片只读相对松散路径，经已有 SAF/本地资源读契约。
- **Font：** 审计字体的正/负 height（绝对值 8–128）、face、实际文字宽高；
  UTF-16/中文、换行、kerning、抗锯齿或单色字形。drawText 支持 0–255 opacity，
  不支持 shadow、字体样式、任意外部字体或旋转；调用时报错。
- **输入/生命周期：** Window 和命中 Layer 的 TouchDown/Up/Move、MouseDown/Up/Move；
  主指针模拟左键，Layer 点击回调，捕获保留拖动目标，局部坐标按父层换算。
  黑边输入忽略。窗口及 focusedLayer 接收常用字母/数字、方向、Enter 等 KeyDown/
  Up/Press 和 TVP shift 标志；窗口接收 Activate/Deactivate/Resize。
  桌面双击、菜单、IME 和其他事件不属于已验证子集。
- **边界：** 64 层、树深 32、单边 1–2048、位置 ±32768；image 像素与 layer
  矩形像素分别总计最多 8 Mi 像素。单资源读 8 MiB，解码器仍执行已有 M0
  尺寸/内存门槛，解码后再次检查显示尺寸。文字最多 4096 UTF-16 单元。
  限制、损坏图片、路径越界和不支持成员返回 SCRIPT_ERROR；不会静默跳过。

CPU 合成在脚本 worker，RGBA 帧通过独立锁以不可变 shared_ptr 发布；GL 线程
仅上传纹理和绘制居中等比画面，不持有 VM 锁。画面状态保存在 CPU 会话中，
surface/Activity 重建只重建 GL 对象。无标准窗口的历史 M1 场景继续使用测试
宿主；标准窗口显式 invalidate 后为黑画面，不回退为测试方块。

## 启动和线程时序

1. Broker 验证请求、只读 SAF 授权和游戏私有存档目录。
2. `KrkrScriptSession.prepare` 读取和严格解码 startup，保留单个会话，尚不执行
   startup。旧调试入口的无宿主单次运行仍保留。
3. Runtime Activity 创建 GLSurfaceView 和原生宿主，接收真实 surface 尺寸。
4. `hostReady` 在同一脚本 worker 上排入一次激活，随后才处理 surface/input
   回调；UI/GL 仅提交事件、获取不可变显示帧及轮询原子状态。
5. 重新创建 surface/Activity 沿用该会话，不重复激活。进程死亡后旧句柄失效。
6. 结束时清除待处理回调，先发出不获取 VM 锁的取消标志，再由 worker 释放 VM；
   Broker/Runtime 仍通过统一结果协议返回。

## 调度与清理约束

启动执行预算 5 秒，普通回调/定时事件批次 2 秒，脚本终结清理预算 2 秒。
VM opcode 和 lexer token 每 1024 次检查时间/取消；嵌套 Scripts 调用共享外层
预算。使用上游 `eTJSSilent` 跨过脚本 catch，错误超时返回 SCRIPT_ERROR，用户
退出正常结束。这些预算覆盖脚本执行，不保证 Android Provider I/O 的硬超时。
关闭会话时显式无效化 Timer/AsyncTrigger 和视觉对象，打断 action 自引用；
关闭期间拒绝创建新的此类对象，字体上下文保持到 VM 释放之后。关闭期间的脚本
finalize 异常/超时记录日志后仍释放原生成员。脚本主动 invalidate 的异常照常传播。
新请求在 startup worker 上最多等待旧会话释放 5 秒，覆盖取消和 2 秒终结清理；
该等待不占用 Android UI/GL 线程。

Timer 和 AsyncTrigger 在会话 worker 上运行，由 GL 帧请求合并后的 tick；
最多 128 个 Timer、128 个 AsyncTrigger、256 个原生待处理事件、64 个 Java
待处理回调，最多一个待处理 tick。事件队列及当前派发批次合计受限；取消可
删除批次中尚未执行的事件，回调内新增事件留到下一 tick。AsyncTrigger 支持
cached、trigger、cancel 和 normal/exclusive/atIdle mode，非法 mode 报错。
interval 为 0 时不触发，有效范围 0–86400000 毫秒；实际最短调度间隔 3 毫秒，
受帧率影响。每帧最多为一个 Timer 发一次事件，按 exclusive、normal、idle
优先级派发。暂停不派发，恢复重置下一次时间，后台经过的时间不补发事件。
该策略是 M2 有界 Android 调度契约，不宣称复刻桌面定时器的所有时序。

完整阶段验收和后续范围见 [M2 交接](KRKR_M2_HANDOFF.md)；总体阶段边界见
[ROADMAP](ROADMAP.md)。
