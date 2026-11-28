package fr.paul.telecommande.box

import fr.paul.telecommande.box.ProfileBadge.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pastille de profil sur l'accueil Freebox (Oqee) : repérage du bandeau, de son avatar et du focus. */
class ProfileBadgeTest {
    private val h = 1080

    /** Bandeau d'Oqee tel que sur la photo : onglets, loupe, avatar, puis l'horloge (non sélectionnable). */
    private fun oqeeHeader() = listOf(
        Node("Pour vous", 60, 50, 250, 90, true), Node("Chaînes", 300, 52, 450, 92, true),
        Node("Replay", 500, 54, 640, 94, true), Node("Guide TV", 690, 56, 870, 96, true),
        Node("VOD", 920, 58, 1000, 98, true), Node("Ma liste", 1050, 60, 1200, 100, true),
        Node("", 1240, 62, 1290, 102, true),               // loupe
        Node("Profil", 1340, 40, 1440, 130, true),         // avatar Oqee
        Node("12:47", 1640, 66, 1730, 104, false),         // horloge
        Node("Alexis", 640, 200, 800, 330, true))          // profils Oqee, plus bas

    @Test fun findsTheAvatarAtTheEndOfTheHeader() {
        val end = ProfileBadge.headerEnd(oqeeHeader(), h)!!
        assertEquals("Profil", end.text)
        assertEquals(1440, end.right)
    }

    @Test fun noBadgeWithoutTheHeader() {
        // Lecture en plein écran : pas d'onglets à l'écran
        assertNull(ProfileBadge.headerEnd(listOf(Node("TF1", 100, 900, 300, 960, true), Node("12:47", 1640, 66, 1730, 104, false)), h))
        // Un seul mot qui ressemble à un onglet ne suffit pas
        assertNull(ProfileBadge.headerEnd(listOf(Node("Replay", 100, 50, 300, 90, true)), h))
        // Onglets trop bas (contenu d'une page, pas le bandeau)
        assertNull(ProfileBadge.headerEnd(listOf(Node("Replay", 100, 600, 300, 640, true), Node("VOD", 400, 600, 500, 640, true)), h))
    }

    @Test fun rightArrowOnlyFromTheAvatar() {
        val end = ProfileBadge.headerEnd(oqeeHeader(), h)!!
        assertTrue(ProfileBadge.focusAtEnd(1340, 40, 1440, 130, end))     // focus sur l'avatar
        assertFalse(ProfileBadge.focusAtEnd(1050, 60, 1200, 100, end))    // focus sur « Ma liste »
        assertFalse(ProfileBadge.focusAtEnd(1340, 500, 1440, 600, end))   // même colonne, plus bas dans la page
    }

    @Test fun onlyOnFreebox() {
        assertTrue(ProfileBadge.isFreebox("Freebox", "Freebox Player POP"))
        assertFalse(ProfileBadge.isFreebox("Google", "Chromecast"))
        assertFalse(ProfileBadge.isFreebox("NVIDIA", "SHIELD Android TV"))
    }

    // ---------- Accueil de toutes les box ----------

    /** Menu des applications de la Freebox (photo du 28/11) : recherche, carrousel, notifications, réglages, horloge, rangées d'applis. */
    private fun freeboxMenu() = listOf(
        Node("Essayez la requête de recherche", 100, 40, 700, 90, true),
        Node("(re)Découvrir la saga", 420, 300, 640, 360, true),
        Node("", 700, 20, 1860, 420, false),                 // grande image du carrousel (trop haute pour le coin)
        Node("1", 1580, 40, 1620, 80, true),                  // notifications
        Node("Paramètres", 1660, 40, 1710, 90, true),         // réglages
        Node("01:55", 1740, 45, 1860, 90, false),             // horloge
        Node("free TV", 120, 450, 280, 540, true), Node("Netflix", 300, 620, 450, 700, true),
        Node("YouTube", 900, 620, 1050, 700, true))

    @Test fun homeBadgeGoesLeftOfTheTopRightIcons() {
        val s = ProfileBadge.homeSpot(freeboxMenu(), 1920, h, 40, 26)!!
        assertEquals(1580 - 26 - 20, s.x)                     // juste à gauche de la bulle de notifications
        assertTrue(s.y in 40..90)                             // sur la ligne de l'horloge
    }

    @Test fun homeBadgeInTheCornerWhenNothingIsThere() {
        val tiles = (0 until 6).map { Node("Appli $it", 100 + it * 200, 500, 280 + it * 200, 600, true) }
        val s = ProfileBadge.homeSpot(tiles, 1920, h, 40, 26)!!
        assertTrue(s.x > 1700 && s.y < 120)
    }

    @Test fun noHomeBadgeOverAFullScreenVideo() {
        assertNull(ProfileBadge.homeSpot(listOf(Node("", 0, 0, 1920, 1080, false), Node("Pause", 900, 900, 1000, 980, true)), 1920, h, 40, 26))
    }

    @Test fun upOrRightThatDoesNotMoveAtTheTopReachesTheBadge() {
        assertTrue(ProfileBadge.reachesBadge(intArrayOf(1660, 40, 1710, 90), intArrayOf(1660, 40, 1710, 90), h))
        assertFalse(ProfileBadge.reachesBadge(intArrayOf(1660, 40, 1710, 90), intArrayOf(1580, 40, 1620, 80), h))  // le focus a bougé
        assertFalse(ProfileBadge.reachesBadge(intArrayOf(300, 620, 450, 700), intArrayOf(300, 620, 450, 700), h))  // bas de l'écran
        assertFalse(ProfileBadge.reachesBadge(null, null, h))
    }
}
