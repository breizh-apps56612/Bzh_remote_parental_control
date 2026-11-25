package fr.paul.telecommande.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fr.paul.telecommande.R
import fr.paul.telecommande.core.BoxClient
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.Usage
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONObject

/** « Ma maison » : toutes les TV équipées de l'appli Box d'un coup d'œil, avec les actions rapides. */
class HomeOverviewActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private val status = HashMap<String, JSONObject?>()
    private val errors = HashMap<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.systemBars(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(this@HomeOverviewActivity, 16); setPadding(p, p * 2, p, p * 2) }
        setContentView(ScrollView(this).apply { setBackgroundResource(R.drawable.bg_screen); addView(root) })
        render()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun boxes() = DeviceStore(this).all().filter { it.hasBoxApp }

    private fun refresh() = lifecycleScope.launch {
        boxes().map { d -> d to async { runCatching { BoxClient.of(this@HomeOverviewActivity, d).call("status") } } }.forEach { (d, job) ->
            job.await().onSuccess { status[d.id] = it; errors.remove(d.id) }.onFailure { status[d.id] = null; errors[d.id] = "Injoignable (TV éteinte ?)" }
        }
        render()
    }

    private fun render() {
        val c = this
        root.removeAllViews()
        root.addView(Ui.label(c, "Ma maison"))
        root.addView(Ui.title(c, "Toutes les TV"), Ui.lp(c, 4))
        val list = boxes()
        if (list.isEmpty()) { root.addView(Ui.text(c, "Aucune TV avec l'appli Box reliée pour l'instant.", 15f, muted = true), Ui.lp(c, 16)); return }
        var total = 0L
        list.forEach { d ->
            val st = status[d.id]
            total += st?.optLong("todayTotal") ?: 0L
            val card = Ui.card(c)
            val head = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(Ui.text(c, d.name, 18f).apply { typeface = Ui.bold }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(Ui.text(c, if (st == null) "●" else if (st.optBoolean("interactive")) "● allumée" else "● veille", 13f).apply {
                setTextColor(Ui.color(c, if (st?.optBoolean("interactive") == true) R.color.ok else R.color.muted))
            })
            card.addView(head)
            val who = st?.optJSONObject("profile")
            val line = when {
                st == null -> errors[d.id] ?: "Chargement…"
                !st.optBoolean("interactive") -> "TV en veille"
                else -> listOfNotNull(who?.let { "${it.optString("emoji")} ${it.optString("name")}" }, st.optString("label").ifBlank { "Accueil" }).joinToString(" · ")
            }
            card.addView(Ui.text(c, line, 14f, muted = true), Ui.lp(c, 4))
            if (st != null) card.addView(Ui.text(c, "Aujourd'hui : " + Usage.format(st.optLong("todayTotal")), 14f), Ui.lp(c, 8))
            val row = LinearLayout(c)
            row.addView(Ui.button(c, "📣") { BoxActions.message(c, d) }, LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f).apply { marginEnd = Ui.dp(c, 6) })
            row.addView(Ui.button(c, "🎉") { BoxActions.reward(c, d) }, LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f).apply { marginEnd = Ui.dp(c, 6) })
            row.addView(Ui.button(c, "Détails", primary = true) {
                startActivity(Intent(c, BoxDashboardActivity::class.java).putExtra(BoxDashboardActivity.EXTRA_ID, d.id))
            }, LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1.6f))
            card.addView(row, Ui.lp(c, 12))
            root.addView(card, Ui.lp(c, 12))
        }
        root.addView(Ui.text(c, "Toutes TV confondues aujourd'hui : " + Usage.format(total), 14f, muted = true), Ui.lp(c, 14))
    }
}
