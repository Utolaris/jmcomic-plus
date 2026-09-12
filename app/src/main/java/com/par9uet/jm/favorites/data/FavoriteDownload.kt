package com.par9uet.jm.favorites.data

import com.par9uet.jm.data.models.Comic

/** Favorites' own download port. Bound in DI to DownloadManager; no download imports here. */
fun interface FavoriteDownloader {
    fun downloadComics(comics: List<Comic>)
}
