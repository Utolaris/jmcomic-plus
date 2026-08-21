# JMcomic Plus

<img src=".\app\src\main\res\mipmap-hdpi\logo_round.webp" alt="logo" style="zoom:200%;" />

#### ⚠️⚠️ 本项目含有 NSFW 内容，请酌情观看 ⚠️⚠️

JMcomic Plus 是一个基于 Kotlin、Jetpack Compose 与 Material 3 构建的 Android 漫画客户端，是 [HongShi2333/jmcomic-next](https://github.com/HongShi2333/jmcomic-next) 的个人维护 fork。上游项目基于 [Dedicatus546/jm-mobile](https://github.com/Dedicatus546/jm-mobile) 二次开源整理而来，并使用 [JUKOMU/JMComic-Api-Java](https://github.com/JUKOMU/JMComic-Api-Java) 作为默认 API。

本项目在完整保留上游功能的基础上，重点针对「漫画正文加载」和「封面展示」两条图片链路做了大量性能与稳定性改进。分叉基线为上游 v1.2.3。

> 本项目仅供学习、研究和技术交流使用，请遵守当地法律法规及相关服务条款。

## 相比上游的改进

### 漫画正文加载（Reader CDN 加速）

- **多 CDN 自动加速**：正文图片不再只依赖单一节点，客户端会维护一份 JM 图片镜像目录，结合延迟探测、历史延迟和失败冷却自动选择更快的节点。
- **CDN 竞速（Hedge）**：翻页时主节点未在极短时间内返回响应头，会立即并行拉起备用节点，谁先返回就用谁，并取消慢的那一路，明显降低肉眼等待时间。
- **Fast-header / Slow-body 不误伤**：竞速判定只看「连接 + 首个响应头」的快慢，不再错误地把「响应快但图片本身下载慢」的正常页面当成失败而启动第二路下载。
- **更稳的节点记忆**：节点排序基于历史延迟平滑统计（EWMA），某一次偶然的成功不再立刻抢占第一；失败的节点会进入冷却期，避免反复踩坑。
- **图片源去重**：同一张图无论走哪个镜像，都共享同一份加载任务和缓存，阅读、预加载、下载之间不会重复下载同一张原图。
- **后台任务主动让路**：正在偷偷预加载或下载时一旦用户翻页，后台请求会立刻让出网络通道，保证当前可见页面的图片优先显示。
- **智能预加载调度**：预加载的并发数真正受控（单槽设备仅 1 路、高性能设备最多 2 路），翻页方向变化会即时取消过期任务，因此在提升加载速度的同时不会抢占前台资源。
- **连接预热提前**：进入章节拿到图片列表后立刻预热首屏连接的 CDN 节点，减少第一张图的等待。
- **更安全的镜像路径**：只对确认属于 JM 图片体系的路径做镜像替换（如 `/media/photos/`、`/media/albums/`），其他未知路径保持原样，不随意换节点、不丢 query 参数。

### 漫画封面展示

- **封面多 CDN 兜底**：修复了「某一个 CDN 节点不可用导致整个列表封面全部加载不出来」的问题。封面会按顺序尝试：上次成功的节点 → 远端配置节点 → 已知镜像节点，前一个失败自动切换下一个。
- **全列表快速收敛**：某个节点失败后全局记住，后续漫画封面直接以可用节点开头，首页几十张封面只需少量失败请求即可整体恢复。
- **缓存不重复**：不同镜像节点返回的是同一张封面，统一使用按漫画 ID 的逻辑缓存键，换节点不会产生多份重复缓存。
- **不干扰正文**：封面仍走 Coil 链路，与正文 Reader 管道完全分离；封面不参与正文的竞速、预加载和切片解码。

### 稳定性与其他

- 网络请求真正具备优先级与并发上限，前置页面加载不再被后台任务拖慢。
- 下载封面等场景同样接入多节点兜底，避免后台任务因为单一节点失效而失败。
- 关键路径配套了真实网络时序回归测试、镜像路由测试和预加载调度测试，防止上述问题回归。

## 截图

| 首页                            | 详情（日间）                                            |
| ------------------------------- | ------------------------------------------------------- |
| ![首页](readme-assets/首页.jpg) | ![详情（日间模式）](readme-assets/详情（日间模式）.jpg) |

| 详情（夜间）                                            | 搜索                             |
| ------------------------------------------------------- | -------------------------------- |
| ![详情（夜间模式）](readme-assets/详情（夜间模式）.jpg) | ![搜索](readme-assets/搜索1.jpg) |

| 搜索结果                             | 每周必看                                |
| ------------------------------------ | --------------------------------------- |
| ![搜索结果](readme-assets/搜索2.jpg) | ![每周必看](readme-assets/每周必看.jpg) |

| 签到                            | 个人中心                                |
| ------------------------------- | --------------------------------------- |
| ![签到](readme-assets/签到.jpg) | ![个人中心](readme-assets/个人中心.jpg) |

## 功能概览


- 首页推荐：展示轮播推荐内容，并提供常用页面入口。
- 漫画搜索：支持关键词搜索、搜索结果列表、历史搜索记录与排序筛选。
- 漫画详情：展示封面、标题、作者、标签、章节、评论、相关推荐与收藏状态。
- 漫画阅读：支持滚动阅读和分页阅读，支持多 CDN 加速、预加载与图片切片还原。
- 用户系统：支持登录、自动登录、签到、收藏列表、阅读历史与评论历史。
- 评论功能：支持查看漫画评论、发表评论和回复评论。
- 每周推荐：支持按分类和类型查看每周必看内容。
- 本地下载：使用 WorkManager 创建后台下载任务，下载封面和图片后写入本地缓存。
- 下载管理：使用 Room 保存下载任务、进度、状态、封面路径和压缩包路径。
- 本地阅读：已下载漫画可从本地缓存或 zip 包中读取图片。
- PDF 导出：已缓存漫画可通过系统目录授权导出为 PDF。
- AI 对话：提供本地会话管理和流式响应展示，支持可选的思考块展示逻辑。
- 本地设置：支持 API 域名、图片分流、主题模式、阅读模式和预加载数量等配置。
- 深色模式：通过 Material 3 主题适配浅色、深色和跟随系统模式。

## 技术栈

| 类型       | 使用内容                                             |
| ---------- | ---------------------------------------------------- |
| 语言       | Kotlin                                               |
| UI         | Jetpack Compose、Material 3、Material Icons Extended |
| 导航       | AndroidX Navigation Compose                          |
| 架构与状态 | ViewModel、StateFlow、Compose State                  |
| 依赖注入   | Koin                                                 |
| 网络       | Retrofit、OkHttp、Gson                               |
| 图片加载   | Coil                                                 |
| 本地数据库 | Room、Room Paging                                    |
| 分页       | Paging 3                                             |
| 后台任务   | WorkManager                                          |
| 构建       | Gradle Kotlin DSL、KSP、Android Gradle Plugin        |

## 环境要求

- Android Studio Narwhal 或更新版本。
- JDK 17 或更高版本，项目源码编译目标为 JVM 17。
- Android SDK：`compileSdk 36`、`targetSdk 35`、`minSdk 23`。
- 建议使用仓库内自带的 Gradle Wrapper（`gradlew`）。

## 快速开始

```bash
git clone https://github.com/Utolaris/jmcomic-plus.git
cd jmcomic-plus
```

使用 Android Studio 打开项目根目录，等待 Gradle 同步完成后运行 `app` 模块。

构建 Debug APK：

```bash
./gradlew :app:assembleDebug --console=plain
```

构建 Release APK：

```bash
./gradlew :app:assembleRelease --console=plain
```

运行全部单元测试：

```bash
./gradlew test --console=plain
```

## 应用信息

| 项                 | 当前值               |
| ------------------ | -------------------- |
| 应用名             | JMcomic              |
| Android 模块       | `app`                |
| namespace          | `com.par9uet.jm`     |
| applicationId      | `jmcomicoi.net`      |
| minSdk             | `23`                 |
| targetSdk          | `35`                 |
| compileSdk         | `36`                 |
| 当前 versionName   | `1.2.3`              |
| License            | GPL-3.0              |

## 项目结构

```text
.
├── app/                              Android 应用模块
│   ├── build.gradle.kts              app 模块构建配置
│   └── src/main/                     Kotlin 源码与资源
├── gradle/                           依赖版本管理与 Wrapper
├── readme-assets/                    README 截图资源
├── CHANGELOG                         变更记录
├── LICENSE                           GPL-3.0 许可证
├── README.md                         项目说明
├── settings.gradle.kts               仓库源和模块声明
└── version.properties                应用版本号配置
```

核心源码位于 `app/src/main/java/com/par9uet/jm`，包含 cover（Coil 封面配置）、coil（图片加载）、retrofit（网络层）、repository（数据仓库）、store（全局状态）、ui（Compose 页面与组件）、worker（后台任务）等目录；正文图片加速相关代码集中在 `reader` 目录。

## 二次开源与 Fork 说明

- 本项目是 `HongShi2333/jmcomic-next` 的个人维护 fork，分叉基线为上游 v1.2.3。
- 上游由 `Dedicatus546/jm-mobile` 二次开源整理而来，API 来自 `JUKOMU/JMComic-Api-Java`。
- 本 fork 的主要方向：正文图片加载加速、封面展示稳定性、以及配套的网络与调度可靠性测试。
- 如上游有新的功能或修复，欢迎合并回本仓库。

感谢 [LINUX DO 论坛](https://linux.do)、[RawChat 团队](https://linux.do/u/RawChat) 以及所有为上游 jmcomic-next 提供建议和代码的朋友。
也感谢为上游提交 issue、提供建议的各位，以及 [Jea1ousy](https://github.com/Jea1ousy)、[linze0721](https://github.com/linze0721)、[hifumi_mizuhara](https://linux.do/u/hifumi_mizuhara) 等为项目添砖加瓦的朋友。

## 免责声明

本项目仅供学习、研究和技术交流使用。项目作者与任何第三方服务、原始应用或内容提供方无关。

使用者应自行遵守当地法律法规以及相关服务条款。因使用本项目产生的任何法律、版权、账号、数据或财务风险均由使用者自行承担。

## License

本项目遵循仓库中的 `LICENSE` 文件，许可证为 GPL-3.0。原项目版权和许可证信息请参考 `HongShi2333/jmcomic-next` 与 `Dedicatus546/jm-mobile`。
