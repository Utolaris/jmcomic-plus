package com.par9uet.jm.update

data class GithubRelease(
    val version: String,
    val name: String,
    val url: String,
    val body: String,
    val downloadUrl: String,
    val fileName: String
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class Success(val release: GithubRelease, val hasUpdate: Boolean) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

internal fun normalizeVersion(value: String): String {
    return value.trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore(" ")
        .substringBefore("-")
}

internal fun compareVersion(left: String, right: String): Int {
    val leftParts = normalizeVersion(left).split(".").map { it.toIntOrNull() ?: 0 }
    val rightParts = normalizeVersion(right).split(".").map { it.toIntOrNull() ?: 0 }
    val count = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until count) {
        val l = leftParts.getOrElse(index) { 0 }
        val r = rightParts.getOrElse(index) { 0 }
        if (l != r) return l.compareTo(r)
    }
    return 0
}

