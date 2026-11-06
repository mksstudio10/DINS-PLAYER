package com.dinsplayer.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache

class DinsApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Media3Manager builds ExoPlayer on the first Activity/Service thread that calls
        // init(), and DataStore cannot be read synchronously there without risking an ANR.
        // Loading here runs before any Activity exists.
        com.dinsplayer.app.data.PlayerSettings.load(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(false)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.05)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }
}
