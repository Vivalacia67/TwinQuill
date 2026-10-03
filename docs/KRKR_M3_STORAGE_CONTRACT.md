# Krkr M3：存储、路径、编码与写入契约

更新：2026-10-04。适用分支：refactor/krkr-direct-integration。M2 基线提交 db8f24b。
本契约描述 M3 存储基础；完整 KAG 执行和游戏状态存读档分别属于 M4、M5。

## 实现与源码边界

统一入口是 engine-krkr/src/main/cpp/krkr_resource.*，松散文件与 SAF 后端在
krkr_resource_backend.cpp；XP3 在 krkr_xp3.cpp。图片、脚本、文本和二进制读取
共用解析器；TJS 二进制读流支持 seek 和分段读取。字体、媒体文件可作为资源流读取，
其具体格式、外部字体渲染和媒体播放能力按后续阶段扩展，不因字节可读而声称格式兼容。

依据固定 Kirikiroid2 d1c2b1259423542c893e0b65eaeb46c848848f2b：
StorageIntf.cpp 的 NormalizeInArchiveStorageName、TVPGetPlacedPath、
TVPAutoPathTable.Add；XP3Archive.cpp 的 info/segm/adlr 和续索引；StorageImpl.cpp 的
GetListAt。M3 编写有界 Android 后端，不纳入完整上游桌面存储实现或新上游翻译单元。
解压复用已审计 zlib 静态目标，最终链接仍经既有 Cocos 边界检查。

补丁 0008-tjs-text-stream-abort.patch 只增加 TJS 文本写流 Abort 默认钩子，并替换
Array.save/saveStruct、Dictionary.saveStruct 的异常清理调用；旧宿主默认行为保留。
Android 写流在 Abort 时丢弃缓冲，成功 Destruct 才提交。补丁应用于生成副本，
摘要记入 third_party/sources.toml，源快照保持原样。

## 路径与查找

- 相对游戏根路径，例如 images/checker.png；显式 ./（或 .\）前缀限定游戏根及根挂载归档，
  不回退到短文件名搜索目录。反斜线转正斜线。
  拒绝绝对路径、盘符、NUL、点段、上级段和嵌套归档。松散路径保留原拼写；
  本地按文件系统匹配，SAF 沿用唯一的大小写无关回退，歧义不选取。
  XP3 成员按上游只折叠 ASCII A–Z，Unicode 名称不做语言相关转换。
- 显式归档路径：data.xp3>images/checker.png。中文/日文成员名使用严格 UTF-16LE
  索引和 UTF-8 路径桥接；不接受非法代理项。归档内规范化后重名直接报损坏。
- 当前游戏根松散文件优先。TwinQuill 自动登记根目录的 XP3，按 ASCII 折叠名称
  升序、原名称打破同名排序，后登记者优先；通常 patch.xp3 覆盖 data.xp3。
  这是明确的启动策略，来自 TVP 搜索表的后添加覆盖语义；不模拟桌面 EXE 搜索。
- 默认归档根支持相对成员子目录。显式 addAutoPath 的目录只按请求的文件名查找，
  后添加路径优先；removeAutoPath 和 clearAutoPathCache 清除归档索引缓存。
  新增/移除根归档应退出并重启游戏，启动时重新发现，不热更新根目录快照。
- Storages.isExistentStorage、getPlacedPath、addAutoPath、removeAutoPath、
  clearAutoPathCache 可供脚本使用。M3 扩展 getListAt 返回数组；松散/SAF 列出
  普通文件，XP3 列出直接成员及带 / 的直接子目录，返回值为相对名称。
- 未配置游戏文件写权限；任何松散文件的打开都是只读。不存在的查询返回 false，
  介质权限失败、损坏归档和非法路径不会伪装成不存在或成功。

### M4 的 KAG 目录补充

为完整 KAG 框架接通 `system/`、`scenario/` 等普通搜索目录：getListAt 合并物理目录
与根 XP3 中的直接文件/子目录；松散名称优先，归档按现有覆盖顺序去重，合并后最多
4096 项。注册物理目录只校验权限和目录类型，避免逐个查询子文件；显式列举仍执行
4096 项上限。虚拟归档目录仍校验索引。注册普通目录后，请求文件名也能解析到该目录下的归档成员。权限和归档
元数据仍检查；每次查找内使用弱引用复用索引，不扩大四个索引缓存额度。

SAF 在单次文件查找内复用同一目录的子项快照，结束或抛错立即清除；后续查找
重新查询，不跨请求缓存不存在的文件。文档 stat/open 仍向 Provider 校验访问。
快照最多 128 个目录、合计 4096 项、524288 个名称/ID 字符；超额回退到原有
流式解析，不因优化额度拒绝可读文件。该可选查找作用域仅由 Krkr 使用。

私有 `tqsave://.//...` 接受框架拼接产生的额外前导分隔符，但仍拒绝上级段、外部路径
和嵌套归档。原子写入和游戏隔离规则不变。证据见 [M4 审计](KRKR_M4_INTERFACE_AUDIT.md)。

## XP3 支持与限制

支持头从文件第 0 字节开始的标准未保护 XP3：多成员、子目录、raw/zlib 索引、
续索引、raw/zlib 混合多段和空文件。要求 info、segm、adlr，验证范围和总长度。
原始段按需读；压缩段首次读取时有界解压，最多缓存一个段。检查 zlib 校验及完整
输入/输出长度；如上游一般读取，不另校验整个文件的 adlr（它保留为格式字段）。

不支持受保护文件、外部解密/过滤器、自解压 EXE 前缀、嵌套归档或其他压缩方法。
启动器探测同样验证全部索引和段元数据；不预先解压每个资源。损坏的后续资源
可能被识别为游戏，实际请求时仍明确报错；识别成功不代表所有资源完好。

| 边界 | 上限 / 处理 |
| --- | --- |
| 可 seek 文件 / 归档 | 4 GiB；整个成员最多 512 MiB |
| 非 seekable SAF | 只读临时缓存最多 128 MiB，关闭或失败后清理；超限返回明确错误 |
| 索引 | raw/zlib 压缩输入均最多 16 MiB；所有解压后续索引合计 16 MiB |
| 索引 / 成员 / 段 | 最多 32 个索引，65,536 成员；每成员 4,096 段，归档共 262,144 段 |
| 压缩资源段 | 压缩及解压后各最多 32 MiB；解压实际长度必须等于声明 |
| 整体读取 / 文本 | 字节整体读取或写入最多 32 MiB；TJS/文本解码最多 8 MiB |
| 路径 / 搜索 / 缓存 | UTF-8 路径最多 4,096 字节；32 个根归档、128 搜索路径、4 个索引缓存 |
| 单目录枚举 | 最多 4,096 子项；Krkr 专用 VFS 在 Java 分配结果帧前拒绝超限 |

缓存仅复用大小、修改时间均已知且未变的索引；每次查找检查介质权限与元数据，
SAF 流每次读取也重新检查访问。未知元数据重新解析；解压段缓存命中也触及介质。
局部文件的规范路径必须留在游戏根内。外部对同大小、同时间文件的修改需清缓存
或重启。Provider 自身阻塞的 Binder/IO 仍不具备硬超时；上述限制约束读取量和内存，
TJS 执行期限不能中断已阻塞的 Provider。

## 显式文本编码

根目录可放 UTF-8（或带 BOM Unicode）twinquill-krkr.conf，最多 4 KiB：

    # 只影响此游戏的无 BOM 文本；修改后退出重启
    textEncoding=cp932

唯一键为 textEncoding；重复键、未知键、空值或未知编码报错。配置只读取松散根，
不从归档自动搜出，避免启动编码依赖编码自身。默认 utf-8；unicode/utf8 为其别名。
cp932、windows-31j、shift-jis、shift_jis、sjis 在此契约均明确选择 Android
windows-31j 映射，使用 REPORT 拒绝错误和不可映射字节，不做猜测。
UTF-8/UTF-16 BOM 优先于游戏的旧编码设置，始终按 M1 严格 Unicode 规则读取。

Scripts.execStorage/evalStorage 第二参数可明确指定这些编码；M3 扩展
Storages.readText 同样支持第二参数。Unicode 文件名与文本内容编码独立。
私有文件默认 UTF-8，保持跨启动一致；动态 Scripts.exec/eval 仍是 Unicode 文本。

## 每游戏私有写入

生产 broker 先验证 filesDir/saves/<game-id>，再将同一目录绑定进持久 TJS 会话；
Android 目录别名先规范化；存档基目录及每游戏目录不接受符号链接重定向。
运行时请求不能用另一游戏的目录重用这个会话。System.dataPath 是只读属性
tqsave://./，对应 filesDir/saves/<game-id>/krkr/。ONS 继续使用已有父目录布局。

提供 M3 扩展 Storages.createFolders、writeText、writeBytes、readText、readBytes；
文本写 UTF-8，字节使用 TJS octet；readBytes(name, offset, count) 支持有界分段读取。
已有 Array.save/saveStruct 和 Dictionary.saveStruct 的默认文本模式接入原子写流；
其他文本模式及二进制对象序列化明确拒绝，后续按框架/存档要求审定。

写路径必须以 System.dataPath 开头；逐层用目录描述符和 O_NOFOLLOW 打开，
拒绝上级路径及目录符号链接。父目录最多 16 层，可自动创建。临时文件同目录
创建并完整写入，fsync、close 后以 renameat 原子替换，再 fsync 目录。
替换前失败保留原文件并清理临时文件；替换后的目录 fsync 失败意味着提交持久性
未确认，不能宣称旧内容仍在。进程死亡可能留下未提交临时文件，不把它当有效存档。

文本流序列化失败调用 Abort，丢弃部分缓冲。目录、文件 IO 和额度错误可由脚本
捕获，成功不伪造；游戏目录完全只读。这里提供持久存储基础，不包含 KAG 的
场景、变量、已读记录或 M5 游戏状态恢复。

## 回归与验收入口

同一组自制 m3-vectors 供原生读取和 Java 探测测试使用；另有松散/压缩显示、
补丁、CP932、私有重启和失败保存夹具。生成器使用固定 DEFLATE 编码及 zlib
独立校验，避免 Python 的 zlib 版本改变夹具字节。详见 KRKR_M3_HANDOFF.md。
