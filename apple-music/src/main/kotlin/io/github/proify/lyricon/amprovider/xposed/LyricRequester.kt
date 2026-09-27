/*
 * Copyright 2026 Proify, Tomakino, Naivety
 * PlaybackItem request approach based on ColorOS-Live-Lyrics-Providers,
 * Copyright 2026 Andrea-TB. Licensed under the Apache License, Version 2.0.
 */
package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XposedHelpers

class LyricRequester(private val classLoader: ClassLoader, private val application: Application) {
    private val main = Handler(Looper.getMainLooper())
    private val items = object : LinkedHashMap<String, Any>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) = size > 32
    }
    private var viewModel: Any? = null
    private var generation = 0L
    private var pendingId: String? = null

    fun rememberPlaybackItem(item: Any) = onMain {
        val id = runCatching { XposedHelpers.callMethod(item, "getId")?.toString() }.getOrNull()
        if (!id.isNullOrBlank()) {
            items[id] = item
            if (pendingId == id) request(id, generation, 0)
        }
    }

    fun cancelPending() = onMain { generation++; pendingId = null }

    fun requestDownload(mediaId: String) = onMain {
        generation++
        pendingId = mediaId.takeIf { it.isNotBlank() }
        pendingId?.let { request(it, generation, 0) }
    }

    private fun request(id: String, token: Long, attempt: Int) {
        if (token != generation || pendingId != id) return
        val item = items[id]
        if (item == null) {
            if (attempt < 5) main.postDelayed({ request(id, token, attempt + 1) }, 500L)
            else YLog.debug("LyricRequester: Waiting for PlaybackItem $id")
            return
        }
        // Clear before invoking: loadLyrics is itself observed by the item hook.
        pendingId = null
        runCatching {
            val model = viewModel ?: classLoader
                .loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
                .getConstructor(Application::class.java).newInstance(application).also { viewModel = it }
            XposedHelpers.callMethod(model, "loadLyrics", item)
            YLog.debug("LyricRequester: Requested lyrics with PlaybackItem $id")
        }.onFailure { error ->
            YLog.error("LyricRequester: Failed to trigger download", error)
            if (attempt < 2) {
                pendingId = id
                main.postDelayed({ request(id, token, attempt + 1) }, 1000L)
            }
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }
}
