package fr.paul.telecommande.box

/**
 * Pastille de profil sur l'accueil Freebox (Oqee) : logique pure, testable sans TV.
 *
 * Sur la Freebox, le bandeau du haut d'Oqee (« Pour vous · Chaînes · Replay · Guide TV · VOD · Ma liste »)
 * se termine par l'avatar Oqee puis l'horloge. La pastille se place juste après l'avatar ; flèche droite
 * depuis l'avatar la sélectionne, OK ouvre « Qui regarde ? ».
 */
object ProfileBadge {
    /** Accueil et appli TV de Free. Les autres box ne sont pas connues : pas de pastille ailleurs. */
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

    /** L'élément qui a le focus est-il le dernier du bandeau (l'avatar) ? Alors flèche droite va sur la pastille. */
    fun focusAtEnd(focusLeft: Int, focusTop: Int, focusRight: Int, focusBottom: Int, end: Node): Boolean {
        val cy = (focusTop + focusBottom) / 2
        return kotlin.math.abs(cy - end.centerY) <= maxOf(end.height, 24) && focusRight >= end.right - 8 && focusLeft < focusRight
    }
}
