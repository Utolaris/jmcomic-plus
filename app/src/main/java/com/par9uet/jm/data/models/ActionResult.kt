package com.par9uet.jm.data.models

/**
 * 一次写操作（发评论、签到等）的结果。
 *
 * `isSuccess` 的判定规则由数据层从服务端 `status` 字段收敛，调用方只读结论——
 * 早期版本把 `status` 抬到 ViewModel 里逐处比较，规则会随接口文案漂移。
 */
data class ActionResult(
    val isSuccess: Boolean,
    val message: String,
)
