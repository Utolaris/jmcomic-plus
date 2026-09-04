# 四层架构约束

项目增量采用 L1～L4 四层架构。目标是让功能入口、流程决策和底层实现容易定位，
而不是为了分层机械增加文件数量。尚未迁移的代码应被明确列为例外，不能只改目录名称。

## 层级

- L1 Entry：`MainActivity`、根 Compose 入口、Screen、Worker 等 Android 入口。只接收事件并交给 L2。
- L2 Coordinator：ViewModel、启动协调器及 `feature/<name>/coordinator`。集中保存流程顺序、分支和跨边界协调。
- L3 Molecule：`feature/<name>/usecase` 或 `molecule`。组合多个原子能力，完成一个完整业务动作。
- L4 Atom：Repository/DataSource、DAO、Storage、解析器、网络和文件适配器。每个原子只负责一个底层契约。
- Shared Contract：`core/model` 等不含行为的稳定 DTO，可被各层依赖。

依赖方向为 `L1 -> L2 -> L3 -> L4`。L3 之间、L4 之间不得为了方便横向调用；
需要组合时提升到 L3，需要决定顺序时提升到 L2。`di` 是组合根，可以引用所有层，
但不得承载业务判断。

## 新功能目录模板

```text
feature/<name>/
├── entry/          # L1：界面或框架入口
├── coordinator/    # L2：流程和分支
├── molecule/       # L3：完整业务动作
└── atom/           # L4：网络、数据库、文件等叶子能力
```

小功能不必创建四个目录，但仍需遵守同样的依赖方向。

## 已采用的边界

- 启动后任务由 `startup/PostStartupCoordinator` 统一排序。
- 下载业务通过 `DownloadWorkScheduler` 端口提交任务，不直接构造 Worker。
- 通用异步状态放在 `core/model`，状态存储层不再依赖 UI 包。
- 收藏分页适配器归属收藏功能，收藏功能不再反向依赖通用 UI 包。
- PDF 导出归属 `download/export`，通用工具包不再反向依赖下载缓存。
- 屏蔽规则和桌面入口切换分别归属 `contentfilter`、`launcher`，不再作为通用工具依赖业务模型。
- 阅读器图片链路由 `ReaderImagePipeline`（L2）统一调度，来源加载在 L3，内存和磁盘缓存位于 L4。
- `ArchitectureBoundaryTest` 固定以上边界，防止后续补丁重新引入反向依赖。

### 阅读器图片链路

```text
UI / ViewModel / Download Worker       # 调用入口
└── reader/ReaderImagePipeline         # L2 请求优先级、去重、解码顺序
    ├── reader/coordinator/ReaderRemoteTelemetry # L2 远端事件归档
    ├── reader/molecule/ReaderSourceLoader   # L3 来源缓存、CDN、回退与重试
    └── reader/atom/
        ├── ReaderBitmapCache          # L4 解码图内存所有权
        └── ReaderImageDiskCache       # L4 文件租约、代次、写入与清理
```

Reader 的 L3 不得依赖 UI、Worker 或 Store，L4 不得反向依赖 L3。磁盘缓存将源文件与
解码文件保留在同一个原子内，是为了让清理时的锁顺序和 cache generation 保持原子性；
拆成两个互相调用的 L4 会重新引入竞态和横向依赖。

`ReaderImagePipeline` 直接持有内存、磁盘缓存和解码等选定适配器，是 L2 的显式例外：缓存命中
和解码结果决定了请求是否进入后续流程，留在 L2 才能完整读出“内存 → 本地/磁盘 → 来源 →
解码 → 回写”的控制流。继续包一层只会形成转发型假分层，且会把资源所有权藏到 L3。

## 审查判断

- `AboutScreen`、`BackupRestoreScreen` 是明确的结构膨胀：界面直接执行网络、文件、安装或数据库流程。
- `DownloadComicWorker`、`FavoriteStore` 是职责膨胀：一个对象同时决定流程并处理多个底层细节。
- `ReaderImagePipeline` 已完成首轮拆分；剩余复杂度集中在可独立测试的来源策略和缓存生命周期，不再堆在公开入口中。
- `LocalSettingScreen` 等纯展示文件虽然较长，但当前主要问题是可读性，不是跨层耦合，因此不作为首批拆分目标。
- `data`、`repository`、`retrofit`、`store` 之间仍有历史双向依赖。应在迁移具体功能时收拢模型和端口，不能用一次性改包名掩盖依赖环。

## 当前例外与迁移顺序

1. `AboutScreen` 同时包含 UI、版本判断、GitHub 请求和 APK 安装，应优先拆为更新协调器、版本用例和网络/安装原子。
2. `BackupRestoreScreen` 在 UI 内编排验证、备份、恢复和数据库写入，应拆出备份恢复协调器。
3. `DownloadComicWorker` 仍同时承担入口和完整下载流程，应把封面、分页下载、落盘及状态提交移入 L3。
4. `FavoriteStore` 混合事务、同步规划、SQL 构造和实体映射，应先提取纯同步规划及映射原子。

每次只迁移一个可独立验证的边界，并为 L2 分支、L3 组合和 L4 契约分别补测试。
