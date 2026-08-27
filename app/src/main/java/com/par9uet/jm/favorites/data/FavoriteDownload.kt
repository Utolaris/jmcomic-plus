package com.par9uet.jm.favorites.data

import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.store.DownloadManager

interface FavoriteDownloader {
    fun downloadComics(comics: List<Comic>)
}

class DownloadManagerFavoriteDownloader(
    private val downloadManager: DownloadManager,
) : FavoriteDownloader {
    override fun downloadComics(comics: List<Comic>) {
        downloadManager.downloadComics(comics)
    }
}
