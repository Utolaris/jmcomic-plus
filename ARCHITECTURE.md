# 四层架构约束

项目增量采用 L1～L4 四层架构。目标是让功能入口、流程决策和底层实现容易定位，
而不是为了分层机械增加文件数量。尚未迁移的代码应被明确列为例外，不能只改目录名称。

> 本文描述的是**当前代码的真实状态**，不是目标状态。文中出现的每个类名、路径和数字都应能在
> `app/src/main/java/com/par9uet/jm` 下找到；与代码不符的措辞视为文档缺陷，应直接修正。
> 最近一次核对：v1.4.2（`VERSION_CODE=142`），主源码 **337** 个 Kotlin 文件 / **42,732** 行
> （`find app/src/main/java -name '*.kt' | wc -l` + `wc -l` 口径）。
> 本轮迁移（store 清空 + 依赖环消除）后的全量核对：2026-09-12；
> 工具链与 hygiene 对齐后的再次核对：2026-09-12（Java 21 / OpenJDK 21 构建，详见文末）。

## 层级

| 层 | 职责 | 主要落点 |
| --- | --- | --- |
| L1 Entry | 只接收事件并交给 L2，不做业务判断 | `ui/screens`（32 个 `*Screen.kt`）、`ui/navigation`、`MainActivity`、`App`、`worker/DownloadComicWorker`（26 行）、`worker/CacheMigrationWorker`（54 行） |
| L2 Coordinator | 集中保存流程顺序、分支和跨边界协调 | `ui/viewModel`（14 个，加上 `favorites/presentation/FavoritesViewModel` 共 15 个）、`reader/ReaderImagePipeline`、`reader/coordinator`、`download/coordinator`（含 `DownloadManager`）、`cache/migration` 的协调器与通知适配、`favorites/sync`、`session`（`UserManager`、`UserRepository`、`AuthenticatedRequestRecovery`、`SessionReadinessHolder`）、`startup/PostStartupCoordinator` |
| L3 Molecule | 组合多个原子能力，完成一个完整业务动作 | `reader/molecule`、`download/molecule`（含 `DownloadLibraryQueries`）、`cache/migration` 的操作端口与实现、`favorites/usecase`、`backup/BackupRestoreOperations`、`download/export/DownloadExportOperations`、`repository/impl`（组合网络服务、内置客户端与领域映射） |
| L4 Atom | 每个原子只负责一个底层契约 | `database`、`storage`、`retrofit`、`data`、`network`（含内置 API 客户端三件套）、`image`、`coil`、`cache/atom`、`reader/atom`、`download/atom`、`download/export/PdfExport`、`favorites/data`（含 `FavoriteStore`）、`update`（含 `AppUpdateDownloadManager` 下载适配）、`contentfilter`、`launcher`、`utils` |
| Shared Contract | 不含行为的稳定 DTO，可被各层依赖 | `core/model`（`CommonUIState`、`User`、`RemoteSetting`、`SignInData`）、`core/network`（`NetWorkResult` / `ResponseWrapper` / `AuthFailure`）、`favorites/model/FavoritesModels`、`reader/ReaderImageModels`、`download/model/DownloadLibraryModels` |

依赖方向为 `L1 -> L2 -> L3 -> L4`。L3 之间、L4 之间不得为了方便横向调用；
需要组合时提升到 L3，需要决定顺序时提升到 L2。`di` 是组合根，可以引用所有层，
但不得承载业务判断。

`store` 包已**整体清空并删除**（2026-09-12），原混合包按真实层级拆分：
`UserManager` / `UserRepository` / `AuthenticatedRequestRecovery` / `SessionReadinessHolder` →
`session`（L2 会话协调）；`FavoriteStore` 及其 SQL 侧（`FavoriteQueries` / `FavoriteSyncPlanner` /
`FavoriteMappers`）→ `favorites/data`；`FavoriteSyncState` → `favorites/sync`；
`RemoteConfigManager` → `network`；`LocalSettingManager` 及各种偏好、
`HistorySearchManager` / `ReadHistoryManager` / `ReaderResumeManager` → `storage`；
`BackupManager` → `backup`；`ToastManager` → `core`。
`store` 不再存在于源码树中，新代码不得重建该包名。

## 目录约定

**不存在** `feature/<name>/` 这一层。领域包直接位于 `com.par9uet.jm` 之下，
只有完成迁移的领域才带分层子目录，且各领域用词并不统一：

```text
reader/                      已迁移
├── ReaderImagePipeline.kt         L2 总协调器
├── Reader*.kt（其余 14 个根包文件，见下文）   L2/L4 内部件
├── coordinator/                   L2
├── molecule/                      L3
└── atom/                          L4

download/                    已迁移
├── DownloadWorkScheduler.kt       L2 排队端口（实现在 worker/）
├── model/DownloadLibraryModels    共享契约（DownloadItem / Group / Status）
├── coordinator/                   L2（含 DownloadManager、DownloadToastAggregator）
├── molecule/                      L3（含 DownloadLibraryQueries、DownloadTaskOperations）
├── atom/                          L4
└── export/                        L3 操作 + L4 PDF 编码

favorites/                   已迁移，但用词不同
├── model/                         共享契约
├── presentation/ViewModel         L2
├── sync/                          L2
├── usecase/                       L3
└── data/                          L4（FavoriteStore 及其端口、SQL 侧都在这里）

cache/                       部分迁移
├── atom/CacheFiles.kt             L4
├── migration/                    L2 + L3
│   ├── CacheMigrationCoordinator.kt         L2 迁移顺序、失败分支、提交
│   ├── CacheMigrationWork.kt                L2 与 Worker 共用的 WorkManager 键与任务名
│   ├── CacheMigrationWorkState.kt           L2 迁移状态契约
│   ├── CacheMigrationScheduler.kt           L2 入队 + 观察端口（实现见 worker/）
│   ├── CacheMigrationNotifications.kt       L2 前台通知适配
│   ├── CacheMigrationOperations.kt          L3 操作端口与值类型
│   └── DeviceCacheMigrationOperations.kt    L3 文档读写与 DAO 组合
└── CacheModels / ComicDownloadCache / DocumentCacheStorage / CacheMigrationPaths / Config   未归位的 L4

update/ backup/ contentfilter/ launcher/ startup/   扁平包，按类判断层级
update/AppUpdateDownloadManager  已从 store 迁入 update（L2 下载协调 + 状态契约）
core/                        共享契约与基础类型：core/model（User / RemoteSetting / SignInData /
                             CommonUIState）、core/network（NetWorkResult / ResponseWrapper）、
                             core/BaseRepository、core/ToastManager
session/                     L2 会话协调（UserManager / UserRepository /
                             AuthenticatedRequestRecovery / SessionReadinessHolder）
network/                     L4：Doh 三件套、RemoteConfigManager、内置 API 客户端
                             （EmbeddedClientManager / AuthenticatedEmbeddedClient / EmbeddedSessionCookies）
storage/                     L4：LocalSettingManager、各种 *Preferences、历史/续读管理器
data/ repository/ retrofit/  历史命名保留，包间依赖环已全部消除（见「依赖现状」）；
                             store 已删除
```

新代码优先沿用所在领域已有的子目录命名；跨领域新建时建议统一用
`coordinator / molecule / atom`。小功能不必创建子目录，但仍需遵守同样的依赖方向。
空目录 `task/` 已无文件，不应再往里放东西。

## 已采用的边界

- Gson 反射序列化的 DTO（登录数据 `core/model/User`、备份文件 `backup/BackupFile` /
  `BackupMeta` / 缓存备份载荷、服务器响应包装 `core/network/ResponseWrapper`、远程配置
  `core/model/RemoteSetting`、下载缓存 config）由 `app/proguard-rules.pro` 的精确保留规则
  覆盖；模型迁包时必须同步迁移对应规则。发布前用 `scripts/check-release-json-fields.sh`
  校验 DEX 里的真实字段名——本项目的 R8 版本在 `mapping.txt` 中会省略"保留原名"成员的
  字段行，不能拿 mapping 当兼容性依据。
- 启动后任务由 `startup/PostStartupCoordinator` 统一排序。
- 下载业务通过 `download/DownloadWorkScheduler` 端口提交任务，不直接构造 Worker。
  实现 `worker/WorkManagerDownloadWorkScheduler` 以 **comicId 为粒度**调用
  `enqueueUniqueWork(..., ExistingWorkPolicy.KEEP, ...)`，暂停/删除走
  `cancelUniqueWork`；批量下载通过 `batchId` / `batchTotal` 入参传递批次信息。
  `DownloadComicWorker`（26 行）只解析参数并调用 `DownloadComicCoordinator` 映射结果。
- 缓存目录迁移的入队与状态观察都走 `cache/migration/CacheMigrationScheduler` 端口，
  由 `worker/CacheMigrationWorker`（54 行）执行；Worker 只读入参、调用协调器并把结果映射成 WorkManager 终态，
  唯一构造 Worker、唯一解读 `WorkInfo` 的位置是 `worker/WorkManagerCacheMigrationScheduler`
  （`ui/viewModel/CachePathViewModel` 因此既不 import `worker.*` 也不 import `androidx.work.`）。
  同名任务会留下历史记录且 `getWorkInfosForUniqueWork` 不保证顺序，所以状态只从
  "未结束的那条"或"入队时记下的任务 id"（存在 `cache/Config` 的偏好里，跨进程重启仍在）
  认领当前那次，认不出来就不显示结果——不按列表顺序猜，避免把更早的结果当成本次结果。
  `cache/migration/CacheMigrationCoordinator`（L2）持有迁移顺序与失败分支——
  先解析全部来源再动目标、全部文件落地后才写索引并切换目录、提交段整体 `NonCancellable`；
  `cache/migration/CacheMigrationOperations`（L3）组合 `cache/*` 文档原子与下载 DAO，
  自身不做顺序判断；前台通知由 `cache/migration/CacheMigrationNotifications`（L2）构造，
  Worker 不再反向引用 `MainActivity`。等下载空闲通过组合根提供的窄回调
  `CacheMigrationDownloadGate` 传入，`cache/migration` 不依赖 `download` 域。
- `download/coordinator/DownloadManager` 是下载任务管理的 L2 入口，持有协程生命周期，
  按业务结果入队并发送提示；`download/molecule/DownloadTaskOperations` 组合 DAO 与
  `download/atom/DownloadFiles`，处理创建、重试、恢复和重新下载；
  `download/molecule/DownloadLibraryQueries` 把下载 DAO 与 Room 实体映射成
  `download/model` 契约，供 ViewModel 观察列表与分组，UI 不再 import `database`；
  `download/atom/DownloadFiles` 只清理已有缓存文件。
  业务层不依赖 Worker 或 UI。排队端口仍由 L2 调用，以保留单篇创建先提示后入队、
  其他操作先入队后提示的现有顺序。暂停、删除、清理和批量重下都在此入口串行化；
  批量章节到漫画组的查询归属 L3。
  `download/coordinator/DownloadExecutionControl` 只暴露停止并等待写入结束的能力，
  任务管理不再依赖下载执行器的具体类型。
  跨域消费走领域自有窄端口 + DI adapter，不反向 import coordinator：
  `backup/BackupTaskScheduler`、`favorites/data/FavoriteDownloader` 在组合根绑定到
  `DownloadManager`；`download/export` 自带 `DownloadItem→DownloadComic` private 映射，
  不依赖 `download/molecule` 的实体转换。
- 应用更新 APK 下载归属 `update/AppUpdateDownloadManager`（实现 `AppUpdateDownloads`），
  状态契约（`AppUpdateDownloadState` / `Status` / `Request`）同包；不再放在 `store`。
- `download/coordinator/DownloadComicCoordinator` 负责下载顺序、进度、重试和取消，
  `DownloadFeedback` 适配通知、速度统计与批量提示，`DownloadComicPolicy` 收敛策略判断；
  `download/molecule/DownloadContentOperations` 组合封面回退、逐页下载和完成提交。
  `download/atom` 封装 Coil 请求与缓存文件读写（`DownloadCoverImages`、`DownloadContentFiles`）；
  新缓存以漫画/章节 ID 区分目录，旧缓存沿保存路径读取，配置格式保持兼容。
  L2 直接操作下载 DAO（已确认 `DownloadComicCoordinator` 直接 import `DownloadComicDao`），
  以集中维护进度和失败分支；L3 不反向依赖 Worker 或协调器。
- 通用异步状态放在 `core/model/CommonUIState`，状态存储层不再依赖 UI 包。
- 收藏分页适配器 `favorites/presentation/CollectComicPagingSource` 归属收藏功能，
  收藏功能不再反向依赖通用 UI 包。
- PDF 导出归属 `download/export`，通用工具包不再反向依赖下载缓存。
- 屏蔽规则和桌面入口切换分别归属 `contentfilter`、`launcher`，不再作为通用工具依赖业务模型。
- 阅读器图片链路由 `reader/ReaderImagePipeline`（L2）统一调度，来源加载在 L3，
  内存和磁盘缓存位于 L4。
- 本地阅读由 `ComicReadViewModel` 调用 `reader/molecule/LoadLocalChapter`，在 IO 线程组合下载记录、
  已完成章节和 `reader/atom/LocalChapterFiles`（实现 `DeviceLocalChapterFiles`）；
  目录查找、自然排序及旧 ZIP 解压均在文件适配器内。ZIP 先解压到临时目录，成功后提交；
  ViewModel 以请求代次隔离迟到结果，收藏复用现有收藏用例。
- `CacheCleanupViewModel` 持有扫描、选择、清理状态，决定何时停止下载以及清理阅读器缓存；
  `cache/atom/CacheFiles` 只负责普通缓存目录的扫描与删除。阅读器目录必须经过原缓存代次/租约协议，
  不能被"全部清理"的普通文件删除绕过。`CacheCleanupScreen` 仅渲染与提交事件。
- `DownloadExportViewModel` 持有导出选择、文件选择器前的章节/模式快照、导出任务及文件统计请求代次；
  `download/export/DownloadExportOperations` 负责文档授权、PDF 写入和文件统计，复用现有 PDF 格式与命名。
  `DownloadComicDetailScreen` 仅保留展示、导航、对话框和系统文件选择器。
- 首页、搜索、周推荐分别由 `HomeViewModel`、`SearchViewModel`、`WeekViewModel` 持有独立状态与任务。
  搜索入口、编辑页和结果页仍使用同一个 Activity 范围的 `SearchViewModel`（`AppScreen`、
  `ComicSearchScreen`、`ComicSearchResultScreen`），保持条件与滚动恢复；
  首页与工具栏共享 `HomeViewModel`（`HomeScreen`、`TopBarComponent`）。
  原 `ComicViewModel` 已移除，不保留转发型兼容外壳。
- 更新入口拆为 `AboutScreen` 和 `CheckUpdateScreen`；`AppUpdateViewModel` 管理检查、弹窗、下载和安装决策，
  `update` 提供版本解析（`GithubReleaseSource`）、发布模型（`AppRelease`）、系统安装适配器（`ApkInstaller`）
  与 APK 下载协调（`AppUpdateDownloadManager`）。
- `BackupRestoreViewModel` 管理备份/恢复步骤及任务生命周期，`backup/BackupRestoreOperations`
  组合设置快照、文档读写和下载排队；Screen 仅持有系统文件选择器和展示组件。
- `favorites/sync/FavoriteSyncController` 是唯一收藏同步任务入口，按登录会话代次隔离任务、进度与结果；
  `favorites/usecase/SyncFavorites` 负责远端分页、元数据补齐和受会话保护的本地提交。
- `favorites/data/FavoriteStore` 保留 Room 事务及 DAO 操作，纯 SQL 构造、同步规划和实体映射分别位于
  `favorites/data/FavoriteQueries`、`favorites/data/FavoriteSyncPlanner` 和 `favorites/data/FavoriteMappers`。
  它直接实现 `favorites/data` 定义的三个窄本地端口 `FavoriteLocalQuery`、
  `FavoriteLocalMutation`、`FavoriteLocalSync`，避免额外转发对象。
- `ArchitectureBoundaryTest` 固定以下边界，防止后续补丁重新引入反向依赖。
  当前生效的断言（以测试代码为准）：

  | 受约束位置 | 禁止 import |
  | --- | --- |
  | `ui`（整体） | `database.` |
  | `ui/viewModel/ComicReadViewModel.kt` | `java.io.`、`java.util.zip.`、`database.`、`cache.` |
  | `ui/screens/CacheCleanupScreen.kt`、`ui/screens/downloadScreen/DownloadComicDetailScreen.kt` | `java.io.`、`kotlinx.coroutines.`、`download.coordinator.DownloadManager`、`reader.ReaderImagePipeline`、`database.`、`download.export.export`、`download.export.getCachedComicInfo`、`cache.atom.` |
  | `ui/screens/AboutScreen.kt`、`CheckUpdateScreen.kt`、`BackupRestoreScreen.kt` | `okhttp3.`、`gson`、`java.io.File`、`FileProvider`、`database.`、`backup.BackupManager`、`download.coordinator.DownloadManager`、`storage.LocalSettingManager`、`update.AppUpdateDownloadManager` |
  | `cache/atom` | `ui.`、`store.`、`reader.` |
  | `data`（整体） | `repository.`、`session.`、`reader.` |
  | `retrofit`（整体） | `data.`、`store.`、`session.` |
  | `network`（整体） | `repository.`、`data.` |
  | `session`（整体） | `ui.`、`data.`、`repository.` |
  | `favorites/data` | `download.coordinator.`、`repository.` |
  | `download/molecule` | `store.`、`ui.`、`worker.`、`download.coordinator.`、`reader.`、`java.io.`、`androidx.work.` |
  | `download/atom` | `download.molecule.`、`store.`、`download.coordinator.`、`reader.`、`ui.`、`worker.`、`database.dao.`、`database.AppDatabase` |
  | `download/coordinator/DownloadManager.kt` | `download.coordinator.DownloadComicCoordinator`、`database.`、`download.atom.`、`java.io.` |
  | `store`（整体，已删除，断言保留防复活） | `ui.`、`worker.` |
  | `favorites`（整体） | `ui.` |
  | `favorites/data` | `download.coordinator.` |
  | `backup` | `ui.`、`download.coordinator.` |
  | `update`（整体） | `ui.` |
  | `download/export` | `download.molecule.` |
  | `reader/atom` | `reader.molecule.`、`reader.coordinator.`、`ui.`、`worker.`、`store.` |
  | `reader/molecule` | `reader.coordinator.`、`ui.`、`worker.`、`store.` |
  | `worker/DownloadComicWorker.kt` | `database.`、`repository.`、`reader.`、`store.`、`download.molecule.`、`download.atom.`、`coil.`、`java.io.` |
  | `worker/CacheMigrationWorker.kt` | `database.`、`repository.`、`reader.`、`store.`、`download.`、`cache.`（`cache.migration.` 除外）、`coil.`、`java.io.`、`android.provider.`、`MainActivity`、`R` |
  | `ui/viewModel/CachePathViewModel.kt` | `worker.`、`androidx.work.` |
  | `cache/migration` | `ui.`、`worker.`、`store.`、`reader.`、`download.` |
  | `utils` | `cache.`、`data.` |

### 阅读器图片链路

```text
UI / ViewModel / Download Worker       # 调用入口
└── reader/ReaderImagePipeline         # L2 请求优先级、去重、解码顺序
    ├── reader/coordinator/ReaderRemoteTelemetry   # L2 远端事件归档
    ├── reader/molecule/ReaderSourceLoader         # L3 来源缓存、CDN、回退与重试
    │   └── reader/ReaderRemoteFetcher             # internal，远端获取与竞速
    └── reader/atom/
        ├── ReaderBitmapCache                      # L4 解码图内存所有权
        └── ReaderImageDiskCache                   # L4 文件租约、代次、写入与清理
```

Reader 的 L3 不得依赖 UI 或 Worker，L4 不得反向依赖 L3。磁盘缓存将源文件与
解码文件保留在同一个原子内，是为了让清理时的锁顺序和 cache generation 保持原子性；
拆成两个互相调用的 L4 会重新引入竞态和横向依赖。

`ReaderImagePipeline` 直接持有内存、磁盘缓存和解码等选定适配器，是 L2 的显式例外：缓存命中
和解码结果决定了请求是否进入后续流程，留在 L2 才能完整读出"内存 → 本地/磁盘 → 来源 →
解码 → 回写"的控制流。继续包一层只会形成转发型假分层，且会把资源所有权藏到 L3。

**根包尚未归位。** `reader/` 下 21 个文件里只有 6 个在上述分层目录中，其余 15 个仍留在包根，
且绝大多数是 `internal`，属于 Pipeline 的私有协作对象，按职责分为：

- 并发控制：`ReaderNetworkScheduler`、`ReaderDynamicLimiter`、`ReaderConcurrencyPolicy`
- 请求合并与可见性：`ReaderInFlightRegistry`（含 `ReaderVisibleRequestTracker`）
- 来源与竞速：`ReaderRemoteFetcher`、`ReaderImageHostManager`、`ReaderHedge`
- 解码与还原：`ReaderImageDecoder`（`internal` 顶层函数，无注入端口）、`ReaderScramble`
- 预加载与容量：`ReaderPrefetchPlanner`、`ReaderPrefetchPolicy`、`ReaderLruCache`
- 观测：`ReaderMetrics`
- 契约：`ReaderImageModels`（`ReaderPage` / `ReaderPageKey` / `ReaderDecodeProfile` / 优先级枚举）

这些文件粒度已经足够小且可独立测试，机械下沉到 `atom/` 只会增加目录层级，
因此不列为近期迁移目标；但它们**不是**分层目录的一部分，阅读链路时不要只看子目录。

## 依赖现状与已知环

**历史依赖环已全部消除（2026-09-12）。** 此前文档列出 5 个真实技术债环
（`data`↔`repository`、`data`↔`retrofit`、`retrofit`↔`store`、`repository`↔`store`、
`data`↔`reader`），消除方式与落点：

| 原环 | 消除方式 | 现存单向依赖 |
| --- | --- | --- |
| `data` ↔ `repository` | 内置 API 客户端三件套（`EmbeddedClientManager` / `AuthenticatedEmbeddedClient` / `EmbeddedSessionCookies`）本质是网络设施，从 `repository/impl` 迁到 `network`；`data`/`favorites/data`/`di` 改引 `network` | `repository → data`（仓库用领域模型与数据源，合法向下） |
| `data` ↔ `retrofit` | response→领域模型的 `toXxx()` mapper 从 `retrofit/model` 成员函数改为 `data/comic/mapper/ResponseMappers` 的扩展函数，`retrofit/model` 回归纯 wire DTO | `data → retrofit`（数据源用 wire 类型与服务接口，合法向下） |
| `retrofit` ↔ `store` | store 清空；`UserManager` 迁 `session`，`LoginResponse.toUser` 等以共享契约为目标的 mapper 留在 `retrofit/model`（目标类型在 `core/model`） | `session → retrofit`（会话协调用登录服务，合法向下） |
| `repository` ↔ `store` | store 清空；`RemoteConfigManager` 迁 `network` 后改用 `network` 内窄端口 `RemoteSettingFetch`（返回 `core.model.RemoteSetting`），由组合根（`di/AppModule`）委托给仓库并完成 response 映射 | `network → core/storage/utils`（纯 L4 设施） |
| `network` ↔ `session` ↔ `retrofit` | **三边环（评审发现）**：`network → session`（客户端三件套引会话类型）、`session → retrofit`（UserManager 用 `ActiveSessionCookieStore`）、`retrofit → network`（`Retrofit.kt` 以全限定类名引用 `DohManager`，绕过 import 统计）。修法：`AuthenticatedSessionRequiredException` 下沉 `core/network`；`network` 定义 `AuthenticatedRequestGate` 端口，认证请求的编排归属归还 session——组合根把端口绑定到 `AuthenticatedSessionGate`（其内部经 `UserManager` 注册的 executor 做登录恢复重试），`AuthenticatedEmbeddedClient` 不再感知会话类型；`EmbeddedClientManager` 删除未使用的 session import；`Retrofit.kt` 参数改为 `okhttp3.Dns`，组合根注入 `DohManager` | `network` 只依赖 `core/storage/utils`；`session → retrofit` 单向保留 |
| `data` ↔ `reader` | `ComicPicImageState` 不再持有 `ReaderPage`/`ReaderPageKey` 转换与 `java.io.File` 探测，改由 `reader/ComicPicImageStateReader` 扩展适配器承担 | `reader → data`（阅读器读领域模型，合法向下） |

随之消除的连带反向依赖：`session → data`（共享 DTO `User` / `RemoteSetting` / `SignInData`
迁到 `core/model`）、`network → repository`、`network → data`（`RemoteSetting` 迁
`core/model`）、`network → session` 与 `retrofit → network`（见上表三边环）。

以上每个被切断的方向都有 `ArchitectureBoundaryTest` 断言钉住（`data` 禁
`repository`/`session`、`retrofit` 禁 `data`/`store`/`session`/`network`、`network` 禁
`repository`/`data`/`session`/`retrofit`、`session` 禁 `data`/`repository`、`favorites/data`
禁 `repository`）。断言用 `forbiddenQualifiedUsages` 同时扫描**全限定引用**（非注释行），
防止 `com.par9uet.jm.network.DohManager` 这类写法绕过 import 统计——这正是评审发现的
`retrofit → network` 隐形边。回归时先看边界测试。`download`↔`store` 的残余已随 store 清空
自然消失（`ToastManager` → `core`，偏好 → `storage`）；`favorites`↔`store` 的"自然双向"
已随 `FavoriteStore` 迁入 `favorites/data` 变成包内实现细节，不再是跨包环。

## 耦合热点与拆分优先级

下表由脚本对 `import com.par9uet.jm.*` 统一重算（2026-09-12，store 清空与依赖环消除之后）。
**口径**：模块 = 一二级包目录（`ui/screens`、`ui/viewModel`、`cache/migration`、
`database/model`、`retrofit/model` 单列，其余子目录并入一级包）；Ce = 该模块 import 到的
模块数，Ca = 依赖它的模块数，I = Ce/(Ce+Ca)。行数为 `wc -l` 口径，可能有约 1% 出入。
迁移前的旧表数值按旧口径手算，两者**不可跨版本直接对比**；本表自洽，且可用同一脚本复现。

| 模块 | 行数 | Ce | Ca | I | 判断 |
| --- | --- | --- | --- | --- | --- |
| `ui/screens` | 16,022 | 14 | 2 | 0.88 | 表现层；store 拆除后扇出已收敛到领域包与 `core/model` |
| `ui`（根：components/glass/theme/pagingSource/navigation 等） | 5,080 | 11 | 3 | 0.79 | 表现层支撑 |
| `reader` | 3,374 | 6 | 4 | 0.60 | 已迁移；根包文件说明见下文 |
| `ui/viewModel` | 2,975 | 15 | 3 | 0.83 | 扇出最高，但多为契约与偏好 |
| `favorites` | 2,621 | 7 | 3 | 0.70 | 已迁移（含 `FavoriteStore` 及其端口） |
| `storage` | 1,588 | 3 | 13 | 0.19 | 偏好与持久化收口点（原 store 的 L4 部分落在这里），稳定 |
| `download` | 1,351 | 11 | 4 | 0.73 | 已迁移 |
| `data` | 1,123 | 4 | 13 | 0.24 | 稳定契约；response mapper 收口在 `data/comic/mapper` |
| `network` | 954 | 4 | 8 | 0.33 | 新收口点：Doh、`RemoteConfigManager`、内置 API 客户端三件套 |
| `image` | 695 | 0 | 5 | 0.00 | 稳定，不要动 |
| `session` | 655 | 4 | 8 | 0.33 | L2 会话协调（原 store 的 L2 入口落在这里） |
| `cache` | 628 | 1 | 3 | 0.25 | 含未归位的 L4 根文件 |
| `cache/migration` | 602 | 4 | 3 | 0.62 | 已拆：L2 协调器 + L3 操作端口 |
| `utils` | 564 | 0 | 5 | 0.00 | 稳定，不要动 |
| `di` | 550 | 19 | 0 | 1.00 | 组合根，合法，不动 |
| 根包（`App` / `MainActivity` / `JmApplication`） | 471 | 8 | 0 | 1.00 | 入口 |
| `update` | 425 | 2 | 3 | 0.40 | 已迁移 |
| `repository` | 420 | 6 | 6 | 0.50 | 已收口：只剩指向 data/network/session/retrofit 的向下依赖 |
| `database` | 399 | 1 | 6 | 0.14 | 稳定 |
| `backup` | 383 | 3 | 2 | 0.60 | 已迁移 |
| `retrofit/model` | 370 | 1 | 9 | 0.08 | 纯 wire DTO（mapper 已移入 data），稳定 |
| `retrofit` | 284 | 3 | 4 | 0.43 | 客户端与拦截器 |
| `core` | 229 | 0 | 16 | 0.00 | 最稳定：共享 DTO、`NetWorkResult`、`BaseRepository`、`ToastManager` |
| `worker` | 222 | 2 | 1 | 0.75 | 已拆：两个 Worker 都只解析参数 |
| `database/model` | 186 | 0 | 6 | 0.00 | 稳定，不要动 |
| `contentfilter` | 149 | 1 | 0 | 1.00 | 自洽 |
| `coil` | 102 | 2 | 3 | 0.40 | |
| `launcher` | 86 | 1 | 2 | 0.33 | |
| `startup` | 81 | 4 | 2 | 0.67 | |

单文件维度（"跨层数"= 该文件 import 触及的层数，含自身）：

- **跨 4 层**：`ui/viewModel/ComicDetailViewModel`（473 行）、
  `ui/screens/downloadScreen/DownloadComicDetailScreen`（465 行）
- **跨 3 层且 >600 行**：`LocalSettingScreen`(879)、`ComicDetailScreen`(828)、
  `BackupRestoreScreen`(780)、`ComicReadScreen`(740)、`FavoritesToolbar`(734)、
  `FavoritesModalHost`(692)、`ComicCommentScreen`(666)、`CheckUpdateScreen`(662)、
  `WelcomeScreen`(651)
- **扇出最高**：`cache/migration/DeviceCacheMigrationOperations`（22，均为 `cache` 域内文档原子）、
  `reader/molecule/ReaderSourceLoader`（22，但均为 reader 域内 internal 组件，属正常）

### 已完成的两轮拆分（原"值得拆的目标"）

**1. `store` 包——已清空（2026-09-12）**

此前是 Ca/Ce 双高的全局枢纽。分两轮迁完：第一轮 `DownloadManager` / `DownloadToastAggregator` /
`BackupTaskScheduler` → `download/coordinator`，`DownloadWorkScheduler` → `download`，
`AppUpdateDownloadManager` → `update`；第二轮把剩余 L2 入口下沉到 `session`
（`UserManager` 等）、L4 状态与偏好下沉到 `storage`、`FavoriteStore` 及其 SQL 侧下沉到
`favorites/data`、`RemoteConfigManager` 下沉到 `network`、`BackupManager` → `backup`、
`ToastManager` → `core`。共享 DTO（`User` / `RemoteSetting` / `SignInData`）归位
`core/model`。`store` 目录已删除。

**2. 下载相关的 4 个 UI 文件直连 Room——已修**

`ui` 整体禁止 import `database`；DAO 访问收敛到 `download/molecule/DownloadLibraryQueries`，
UI 使用 `download/model`（`DownloadItem` / `DownloadItemGroup` / `DownloadItemStatus`）。

### 不建议动

- **`data` / `utils` / `database/model` / `retrofit/model` / `core`**：Ca 高但 Ce 极低，
  是被依赖的稳定契约，拆分只会制造转发层。
- **`reader` 根包文件**：粒度足够小，下沉到 `atom/` 只增加目录层级。
- **`di`**：I=1.00 是组合根的应有形态。
- **`ui/screens` 的巨型文件**：行数确实跨层（L1 直接读领域包与 `storage`），
  但主要矛盾是可读性。若要动手，优先 `DownloadComicDetailScreen`（跨 4 层）而非
  `LocalSettingScreen`（纯设置项渲染）。

## 审查判断

- 更新和备份恢复的跨边界流程已移出 Screen；剩余较长的界面文件主要是展示组件，不再按行数继续机械拆分。
  `LocalSettingScreen`（878 行）、`ComicDetailScreen`（827 行）、`BackupRestoreScreen`（779 行）、
  `ComicReadScreen`（739 行）、`FavoritesToolbar`（733 行）属于已知的可读性问题，不是跨层耦合。
- `FavoriteStore` 的事务边界保持集中，以避免账号快照替换及缓存索引写入被拆散；纯计算和映射可独立验证。
- 下载任务入口、执行协调、内容下载及文件适配已分离；取消仍直接传播，重试次数和终态提交顺序保持不变。
- Reader 链路的**控制流**已收敛到 `ReaderImagePipeline`，但**实现体**仍集中在包根，
  只是粒度已经足够小。下一步如要继续拆分，应针对来源策略与缓存生命周期，而不是再加一层包装。
- `data`、`repository`、`retrofit`、`store` 之间的环已全部消除（见「依赖现状」）；
  后续任何迁移都应先抽取窄端口再切断反向 import，避免产生新的转发型假分层。

## 当前例外与迁移顺序

1. 下载协调器直接使用 DAO 和反馈适配器；进度与同组任务状态决定通知和终态分支，避免把这些判断拆散。
2. 更新协调器直接使用下载及安装端口，备份协调器直接使用无副作用的校验/提取能力；
   这些结果直接决定流程分支，避免为它们添加仅转发调用的用例层。
3. 缓存清理协调器通过组合根提供的窄回调协调下载和阅读器的资源生命周期，并直接调用缓存文件端口；
   导出协调器直接调用 PDF 文件端口；缓存迁移协调器同样以窄回调
   （`CacheMigrationDownloadGate`）等待下载空闲。这些是 L2 的跨功能协调及选定适配器例外，
   L4 不得反向调用协调器。
4. 下载相关的 4 个 UI 文件直连 Room——**已修**，`ui` 禁止 import `database`，
   DAO 观察走 `download/molecule/DownloadLibraryQueries`。
5. `RemoteConfigManager` 通过 `network` 包内的窄端口 `RemoteSettingFetch`（返回
   `core.model.RemoteSetting`）取数，组合根把端口委托给 `RemoteSettingRepository` 并在
   di 侧完成 response 映射——network 不依赖 repository/retrofit 的代价，属于有意的端口例外。
6. 认证请求的编排归属在 session 层：`network/AuthenticatedEmbeddedClient` 只依赖
   `network/AuthenticatedRequestGate` 端口，组合根把它绑定到 `session/AuthenticatedSessionGate`
   （内部经 `UserManager` 注册的 `AuthenticatedRequestExecutor` 完成登录恢复与一次性重试）。
   端口实现由 di 桥接，network/session 互不 import。共享异常
   `AuthenticatedSessionRequiredException` 落在 `core/network`。

上述 1～4、5～6 项是有意保留的例外。store 清空与 5 个依赖环消除后，
耦合表里已没有"先拆谁"级别的热点；后续候选（按收益排序）：
`ui/screens` 巨型文件的可读性拆分（优先 `DownloadComicDetailScreen`，跨 4 层）、
`reader` 根包按来源策略与缓存生命周期继续归位、
`repository` 包名与职责的进一步澄清（现仅剩接口 + 三个实现）。

新增回归约束覆盖：批量重下去重与停止顺序、本地加载 IO 线程、旧目录/ZIP 兼容和失败清理、
清理期间的重复点击与阅读器租约保护、导出选择快照及过期统计、首页/搜索/周推荐独立注入与状态、
缓存迁移的来源解析顺序与"提交前不切目录"。
`ArchitectureBoundaryTest` 禁止文件访问重新进入阅读 ViewModel 和清理/导出 Screen，
并禁止缓存迁移的实现重新进入 Worker。

每次只迁移一个可独立验证的边界，并为 L2 分支、L3 组合和 L4 契约分别补测试。

## 构建与密钥（2026-09-12 核对）

- **语言级别**：Java / Kotlin bytecode **21**（`JvmTarget.JVM_21`，`source/targetCompatibility = 21`），
  与本机 GraalVM 21.0.9 LTS 语言级对齐。
- **构建 JDK**：Gradle daemon 必须使用标准 **OpenJDK 21**（如 Homebrew `openjdk@21`）。
  不要用 GraalVM 当 `JAVA_HOME`：AGP 的 `JdkImageTransform` 会对
  `core-for-system-modules.jar` 跑 jlink，而 GraalVM 的 `java.base` 仍依赖 `jdk.internal.vm.ci`，变换会失败。
  `gradle/wrapper` 为 Gradle **9.7.1**，AGP **9.4.0**，KSP **2.3.12**。本地 `gradlew` 默认走 wrapper；
  需要本机 brew Gradle 时设 `JM_USE_LOCAL_GRADLE=1`。
- **签名密钥**：`release` 的 `storePassword` / `keyPassword` 只读环境变量
  `JMCOMIC_RELEASE_STORE_PASSWORD` / `JMCOMIC_RELEASE_KEY_PASSWORD`；`release-key/`、
  `*.p12` / `*.jks` / `*.keystore`、`.env*` 均在 `.gitignore` 中，**git 历史中从未出现过签名材料**。
- **客户端协议常量**：`retrofit/ApiContext.kt` 的 `APP_DATA_SECRET`（推荐接口 payload 解密用）
  是**写死在源码里的协议盐**，不是用户密钥、也不是签名密钥。它会随 APK 一起被逆向，
  放进 BuildConfig/本地属性也无法保密；仓库内保留字面量是为了与线上 JM 协议兼容。
- **本地数据**：登录口令/会话等经 `storage/CryptoManager`（Android Keystore `app_master_key`，AES-GCM）加密落盘，仓库不持有用户密钥。
