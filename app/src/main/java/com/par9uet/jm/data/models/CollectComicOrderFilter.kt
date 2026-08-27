package com.par9uet.jm.data.models

enum class TagFilterLogic(val label: String) {
    AND("同时包含"),
    OR("包含任意"),
    NOT("不包含")
}
