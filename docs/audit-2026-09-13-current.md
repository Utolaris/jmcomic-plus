# jmcomic-plus 当前版本全面审计

日期：2026-09-13。基线：`63e95c79a88a35527e802b428019841e6e344778`。审计开始时工作区干净。本报告审查四批修复后的代码，不将上一份报告的结论直接当作当前缺陷。全程未使用子代理，未修改产品代码。

## 结论

**当前仍有 8 项需要修复的功能或安全问题：2 项 P1、6 项 P2。** 最先处理备份密码的快速猜测通道和安全设置的虚假保存成功，再处理恢复入口绕过已有锁、备份结构校验、阅读器返回事件、下载索引并发及账号／DNS 边界。

架构已经形成可辨认的协调入口，上一轮通知工具到 MainActivity 的依赖环已消除。当前主要维护成本来自**失败契约不完整、业务状态分散、同一功能经过多次模型转换，以及规则与实现不同步**。继续机械移动目录或给每个 setter 增加接口，不是优先解决办法。

| 编号 | 优先级 | 当前问题 | 证据等级 |
|---|---|---|---|
| R01 | P1 | 加密备份仍暴露无盐 SHA-256 密码／图案摘要，可绕过慢速派生进行离线猜测 | 实际编解码器复现 |
| R02 | P1 | 安全存储未确认写盘就返回成功；普通隐私设置还忽略明确的写入失败 | 存储边界与设置管理器复现 |
| R03 | P2 | 恢复设置可重新开启引导，已有应用锁被引导入口绕开 | 状态复现＋UI 调用链 |
| R04 | P2 | `comicCache.groups:null` 仍能使恢复内容选择回调抛异常 | 实际 ViewModel 回调复现 |
| R05 | P2 | 阅读器返回键回调重复注册，离开页面后不移除 | 源码＋Android API 契约 |
| R06 | P2 | 同组章节并发完成后，索引仍可能保留未完成状态 | 实际完成操作并发复现 |
| R07 | P2 | CDN 健康探测绕过 DoH，仍走系统 DNS | 生产 DI 与请求路径核验 |
| R08 | P2 | 历史漫画分页与选择状态仍未绑定账号会话 | 缓存、刷新及删除调用链核验 |

P1 表示应优先修复的安全承诺缺口；P2 表示有具体触发条件的行为、隐私或可靠性问题。优先级不是 CVSS 分数。未确认无条件远程代码执行、SQL 注入或任意账号接管。

## 范围与验证

全局扫描了 **353 个主源码 Kotlin 文件、43,572 行**，以及清单、资源权限、依赖、R8 规则、测试和检查脚本。深入追踪了备份／恢复、应用锁／持久化、账号／历史／收藏、下载／缓存迁移、阅读器／导航、网络／DoH／更新链路；其余展示代码进行了结构、危险调用和维护性检查。这里的“全面”指覆盖这些审计维度与主要边界，不表示每个 UI 状态均在设备上执行过。

| 验证 | 本次结果 |
|---|---|
| 完整 JVM 测试 | **515 项通过，0 失败、0 错误、0 跳过**；其中既有测试 509 项，本次审计复现 6 项 |
| Android Lint | 0 错误、13 警告；12 项版本提示、1 项空 `super.onCleared()` 调用 |
| 发布版 R8 构建 | `minifyReleaseWithR8` 通过；未执行签名安装或发布 |
| 发布 JSON 字段检查 | 对本次 DEX 重跑，12 个模型、50 个列举字段通过；另确认 `encryptionSalt` 仍保留原名 |
| 依赖公告查询 | 从 `releaseRuntimeClasspath` 解析出 229 个组件，逐一查询 OSV；1 个组件命中公告，详见下文 |
| 架构扫描 | 现有边界测试通过；重新生成 import 耦合表并统一根文件命名后计算 SCC |
| Android 设备测试 | 未运行：`adb devices -l` 没有已连接设备 |

六项复现的“通过”表示测试成功见证了缺陷，**不是问题已被修复**。存储故障使用可控替身，并发测试使用真实业务操作与可控文件端口；没有故意填满设备、制造 OOM 或对真实账号执行删除。

## 问题详情

### R01 · P1 · 备份加密保留了快速密码验证通道

位置：[BackupManager.kt:135](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/backup/BackupManager.kt:135)、[密钥派生:371](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/backup/BackupManager.kt:371)。

新备份的内容确实经过 AES-GCM 加密，但公开的 `meta.passwordHash` 和 `meta.patternHash` 仍分别保存 `SHA-256(password)`、`SHA-256(pattern)`。拿到备份文件的人，每猜一次只需做一次 SHA-256 比对，不需要执行 120,000 次 PBKDF2 迭代。猜中后才做一次真正解密；“密码＋图案”还可分开猜测两个字段。

复现使用本地构造的备份，仅比较候选字符串的 SHA-256，恢复两个凭据后成功解密出真实设置。没有破解真实用户密码。风险主要影响短 PIN、图案及常见密码；高熵密码仍需要被猜中，不能将此描述为 AES 本身失效。快速摘要不适合用作密码验证数据，参见 [OWASP 密码存储指南](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)。

**修复建议：**新格式删除快速摘要，以派生后的密钥及 GCM 标签判断凭据是否正确；若必须保留验证字段，也应使用独立盐和合适的慢速派生。两个凭据使用有结构的编码输入，明确升级格式与旧文件兼容路径。旧明文备份不会因为客户端升级而自动获得保密性。

**验收：**备份中不能用一次快速摘要判断猜测是否正确；错误凭据与篡改密文均不能恢复内容。

### R02 · P1 · 保存成功没有覆盖真正的写盘失败

位置：[SecureStorage.kt:100](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/storage/SecureStorage.kt:100)、[LocalSettingManager.kt:388](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/storage/LocalSettingManager.kt:388)。

`writeEncrypted()` 加密后调用默认的 `SharedPreferences.edit { ... }`，随后立即返回 `StorageWriteResult.Success`。该扩展默认使用 `apply()`，无法返回磁盘保存失败。上层新增的应用锁回滚只能覆盖加密阶段报错，不能保证“界面提示已启用的锁”已经保存。磁盘写入失败后，下次启动仍可能读取旧的无锁状态。参见 [AndroidX edit 参数](https://developer.android.com/reference/androidx/core/content/SharedPreferencesKt)和 [Android 写入语义](https://developer.android.com/reference/android/content/SharedPreferences.Editor)。

另一个仍存在的缺口是普通 `updateSetting()`：先发布新状态，再完全忽略 `persistence.persist(next)` 的返回值。隐藏漫画通知名称、关闭剪贴板检测、修改 DoH 选项等也会在保存明确失败时显示已经生效。复现中关闭通知名称后，当前管理器显示关闭，重新构造管理器又显示开启。

**修复建议：**统一“写入确认后发布”的契约。安全与隐私设置应等待可观察的持久化结果，在 IO 线程直接检查底层提交结果或采用具有明确完成语义的存储方式，并把失败送回 UI。仅写成 `edit(commit = true)` 还不够：KTX 扩展本身返回 Unit，必须实际取得并处理底层结果。设备图标切换与保存失败也要有一致的补偿顺序。

**验收：**分别注入加密失败、提交失败，锁与隐私开关都不能报告成功；重建存储／管理器后仍与最后一次成功保存一致。

### R03 · P2 · 恢复设置会把已完成引导的设备重新送入可修改应用锁的引导

位置：[LocalSettingManager.kt:229](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/storage/LocalSettingManager.kt:229)、[App.kt:106](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/App.kt:106)、[WelcomeScreen.kt:249](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/ui/screens/WelcomeScreen.kt:249)。

恢复保留了当前设备的应用锁凭据，却直接复制备份的 `onboardingCompleted`。旧文件缺少此字段，或文件写为 false 时，会得到“已有启用的锁，但引导未完成”的状态。`App` 优先显示引导，且 `showAppLock` 明确排除了引导场景。引导第 4 步可以直接执行 `disableAndClearAppLock()`，不验证现有凭据。

最小复现确认：有锁的已初始化设备应用 `LocalSetting()` 后，锁仍开启、引导却变为未完成，命中上述入口条件；清锁操作随后成功。**前提是设备先恢复了这样的设置文件**，不是任意外部应用可直接跳过正常锁屏。界面点击与重启组合尚未真机验证。

**修复建议：**恢复外观和功能偏好时保留当前设备的初始化完成状态；启动入口应优先验证已有凭据，再允许进入任何能改锁的页面。正常首次安装的引导保持原行为。

**验收：**已启用锁的设备恢复缺少该字段或为 false 的文件，冷启动后仍要求原凭据；引导不能用于重设旧锁。

### R04 · P2 · 外层类型修复没有覆盖 Gson 产生的空字段

位置：[BackupManager.kt:241](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/backup/BackupManager.kt:241)、[BackupRestoreViewModel.kt:219](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/ui/viewModel/BackupRestoreViewModel.kt:219)。

上一轮把 `comicCache:[]` 改为空结果，避免了一种异常。但 `{"comicCache":{"groups":null}}` 仍能成功反序列化；Kotlin 声明的非空 `List` 不会自动成为 Gson 的输入校验。选择恢复缓存时，空值进入 `restoreGroups` 的非空参数，直接从 UI 回调抛出 `NullPointerException`，不经过 `runOperation` 的错误处理。

本次已直接执行真实 ViewModel 的读取与选择方法复现。其他 `groups:[null]`、`chapters:null` 及设置段字段也应一并校验，但未将每一种畸形输入分别计成漏洞。

**修复建议：**读取／解密结束后一次性验证版本、段类型、集合元素、必填字段及 ID，输出已验证的恢复模型。区分“未选择此内容”“旧版本没有该内容”“内容损坏”，不要全部降成空集合。

**验收：**异常文件在进入选择页前得到可理解的错误；损坏内容不导致崩溃或“空内容恢复成功”。

### R05 · P2 · 阅读器返回键回调不受组合生命周期管理

位置：[ComicReadScreen.kt:262](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/ui/screens/readScreen/ComicReadScreen.kt:262)。

`readerBackCallback` 虽用 `remember` 保存，却在 Composable 函数体中直接 `addCallback(readerBackCallback)`。每次执行到此的重组都会再注册；没有 `LifecycleOwner`，也没有 `onDispose { remove() }`。

普通返回会禁用当前回调，但没有清理注册项。通过章节跳转替换阅读页、打开评论页，或者应用锁移除主 UI 时，旧回调还可能保持启用，并继续持有旧章节的续读状态、控制器和窗口引用。后续返回可能被旧页面处理；多次重组还会积累无用注册。Android 要求无生命周期注册由调用方移除，见 [OnBackPressedDispatcher 文档](https://developer.android.com/reference/androidx/activity/OnBackPressedDispatcher)。

**修复建议：**使用 Compose `BackHandler`；如保留自定义回调，则在 `DisposableEffect` 中只注册一次并解绑，同时用 `rememberUpdatedState` 提供最新处理逻辑。不能只加一个 `remember` 包住注册动作。

**验收：**反复翻页、跳章、开关评论／应用锁后，每次返回只有当前页面处理；离开阅读器后没有残留活动回调。此项尚未执行设备导航实验。

### R06 · P2 · 下载索引缺少漫画组级别的串行提交

位置：[DownloadContentOperations.kt:121](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/download/molecule/DownloadContentOperations.kt:121)、[CacheDocumentIo.kt:215](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/cache/CacheDocumentIo.kt:215)、[WorkManagerDownloadWorkScheduler.kt:35](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/worker/WorkManagerDownloadWorkScheduler.kt:35)。

同组不同章节有各自的任务，可并行完成。每个 `complete()` 先读取整组旧快照，再只把自己改为完成，写同一个 `config.json`，最后修改数据库。如果 A、B 都在对方提交前读快照，最后写出的索引只包含自己完成的版本。

并发复现使两个真实完成操作同时到达文件端口：数据库最终 **2 个 COMPLETE**，最后一个索引快照却只有 **1 个 COMPLETE**，另一个章节路径仍为空。实际文件写入还没有原子替换，同文件交错写入存在进一步损坏风险；本次动态验证的是过期快照，未模拟真实 SAF 字节交错。

**修复建议：**以组 ID 串行化“读取最终状态、生成索引、写入索引、确认提交”，保留上一轮对写索引失败可重试的保护。普通文件用临时文件替换；SAF 路径需明确失败恢复语义。只锁 `writeConfig()` 本身不够，锁外快照仍可能过期。

**验收：**同组至少两个章节同时完成，最终索引与数据库一致；写入中断和失败重试都不会永久留下半份或陈旧索引。图片本身不因此必然丢失，不应把本项夸大为整部漫画不可读。

### R07 · P2 · 健康探测客户端遗漏统一 DNS 策略

位置：[JmImageHostHealthManager.kt:260](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/image/JmImageHostHealthManager.kt:260)、[AppModule.kt:100](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/di/AppModule.kt:100)。

生产 DI 没有传 `baseHttpClient`，因此使用默认的普通 OkHttpClient。其派生 `probeClient` 不设置 DoH，在初始化和网络变化时对图片 CDN 主机发 HEAD 请求。即使阅读和更新的业务客户端已经修复，这条周期触发的请求仍使用系统 DNS。

影响是 CDN 域名的 DNS 可见性及污染网络下不一致的健康排序。探测不携带具体漫画 ID，不能描述成完整阅读历史泄露。它也不是解析 DoH 服务地址所需的 bootstrap 例外。

**修复建议：**生产组合根必须传共享 DNS 策略的无 Cookie 客户端；把所有应用自有 OkHttpClient 的构造列为可检查的集中清单，避免每次只修被发现的路径。

**验收：**启用 DoH 后，初始化探测与网络切换探测使用同一 resolver；系统 DNS 只保留明确列出的 bootstrap 例外。当前未抓取设备网络流量。

### R08 · P2 · 历史漫画仍保留跨账号分页和选择状态

位置：[UserViewModel.kt:94](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/ui/viewModel/UserViewModel.kt:94)、[UserHistoryComicScreen.kt:88](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/ui/screens/UserHistoryComicScreen.kt:88)。

评论分页已经按 `sessionState` 重建，但漫画历史分页仍只组合标签和刷新版本，并在 Activity ViewModel 中缓存；`historyEditState` 也没有随会话清空。页面 resume 的 `refresh()` 明确保留当前展示项直到新结果到达，因此不能替代账号隔离。

A 看过或选择过历史，切到 B 后重新进入，B 的刷新完成前仍可能看到 A 的缓存；刷新失败会延长这个窗口。若此时确认旧选择，删除函数捕获的是当前 B 的会话，并不能识别这些 ID 来自 A 的页面。上一轮解决的是**已开始的删除批次中途换账号**，与这里的**发起动作前已陈旧的选择**不同。

**修复建议：**漫画历史与评论使用一致的账号＋会话代次分页生命周期，切换时发布空数据、清选择；将选择所属会话带到删除入口校验。

**验收：**A→B、登出、刷新失败三种场景都立即清空 A 的展示和选择；旧选择在 B 会话下零次提交。本项为源码路径确认，未用真实账号做端到端删除。

## 架构、可读性与过度防御

### A01 · 分层进步明确，但“层”与真实职责仍未完全对应

下载协调器、缓存迁移协调器和收藏同步入口确实集中了一部分时序；仓库也已输出领域模型。重新统一根节点名称后，import 图只有 reader 各分包与 Retrofit 各分包两组 SCC，**不再有上一轮的通知工具→MainActivity 环**。SCC 中含共享类型和实现协作，不能仅凭包级循环认定每条边都是业务流程环。

仍应收敛的边界包括：

- [PdfExport.kt:26](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/download/export/PdfExport.kt:26)被文档归为 L4 编码，但同时做章节来源解析、输出文档创建、逐页处理、失败清理，并调用另一个领域的 `DeviceLocalChapterFiles`。将“读取哪些图片／生成什么文件”的组合放在已有 L3，编码器只接收明确输入即可。
- [BackupRestoreViewModel.kt:203](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/ui/viewModel/BackupRestoreViewModel.kt:203)同步调用含 PBKDF2、AES 和 JSON 解析的 `unlockBackup()`。这是 UI 回调上的实际重工作，较大备份或低端设备会造成卡顿；应移入操作端口的后台执行路径并提供忙碌状态，尚未测得设备延迟。
- `App` 作为组合根仍承载剪贴板解析、详情请求和续读恢复决策；`WelcomeScreen` 直接操作锁和登录状态。结合 R03、R05 优先收拢安全和生命周期决策，比给无状态布局再加一层更有价值。

建议保留现有目录，仅把上述组合职责移动到已有 coordinator／operations。必要的共享状态契约可移入 model；不要为了满足“40～100 行”的指导值拆散一个完整低层契约。

### A02 · 无效转发和模型往返仍增加理解成本

[Retrofit.kt:20](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/retrofit/Retrofit.kt:20)的 `ActiveSessionCookieStore` 只剩空 `clearCookie()`；生产源码已没有调用者，却还保留接口、实现和 DI 绑定。应整体删除这些无效部件。**当前 UserManager 已不依赖该接口**，不沿用旧报告关于会话层仍持有它的结论。

其他可随功能改动收敛的例子：Embedded 模型先转换成 Retrofit 响应再转换成领域模型；导出把 `DownloadItem` 再转换成 Room 实体；[LocalSettingManager.kt:24](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/storage/LocalSettingManager.kt:24)实现 15 个接口，再由 SettingsViewModel 把多个投影重新组合成完整设置状态。这些不等于必须重写，但说明新增字段需要在多处同步修改。保留窄读取接口，优先统一一次提交的状态发布和失败反馈。

### A03 · 自动检查通过的含义被写得过宽

- [check-coupling.py:71](/Users/utolaris/Documents/ai/jmcomic-plus/scripts/check-coupling.py:71)仍把根源文件归为 `MainActivity.kt`，import 目标归为 `MainActivity`；通配 import、别名及同包调用也不是完整统计。附件 SCC 仅修正根节点命名，并未伪装成完整语义依赖分析。
- [ArchitectureBoundaryTest.kt](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/test/java/com/par9uet/jm/architecture/ArchitectureBoundaryTest.kt)对不存在的受检路径返回空结果，且仍检查已删除的 `store`；规则主要枚举禁止前缀，不能证明全部 L1～L4 约束成立。必须存在的路径应明确断言，迁移例外应单独声明。
- [check-release-json-fields.py:44](/Users/utolaris/Documents/ai/jmcomic-plus/scripts/check-release-json-fields.py:44)没有列出新增 `BackupMeta.encryptionSalt`，也没有覆盖整个 LocalSetting；最终却打印“全部实例字段保留原名／反射序列化兼容”。当前盐字段实际保留，因此没有据此认定发布版坏包；问题是检查会漏掉将来的回归。
- 本次 509 项既有测试全通过，而 6 项缺陷见证也通过，说明还缺少**真实持久化失败、反序列化空值、组并发和页面生命周期**测试。优先补这些边界，而非增加源码字符串匹配测试数量。

### A04 · 过度防御主要是把失败抹平，以及过宽的保留范围

应收敛：R02 的失败忽略、R04 的损坏数据降为空集合、[DownloadExportViewModel.kt:86](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/ui/viewModel/DownloadExportViewModel.kt:86)把所有统计异常都当作文件可能消失。后者让权限失效与普通清理无法区分，至少应给出“统计不可用”的状态与原因。

[proguard-rules.pro:12](/Users/utolaris/Documents/ai/jmcomic-plus/app/proguard-rules.pro:12)仍整包保留 coroutines、Retrofit、Koin、storage、repository、utils 及 `j$.**` 等，还存在重复属性与 keep 规则。它们削弱裁剪和优化能力；应结合 consumer rules、反射入口与发布验证逐步缩小，不建议一次删光。

应保留：候选登录隔离、会话代次、下载取消后等待写入者退出、源文件租约、缓存代次、临时文件提交、取消异常传播。它们保护真实并发与 IO 边界，不是多余防御。

仍待加固但本次不单列为已证实漏洞：DoH 响应用 `bytes()` 整体读入且无上限；旧 ZIP 解压缺少条目数／总解压字节限制和循环内取消检查；永久损坏的设置被映射成“暂时不可用”，只有重试难以恢复。需要合理资源预算和明确恢复入口，不应通过默认无锁状态解困。

### A05 · 长文件应按职责拆，而不是按行数拆

`LocalSettingScreen` 880 行、`ComicDetailScreen` 826 行、`BackupRestoreScreen` 777 行。很多是正常 Compose 布局，不宜直接认定为架构错误。优先提取输入稳定的对话框／区块，把状态决策留在已有 ViewModel；R03 的入口决策、R05 的生命周期副作用则必须明确归属。

另外还有旧测试包 `store`、拼写 `rememberPostionScrollState` / `FilterItemSketelon`、中英文注释混杂、同包重复 import、全限定名与普通 import 混用。属于低优先级清理，不值得为此启动大规模重构。

## 无效及误导注释

| 位置 | 具体问题 | 处理 |
|---|---|---|
| [BackupManager.kt:185](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/backup/BackupManager.kt:185)附近的解析说明 | 写“兼容 v1/v2”，实现未拒绝未来版本，也没有完整校验 | 明确版本接受范围，与 R04 一起实现 |
| [LocalSettingManager.kt:227](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/storage/LocalSettingManager.kt:227) | 称身份变化副作用在写入后执行，实际别名切换在 `updateSetting` 之前 | 与真实提交／补偿顺序一致 |
| [LocalSettingManager.kt:395](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/storage/LocalSettingManager.kt:395) | 称回滚到最后持久化快照，实际取的是当前内存值；普通更新可能此前保存失败 | 用真实持久化快照支撑承诺 |
| [AppUpdateDownloadManager.kt:247](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/update/AppUpdateDownloadManager.kt:247)附近 gate 说明 | 仍称 job cancel 可令阻塞 IO 退出；当前及时取消实际依赖外层 `activeCall.cancel()` | 明确 gate 只做串行，网络取消归外层 |
| [Retrofit.kt:16](/Users/utolaris/Documents/ai/jmcomic-plus/app/src/main/java/com/par9uet/jm/retrofit/Retrofit.kt:16) | 注释已承认接口无真实能力，但实现和绑定仍保留 | 删除空接口及相关注释 |
| [ARCHITECTURE.md:386](/Users/utolaris/Documents/ai/jmcomic-plus/ARCHITECTURE.md:386) | 相邻段落对长 Screen 的跨层判断不一致；多处旧精确行数及“稳定，不要动”结论 | 保留现行规则／例外，指标自动生成，迁移过程移入历史记录 |

仅复述函数名的“创建备份 JSON 字符串”“缓存目录备份整体结构”等注释可删。JMComic 1.1.8 的特定评论兼容原因、文件提交边界、取消语义与旧数据迁移条件仍有价值，应保留。

## 依赖与安全边界

实际发布依赖中 `org.jsoup:jsoup:1.17.2` 由 `jmcomic-core:1.1.8` 间接引入，命中 **CVE-2026-71497 / GHSA-pmhh-3w7g-xqp8**。维护者说明触发条件是自定义 Safelist 允许 raw-text 元素，修复版为 **1.23.1**，内置 Safelist 不受影响，见 [上游安全公告](https://github.com/jhy/jsoup/security/advisories/GHSA-pmhh-3w7g-xqp8)。

本项目没有直接 Cleaner／Safelist 调用；本次 R8 `usage.txt` 明确列出相关类已移除，DEX 中也未找到它们。因此归为 **P3 依赖维护项／当前发布路径未确认可利用**，不计入上面的 8 项产品缺陷。建议通过兼容性检查升级传递依赖，避免后续新功能重新使危险路径可达。OSV 无命中也不等于组件无漏洞；查询覆盖已解析运行依赖，不含构建插件漏洞审计。

本次未将以下内容误报为漏洞：

- 发布网络配置禁用明文流量，没有发现信任全部证书或无条件跳过主机名验证；用户可选信任设备证书是明确配置。
- FileProvider 未导出，路径限定于更新缓存，没有广泛共享私有根目录。
- 收藏 SQL 对用户筛选值使用绑定参数，内部拼装 SQL 结构不等于 SQL 注入。
- ZIP 解压使用末级文件名及临时目录，未找到典型 `../` Zip Slip 路径；资源限制仍需补齐。
- APK 没有应用内独立摘要校验是可改进项，但 HTTPS 与系统更新签名校验仍提供边界，不能据此宣称任意 APK 可替换现有应用。
- 已检查构建签名配置、日志调用和入口权限；没有读取或导出发布私钥内容，也没有对真实在线服务进行破坏性测试。

## 上次报告的复核状态

| 原编号 | 当前判断 |
|---|---|
| F01 保护信息删除后可直接读内容 | 新建加密备份已阻止此路径；仍有 R01 的快速猜测缺口，旧明文文件保留旧风险 |
| F02 设置读失败按无锁进入 | 已有 `securityLoadBlocked`，相关测试通过；R03 是不同的引导入口问题 |
| F03 保存失败仍成功 | 加密错误回滚已补，但真实写盘确认和普通隐私设置失败仍未闭合，见 R02 |
| F04 mapper 异常逃逸 | `NetWorkResult.map` 已转换为 Parsing 错误且保留取消，相关测试通过 |
| F05 畸形备份回调异常 | `comicCache:[]` 已处理，深层 null 仍可复现，见 R04 |
| F06 我的评论跨账号缓存 | 评论分页已随会话重建；漫画历史及选择仍有 R08 |
| F07 删除批次中途切账号 | 已增加绑定会话与每项检查；R08 是动作发起前的旧数据／旧选择 |
| F08 索引失败留下 COMPLETE | 已调整为先写索引、后设完成，失败顺序测试通过；组并发仍有 R06 |
| F09 更新下载无法及时取消阻塞读 | 显式取消入口已调用 `Call.cancel()`；弱网真机时序未验证 |
| F10 系统备份遗漏 startup 文件 | 两套规则已排除该文件；未做系统备份真机实验 |
| F11 部分请求绕过 DoH | 图片回退与更新检查已注入 DoH，健康探测仍有 R07 |
| F12 备份／图片回退读入无上限 | 两条已增加读入期间上限；不代表所有外部 IO 已有资源预算 |
| A01 通知工具到入口依赖环 | 当前已消除 |

## 建议修复顺序

1. **安全状态和备份：R01～R04。** 先落实保存结果与恢复输入契约，再调整 UI。一起验证初始化状态与旧文件兼容，避免局部补丁再次留下旁路。
2. **用户可见的可靠性：R05、R06。** 修复返回键注册生命周期和组索引提交，分别补设备导航测试与并发／写入失败测试。
3. **跨账号和网络隐私：R07、R08。** 统一客户端 DNS 注入，统一历史页的会话归属与空态切换。
4. **维护债：A01～A05 和依赖升级。** 删无效接口、缩小保留范围、修正文档和检查覆盖；按触碰的功能逐步整理长文件。

## 附件

- [验证汇总](/Users/utolaris/Documents/ai/jmcomic-plus/docs/audit-2026-09-13-current/verification.json)
- [六项复现源码](/Users/utolaris/Documents/ai/jmcomic-plus/docs/audit-2026-09-13-current/CurrentAuditReproductionTest.kt)与[执行结果](/Users/utolaris/Documents/ai/jmcomic-plus/docs/audit-2026-09-13-current/reproduction-results.xml)
- [依赖公告查询原始结果](/Users/utolaris/Documents/ai/jmcomic-plus/docs/audit-2026-09-13-current/dependency-advisories.json)
- [Lint 明细](/Users/utolaris/Documents/ai/jmcomic-plus/docs/audit-2026-09-13-current/lint-issues.json)、[发布 JSON 字段检查](/Users/utolaris/Documents/ai/jmcomic-plus/docs/audit-2026-09-13-current/release-json-fields.txt)、[import SCC](/Users/utolaris/Documents/ai/jmcomic-plus/docs/audit-2026-09-13-current/import-scc.json)

复现源码只作为审计附件保留；执行时临时放入测试源集，复用了项目测试替身，结束后已移出。产品缺陷没有在本次审计中修复。
