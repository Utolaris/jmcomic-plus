# 真机插桩测试

JVM 测试覆盖不了真机行为：文件型 Room、真实文件系统 / SAF、`PdfDocument`、`WorkerParameters`。这些走 `app/src/androidTest`。

## 怎么跑

```bash
./scripts/run-instrumented-tests.sh                      # 全量
./scripts/run-instrumented-tests.sh -p com.par9uet.jm.ui # 一个包
./scripts/run-instrumented-tests.sh -c <完整类名>         # 一个类
./scripts/run-instrumented-tests.sh -c <类名> -m <方法名>  # 一条用例
./scripts/run-instrumented-tests.sh --no-build -c ...     # 已装包、未改代码时跳过编译
./scripts/run-instrumented-tests.sh -l                    # 额外抓 logcat
./scripts/run-instrumented-tests.sh --fresh               # 卸载重装（会清登录数据）
./scripts/run-instrumented-tests.sh <序列号>               # 多台设备时指定
```

脚本会：`assembleDebug` + `assembleDebugAndroidTest` → 覆盖安装两个 APK → 准备设备 → `am instrument`。只连一台设备时可省略序列号。

默认 `install -r` **保留数据**（登录、设置、下载记录）。覆盖安装失败会直接退出；确认签名/降级问题后再用 `--fresh`。

## HyperOS / MIUI：必须开「后台弹出界面」

HyperOS 4 把 instrumentation 拉起的 Activity 当成**后台弹窗**。未授权时系统会立刻把 Activity 压回 Launcher：

- 现象：app 闪一下就到桌面；Compose `waitForIdle` / `waitUntil` 一直挂着；脚本报「卡死」
- 应用本身往往没问题；单独看 logcat 也未必有明显异常
- 权限标志是 **`MIUIOP(10021)`**（设置里叫「后台弹出界面」）

脚本只做 **preflight 检查**，**不会**自动改写 appops。若为 `ignore`，会失败并打印：

```bash
adb shell appops set --user 0 jmcomic.debug      10021 allow
adb shell appops set --user 0 jmcomic.debug.test 10021 allow
```

检查当前状态：

```bash
adb shell appops get jmcomic.debug | grep 10021
# 期望：MIUIOP(10021): allow
```

跑测期间请勿操作手机。脚本只做瞬时唤醒/收起通知栏，不改 stayon / DND / 白名单，也不做后台 `am start` 抢回 Activity。

## 看门狗与结果

- 默认 **180s** 没有新输出判卡死并 `force-stop`（`--stall <秒>` 可调）。当时前台窗口会写进输出。
- **不要看 `adb` 退出码**：失败、筛不到用例、runner 没起来都可能返回 0。以输出流为准：
  - `FAILURES!!!` / `STATUS_CODE: -1`（异常）/ `-2`（断言失败）/ 汇总 `Failures|Errors: [1-9]` → 失败
  - 没跑到用例、没有 `INSTRUMENTATION_CODE: -1`、数不出用例数 → 失败
- 原始输出：`build/instrumented-output.txt`；`-l` 时 logcat：`build/instrumented-logcat.txt`。

手工等价命令：

```bash
adb install -r -t app/build/outputs/apk/debug/*.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/*.apk
adb shell appops set --user 0 jmcomic.debug      10021 allow
adb shell appops set --user 0 jmcomic.debug.test 10021 allow

adb shell am instrument -w -r jmcomic.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class <类名> \
  jmcomic.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class <类名>#<方法名> \
  jmcomic.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e package <包名> \
  jmcomic.debug.test/androidx.test.runner.AndroidJUnitRunner
```

## 测试集（当前 20 个类 / 约 69 条）

| 包 | 类 | 真机上要什么 |
| --- | --- | --- |
| backup | `BackupRestoreOperationsTest` | 备份编解码 + 下载排队 |
| cache | `DocumentCacheStorageTest` | SAF 文档 |
| cache.atom | `CacheFilesDeviceTest` | 真实删除；`reader_pages` 租约目录不被普通清理删掉 |
| cache.migration | `CacheMigrationDeviceTest` | 文件↔SAF 互迁、旧 ZIP、不可读来源不切目录 |
| database | `AppDatabaseMigrationTest` / `FavoriteStoreRealDatabaseTest` | Room migration；文件型 SQLite、中文搜索、账号隔离 |
| download | `DownloadContentFilesTest` | 章节页文件读写 |
| download.export | `PdfExportDeviceTest` | 真实 `PdfDocument` + SAF；失败时用 `DocumentsContract.deleteDocument` 清不完整 PDF |
| launcher | `LauncherDisguiseInstrumentedTest` | 桌面别名 |
| reader.atom | `LocalChapterFilesDeviceTest` | 三种历史布局、自然排序、ZIP |
| storage | `SessionPersistenceTest` | 会话落盘 |
| store | `FavoriteStoreSyncTest` | 收藏同步与 Room 事务（测试包仍在 `store`，主源码 `store` 已拆到领域包） |
| ui / ui.glass / ui.navigation / ui.viewModel | `FavoriteSyncGridTest`、`MainNavigationFlowTest`、`NavigationInteractionTest`、`GlassCaptureHostSettleTest`、`RetainedMainNavigationTest`、`ReaderFavoriteMutationTest` | Compose 必须 RESUMED；Glass 静态源要能收敛 |
| worker | `DownloadComicWorkerContractTest`、`CacheMigrationWorkerContractTest` | 真实 `WorkerParameters` |

## 调试要点

- 定位阶段只跑 `-c` / `-m`；改完代码后**不要**再加 `--no-build`。
- 堆栈用 `-l` 看 `build/instrumented-logcat.txt`。
- 测数据库残留：`adb shell ls /data/data/jmcomic.debug/databases`。
- 整套不发起真实网络请求，飞行模式也能跑。
- PDF/SAF 用例依赖 debug 变体 Provider `jmcomic.debug.test.cache-documents`（`app/src/debug/AndroidManifest.xml`），release 上不存在。

静态 import 边界由 JVM 的 `ArchitectureBoundaryTest` 负责；这里只测运行时行为。
