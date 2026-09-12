package com.par9uet.jm.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureBoundaryTest {
    @Test
    fun `lower layers do not import the entry packages already removed from them`() {
        val violations = buildList {
            addAll(forbiddenImports("ui/viewModel/ComicReadViewModel.kt", listOf(
                "java.io.", "java.util.zip.", "com.par9uet.jm.database.", "com.par9uet.jm.cache.",
            )))
            addAll(forbiddenImports("ui", listOf("com.par9uet.jm.database.")))
            addAll(forbiddenImports("data", listOf("com.par9uet.jm.reader.")))
            addAll(forbiddenImports("session", listOf("com.par9uet.jm.ui.")))
            addAll(forbiddenImports("core", listOf("com.par9uet.jm.ui.")))
            // 依赖环收口后的单向约束（详见 ARCHITECTURE.md「依赖现状与已知环」）：
            // data 不再反向依赖 repository；retrofit 是纯 wire 层，不回头 import 领域模型；
            // network 不依赖 repository/data/session/retrofit；session 不依赖 data（共享 DTO 在 core/model）。
            // forbiddenQualifiedUsages 同时扫描全限定引用，防止绕过 import 统计。
            addAll(forbiddenImports("data", listOf(
                "com.par9uet.jm.repository.", "com.par9uet.jm.session.",
            )))
            addAll(forbiddenImports("retrofit", listOf(
                "com.par9uet.jm.data.", "com.par9uet.jm.store.", "com.par9uet.jm.session.",
                "com.par9uet.jm.network.",
            )))
            addAll(forbiddenImports("network", listOf(
                "com.par9uet.jm.repository.", "com.par9uet.jm.data.",
                "com.par9uet.jm.session.", "com.par9uet.jm.retrofit.",
            )))
            // session 不得依赖 data 的数据源层（data/comic、wire mapper）；但 data/models 是
            // 零出度的共享领域契约，会话仓库要对外返回 ComicPage / CommentPage / ActionResult，
            // 因此只对 data.models 开一个精确例外。
            addAll(forbiddenImports("session", listOf(
                "com.par9uet.jm.data.", "com.par9uet.jm.repository.",
            ), except = listOf("com.par9uet.jm.data.models.")))
            addAll(forbiddenImports("favorites/data", listOf(
                "com.par9uet.jm.repository.",
            )))
            // UI only speaks favorites.model ports/contracts; Room/session impl stay in data.
            addAll(forbiddenImports("ui", listOf("com.par9uet.jm.favorites.data.")))
            addAll(forbiddenQualifiedUsages("ui", listOf("com.par9uet.jm.favorites.data.")))
            // Screens must not touch cache file atoms; CacheCleanupViewModel is the L2 exception.
            addAll(forbiddenImports("ui/screens", listOf("com.par9uet.jm.cache.atom.")))
            // 支撑层不得反向依赖具体页面：导航基础设施与通用组件只允许依赖 ui/navigation、
            // ui/theme、ui/glass 同层设施，LocalMainNavController 因此归 ui/navigation。
            addAll(forbiddenImports("ui/components", listOf("com.par9uet.jm.ui.screens.")))
            addAll(forbiddenImports("ui/glass", listOf("com.par9uet.jm.ui.screens.")))
            addAll(forbiddenQualifiedUsages("ui/components", listOf("com.par9uet.jm.ui.screens.")))
            addAll(forbiddenQualifiedUsages("ui/glass", listOf("com.par9uet.jm.ui.screens.")))
            // 表现层不得认识 wire DTO：`retrofit/model` 的映射在 repository/impl 内完成，
            // 仓库对外只返回 data/models 的领域类型。App.kt 是根包入口，单独钉一条。
            addAll(forbiddenImports("ui", listOf("com.par9uet.jm.retrofit.model.")))
            addAll(forbiddenQualifiedUsages("ui", listOf("com.par9uet.jm.retrofit.model.")))
            addAll(forbiddenImports("App.kt", listOf("com.par9uet.jm.retrofit.model.")))
            addAll(forbiddenQualifiedUsages("App.kt", listOf("com.par9uet.jm.retrofit.model.")))
            // ui/components 是通用支撑层：只接收参数、只消费 ui/* 的环境值（CompositionLocal）。
            // 不得依赖持久化、数据层、会话、缓存或任何具体领域的 L2/L4——取数由调用方或
            // 组合根负责（见 ARCHITECTURE.md「已采用的边界」）。
            addAll(forbiddenImports("ui/components", listOf(
                "com.par9uet.jm.storage.", "com.par9uet.jm.repository.", "com.par9uet.jm.database.",
                "com.par9uet.jm.session.", "com.par9uet.jm.cache.", "com.par9uet.jm.download.",
                "com.par9uet.jm.backup.", "com.par9uet.jm.update.", "com.par9uet.jm.network.",
                "com.par9uet.jm.reader.", "com.par9uet.jm.favorites.", "com.par9uet.jm.ui.viewModel.",
            )))
            addAll(forbiddenQualifiedUsages("ui/components", listOf(
                "com.par9uet.jm.storage.", "com.par9uet.jm.repository.", "com.par9uet.jm.session.",
                "com.par9uet.jm.cache.", "com.par9uet.jm.ui.viewModel.",
            )))
            listOf(
                "data", "retrofit", "network", "session", "favorites/data",
            ).forEach { pkg ->
                addAll(forbiddenQualifiedUsages(pkg, listOf("com.par9uet.jm.store.")))
            }
            addAll(forbiddenQualifiedUsages("data", listOf(
                "com.par9uet.jm.repository.", "com.par9uet.jm.session.",
            )))
            addAll(forbiddenQualifiedUsages("retrofit", listOf(
                "com.par9uet.jm.data.", "com.par9uet.jm.session.", "com.par9uet.jm.network.",
            )))
            addAll(forbiddenQualifiedUsages("network", listOf(
                "com.par9uet.jm.repository.", "com.par9uet.jm.data.",
                "com.par9uet.jm.session.", "com.par9uet.jm.retrofit.",
            )))
            addAll(forbiddenQualifiedUsages("session", listOf(
                "com.par9uet.jm.data.", "com.par9uet.jm.repository.",
            ), except = listOf("com.par9uet.jm.data.models.")))
            listOf("CacheCleanupScreen.kt", "downloadScreen/DownloadComicDetailScreen.kt").forEach { screen ->
                addAll(forbiddenImports("ui/screens/$screen", listOf(
                    "java.io.", "kotlinx.coroutines.", "com.par9uet.jm.download.coordinator.DownloadManager",
                    "com.par9uet.jm.reader.ReaderImagePipeline", "com.par9uet.jm.database.",
                    "com.par9uet.jm.download.export.export", "com.par9uet.jm.download.export.getCachedComicInfo",
                    "com.par9uet.jm.cache.atom.",
                )))
            }
            // 2026-09-13 表现层第三步：详情/阅读下载与提取编码取数下沉 L2。
            // qualified 同步扫，防止默认参数或全限定写法绕过 import 统计。
            listOf(
                "ComicDetailScreen.kt",
                "readScreen/ComicReadScreen.kt",
            ).forEach { screen ->
                addAll(forbiddenImports("ui/screens/$screen", listOf(
                    "com.par9uet.jm.download.coordinator.DownloadManager",
                )))
                addAll(forbiddenQualifiedUsages("ui/screens/$screen", listOf(
                    "com.par9uet.jm.download.coordinator.DownloadManager",
                )))
            }
            addAll(forbiddenImports("ui/screens/ExtractCodeScreen.kt", listOf(
                "com.par9uet.jm.repository.",
            )))
            addAll(forbiddenQualifiedUsages("ui/screens/ExtractCodeScreen.kt", listOf(
                "com.par9uet.jm.repository.",
            )))
            addAll(forbiddenImports("cache/atom", listOf(
                "com.par9uet.jm.ui.", "com.par9uet.jm.store.", "com.par9uet.jm.reader.",
            )))
            addAll(forbiddenImports("download/coordinator/DownloadManager.kt", listOf(
                "com.par9uet.jm.download.coordinator.DownloadComicCoordinator",
            )))
            addAll(forbiddenImports("store", listOf("com.par9uet.jm.ui.", "com.par9uet.jm.worker.")))
            addAll(forbiddenImports("favorites", listOf("com.par9uet.jm.ui.")))
            addAll(forbiddenImports("favorites/data", listOf("com.par9uet.jm.download.coordinator.")))
            // Room entities stay in L4; model contracts and presentation must not see them.
            addAll(forbiddenImports("favorites/model", listOf("com.par9uet.jm.database.")))
            addAll(forbiddenImports("favorites/presentation", listOf("com.par9uet.jm.database.")))
            addAll(forbiddenQualifiedUsages("favorites/model", listOf("com.par9uet.jm.database.")))
            addAll(forbiddenQualifiedUsages("favorites/presentation", listOf("com.par9uet.jm.database.")))
            addAll(forbiddenImports("backup", listOf("com.par9uet.jm.ui.", "com.par9uet.jm.download.coordinator.")))
            addAll(forbiddenImports("update", listOf("com.par9uet.jm.ui.")))
            addAll(forbiddenImports("download/export", listOf("com.par9uet.jm.download.molecule.")))
            addAll(forbiddenImports("download/coordinator/DownloadManager.kt", listOf(
                "com.par9uet.jm.database.", "com.par9uet.jm.download.atom.", "java.io.",
            )))
            addAll(forbiddenImports("download/molecule", listOf(
                "com.par9uet.jm.store.", "com.par9uet.jm.ui.", "com.par9uet.jm.worker.",
                "com.par9uet.jm.download.coordinator.", "com.par9uet.jm.reader.",
                "java.io.", "androidx.work.",
            )))
            addAll(forbiddenImports("download/atom", listOf(
                "com.par9uet.jm.download.molecule.", "com.par9uet.jm.store.",
                "com.par9uet.jm.download.coordinator.", "com.par9uet.jm.reader.",
                "com.par9uet.jm.ui.", "com.par9uet.jm.worker.", "com.par9uet.jm.database.dao.",
                "com.par9uet.jm.database.AppDatabase",
            )))
            addAll(forbiddenImports("worker/DownloadComicWorker.kt", listOf(
                "com.par9uet.jm.database.", "com.par9uet.jm.repository.", "com.par9uet.jm.reader.",
                "com.par9uet.jm.store.", "com.par9uet.jm.download.molecule.",
                "com.par9uet.jm.download.atom.", "coil.", "java.io.",
            )))
            addAll(
                forbiddenImports(
                    "worker/CacheMigrationWorker.kt",
                    listOf(
                        "com.par9uet.jm.database.", "com.par9uet.jm.repository.", "com.par9uet.jm.reader.",
                        "com.par9uet.jm.store.", "com.par9uet.jm.download.", "com.par9uet.jm.cache.",
                        "coil.", "java.io.", "android.provider.", "com.par9uet.jm.MainActivity",
                        "com.par9uet.jm.R",
                    ),
                    except = listOf("com.par9uet.jm.cache.migration."),
                )
            )
            addAll(forbiddenImports("cache/migration", listOf(
                "com.par9uet.jm.ui.", "com.par9uet.jm.worker.", "com.par9uet.jm.store.",
                "com.par9uet.jm.reader.", "com.par9uet.jm.download.",
            )))
            addAll(forbiddenImports("ui/viewModel/CachePathViewModel.kt", listOf(
                "com.par9uet.jm.worker.", "androidx.work.",
            )))
            listOf("AboutScreen.kt", "CheckUpdateScreen.kt", "BackupRestoreScreen.kt").forEach { screen ->
                addAll(forbiddenImports("ui/screens/$screen", listOf(
                    "okhttp3.", "com.google.gson.", "java.io.File", "androidx.core.content.FileProvider",
                    "com.par9uet.jm.database.", "com.par9uet.jm.backup.BackupManager",
                    "com.par9uet.jm.download.coordinator.DownloadManager", "com.par9uet.jm.storage.LocalSettingManager",
                    "com.par9uet.jm.update.AppUpdateDownloadManager",
                )))
            }
            addAll(forbiddenImports("utils", listOf("com.par9uet.jm.cache.", "com.par9uet.jm.data.")))
            addAll(
                forbiddenImports(
                    "reader/atom",
                    listOf(
                        "com.par9uet.jm.reader.molecule.",
                        "com.par9uet.jm.reader.coordinator.",
                        "com.par9uet.jm.ui.",
                        "com.par9uet.jm.worker.",
                        "com.par9uet.jm.store.",
                    ),
                )
            )
            addAll(
                forbiddenImports(
                    "reader/molecule",
                    listOf(
                        "com.par9uet.jm.reader.coordinator.",
                        "com.par9uet.jm.ui.",
                        "com.par9uet.jm.worker.",
                        "com.par9uet.jm.store.",
                    ),
                )
            )
        }

        assertTrue(
            "Layer boundary violations:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    private fun forbiddenQualifiedUsages(
        packagePath: String,
        prefixes: List<String>,
        except: List<String> = emptyList(),
    ): List<String> {
        val sourceRoot = sourceRoot()
        val packageRoot = sourceRoot.resolve(packagePath)
        if (!Files.exists(packageRoot)) return emptyList()

        return buildList {
            Files.walk(packageRoot).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { path ->
                        Files.readAllLines(path).forEachIndexed { index, line ->
                            val trimmed = line.trim()
                            val isComment = trimmed.startsWith("//") ||
                                trimmed.startsWith("*") || trimmed.startsWith("/*")
                            if (!isComment && prefixes.any(line::contains) &&
                                except.none(line::contains)
                            ) {
                                add("${sourceRoot.relativize(path)}:${index + 1}: $line")
                            }
                        }
                    }
            }
        }
    }

    private fun forbiddenImports(
        packagePath: String,
        prefixes: List<String>,
        except: List<String> = emptyList(),
    ): List<String> {
        val sourceRoot = sourceRoot()
        val packageRoot = sourceRoot.resolve(packagePath)
        if (!Files.exists(packageRoot)) return emptyList()

        return buildList {
            Files.walk(packageRoot).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { path ->
                        Files.readAllLines(path).forEachIndexed { index, line ->
                            val imported = line.removePrefix("import ")
                            if (line.startsWith("import ") &&
                                prefixes.any(imported::startsWith) &&
                                except.none(imported::startsWith)
                            ) {
                                add("${sourceRoot.relativize(path)}:${index + 1}: $line")
                            }
                        }
                    }
            }
        }
    }

    private fun sourceRoot(): Path = sequenceOf(
        Path.of("src/main/java/com/par9uet/jm"),
        Path.of("app/src/main/java/com/par9uet/jm"),
    ).first(Files::exists)
}
