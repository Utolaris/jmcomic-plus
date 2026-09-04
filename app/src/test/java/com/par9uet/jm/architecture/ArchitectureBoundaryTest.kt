package com.par9uet.jm.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureBoundaryTest {
    @Test
    fun `lower layers do not import the entry packages already removed from them`() {
        val violations = buildList {
            addAll(forbiddenImports("store", listOf("com.par9uet.jm.ui.", "com.par9uet.jm.worker.")))
            addAll(forbiddenImports("favorites", listOf("com.par9uet.jm.ui.")))
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

    private fun forbiddenImports(packagePath: String, prefixes: List<String>): List<String> {
        val sourceRoot = sourceRoot()
        val packageRoot = sourceRoot.resolve(packagePath)
        if (!Files.exists(packageRoot)) return emptyList()

        return buildList {
            Files.walk(packageRoot).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { path ->
                        Files.readAllLines(path).forEachIndexed { index, line ->
                            val imported = line.removePrefix("import ")
                            if (line.startsWith("import ") && prefixes.any(imported::startsWith)) {
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
