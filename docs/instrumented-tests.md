# 真机插桩测试与 adb 调试

`app/src/test` 里的 JVM 测试跑得快，但有几类行为在 JVM 上根本测不出来：Room 只能跑在真机
SQLite 上、缓存清理要碰真实文件系统、PDF 导出要走真实 `ContentResolver` 和 `PdfDocument`、
WorkManager 的参数解析依赖真实的 `WorkerParameters`。

本文说明 `app/src/androidTest` 这一套插桩测试怎么在真机上跑、怎么用 adb 只跑某一条用例。

## 一键运行

```bash
./run-instrumented-tests.sh                       # 编译安装后跑全部插桩测试
./run-instrumented-tests.sh -p com.par9uet.jm.worker          # 只跑一个包
./run-instrumented-tests.sh -c com.par9uet.jm.database.FavoriteStoreRealDatabaseTest
./run-instrumented-tests.sh -c com.par9uet.jm.cache.atom.CacheFilesDeviceTest -m scanReportsRealByteCountsAndZeroForMissingAreas
./run-instrumented-tests.sh -l                    # 同时把 logcat 抓到 build/instrumented-logcat.txt
./run-instrumented-tests.sh --no-build -c ...     # 已装过 APK，只重跑用例
./run-instrumented-tests.sh --start-app -c ...    # 跑之前先把应用切到前台（UI 用例用）
./run-instrumented-tests.sh --stall 120 -c ...    # 120s 没新输出就判定卡死并中止
./run-instrumented-tests.sh --fresh               # 干净安装（应用数据、登录会话会丢）
./run-instrumented-tests.sh <序列号>              # 多台设备时指定（adb devices -l 查看）
```

脚本做的是 `assembleDebug` + `assembleDebugAndroidTest` → **覆盖安装**两个 APK →
`adb shell am instrument`。`--no-build` 可以跳过 Gradle，改一行用例后重跑只要几秒。

安装默认走 `adb install -r`，**保留应用数据**：登录会话、设置和下载记录都在应用私有目录里，
先卸载再装会把这些全清掉，装完是个没登录的干净应用——依赖登录态的 UI 用例就再也跑不起来。
覆盖安装**失败时脚本直接报错退出**（临时故障、空间不足、签名不一致都可能是原因），
不会替你做丢数据的决定；确认是签名不一致或版本降级后，再用 `--fresh` 卸载重装。

## 手工执行等价命令

不依赖脚本时，直接发 instrumentation 命令：

```bash
# 装包
adb install -r -t app/build/outputs/apk/debug/*.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/*.apk

# 全量
adb shell am instrument -w -r jmcomic.debug.test/androidx.test.runner.AndroidJUnitRunner

# 单个类
adb shell am instrument -w -r \
  -e class com.par9uet.jm.worker.DownloadComicWorkerContractTest \
  jmcomic.debug.test/androidx.test.runner.AndroidJUnitRunner

# 单条用例（方法名支持子串）
adb shell am instrument -w -r \
  -e class com.par9uet.jm.database.FavoriteStoreRealDatabaseTest#chineseSearchMatchesTitlesOnRealSqlite \
  jmcomic.debug.test/androidx.test.runner.AndroidJUnitRunner

# 一个包
adb shell am instrument -w -r -e package com.par9uet.jm.cache \
  jmcomic.debug.test/androidx.test.runner.AndroidJUnitRunner
```

## 结果怎么判定

`adb shell am instrument` 的退出码**不能**当结果用：用例失败、筛选条件一条都没匹配到、
甚至 runner 根本没起来，它都可能返回 0，于是失败被报成"通过"。脚本因此只看输出流：

- 出现 `INSTRUMENTATION_FAILED` / `shortMsg=` / `FAILURES!!!` / `Failures: [1-9]` /
  `Errors: [1-9]` / 单条用例的 `INSTRUMENTATION_STATUS_CODE: -1`（抛异常）或 `-2`（断言失败）→ 失败。
- 一条用例都没跑到（`OK (0 tests)`，通常是 `-c` 类名或 `-m` 方法名写错）→ 失败。
- 输出里没有 `INSTRUMENTATION_CODE: -1`（正常结束）→ 失败。
- **数不出用例数**（没有汇总行，也没有任何一条 `STATUS_CODE: 0`）→ 失败。正常的
  AndroidJUnitRunner 一定会留下这些痕迹，什么都没有就说明这次输出不可信，不能算通过。

原始输出整份留在 `build/instrumented-output.txt`，`-l` 抓的 logcat 在
`build/instrumented-logcat.txt`。手工跑 `am instrument` 时按同样几条自己看一眼，
别只看 `$?`；`-r` 的意义就在这些原始状态行里（`STATUS_CODE: 0` 通过、`-2` 失败、`-1` 异常、
`-3`/`-4` 被忽略或假设不成立，后两者不算失败）。

## 卡死：UI 用例必须占着前台

Compose 的 `waitForIdle` / `onNode...` 要当前界面是 resumed 并且能出帧。别的窗口（聊天、
通知栏、MIUI 的安全中心弹窗）一旦抢到前台，等待就永远不返回，`am instrument` 会一直挂着，
看起来像"测试跑不完"，其实是环境问题。

所以：

- 跑 UI 用例加 `--start-app`，跑的过程中不要操作手机；通知栏和悬浮通知最容易被误触。
- 脚本自带看门狗：默认 180s 没有新输出就判定卡死，`am force-stop` 收尾，并把**当时的前台窗口**
  记到 `build/instrumented-output.txt`；用 `--stall <秒>` 调整。判定结果为失败。
- 真机经验（MIUI）：`RetainedMainNavigationTest`、`NavigationInteractionTest` 这两个类
  在设备被占用时会卡在宿主活动被切到后台之后的第一次等待上——它们是纯 Compose 宿主活动，
  不需要登录，也不是被测应用本身的问题。手机空闲时才有机会跑过。

`-w` 等待结果，`-r` 打印原始结果流（每个用例一行）。**别拿退出码当结论**：用例失败、
筛选条件一条都没匹配到、甚至 runner 没起来，`adb shell am instrument` 都可能返回 0；
按上面「结果怎么判定」里的几条看输出。
想确认测试 APK 是否装上了：`adb shell pm list instrumentation`。

## 测试集

### 本次新增（面向真机才有意义的行为）

| 测试类 | 覆盖什么 | 为什么必须上真机 |
| --- | --- | --- |
| `cache/atom/CacheFilesDeviceTest` | `DeviceCacheFiles` 的真实删除行为；`CacheArea.ALL` 必须保留 `reader_pages` | 需要真实文件系统与权限，验证"阅读器目录只能由租约协议回收"这条规则 |
| `worker/DownloadComicWorkerContractTest` | Worker 只做参数透传与结果映射，不碰 repository / storage / 网络 | 需要真实 `WorkerParameters` 与 `CoroutineWorker` 调度 |
| `database/FavoriteStoreRealDatabaseTest` | 收藏快照在**文件型**数据库上的持久化、中文搜索、屏蔽标签、账号隔离、文件夹范围 | in-memory Room 掩盖真实 SQLite 行为：跨进程持久化、Unicode LIKE、索引使用 |
| `download/export/PdfExportDeviceTest` | PDF 导出的文件头、多章合并、空缓存报错、文件统计 | 需要真实 `PdfDocument` + SAF `ContentResolver`，依赖 debug 变体的测试 Provider |
| `reader/atom/LocalChapterFilesDeviceTest` | 本地章节的三种历史布局（当前目录 / `<comicId>` 旧目录 / ZIP）、自然排序、解压残留 | 依赖真实文件排序与 ZIP 解压 |
| `cache/migration/CacheMigrationDeviceTest` | 缓存迁移的真机行为：文件缓存↔SAF 互迁、旧 ZIP、断点续传目录、来源不可读时不动库也不切目录、重试先清残留 | 需要真实 `DocumentsContract` 读写与 Room；迁移的决策分支由 JVM 的 `cache.migration.CacheMigrationCoordinatorTest` 覆盖 |
| `worker/CacheMigrationWorkerContractTest` | 迁移 Worker 只做参数透传与结果映射；目标目录只经过协调器，通知与缓存 API 不进入 Worker | 需要真实 `WorkerParameters`、`ProgressUpdater` 与 `ForegroundUpdater` |

### 原有插桩测试

`backup/BackupRestoreOperationsTest`、`cache/DocumentCacheStorageTest`、
`database/AppDatabaseMigrationTest`、`download/DownloadContentFilesTest`、
`launcher/LauncherDisguiseInstrumentedTest`、`storage/SessionPersistenceTest`、
`store/FavoriteStoreSyncTest`、`ui/FavoriteSyncGridTest`、`ui/MainNavigationFlowTest`、
`ui/NavigationInteractionTest`、`ui/navigation/RetainedMainNavigationTest`、
`ui/viewModel/ReaderFavoriteMutationTest`。

## 调试要点

- **只跑失败的那一条**。插桩测试一轮要装两个 APK，全量跑通常几分钟；定位阶段一律用
  `-c` / `-m` 缩小范围，改完再用 `--no-build` 复跑。
- **看异常堆栈用 `-l`**。`am instrument` 只打印断言摘要，完整堆栈在 logcat 里。
  脚本会把 `adb logcat -d` 的结果写到 `build/instrumented-logcat.txt`。
- **测试数据库要清理**。`FavoriteStoreRealDatabaseTest` 用 `System.nanoTime()` 命名数据库文件，
  并在 `@After` 里 `deleteDatabase`；手动中断后残留文件可用 `adb shell ls /data/data/jmcomic.debug/databases` 查看。
- **不要依赖网络**。整套测试不发起真实网络请求，飞行模式下也能跑通；需要网络的验证请手动进行。
- **测试 Provider 只在 debug 变体里**。`jmcomic.debug.test.cache-documents` 声明在
  `app/src/debug/AndroidManifest.xml`，release 构建下不存在，相关用例只能在 debug 上跑。

## 与架构约束的关系

`ArchitectureBoundaryTest`（JVM）负责静态的 import 边界，插桩测试负责运行时行为。
新增用例时遵守 ARCHITECTURE.md 的分层：断言只针对被测类所在层的公开契约，
不要在 UI 测试里直接访问 DAO，也不要为了让断言好写而把 internal 实现暴露出来。
