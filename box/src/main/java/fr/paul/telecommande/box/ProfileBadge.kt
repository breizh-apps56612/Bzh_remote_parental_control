package fr.paul.telecommande.box

/**
 * Pastille de profil sur l'accueil Freebox (Oqee) : logique pure, testable sans TV.
 *
 * Sur la Freebox, le bandeau du haut d'Oqee (« Pour vous · Chaînes · Replay · Guide TV · VOD · Ma liste »)
 * se termine par l'avatar Oqee puis l'horloge. La pastille se place juste après l'avatar ; flèche droite
 * depuis l'avatar la sélectionne, OK ouvre « Qui regarde ? ».
 *
 * Sur l'écran d'accueil de n'importe quelle box (menu des applications Freebox, Google TV, Android TV…),
 * la pastille se place à gauche des icônes du coin haut droit (notifications, réglages, horloge).
 * L'enfant la sélectionne en montant tout en haut puis à droite (le focus ne bouge plus), et OK ouvre « Qui regarde ? ».
 */
object ProfileBadge {
    /** Accueil et appli TV de Free : pastille dans le bandeau d'Oqee. */
    val OQEE = setOf("net.oqee.launcher", "net.oqee.androidtv")
    private val HEADER = setOf("pour vous", "chaînes", "chaines", "replay", "guide tv", "vod", "ma liste")

    fun isFreebox(manufacturer: String?, model: String?) =
        manufacturer.orEmpty().contains("freebox", true) || model.orEmpty().contains("freebox", true)

    data class Node(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val focusable: Boolean) {
        val centerY get() = (top + bottom) / 2
        val height get() = bottom - top
    }

    /**
     * Le bandeau d'Oqee est-il à l'écran ? Si oui, renvoie son dernier élément à droite (l'avatar),
     * sinon null (lecture en plein écran, autre page) : pas de pastille par-dessus une vidéo.
     */
    fun headerEnd(nodes: List<Node>, screenH: Int): Node? {
        val tabs = nodes.filter { it.text.trim().lowercase() in HEADER && it.bottom > 0 && it.top < screenH * 0.22 }
        if (tabs.map { it.text.trim().lowercase() }.distinct().size < 2) return null
        val rowY = tabs.map { it.centerY }.sorted()[tabs.size / 2]
        val tolerance = maxOf(tabs.maxOf { it.height }, screenH / 25)
        val row = nodes.filter { kotlin.math.abs(it.centerY - rowY) <= tolerance && it.right > it.left && it.top < screenH * 0.25 }
        return row.filter { it.focusable }.maxByOrNull { it.right } ?: tabs.maxByOrNull { it.right }
    }

    /** Centre voulu de la pastille (pixels écran). */
    data class Spot(val x: Int, val y: Int)

    /**
     * Accueil d'une box : centre de la pastille, à gauche des icônes du coin haut droit, ou dans le coin s'il n'y en a pas.
     * null si l'écran ne ressemble pas à un menu (moins de 4 éléments sélectionnables : vidéo plein écran, veille…).
     */
    fun homeSpot(nodes: List<Node>, screenW: Int, screenH: Int, size: Int, gap: Int): Spot? {
        if (nodes.count { it.focusable && it.right > it.left && it.bottom > it.top } < 4) return null
        val corner = nodes.filter { it.right > it.left && it.bottom > it.top && it.top >= 0 &&
            it.top < screenH * 0.16 && it.bottom < screenH * 0.24 && it.height < screenH * 0.12 &&
            (it.left + it.right) / 2 > screenW * 0.66 && it.right - it.left < screenW * 0.25 }
        if (corner.isEmpty()) return Spot(screenW - gap - size / 2 - screenW / 30, gap + size / 2 + screenH / 30)
        val cy = corner.map { it.centerY }.sorted()[corner.size / 2]
        val row = corner.filter { kotlin.math.abs(it.centerY - cy) <= maxOf(it.height, screenH / 20) }
        val left = row.minOf { it.left }
        return Spot((left - gap - size / 2).coerceAtLeast(size), cy.coerceAtLeast(size / 2 + 4))
    }

    /** Flèche haut ou droite sans effet (focus immobile) en haut de l'écran : l'enfant cherche la pastille. */
    fun reachesBadge(before: IntArray?, after: IntArray?, screenH: Int): Boolean =
        before != null && after != null && before.contentEquals(after) && before[1] < screenH * 0.35

    /** L'élément qui a le focus est-il le dernier du bandeau (l'avatar) ? Alors flèche droite va sur la pastille. */
    fun focusAtEnd(focusLeft: Int, focusTop: Int, focusRight: Int, focusBottom: Int, end: Node): Boolean {
        val cy = (focusTop + focusBottom) / 2
        return kotlin.math.abs(cy - end.centerY) <= maxOf(end.height, 24) && focusRight >= end.right - 8 && focusLeft < focusRight
    }
}
