/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.media.MediaMetadata
import kotlinx.serialization.Serializable

object MediaMetadataCache {
    private val metadataCache = object : LinkedHashMap<String, Metadata>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Metadata>?): Boolean =
            size > 100
    }

    @Synchronized
    fun putAndGet(metadata: MediaMetadata): Metadata? {
        val mediaId = AppleTrackIdentity.mediaId(
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            metadata.getString(AppleTrackIdentity.APPLE_MEDIA_ID)
        ) ?: return null

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val newMetadata = Metadata(mediaId, title, artist, duration)
        metadataCache[mediaId] = newMetadata
        return newMetadata
    }

    @Synchronized
    fun getMetadataById(mediaId: String): Metadata? = metadataCache[mediaId]

    @Serializable
    data class Metadata(
        val id: String,
        val title: String?,
        val artist: String?,
        val duration: Long
    )
}
