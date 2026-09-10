package com.par9uet.jm.cache

enum class CacheArea(val id: String, val title: String, val description: String) {
    COMMON("common", "图片缓存", "Coil 图片加载缓存，清理后图片需重新下载"),
    DOWNLOAD("download", "漫画缓存", "已下载的漫画图片，清理后需重新下载"),
    DECODE("pic_decode", "解码缓存", "图片解密临时文件，可安全清理"),
    READER("reader_pages", "阅读器图片缓存", "阅读页的原图与解码缓存，清理后会重新加载"),
    PDF("pdf", "PDF 导出缓存", "PDF 导出临时文件，可安全清理"),
    ALL("total", "全部应用缓存", "包含以上所有缓存和其他临时文件"),
}

data class CacheSize(val area: CacheArea, val sizeBytes: Long)

