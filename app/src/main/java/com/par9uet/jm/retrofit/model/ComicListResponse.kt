package com.par9uet.jm.retrofit.model

data class ComicListResponse(
    val search_query: String,
    val total: String,
    val redirect_aid: String?,
    val content: List<ContentListItem>
) {
    data class ContentListItem(
        val id: String,
        val author: String,
        val description: String?,
        val name: String,
        val image: String,
        val category: Category,
        val category_sub: Category,
        val is_favorite: Boolean,
        val update_at: Int,
        val tags: List<String>? = null,
    ) {
        data class Category(
            val id: String?,
            val title: String?
        )
    }
}
