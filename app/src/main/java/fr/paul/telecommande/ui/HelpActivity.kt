package fr.paul.telecommande.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import fr.paul.telecommande.R

/**
 * Onglet « Aide » : les questions qu'on se pose vraiment, rangées par thème, avec des réponses courtes en français courant.
 * On touche une question pour voir sa réponse (elle s'ouvre en douceur). En haut : revoir la visite guidée.
 */
class HelpActivity : AppCompatActivity() {

    private class Q(val q: String, val a: String)
    private class Topic(val title: String, val items: List<Q>)

    private val topics = listOf(
        Topic("La télécommande", listOf(
            Q("Comment ouvrir la télécommande ?",
                "Touchez le carré sombre au milieu de la barre du bas. La télécommande monte du bas de l'écran. Pour revenir à l'appli : la flèche vers le bas, en haut à gauche."),
            Q("« Retour TV », « Accueil TV » : c'est quoi ?",
                "Ce sont les touches de la TV, comme sur une vraie télécommande. « Retour TV » revient en arrière sur la TV, « Accueil TV » affiche l'accueil de la TV. Elles ne changent rien dans l'appli."),
            Q("Comment naviguer et valider ?",
                "Glissez le doigt sur le grand pavé pour vous déplacer sur la TV, touchez-le pour valider (OK). Restez appuyé à gauche ou à droite pour défiler en continu. Le volume est sur la barre de droite, les chaînes sur celle de gauche."),
            Q("Comment écrire sur la TV ?",
                "Quand une zone de texte s'ouvre sur la TV, le clavier du téléphone apparaît tout seul. Sinon : le bouton « ⋯ » en haut de la télécommande, puis « Clavier »."),
            Q("Comment allumer la TV ?",
                "Dans la télécommande, restez appuyé sur la touche marche (rouge, en haut à droite)."),
            Q("La TV ne répond pas",
                "Vérifiez que la TV est allumée et que le téléphone est sur le même Wi-Fi que la TV. L'appli réessaie toute seule.")
        )),
        Topic("Les enfants", listOf(
            Q("Comment ajouter un enfant ?",
                "Onglet Enfants, puis « Plus » tout en bas, et « Ajouter un enfant ». Indiquez son prénom et son âge : l'appli propose des réglages adaptés."),
            Q("Le contrat, c'est quoi ?",
                "Vos règles de TV pour un enfant, réglées en 5 phrases à compléter : temps en semaine et le week-end, heure d'extinction le soir, bonus. Vous pouvez ensuite tout ajuster d'un geste, le mettre en pause ou l'arrêter."),
            Q("Donner du temps en plus",
                "Onglet Enfants : « + 15 min ». Avec un contrat, vous pouvez aussi accorder un bonus avec son motif (« Devoirs faits »…). Pour une autre durée : « Plus »."),
            Q("Mon enfant demande du temps sur la TV",
                "Vous recevez une notification sur ce téléphone. Acceptez ou refusez d'un geste ; accepter demande le code parent."),
            Q("Qui regarde la TV ?",
                "À l'allumage, la TV demande « Qui regarde ? ». Chaque enfant a ainsi son propre temps. Le profil Parents n'a aucune limite et demande le code parent.")
        )),
        Topic("Sécurité et données", listOf(
            Q("À quoi sert le code parent ?",
                "Il protège les règles : un enfant ne peut ni les changer, ni s'accorder du temps. Vous le changez dans Réglages."),
            Q("Où vont mes données ?",
                "Nulle part : pas de compte, pas de serveur. Tout reste sur ce téléphone et sur vos TV, échangé en chiffré sur votre Wi-Fi.")
        ))
    )

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.systemBars(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(AuroraNav.wrap(this, ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(root) }, AuroraNav.Tab.HELP))
        Premium.darkStatusBar(this)
        render()
    }

    private fun render() {
        val c = this
        val h = Premium.hero(c)
        h.addView(Premium.heroText(c, "Aide", 28f, bold = true), Ui.lp(c, 8))
        h.addView(Premium.heroText(c, "Les réponses aux questions les plus courantes. Touchez une question pour voir la réponse.", 14.5f, soft = true), Ui.lp(c, 6))
        h.addView(Premium.heroButton(c, "Revoir la visite guidée", true) { Tour.show(c, force = true) }, LinearLayout.LayoutParams(-1, Ui.dp(c, 52)).apply { topMargin = Ui.dp(c, 18) })
        root.addView(h, LinearLayout.LayoutParams(-1, -2))
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(c, 16); setPadding(p, 0, p, 0) }
        root.addView(body, LinearLayout.LayoutParams(-1, -2))
        topics.forEach { t ->
            body.addView(Ui.title(c, t.title, 19f), Ui.lp(c, 24))
            val group = Ui.group(c)
            t.items.forEach { Ui.addRow(group, question(it)) }
            body.addView(group, Ui.lp(c, 10))
        }
        body.addView(AuroraNav.spacer(c))
    }

    /** Une question : touchée, sa réponse s'ouvre dessous (et se referme). */
    private fun question(item: Q): View {
        val c = this
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(c, 18), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 14))
            minimumHeight = Ui.dp(c, 58)
            val tv = android.util.TypedValue()
            c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            isClickable = true; isFocusable = true
        }
        val head = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(c, item.q, 15.5f).apply { typeface = Ui.medium }, LinearLayout.LayoutParams(0, -2, 1f))
        val chevron = TextView(c).apply {
            text = "›"; textSize = 22f; setTextColor(Ui.color(c, R.color.muted)); rotation = 90f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        head.addView(chevron, LinearLayout.LayoutParams(Ui.dp(c, 24), -2).apply { marginStart = Ui.dp(c, 8) })
        box.addView(head)
        val answer = Ui.text(c, item.a, 14.5f, muted = true).apply { setLineSpacing(0f, 1.25f); visibility = View.GONE }
        box.addView(answer, Ui.lp(c, 8))
        box.contentDescription = "${item.q}. Touchez pour voir la réponse"
        box.setOnClickListener {
            val open = answer.visibility != View.VISIBLE
            answer.visibility = if (open) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (open) 270f else 90f).setDuration(200).start()
            if (open && Motion.enabled(c)) {
                answer.alpha = 0f; answer.translationY = -Ui.dp(c, 6).toFloat()
                answer.animate().alpha(1f).translationY(0f).setDuration(240).start()
            }
            box.contentDescription = if (open) "${item.q}. ${item.a}" else "${item.q}. Touchez pour voir la réponse"
        }
        return box
    }
}
