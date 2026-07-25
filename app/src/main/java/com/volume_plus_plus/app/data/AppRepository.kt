package com.volume_plus_plus.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A user-facing app that can be a candidate for the audio-mixing toggle. */
data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val isSystem: Boolean,
    /** Lower sorts first: 0 = known media app, 1 = music, 2 = entertainment, 3 = everything else. */
    val priority: Int,
)

object AppRepository {

    /**
     * Every installed app, so any app that plays audio can be toggled — not just those with a
     * launcher icon. Requires QUERY_ALL_PACKAGES. Each entry is tagged [AppInfo.isSystem] so the
     * UI can hide system apps by default. Updated system apps (e.g. a preinstalled-then-updated
     * Chrome) are treated as user apps, since they are normally user-facing.
     */
    suspend fun loadInstalledApps(context: Context): List<AppInfo> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(0)
                .asSequence()
                .filter { it.packageName != context.packageName }
                .map { appInfo ->
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val icon = runCatching {
                        pm.getApplicationIcon(appInfo).toBitmap().asImageBitmap()
                    }.getOrNull()
                    AppInfo(
                        packageName = appInfo.packageName,
                        label = label,
                        icon = icon,
                        isSystem = appInfo.isSystemApp(),
                        priority = appInfo.audioPriority(),
                    )
                }
                // Known media apps first, then music/entertainment by declared category, then the
                // rest — each tier sorted alphabetically so the apps most likely to be mixed
                // (Spotify, YouTube, Netflix, …) float to the top.
                .sortedWith(compareBy({ it.priority }, { it.label.lowercase() }))
                .toList()
        }

    private fun ApplicationInfo.isSystemApp(): Boolean {
        val system = flags and ApplicationInfo.FLAG_SYSTEM != 0
        val updatedSystem = flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        // Treat updated system apps as user apps — they're usually things the user actually uses.
        return system && !updatedSystem
    }

    private fun ApplicationInfo.audioPriority(): Int {
        if (isKnownMediaApp(packageName)) return 0

        // ApplicationInfo.category exists from API 26; older devices report no category.
        val cat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) category
        else ApplicationInfo.CATEGORY_UNDEFINED
        return when (cat) {
            ApplicationInfo.CATEGORY_AUDIO -> 1                             // music
            ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_GAME,
            ApplicationInfo.CATEGORY_SOCIAL -> 2                            // entertainment
            else -> 3                                                       // other
        }
    }

    /**
     * True for popular streaming / media apps that should always sort to the very top. Matches an
     * exact package name from [MEDIA_PACKAGES] OR any brand token in [MEDIA_TOKENS], so repackaged
     * mods/clones (ReVanced, RVX, Extended, cracked variants) that keep the brand in their package
     * name are caught too.
     */
    private fun isKnownMediaApp(packageName: String): Boolean {
        val p = packageName.lowercase()
        if (p in MEDIA_PACKAGES) return true
        return MEDIA_TOKENS.any { p.contains(it) }
    }

    /** Exact package names of well-known music & video apps. */
    private val MEDIA_PACKAGES = setOf(
        // ── Music / audio ──
        "com.spotify.music",                        // Spotify
        "com.apple.android.music",                  // Apple Music
        "com.google.android.apps.youtube.music",    // YouTube Music
        "com.amazon.mp3",                           // Amazon Music
        "deezer.android.app",                       // Deezer
        "com.aspiro.tidal",                         // TIDAL
        "com.soundcloud.android",                   // SoundCloud
        "com.pandora.android",                      // Pandora
        "com.clearchannel.iheartradio.controller",  // iHeartRadio
        "tunein.player",                            // TuneIn Radio
        "com.bandcamp.android",                     // Bandcamp
        "com.anghami",                              // Anghami
        "com.qobuz.music",                          // Qobuz
        "com.maxmpz.audioplayer",                   // Poweramp
        "com.jio.media.jiobeats",                   // JioSaavn
        "com.bsbportal.music",                      // Wynk Music
        "com.gaana",                                // Gaana
        "com.napster.mp3",                          // Napster
        "com.sec.android.app.music",                // Samsung Music
        "com.google.android.music",                 // Google Play Music (legacy)
        "com.shazam.android",                       // Shazam
        "org.musicpd.mpd",                          // misc players
        "com.doubleTwist.androidPlayer",            // DoubleTwist
        "com.spotify.lite",                         // Spotify Lite
        // ── Video / streaming ──
        "com.google.android.youtube",               // YouTube
        "app.revanced.android.youtube",             // ReVanced YouTube (non-root variant)
        "app.rvx.android.youtube",                  // ReVanced Extended (RVX) YouTube
        "app.revanced.android.apps.youtube.music",  // ReVanced YouTube Music
        "com.netflix.mediaclient",                  // Netflix
        "com.hulu.plus",                            // Hulu
        "com.disney.disneyplus",                    // Disney+
        "com.amazon.avod.thirdpartyclient",         // Prime Video
        "com.wbd.stream",                           // Max
        "com.hbo.hbonow",                           // HBO Max (legacy)
        "com.peacocktv.peacockandroid",             // Peacock
        "com.cbs.app",                              // Paramount+
        "tv.twitch.android.app",                    // Twitch
        "com.crunchyroll.crunchyroid",              // Crunchyroll
        "com.plexapp.android",                      // Plex
        "org.videolan.vlc",                         // VLC
        "com.mxtech.videoplayer.ad",                // MX Player
        "com.mxtech.videoplayer.pro",               // MX Player Pro
        "com.google.android.videos",                // Google TV / Play Movies
        "com.tubitv",                               // Tubi
        "com.viki.android",                         // Viki
        "in.startv.hotstar",                        // Disney+ Hotstar
        "com.sonyliv",                              // SonyLIV
        "com.graymatrix.did",                       // ZEE5
        "com.jio.jioplay.tv",                       // JioTV
        "com.funimation.FunimationNow".lowercase(), // Funimation
        "com.dailymotion.dailymotion",              // Dailymotion
        "com.vimeo.android.videoapp",               // Vimeo
        "com.roku.web.trc",                         // The Roku Channel
        "air.com.vudu.air.DownloaderTablet",        // Vudu / Fandango at Home
        "com.bbc.iplayer.android",                  // BBC iPlayer
        "com.discovery.discoveryplus.mobile",       // Discovery+
        "com.espn.score_center",                    // ESPN
        "com.pluto.android",                        // Pluto TV
    )

    /**
     * Brand tokens matched anywhere in the package name — the robust catch-all for modded/cloned
     * builds (e.g. app.revanced.android.youtube, app.rvx.android.youtube, com.spotify.music.mod).
     * Kept brand-specific to avoid false positives (no generic words like "music" or "video").
     */
    private val MEDIA_TOKENS = listOf(
        // NOTE: deliberately NOT matching "revanced"/"rvx" — that would also pin ReVanced Manager
        // (app.revanced.manager) and ReVanced GmsCore/microG (app.revanced.android.gms), which
        // aren't media apps. Patched YouTube variants keep "youtube" in their package
        // (app.revanced.android.youtube, app.rvx.android.youtube, …) so they're caught below anyway.
        "youtube",                     // YouTube + YouTube Music + ReVanced/RVX YouTube builds
        "spotify",
        "netflix",
        "deezer",
        "soundcloud",
        "pandora",
        "tidal",
        "audiomack",
        "iheart",
        "tunein",
        "bandcamp",
        "anghami",
        "qobuz",
        "poweramp",
        "crunchyroll",
        "funimation",
        "twitch",
        "plexapp",
        "videolan",                    // VLC forks
        "disneyplus",
        "peacock",
        "hotstar",
        "sonyliv",
    )
}
