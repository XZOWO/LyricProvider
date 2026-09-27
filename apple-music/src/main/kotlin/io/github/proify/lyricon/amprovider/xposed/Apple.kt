/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import android.media.MediaMetadata
import android.media.session.MediaSession
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.VagueType
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XposedHelpers
import io.github.proify.extensions.android.ScreenStateMonitor
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge

object Apple : YukiBaseHooker() {
    private lateinit var application: Application
    private lateinit var classLoader: ClassLoader

    // 播放器状态
    private var isPlaying = false

    // 反射缓存
    private var exoMediaPlayerInstance: Any? = null
    private var getPositionMethod: Method? = null

    // 协程作用域
    private val coroutineScope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
    private var progressJob: Job? = null

    private var provider: LyriconProvider? = null

    override fun onHook() {
        onAppLifecycle {
            onCreate { onAppCreate() }
        }
    }

    private fun onAppCreate() {
        application = appContext ?: return
        classLoader = appClassLoader ?: return
        PreferencesMonitor.initialize(application)
        PreferencesMonitor.listener = object : PreferencesMonitor.Listener {
            override fun onTranslationSelectedChanged(selected: Boolean) {
                provider?.player?.setDisplayTranslation(selected)
            }
        }

        DiskSongManager.initialize(application)
        initScreenStateMonitor()
        initProvider()

        startHooks()
    }

    private fun initProvider() {
        val helper =
            LyriconFactory.createProvider(
                context = application,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = application.packageName,
                logo = ProviderLogo.fromBase64(Constants.ICON)
            )

        PlaybackManager.init(
            remotePlayer = helper.player,
            requester = LyricRequester(classLoader, application)
        )

        helper.player.setDisplayTranslation(PreferencesMonitor.isTranslationSelected())
        helper.register()
        this.provider = helper
    }

    private fun startHooks() {
        runCatching { hookMediaMetadataChange() }.onFailure { YLog.error("MediaSession metadata hook failed", it) }
        runCatching { hookPlaybackItems() }.onFailure { YLog.error("PlaybackItem hooks failed", it) }
        runCatching { hookLyricBuildMethod() }.onFailure { YLog.error("Native lyric hook failed", it) }
        runCatching { hookExoMediaPlayer() }.onFailure { YLog.error("Playback clock hook failed", it) }

//        XposedHelpers.findAndHookMethod(
//            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
//            classLoader,
//            "loadLyrics",
//            classLoader.loadClass("com.apple.android.music.model.PlaybackItem"),
//            object : XC_MethodHook() {
//                @Throws(Throwable::class)
//                override fun afterHookedMethod(param: MethodHookParam?) {
//                    val arg = param?.args?.get(0) ?: return
//                    ObjectUtils.print(arg)
//                }
//            })
    }

    // --- Hook 1: 歌曲切换监听 ---
    private fun hookMediaMetadataChange() {
        val method = MediaSession::class.java.getDeclaredMethod("setMetadata", MediaMetadata::class.java)

        method.hook {
            after {
                val mediaMetadata = args[0] as? MediaMetadata ?: return@after
                val metadata = MediaMetadataCache.putAndGet(mediaMetadata) ?: return@after

                // 委托给 Manager 处理
                PlaybackManager.onSongChanged(metadata.id)
            }
        }
        YLog.debug("MediaSession.setMetadata hook ready")
    }

    private fun hookPlaybackItems() {
        val itemClass = classLoader.loadClass("com.apple.android.music.model.PlaybackItem")
        val viewModelClass = classLoader.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
        viewModelClass.declaredMethods.filter {
            it.name == "loadLyrics" && it.parameterCount == 1 && itemClass.isAssignableFrom(it.parameterTypes[0])
        }.forEach { method -> method.hook { before { args[0]?.let(PlaybackManager::onPlaybackItem) } } }
        fun methods(type: Class<*>) = type.declaredMethods.filter {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 1 && itemClass.isAssignableFrom(it.returnType)
        }
        val candidates = listOf("com.apple.android.music.player.N", "com.apple.android.music.player.M")
            .mapNotNull { runCatching { classLoader.loadClass(it) }.getOrNull() }
            .flatMap(::methods).toMutableList()
        if (candidates.isEmpty()) {
            DexKitBridge.create(application.applicationInfo.sourceDir).use { bridge ->
                bridge.findClass {
                    searchPackages("com.apple.android.music.player")
                    matcher { usingStrings(AppleTrackIdentity.APPLE_MEDIA_ID, AppleTrackIdentity.APPLE_ENDPOINT) }
                }.forEach { data ->
                    runCatching { data.getInstance(classLoader) }.getOrNull()?.let { candidates.addAll(methods(it)) }
                }
            }
        }
        candidates.distinct().forEach { method -> method.hook { after { result?.let(PlaybackManager::onPlaybackItem) } } }
        YLog.debug("PlaybackItem mapper hooks ready: ${candidates.size}")
    }

    // --- Hook 2: 歌词构建监听 ---
    private fun hookLyricBuildMethod() {
        val m =
            classLoader.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
                .resolve()
                .firstMethod { name = "buildTimeRangeToLyricsMap" }
                .hook {
                    after {
                        YLog.debug("buildTimeRangeToLyricsMap:$args")
                        val arg: Any? = args[0]
                        if (arg == null) {
                            YLog.debug("args0 null")
                            return@after
                        }
                        val songNative = XposedHelpers.callMethod(arg, "get")
                        YLog.debug("songNative: $songNative")

                        // 委托给 Manager 处理
                        PlaybackManager.onLyricsBuilt(songNative)
                    }
                }
        YLog.debug("hookLyricBuildMethod Hooked: $m")
    }

    // --- Hook 3: 播放器控制  ---
    private fun hookExoMediaPlayer() {
        val exoPlayerClass =
            classLoader.loadClass("com.apple.android.music.playback.player.ExoMediaPlayer")

        exoPlayerClass.declaredConstructors.forEach { constructor ->
            constructor.hook {
                after {
                    exoMediaPlayerInstance = instanceOrNull
                    getPositionMethod = instanceClass?.getDeclaredMethod("getCurrentPosition")
                }
            }
        }

        exoPlayerClass.resolve().firstMethod {
            name = "seekToPosition"
            parameters(Long::class)
        }.hook {
            after {
                val position = args(0).cast<Long>() ?: 0L
                if (isPlaying) provider?.player?.seekTo(position)
            }
        }

        classLoader.loadClass("com.apple.android.music.playback.controller.LocalMediaPlayerController")
            .resolve()
            .method {
                name = "onPlaybackStateChanged"
                parameters(VagueType, Int::class, Int::class)
            }.first().hook {
                after {
                    when (PlaybackState.of(args[2] as Int)) {
                        PlaybackState.PLAYING -> startSyncAction()
                        else -> stopSyncAction()
                    }
                }
            }
    }

    // --- 进度同步逻辑 ---

    private fun startSyncAction() {
        if (isPlaying) return
        isPlaying = true
        provider?.player?.setPlaybackState(true)
        resumeCoroutineTask()
    }

    private fun stopSyncAction() {
        isPlaying = false
        provider?.player?.setPlaybackState(false)
        pauseCoroutineTask()
    }

    private fun resumeCoroutineTask() {
        if (progressJob?.isActive == true) return
        progressJob = coroutineScope.launch {
            while (isActive && isPlaying) {
                try {
                    val pos = getPositionMethod?.invoke(exoMediaPlayerInstance) as? Long ?: 0L
                    provider?.player?.setPosition(pos)
                } catch (_: Exception) {
                }
                delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
            }
        }
    }

    private fun pauseCoroutineTask() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun initScreenStateMonitor() {
        ScreenStateMonitor.initialize(application)
        ScreenStateMonitor.addListener(object : ScreenStateMonitor.ScreenStateListener {
            override fun onScreenOn() {
                if (isPlaying) resumeCoroutineTask()
            }

            override fun onScreenOff() {
                pauseCoroutineTask()
            }

            override fun onScreenUnlocked() {
                if (isPlaying && progressJob == null) resumeCoroutineTask()
            }
        })
    }

}
