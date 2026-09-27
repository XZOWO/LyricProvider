/*
 * Copyright 2026 Naivety
 * Based on AppleTrackIdentity in ColorOS-Live-Lyrics-Providers, Copyright 2026 Andrea-TB.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.proify.lyricon.amprovider.xposed

internal object AppleTrackIdentity {
    const val APPLE_MEDIA_ID = "com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID"
    const val APPLE_ENDPOINT = "com.apple.android.music.playback.metadata.METADATA_KEY_PLAYBACK_ENDPOINT_TYPE"

    fun mediaId(standard: String?, apple: String?): String? =
        sequenceOf(standard, apple).firstOrNull { !it.isNullOrBlank() }?.trim()

    fun shouldPublish(currentId: String?, lyricId: String?, unchanged: Boolean): Boolean =
        !currentId.isNullOrBlank() && currentId == lyricId && !unchanged
}
