package com.par9uet.jm.retrofit.model

data class UserHistoryCommentListResponse(
    val list: List<ListItem> = emptyList(),
    val total: Int = 0,
) {
    data class ListItem(
        val AID: String? = null,
        val BID: String? = null,
        val CID: String? = null,
        val UID: String? = null,
        val username: String? = null,
        val nickname: String? = null,
        val likes: String? = null,
        val gender: String? = null,
        val update_at: String? = null,
        val addtime: String? = null,
        val parent_CID: String? = null,
        // 等级相关，这里不写，没啥意义
//        expinfo: {
//        level_name: string
//        level: number
//        nextLevelExp: number
//        exp: string
//        expPercent: number // 100
//        uid: string
//        badges: Array<{
//            content: string
//            name: string
//            id: string
//        }>
//    }
        val name: String? = null,
        val content: String? = null,
        val photo: String? = null,
        val spoiler: String? = null, // 是否剧透 1 和 0
        val replys: List<ListItem>? = null
    )
}
