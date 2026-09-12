package com.par9uet.jm.retrofit.model

class HomeSwiperComicListItemResponse(
    val id: String,
    val title: String,
    val slug: String,
    val type: String,
    val filter_val: String,
    val content: List<ListItem>
) {
    data class ListItem(
        val id: String,
        val author: String,
        val description: String?,
        val name: String,
        val image: String,
        val category: Category,
        val category_sub: Category,
        val is_favorite: Boolean,
        val update_at: Int,
    ) {
        data class Category(
            val id: String?,
            val title: String?
        )
    }
}
