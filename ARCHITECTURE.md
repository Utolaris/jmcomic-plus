# 四层架构约束

项目增量采用 L1～L4 四层架构。目标是让功能入口、流程决策和底层实现容易定位，
而不是为了分层机械增加文件数量。尚未迁移的代码应被明确列为例外，不能只改目录名称。

> 本文描述的是**当前代码的真实状态**，不是目标状态。文中出现的每个类名、路径和数字都应能在
> `app/src/main/java/com/par9uet/jm` 下找到；与代码不符的措辞视为文档缺陷，应直接修正。
> 最近一次核对：v1.4.2（`VERSION_CODE=142`），主源码 328 个 Kotlin 文件 / 42,218 行。

## 层级

| 层 | 职责 | 主要落点 |
| --- | --- | --- |
| L1 Entry | 只接收事件并交给 L2，不做业务判断 | `ui/screens`（33 个 `*Screen.kt`）、`ui/navigation`、`MainActivity`、`App`、`worker/DownloadComicWorker`（26 行）、`worker/CacheMigrationWorker`（54 行） |
| L2 Coordinator | 集中保存流程顺序、分支和跨边界协调 | `ui/viewModel`（14 个，加上 `favorites/presentation/FavoritesViewModel` 共 15 个）、`reader/ReaderImagePipeline`、`reader/coordinator`、`download/coordinator`、`cache/migration` 的协调器与通知适配、`favorites/sync`、`startup/PostStartupCoordinator`、`store` 中的兼容入口 |
| L3 Molecule | 组合多个原子能力，完成一个完整业务动作 | `reader/molecule`、`download/molecule`、`cache/migration` 的操作端口与实现、`favorites/usecase`、`backup/BackupRestoreOperations`、`download/export/DownloadExportOperations` |
| L4 Atom | 每个原子只负责一个底层契约 | `database`、`storage`、`retrofit`、`data`、`repository`、`network`、`image`、`coil`、`cache/atom`、`reader/atom`、`download/atom`、`download/export/PdfExport`、`favorites/data`、`update`、`contentfilter`、`launcher`、`utils` |
| Shared Contract | 不含行为的稳定 DTO，可被各层依赖 | `core/model/CommonUIState`、`favorites/model/FavoritesModels`、`reader/ReaderImageModels` |

依赖方向为 `L1 -> L2 -> L3 -> L4`。L3 之间、L4 之间不得为了方便横向调用；
需要组合时提升到 L3，需要决定顺序时提升到 L2。`di` 是组合根，可以引用所有层，
但不得承载业务判断。

`store` 是一个**历史混合包**（21 个文件 / 3,121 行），同一包内既有 L2 兼容入口
（`DownloadManager`、`UserManager`、`FavoriteStore`、`AppUpdateDownloadManager`），
也有 L4 状态与适配实现（`LocalSettingManager`、`FavoriteStore` 的 SQL 侧、
`DownloadWorkScheduler` 端口、`RemoteConfigManager`）。引用 `store` 时按具体类的层级判断，
不要按包名判断。

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
├── coordinator/                   L2
├── molecule/                      L3
├── atom/                          L4
└── export/                        L3 操作 + L4 PDF 编码

favorites/                   已迁移，但用词不同
├── model/                         共享契约
├── presentation/ViewModel         L2
├── sync/                          L2
├── usecase/                       L3
└── data/                          L4（同时定义 FavoriteStore 实现的窄端口）

cache/                       部分迁移
├── atom/CacheFiles.kt             L4
├── migration/                    L2 + L3
│   ├── CacheMigrationCoordinator.kt         L2 迁移顺序、失败分支、提交
│   ├── CacheMigrationWork.kt                L2 与 Worker 共用的 WorkManager 键与任务名
│   ├── CacheMigrationScheduler.kt           L2 入队端口（实现见 worker/）
│   ├── CacheMigrationNotifications.kt       L2 前台通知适配
│   ├── CacheMigrationOperations.kt          L3 操作端口与值类型
│   └── DeviceCacheMigrationOperations.kt    L3 文档读写与 DAO 组合
└── CacheModels / ComicDownloadCache / DocumentCacheStorage / CacheMigrationPaths / Config   未归位的 L4

update/ backup/ contentfilter/ launcher/ startup/   扁平包，按类判断层级
data/ repository/ retrofit/ store/                   遗留包，含依赖环
```

新代码优先沿用所在领域已有的子目录命名；跨领域新建时建议统一用
`coordinator / molecule / atom`。小功能不必创建子目录，但仍需遵守同样的依赖方向。
空目录 `task/` 已无文件，不应再往里放东西。

## 已采用的边界

- 启动后任务由 `startup/PostStartupCoordinator` 统一排序。
- 下载业务通过 `store/DownloadWorkScheduler` 端口提交任务，不直接构造 Worker。
  实现 `worker/WorkManagerDownloadWorkScheduler` 以 **comicId 为粒度**调用
  `enqueueUniqueWork(..., ExistingWorkPolicy.KEEP, ...)`，暂停/删除走
  `cancelUniqueWork`；批量下载通过 `batchId` / `batchTotal` 入参传递批次信息。
  `DownloadComicWorker`（26 行）只解析参数并调用 `DownloadComicCoordinator` 映射结果。
- 缓存目录迁移由 `CachePathViewModel` 通过 `cache/migration/CacheMigrationScheduler` 端口提交，
  由 `worker/CacheMigrationWorker`（54 行）执行；Worker 只读入参、调用协调器并把结果映射成 WorkManager 终态，
  唯一构造 Worker 的位置是 `worker/WorkManagerCacheMigrationScheduler`。
  `cache/migration/CacheMigrationCoordinator`（L2）持有迁移顺序与失败分支——
  先解析全部来源再动目标、全部文件落地后才写索引并切换目录、提交段整体 `NonCancellable`；
  `cache/migration/CacheMigrationOperations`（L3）组合 `cache/*` 文档原子与下载 DAO，
  自身不做顺序判断；前台通知由 `cache/migration/CacheMigrationNotifications`（L2）构造，
  Worker 不再反向引用 `MainActivity`。等下载空闲通过组合根提供的窄回调
  `CacheMigrationDownloadGate` 传入，`cache/migration` 不依赖 `download` 域。
- `store/DownloadManager` 是下载任务管理的 L2 兼容入口，持有协程生命周期，按业务结果入队并发送提示；
  `download/molecule/DownloadTaskOperations` 组合 DAO 与 `download/atom/DownloadFiles`，
  处理创建、重试、恢复和重新下载；`download/atom/DownloadFiles` 只清理已有缓存文件。
  业务层不依赖 Store、Worker 或 UI。排队端口仍由 L2 调用，以保留单篇创建先提示后入队、
  其他操作先入队后提示的现有顺序。暂停、删除、清理和批量重下都在此入口串行化；
  批量章节到漫画组的查询归属 L3。
  `download/coordinator/DownloadExecutionControl` 只暴露停止并等待写入结束的能力，
  任务管理不再依赖下载执行器的具体类型。
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
  `update` 提供版本解析（`GithubReleaseSource`）、发布模型（`AppRelease`）与系统安装适配器（`ApkInstaller`）。
- `BackupRestoreViewModel` 管理备份/恢复步骤及任务生命周期，`backup/BackupRestoreOperations`
  组合设置快照、文档读写和下载排队；Screen 仅持有系统文件选择器和展示组件。
- `favorites/sync/FavoriteSyncController` 是唯一收藏同步任务入口，按登录会话代次隔离任务、进度与结果；
  `favorites/usecase/SyncFavorites` 负责远端分页、元数据补齐和受会话保护的本地提交。
- `store/FavoriteStore` 保留 Room 事务及 DAO 操作，纯 SQL 构造、同步规划和实体映射分别位于
  `store/FavoriteQueries`、`store/FavoriteSyncPlanner` 和 `store/FavoriteMappers`。
  它直接实现 `favorites/data` 定义的三个窄本地端口 `FavoriteLocalQuery`、
  `FavoriteLocalMutation`、`FavoriteLocalSync`，避免额外转发对象。
- `ArchitectureBoundaryTest` 固定以下边界，防止后续补丁重新引入反向依赖。
  当前生效的断言（以测试代码为准）：

  | 受约束位置 | 禁止 import |
  | --- | --- |
  | `ui/viewModel/ComicReadViewModel.kt` | `java.io.`、`java.util.zip.`、`database.`、`cache.` |
  | `ui/screens/CacheCleanupScreen.kt`、`ui/screens/downloadScreen/DownloadComicDetailScreen.kt` | `java.io.`、`kotlinx.coroutines.`、`store.DownloadManager`、`reader.ReaderImagePipeline`、`database.`、`download.export.export`、`download.export.getCachedComicInfo`、`cache.atom.` |
  | `ui/screens/AboutScreen.kt`、`CheckUpdateScreen.kt`、`BackupRestoreScreen.kt` | `okhttp3.`、`gson`、`java.io.File`、`FileProvider`、`database.`、`store.BackupManager`、`store.DownloadManager`、`store.LocalSettingManager`、`store.AppUpdateDownloadManager` |
  | `cache/atom` | `ui.`、`store.`、`reader.` |
  | `download/molecule` | `store.`、`ui.`、`worker.`、`download.coordinator.`、`reader.`、`java.io.`、`androidx.work.` |
  | `download/atom` | `download.molecule.`、`store.`、`download.coordinator.`、`reader.`、`ui.`、`worker.`、`database.dao.`、`database.AppDatabase` |
  | `store/DownloadManager.kt` | `download.coordinator.DownloadComicCoordinator`、`database.`、`download.atom.`、`java.io.` |
  | `store`（整体） | `ui.`、`worker.` |
  | `favorites`、`backup`、`update`（整体） | `ui.` |
  | `reader/atom` | `reader.molecule.`、`reader.coordinator.`、`ui.`、`worker.`、`store.` |
  | `reader/molecule` | `reader.coordinator.`、`ui.`、`worker.`、`store.` |
  | `worker/DownloadComicWorker.kt` | `database.`、`repository.`、`reader.`、`store.`、`download.molecule.`、`download.atom.`、`coil.`、`java.io.` |
  | `worker/CacheMigrationWorker.kt` | `database.`、`repository.`、`reader.`、`store.`、`download.`、`cache.`（`cache.migration.` 除外）、`coil.`、`java.io.`、`android.provider.`、`MainActivity`、`R` |
  | `ui/viewModel/CachePathViewModel.kt` | `worker.` |
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

Reader 的 L3 不得依赖 UI、Worker 或 Store，L4 不得反向依赖 L3。磁盘缓存将源文件与
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
链路的完整行为说明见 [docs/reader-image-pipeline.md](docs/reader-image-pipeline.md)。

## 依赖现状与已知环

按包统计 `import com.par9uet.jm.*` 得到的环（A→B 且 B→A）：

| 环 | 性质 | 说明 |
| --- | --- | --- |
| `data` ↔ `repository` | 跨层遗留 | 模型与仓库实现双向引用，历史包袱 |
| `data` ↔ `retrofit` | 跨层遗留 | 模型同时被解析层引用 |
| `retrofit` ↔ `store` | 跨层遗留 | `UserManager` 直接用 `retrofit.model.*`，`retrofit` 反向用 `store` 的会话状态 |
| `repository` ↔ `store` | 跨层遗留 | `RemoteConfigManager`、`UserManager` 与仓库互相引用 |
| `data` ↔ `reader` | 跨层遗留 | `data/models/ComicPicImageState` 引用 `reader.ReaderPage` / `ReaderPageKey`，而 `reader/molecule/LoadLocalChapter` 反向引用 `data.models.ComicChapter` |
| `download` ↔ `store` | **L2 内部互调** | `DownloadManager`→`download.molecule/coordinator`；`DownloadFeedback`/`DownloadComicCoordinator`→`store` 的偏好与提示聚合器。同为 L2，不构成逆向依赖 |
| `favorites` ↔ `store` | **契约与实现的自然双向** | `favorites/data` 定义窄端口，`store/FavoriteStore` 实现它们，因此必然互相 import |

前五项是需要在迁移具体功能时收拢模型和端口的**真实技术债**；
不能用一次性改包名掩盖依赖环。后两项是有意为之，不应"修掉"。

另有一处**未登记的例外**：`ui` 包中有 4 个文件直接 import `database`
（`ui/screens/downloadScreen/DownloadListItem.kt`、`ui/viewModel/DownloadViewModel.kt`、
`ui/viewModel/DownloadComicDetailViewModel.kt`、`ui/viewModel/DownloadExportViewModel.kt`）。
这是 L1/L2 直连 L4，与"依赖必须逐层下行"的规则冲突，目前未被 `ArchitectureBoundaryTest` 覆盖。
修复方向是把 DAO 访问收敛到 `download/molecule` 或专门的 L3，而不是放宽测试。

## 耦合热点与拆分优先级

下面是对 `import com.par9uet.jm.*` 做静态统计的结果（模块 = 一二级包目录），
用于决定**先拆谁**。Ce=扇出、Ca=扇入、I=Ce/(Ce+Ca) 不稳定性。

| 模块 | 行数 | Ce | Ca | I | 判断 |
| --- | --- | --- | --- | --- | --- |
| `worker` | 164 | 3 | 1 | 0.75 | 已拆：两个 Worker 都只解析参数，扇出落在各自的 L2 协调器与端口实现 |
| `di` | 521 | 27 | 1 | 0.96 | 组合根，合法，不动 |
| `ui/screens` | 16,072 | 21 | 3 | 0.88 | 表现层，扇出集中在 `store`/`data` |
| `ui/viewModel` | 3,030 | 28 | 3 | 0.90 | 扇出最高，但多为契约与偏好 |
| `store` | 3,142 | 16 | 19 | 0.46 | **全局枢纽**，Ca 与 Ce 双高 |
| `cache/migration` | 577 | 23 | 3 | 0.88 | 扇出集中在 `cache` 域内文档原子与下载 DAO，属 L3 组合，不必再拆 |
| `reader`（根） | 2,504 | 7 | 0 | 1.00 | 无外部依赖方，自洽 |
| `data` | 1,007 | 6 | 21 | 0.22 | 稳定契约，不要动 |
| `utils` | 545 | 2 | 20 | 0.09 | 稳定，不要动 |
| `database/model` | 196 | 0 | 15 | 0.00 | 稳定，不要动 |
| `retrofit/model` | 640 | 2 | 16 | 0.11 | 稳定，不要动 |

单文件维度（"跨层数"= 该文件 import 触及的层数，含自身）：

- **跨 4 层**：`ui/viewModel/ComicDetailViewModel`（473 行）、
  `ui/screens/downloadScreen/DownloadComicDetailScreen`（465 行）
- **跨 3 层且 >600 行**：`LocalSettingScreen`(879)、`ComicDetailScreen`(828)、
  `BackupRestoreScreen`(780)、`ComicReadScreen`(740)、`FavoritesToolbar`(734)、
  `FavoritesModalHost`(692)、`ComicCommentScreen`(666)、`CheckUpdateScreen`(662)、
  `WelcomeScreen`(651)
- **扇出最高**：`cache/migration/DeviceCacheMigrationOperations`（22，均为 `cache` 域内文档原子）、
  `reader/molecule/ReaderSourceLoader`（22，但均为 reader 域内 internal 组件，属正常）

### 值得拆的两个目标

**1. `store` 包（21 文件 / 3,142 行，Ca=19 且 Ce=16）——拆包，收益最大但工作量最大**

它是唯一被 19 个模块依赖、同时又依赖 16 个模块的包，L2 兼容入口与 L4 存储实现混在一起。
这直接导致两件事：按包名判断层级失效；`download↔store`、`favorites↔store` 看起来像依赖环。

拆法：L2 兼容入口（`DownloadManager`、`UserManager`、`AppUpdateDownloadManager`、
`RemoteConfigManager`）下沉到各自领域的 `coordinator/`；L4 状态与偏好
（`LocalSettingManager`、`FavoriteStore` 的 SQL 侧、各种 `*Preferences`）下沉到
`storage/` 或 `database/`。拆完再回看依赖环表，多半会自然消失。

**2. 下载相关的 4 个 UI 文件直连 Room——小改动，先修**

`ui/screens/downloadScreen/DownloadListItem`、`ui/viewModel/DownloadViewModel`、
`DownloadComicDetailViewModel`、`DownloadExportViewModel` 直接 import `database`。
把 DAO 访问收敛到 `download/molecule` 或专门的 L3 即可，不需要动 UI 结构。

### 不建议动

- **`data` / `utils` / `database/model` / `retrofit/model`**：Ca 高但 Ce 极低，
  是被依赖的稳定契约，拆分只会制造转发层。
- **`reader` 根包 15 个文件**：Ca=0，没有外部依赖方，粒度已足够小，下沉到 `atom/`
  只增加目录层级。
- **`di`**：I=0.96 是组合根的应有形态。
- **`ui/screens` 的巨型文件**：行数确实跨层（L1 直接读 `store`/`data`），
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
- `data`、`repository`、`retrofit`、`store` 之间的环已被完整列出（见上表），
  迁移时应先抽取窄端口再切断反向 import，避免产生新的转发型假分层。

## 当前例外与迁移顺序

1. 下载协调器直接使用 DAO 和反馈适配器；进度与同组任务状态决定通知和终态分支，避免把这些判断拆散。
2. 更新协调器直接使用下载及安装端口，备份协调器直接使用无副作用的校验/提取能力；
   这些结果直接决定流程分支，避免为它们添加仅转发调用的用例层。
3. 缓存清理协调器通过组合根提供的窄回调协调下载和阅读器的资源生命周期，并直接调用缓存文件端口；
   导出协调器直接调用 PDF 文件端口；缓存迁移协调器同样以窄回调
   （`CacheMigrationDownloadGate`）等待下载空闲。这些是 L2 的跨功能协调及选定适配器例外，
   L4 不得反向调用协调器。
4. 下载相关的 4 个 UI 文件直连 Room（见"依赖现状"）。这是**待修**而非**有意保留**，
   不应当作"架构允许"来扩散。

上述 1～3 项是有意保留的例外，第 4 项是待修项。优先级见"耦合热点与拆分优先级"。

新增回归约束覆盖：批量重下去重与停止顺序、本地加载 IO 线程、旧目录/ZIP 兼容和失败清理、
清理期间的重复点击与阅读器租约保护、导出选择快照及过期统计、首页/搜索/周推荐独立注入与状态、
缓存迁移的来源解析顺序与"提交前不切目录"。
`ArchitectureBoundaryTest` 禁止文件访问重新进入阅读 ViewModel 和清理/导出 Screen，
并禁止缓存迁移的实现重新进入 Worker。

每次只迁移一个可独立验证的边界，并为 L2 分支、L3 组合和 L4 契约分别补测试。
