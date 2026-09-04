package com.par9uet.jm.store

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.par9uet.jm.data.models.TagFilterLogic

internal fun buildFavoritePagingQuery(
    accountId: Int,
    blockedTagList: List<String>,
    searchText: String,
    selectedTags: Set<String>,
    selectedAuthors: Set<String>,
    folderId: Int,
    tagLogic: TagFilterLogic,
): SupportSQLiteQuery {
    val clauses = mutableListOf("c.accountId = ?")
    val args = mutableListOf<Any>(accountId)
    clauses += "m.folderId = ?"
    args += folderId

    val query = searchText.trim().lowercase()
    if (query.isNotBlank()) {
        clauses += "(LOWER(c.title) LIKE ? OR EXISTS (SELECT 1 FROM favorite_metadata_terms s WHERE s.accountId = c.accountId AND s.albumId = c.albumId AND s.normalizedValue LIKE ?))"
        val pattern = "%$query%"
        args += pattern
        args += pattern
    }

    blockedTagList.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().forEach { tag ->
        clauses += "NOT EXISTS (SELECT 1 FROM favorite_metadata_terms b WHERE b.accountId = c.accountId AND b.albumId = c.albumId AND b.termType = ? AND b.normalizedValue = ?)"
        args += FAVORITE_TERM_TAG
        args += tag
    }

    val normalizedTags = selectedTags.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
    when (tagLogic) {
        TagFilterLogic.AND -> normalizedTags.forEach { tag ->
            clauses += termExistsClause("t", FAVORITE_TERM_TAG)
            args += tag
        }
        TagFilterLogic.OR -> if (normalizedTags.isNotEmpty()) {
            clauses += normalizedTags.joinToString(" OR ", prefix = "(") { _ -> termExistsClause("t", FAVORITE_TERM_TAG) } + ")"
            normalizedTags.forEach { args += it }
        }
        TagFilterLogic.NOT -> normalizedTags.forEach { tag ->
            clauses += "NOT ${termExistsClause("t", FAVORITE_TERM_TAG)}"
            args += tag
        }
    }

    val normalizedAuthors = selectedAuthors.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
    if (normalizedAuthors.isNotEmpty()) {
        clauses += normalizedAuthors.joinToString(" OR ", prefix = "(") { _ -> termExistsClause("a", FAVORITE_TERM_AUTHOR) } + ")"
        normalizedAuthors.forEach { args += it }
    }

    // Scope-aware ordering: each folder list follows its own synchronized membership order.
    val orderBy = "m.remoteOrder ASC"
    return SimpleSQLiteQuery(
        "SELECT c.* FROM favorite_comics c " +
            "JOIN favorite_folder_memberships m ON m.accountId = c.accountId AND m.albumId = c.albumId " +
            "WHERE ${clauses.joinToString(" AND ")} ORDER BY $orderBy, c.albumId ASC",
        args.toTypedArray(),
    )
}

private fun termExistsClause(alias: String, type: String): String =
    "EXISTS (SELECT 1 FROM favorite_metadata_terms $alias WHERE $alias.accountId = c.accountId AND $alias.albumId = c.albumId AND $alias.termType = '$type' AND $alias.normalizedValue = ?)"
