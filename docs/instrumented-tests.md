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
./run-instrumented-tests.sh <序列号>              # 多台设备时指定（adb devices -l 查看）
```

脚本做的是 `assembleDebug` + `assembleDebugAndroidTest` → 卸载旧包 → 安装两个 APK →
`adb shell am instrument`。`--no-build` 可以跳过 Gradle，改一行用例后重跑只要几秒。

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

`-w` 等待结果，`-r` 打印原始结果流（每个用例一行）。退出码非 0 表示有失败。
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
