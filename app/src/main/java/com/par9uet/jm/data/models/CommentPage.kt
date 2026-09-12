package com.par9uet.jm.data.models

/** 一页评论列表；[total] 为服务端声明的总条数。 */
data class CommentPage(
    val items: List<Comment>,
    val total: Int,
)
