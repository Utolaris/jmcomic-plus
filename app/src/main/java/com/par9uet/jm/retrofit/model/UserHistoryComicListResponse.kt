package com.par9uet.jm.retrofit.model

data class UserHistoryComicListResponse (
    val list: List<ListItem>,
) {
    data class ListItem(
        val id: String,
        val author: String,
        val description: String?,
        val name: String,
        val image: String,
        val category: Category,
        val category_sub: Category,
    ) {
        data class Category(
            val id: String?,
            val title: String?
        )
    }
}
