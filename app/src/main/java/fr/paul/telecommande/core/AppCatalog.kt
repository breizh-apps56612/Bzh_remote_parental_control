package fr.paul.telecommande.core

/** Reconnaît l'appli au premier plan sur une box Android TV pour adapter l'assistant. */
object AppCatalog {
    enum class Kind { HOME, YOUTUBE, LIVE_TV, VIDEO, OTHER }

    data class Shortcut(val label: String, val letter: String, val link: String)

    /** Liens de lancement reconnus par Android TV (protocole remote v2). */
    val SHORTCUTS = listOf(
        Shortcut("YouTube", "Y", "https://www.youtube.com"),
        Shortcut("Netflix", "N", "https://www.netflix.com/title"),
        Shortcut("Prime Video", "P", "https://app.primevideo.com"),
        Shortcut("Disney+", "D", "https://www.disneyplus.com")
    )

    private val youtube = setOf(
        "com.google.android.youtube.tv", "com.google.android.youtube.tvkids",
        "com.google.android.youtube.tvunplugged", "com.amazon.firetv.youtube"
    )
    private val home = setOf(
        "", "net.oqee.launcher", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx", "com.google.android.leanbacklauncher",
        "com.android.tv.settings", "com.google.android.tungsten.setupwraith", "com.nvidia.tegrazone3"
    )
    private val names = mapOf(
        "com.google.android.youtube.tv" to "YouTube", "com.google.android.youtube.tvkids" to "YouTube Kids",
        "com.google.android.youtube.tvunplugged" to "YouTube TV", "com.netflix.ninja" to "Netflix",
        "com.amazon.amazonvideo.livingroom" to "Prime Video", "com.disney.disneyplus" to "Disney+",
        "com.canal.android.canal" to "myCANAL", "tv.molotov.app" to "Molotov", "com.plexapp.android" to "Plex",
        "org.xbmc.kodi" to "Kodi", "com.spotify.tv.android" to "Spotify", "com.apple.atve.androidtv.appletv" to "Apple TV",
        "com.google.android.tvlauncher" to "Accueil", "com.google.android.apps.tv.launcherx" to "Accueil Google TV",
        "com.google.android.tv" to "Chaînes TV", "com.android.tv" to "Chaînes TV", "fr.francetv.pluzz" to "france.tv",
        "fr.m6.m6replay.by" to "M6+", "fr.tf1.mytf1" to "TF1+", "com.google.android.videos" to "Google TV",
        "unknown" to "Appli non identifiée", "com.esaba.downloader" to "Downloader", "com.android.vending" to "Play Store",
        "fr.paul.telecommande.box" to "Contrat parental TV", "com.twitch.android.app" to "Twitch", "com.dazn" to "DAZN", "com.crunchyroll.crunchyroid" to "Crunchyroll"
    )
    private val video = setOf(
        "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus", "com.canal.android.canal",
        "tv.molotov.app", "com.plexapp.android", "org.xbmc.kodi", "com.apple.atve.androidtv.appletv", "fr.francetv.pluzz",
        "fr.m6.m6replay.by", "fr.tf1.mytf1", "com.google.android.videos", "com.twitch.android.app", "com.dazn",
        "com.crunchyroll.crunchyroid", "com.liskovsoft.smarttubetv", "com.liskovsoft.smarttubetv.beta"
    )

    /** Couleur de marque et lettre affichées dans la carte « En cours ». */
    fun brand(pkg: String): Pair<Int, String> = when (kind(pkg)) {
        Kind.YOUTUBE -> 0xFFFF0033.toInt() to "▶"
        Kind.LIVE_TV -> 0xFF2563EB.toInt() to "TV"
        Kind.HOME -> 0xFF4B5563.toInt() to "⌂"
        else -> when (pkg) {
            "com.netflix.ninja" -> 0xFFE50914.toInt() to "N"
            "com.amazon.amazonvideo.livingroom" -> 0xFF00A8E1.toInt() to "P"
            "com.disney.disneyplus" -> 0xFF113CCF.toInt() to "D+"
            "com.canal.android.canal" -> 0xFF111111.toInt() to "C+"
            "com.spotify.tv.android" -> 0xFF1DB954.toInt() to "♫"
            "com.twitch.android.app" -> 0xFF9146FF.toInt() to "T"
            "org.xbmc.kodi" -> 0xFF17B2E7.toInt() to "K"
            "com.plexapp.android" -> 0xFFE5A00D.toInt() to "P"
            else -> 0xFF6B7280.toInt() to name(pkg).take(1).uppercase()
        }
    }

    fun shortcutBrand(label: String): Int = when (label) {
        "YouTube" -> 0xFFFF0033.toInt(); "Netflix" -> 0xFFE50914.toInt()
        "Prime Video" -> 0xFF00A8E1.toInt(); "Disney+" -> 0xFF113CCF.toInt(); else -> 0xFF6B7280.toInt()
    }

    fun kind(pkg: String): Kind = when {
        pkg in youtube -> Kind.YOUTUBE
        pkg in home || pkg.contains("launcher") -> Kind.HOME
        pkg == "com.google.android.tv" || pkg == "com.android.tv" || pkg.contains("dtv") || pkg.contains("tvcenter") ||
            pkg.contains("livetv") || pkg.contains("tuner") -> Kind.LIVE_TV
        pkg in video -> Kind.VIDEO
        else -> Kind.OTHER
    }

    fun name(pkg: String): String = names[pkg]
        ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }.ifBlank { "Accueil" }
}
