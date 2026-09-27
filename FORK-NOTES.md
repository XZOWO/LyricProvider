# ZHI AMProvider

Independent Lyricon Apple Music Provider fork. No Apple Music hooks are embedded in ZHIColor.

- Upstream: tomakino/LyricProvider, commit 781c8b97b8210f77b6a6078152058cb980c6b299.
- Adaptation reference: Andrea-lyz/ColorOS-Live-Lyrics-Providers, commit c86d2a363d4e490f2acda6ad9fc559ae48c31f5c, player-apple.
- Reference authors: Andrea-TB; upstream authors: Proify and Tomakino. Apache-2.0 notices are retained.
- Observe the player's platform MediaSession metadata, including Apple's own media-ID key.
- Cache real PlaybackItem instances and request lyrics on the main thread using a Provider-owned lyrics ViewModel.
- Publish Song objects through Lyricon RemotePlayer. Do not write ColorOS lyricInfo from this Provider.
- The fork package is com.naivety.lyricon.amprovider, so the upstream package can remain installed for rollback. Enable only one Apple Music Provider in LSPosed at a time.

Debug builds use the standard local Android debug certificate. Release signing uses the existing RELEASE_* environment variables.
