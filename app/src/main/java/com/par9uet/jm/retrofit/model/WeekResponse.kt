package com.par9uet.jm.retrofit.model

data class WeekResponse(
    val categories: List<CategoryItem> = listOf(),
    val type: List<TypeItem>
) {
    data class CategoryItem(
        val id: String,
        val time: String,
        val title: String,
    )

    data class TypeItem(
        val id: String,
        val title: String
    )
}
