package com.par9uet.jm.store

import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.database.model.FavoriteComicEntity
import com.par9uet.jm.database.model.FavoriteMetadataEntity
import com.par9uet.jm.database.model.FavoriteMetadataTermEntity

internal fun FavoriteRemoteItem.toComicEntity(
    accountId: Int,
    order: Int,
    syncedAt: Long,
    existing: FavoriteComicEntity?,
    keepFullMetadata: Boolean,
): FavoriteComicEntity {
    val existingMetadata = existing?.takeIf { keepFullMetadata }
    val authors = existingMetadata?.authorList ?: authors.normalized()
    val tags = existingMetadata?.tagList ?: tags.normalized().ifEmpty { categoryTags() }
    val roles = existingMetadata?.roleList ?: emptyList()
    val works = existingMetadata?.workList ?: emptyList()
    return FavoriteComicEntity(
        accountId = accountId,
        albumId = albumId,
        title = existingMetadata?.title ?: title,
        authorList = authors,
        // Keep detail text while a possibly shorter/incomplete list response is reconciled.
        description = existingMetadata?.description ?: description,
        image = image,
        tagList = tags,
        roleList = roles,
        workList = works,
        categoryId = categoryId,
        categoryTitle = categoryTitle,
        subCategoryId = subCategoryId,
        subCategoryTitle = subCategoryTitle,
        metadataComplete = existingMetadata != null,
        metadataUpdatedAt = existingMetadata?.metadataUpdatedAt ?: 0L,
        lastFavoriteOrder = order,
        lastFavoriteSyncAt = syncedAt,
    )
}

internal fun FavoriteRemoteItem.toIncompleteMetadata(
    accountId: Int,
    syncedAt: Long,
) = FavoriteMetadataEntity(
    accountId = accountId,
    albumId = albumId,
    tags = tags.normalized().ifEmpty {
        listOfNotNull(categoryTitle, subCategoryTitle).normalized()
    },
    authors = authors.normalized(),
    metadataComplete = false,
    metadataUpdatedAt = syncedAt,
)

internal fun FavoriteRemoteItem.categoryTags(): List<String> =
    listOfNotNull(categoryTitle, subCategoryTitle).normalized()

internal fun FavoriteComicEntity.categoryTags(): List<String> =
    listOfNotNull(categoryTitle, subCategoryTitle).normalized()

internal fun FavoriteRemoteItem.toTerms(accountId: Int): List<FavoriteMetadataTermEntity> =
    buildTerms(
        accountId = accountId,
        albumId = albumId,
        tags = tags.normalized().ifEmpty {
            listOfNotNull(categoryTitle, subCategoryTitle)
        },
        authors = authors,
    )

internal fun FavoriteMetadataPayload.toEntity(accountId: Int, syncedAt: Long) =
    FavoriteMetadataEntity(
        accountId = accountId,
        albumId = albumId,
        tags = tags.normalized(),
        authors = authors.normalized(),
        roles = roles.normalized(),
        works = works.normalized(),
        metadataComplete = true,
        metadataUpdatedAt = syncedAt,
    )

internal fun FavoriteMetadataPayload.toTerms(
    accountId: Int,
    item: FavoriteRemoteItem,
): List<FavoriteMetadataTermEntity> = buildTerms(
    accountId = accountId,
    albumId = albumId,
    tags = tags.normalized().ifEmpty {
        listOfNotNull(item.categoryTitle, item.subCategoryTitle)
    },
    authors = authors,
)

internal fun FavoriteComicEntity.toRemoteItem() = FavoriteRemoteItem(
    albumId = albumId,
    title = title,
    authors = authorList,
    description = description,
    image = image,
    tags = tagList,
    categoryId = categoryId,
    categoryTitle = categoryTitle,
    subCategoryId = subCategoryId,
    subCategoryTitle = subCategoryTitle,
)

internal fun FavoriteComicEntity.toComic() = Comic(
    id = albumId,
    name = title,
    authorList = authorList,
    description = description,
    readCount = 0,
    likeCount = 0,
    commentCount = 0,
    tagList = tagList,
    roleList = roleList,
    workList = workList,
    isCollect = true,
    relateComicList = emptyList(),
    comicChapterList = emptyList(),
    price = 0,
    isBuy = false,
)

internal fun FavoriteRemoteItem.invalidatesMetadata(existing: FavoriteComicEntity): Boolean =
    (title.isNotBlank() && title != existing.title) ||
        (description.isNotBlank() && description != existing.description) ||
        (authors.isNotEmpty() && !existing.authorList.normalized().containsAll(authors.normalized())) ||
        (tags.isNotEmpty() && !existing.tagList.normalized().containsAll(tags.normalized())) ||
        categoryId != existing.categoryId ||
        categoryTitle != existing.categoryTitle ||
        subCategoryId != existing.subCategoryId ||
        subCategoryTitle != existing.subCategoryTitle

internal fun FavoriteComicEntity.matchesLightweight(item: FavoriteRemoteItem): Boolean =
    title == item.title &&
        ((metadataComplete && item.description.isBlank()) || description == item.description) &&
        image == item.image &&
        categoryId == item.categoryId &&
        categoryTitle == item.categoryTitle &&
        subCategoryId == item.subCategoryId &&
        subCategoryTitle == item.subCategoryTitle &&
        (item.authors.isEmpty() || authorList.normalized().containsAll(item.authors.normalized())) &&
        (item.tags.isEmpty() || tagList.normalized().containsAll(item.tags.normalized()))

internal fun List<String>.normalized(): List<String> =
    map { it.trim() }.filter { it.isNotBlank() }.distinct()

internal fun buildTerms(
    accountId: Int,
    albumId: Int,
    tags: List<String>,
    authors: List<String>,
): List<FavoriteMetadataTermEntity> {
    val result = linkedMapOf<String, FavoriteMetadataTermEntity>()
    fun add(type: String, values: List<String>) {
        values.normalized().forEach { value ->
            val normalized = value.lowercase()
            result["$type:$normalized"] = FavoriteMetadataTermEntity(
                accountId = accountId,
                albumId = albumId,
                termType = type,
                value = value,
                normalizedValue = normalized,
            )
        }
    }
    add(FAVORITE_TERM_TAG, tags)
    add(FAVORITE_TERM_AUTHOR, authors)
    return result.values.toList()
}

internal fun Comic.toRemoteItem() = FavoriteRemoteItem(
    albumId = id,
    title = name,
    authors = authorList,
    description = description,
    tags = tagList,
)
