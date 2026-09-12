package com.par9uet.jm.reader

import com.par9uet.jm.data.models.ComicPicImageState
import java.io.File

/** Reader owns conversion from list metadata into pipeline page contracts. */
fun ComicPicImageState.readerPageKey(): ReaderPageKey = ReaderPageKey(
    comicId = comicId,
    pageIndex = index,
    sourceIdentity = originSrc,
    scrambleId = __scrambleId,
    speed = __speed,
)

fun ComicPicImageState.toReaderPage(): ReaderPage = ReaderPage(
    key = readerPageKey(),
    originSrc = originSrc,
    comicId = comicId,
    pageIndex = index,
    scrambleId = __scrambleId,
    speed = __speed,
    localFile = File(originSrc).takeIf(File::isFile),
    fallbackFetcher = imageFetcher,
)
