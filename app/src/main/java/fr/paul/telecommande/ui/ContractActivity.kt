package fr.paul.telecommande.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fr.paul.telecommande.R
import fr.paul.telecommande.core.BoxClient
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.ParentalStore
import fr.paul.telecommande.shared.Contract
import fr.paul.telecommande.shared.ContractWizard
import fr.paul.telecommande.shared.Profile
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Assistant du contrat parental d'un enfant :
 *  1. le choix entre le contrat accompagné et le mode simple (« une limite par jour, et c'est tout ») ;
 *  2. cinq phrases à trous, une par écran ;
 *  3. la table de mixage : les réponses placent les curseurs, le parent affine, la phrase « concrètement » suit en direct ;
 *  4. montrer ou non le contrat à l'enfant sur la TV ; puis le contrat part vers la TV.
 * Avec [EXTRA_START] = [START_MIXER], on ouvre directement la table de mixage d'un contrat existant (pour le modifier).
 */
class ContractActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ID = "device_id"
        const val EXTRA_PROFILE = "profile_id"
        const val EXTRA_START = "start"
        const val START_MIXER = "mixer"
    }

    private sealed class Step {
        object Intro : Step()
        class Question(val index: Int) : Step()
        object Mixer : Step()
        object Child : Step()
    }

    private lateinit var device: Device
    private lateinit var store: ParentalStore
    private lateinit var profile: Profile
    private lateinit var steps: List<Step>
    private var index = 0
    private val answers = ContractWizard.DEFAULT_ANSWERS.toMutableMap()
    private val touched = mutableSetOf<String>()
    private lateinit var mix: ContractWizard.Mix
    /** Curseurs placés par les réponses (pour « Revenir à la proposition »). */
    private lateinit var proposal: ContractWizard.Mix
    private var showChild = false
    private var editing = false
    private var saving = false

    private lateinit var heroBox: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var nav: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = intent.getStringExtra(EXTRA_ID)?.let { DeviceStore(this).get(it) } ?: run { finish(); return }
        store = ParentalStore(this)
        val rules = store.boxRules(device.id)
        profile = rules.profiles.firstOrNull { it.id == intent.getStringExtra(EXTRA_PROFILE) && !it.unlimited } ?: run { finish(); return }
        Ui.systemBars(this)

        val existing = profile.contract
        editing = existing != null && intent.getStringExtra(EXTRA_START) == START_MIXER
        existing?.answers?.let { answers.putAll(it); touched.addAll(it.keys) }
        showChild = existing?.showChild ?: false
        proposal = ContractWizard.propose(profile.age(), answers)
        mix = if (existing != null) ContractWizard.mixOf(rules, profile) else proposal.copy()
        steps = if (editing) listOf(Step.Mixer, Step.Child)
            else listOf(Step.Intro) + ContractWizard.QUESTIONS.indices.map { Step.Question(it) } + listOf(Step.Mixer, Step.Child)

        val c = this
        heroBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(c, 18); setPadding(p, Ui.dp(c, 4), p, p) }
        nav = LinearLayout(c).apply {
            setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 16))
            setBackgroundResource(R.drawable.bg_bar)
        }
        setContentView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_screen)
            addView(ScrollView(c).apply {
                isVerticalScrollBarEnabled = false
                post { Motion.attach(this, null) }
                addView(LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; addView(heroBox, LinearLayout.LayoutParams(-1, -2)); addView(content, LinearLayout.LayoutParams(-1, -2)) })
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(nav, LinearLayout.LayoutParams(-1, -2))
        })
        Premium.darkStatusBar(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { back() }
        })
        // Le contrat change les règles de la TV : code parent d'abord
        heroBox.visibility = View.INVISIBLE; content.visibility = View.INVISIBLE; nav.visibility = View.INVISIBLE
        Ui.createPinIfNeeded(this) {
            Ui.requirePin(this, "Code parent pour le contrat de ${profile.name}.", cancel = { finish() }) {
                heroBox.visibility = View.VISIBLE; content.visibility = View.VISIBLE; nav.visibility = View.VISIBLE
                render()
            }
        }
    }

    private fun back() { if (index > 0) { index--; render() } else finish() }

    private fun next() {
        val s = steps[index]
        // En quittant la dernière phrase, les curseurs prennent la proposition des réponses
        if (s is Step.Question && steps[index + 1] is Step.Mixer && !editing) {
            proposal = ContractWizard.propose(profile.age(), answers)
            mix = proposal.copy()
        }
        if (index < steps.lastIndex) { index++; render() } else save()
    }

    private fun name(text: String) = text.replace("{name}", profile.name)

    private fun render() {
        val c = this
        heroBox.removeAllViews(); content.removeAllViews(); nav.removeAllViews()
        val s = steps[index]
        val h = Premium.hero(c)
        val top = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Premium.heroRound(c, R.drawable.ic_back, "Retour") { back() }, LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)))
        val (n, total) = if (editing) index + 1 to steps.size else index to steps.size - 1
        top.addView(Premium.heroText(c, if (s is Step.Intro) "Contrat parental" else "Étape $n sur $total", 13.5f, soft = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(View(c), LinearLayout.LayoutParams(Ui.dp(c, 44), 1))
        h.addView(top)
        if (s !is Step.Intro) h.addView(Premium.heroProgress(c, n.toFloat() / total), LinearLayout.LayoutParams(-1, Ui.dp(c, 6)).apply { topMargin = Ui.dp(c, 14) })
        heroBox.addView(h, LinearLayout.LayoutParams(-1, -2))
        when (s) {
            is Step.Intro -> intro(h)
            is Step.Question -> question(h, s.index)
            is Step.Mixer -> mixer(h)
            is Step.Child -> child(h)
        }
        if (s !is Step.Intro) {
            // Le premier écran cache la barre du bas : on la remontre ensuite
            nav.visibility = View.VISIBLE
            nav.addView(Ui.button(c, "Retour") { back() }, LinearLayout.LayoutParams(0, Ui.dp(c, 52), 1f).apply { marginEnd = Ui.dp(c, 6) })
            nav.addView(Ui.button(c, if (index == steps.lastIndex) (if (saving) "Envoi…" else "Valider le contrat") else "Continuer", primary = true) { if (!saving) next() },
                LinearLayout.LayoutParams(0, Ui.dp(c, 52), 1.6f).apply { marginStart = Ui.dp(c, 6) })
        } else nav.visibility = View.GONE
    }

    // ---------- 1. Contrat accompagné ou mode simple ----------

    private fun intro(h: LinearLayout) {
        val c = this
        nav.visibility = View.GONE
        h.addView(Premium.heroText(c, "Le contrat de ${profile.name}", 26f, bold = true), Ui.lp(c, 18))
        h.addView(Premium.heroText(c, "Vous fixez les règles, à votre façon. La TV les applique, et l'appli vous aide à les faire évoluer.", 14.5f, soft = true), Ui.lp(c, 8))
        content.addView(choiceCard("Créer un contrat", "5 phrases à compléter, puis vous réglez tout d'un geste. Environ 2 minutes.", true) { next() }, Ui.lp(c, 18))
        content.addView(choiceCard("Rester en mode simple", "Une limite par jour, et c'est tout. Vous la changez quand vous voulez.", false) {
            val i = store.boxRules(device.id).profiles.indexOfFirst { it.id == profile.id }
            startActivity(Intent(c, BoxRulesActivity::class.java).putExtra(BoxRulesActivity.EXTRA_ID, device.id).putExtra(BoxRulesActivity.EXTRA_PROFILE, i))
            finish()
        }, Ui.lp(c, 10))
        content.addView(Ui.text(c, "Il n'y a pas de bonne ou de mauvaise façon : chaque famille a ses règles. Vous pourrez mettre le contrat en pause, le modifier ou l'arrêter à tout moment.", 13f, muted = true), Ui.lp(c, 16))
    }

    private fun choiceCard(title: String, sub: String, main: Boolean, action: () -> Unit) = LinearLayout(this).apply {
        val c = this@ContractActivity
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = Ui.dp(c, 22).toFloat(); setColor(Ui.color(c, R.color.surface))
            setStroke(Ui.dp(c, if (main) 2 else 1), Ui.color(c, if (main) R.color.accent else R.color.line))
        }
        setPadding(Ui.dp(c, 18), Ui.dp(c, 16), Ui.dp(c, 18), Ui.dp(c, 16))
        addView(Ui.text(c, title, 16.5f).apply { typeface = Ui.bold })
        addView(Ui.text(c, sub, 13.5f, muted = true), Ui.lp(c, 4))
        isClickable = true; isFocusable = true; contentDescription = "$title. $sub"
        setOnClickListener { action() }
    }

    // ---------- 2. Phrases à trous ----------

    private fun question(h: LinearLayout, qi: Int) {
        val c = this
        val q = ContractWizard.QUESTIONS[qi]
        h.addView(Premium.heroText(c, "Complétez la phrase", 24f, bold = true), Ui.lp(c, 16))
        h.addView(Premium.heroText(c, "Il n'y a pas de bonne réponse : c'est votre façon de faire.", 14f, soft = true), Ui.lp(c, 6))
        val card = Ui.card(c)
        val chosen = answers[q.id] ?: 0
        val sentence = SpannableStringBuilder(name(q.text).removeSuffix("…").trimEnd() + " ")
        val start = sentence.length
        sentence.append(q.options[chosen])
        sentence.setSpan(ForegroundColorSpan(Ui.color(c, R.color.accent)), start, sentence.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sentence.setSpan(UnderlineSpan(), start, sentence.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sentence.append(".")
        card.addView(TextView(c).apply {
            text = sentence; textSize = 19f; typeface = Ui.bold; setTextColor(Ui.color(c, R.color.ink)); setLineSpacing(0f, 1.2f)
        })
        val opts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        q.options.forEachIndexed { i, o ->
            opts.addView(chip(o, i == chosen) {
                answers[q.id] = i; touched += q.id; render()
                // Une réponse choisie : la phrase suivante arrive d'elle-même (le temps de voir le choix)
                val at = index
                content.postDelayed({ if (!isFinishing && index == at) next() }, 350)
            }, Ui.lp(c, 8))
        }
        card.addView(opts, Ui.lp(c, 10))
        content.addView(card, Ui.lp(c, 18))
        if (qi == ContractWizard.QUESTIONS.lastIndex)
            content.addView(Ui.text(c, "Vos réponses placent les curseurs à l'étape suivante. Vous pourrez tout ajuster.", 13f, muted = true), Ui.lp(c, 12))
    }

    private fun chip(label: String, on: Boolean, action: () -> Unit) = TextView(this).apply {
        val c = this@ContractActivity
        text = label; textSize = 15f; typeface = if (on) Ui.bold else Ui.medium
        setTextColor(Ui.color(c, if (on) R.color.indigo_ink else R.color.ink))
        background = GradientDrawable().apply {
            cornerRadius = Ui.dp(c, 16).toFloat()
            setColor(Ui.color(c, if (on) R.color.indigo_soft else R.color.tile))
            if (on) setStroke(Ui.dp(c, 2), Ui.color(c, R.color.accent))
        }
        minHeight = Ui.dp(c, 50); gravity = Gravity.CENTER_VERTICAL
        setPadding(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 10))
        contentDescription = label + if (on) ", choisi" else ""
        isClickable = true; isFocusable = true; setOnClickListener { action() }
    }

    // ---------- 3. Table de mixage ----------

    private class Fader(val label: String, val steps: Int, val get: (ContractWizard.Mix) -> Int, val set: (ContractWizard.Mix, Int) -> Unit, val text: (Int) -> String)

    private val faders = listOf(
        Fader("Semaine", 16, { it.weekMin / 15 }, { m, v -> m.weekMin = (v * 15).coerceAtLeast(15) }, { ContractWizard.fmtMin((it * 15).coerceAtLeast(15)) }),
        Fader("Week-end", 20, { it.weekendMin / 15 }, { m, v -> m.weekendMin = (v * 15).coerceAtLeast(15) }, { ContractWizard.fmtMin((it * 15).coerceAtLeast(15)) }),
        Fader("Extinction", (ContractWizard.BEDTIME_MAX - ContractWizard.BEDTIME_MIN) / 15,
            { (it.bedtime - ContractWizard.BEDTIME_MIN) / 15 }, { m, v -> m.bedtime = ContractWizard.BEDTIME_MIN + v * 15 }, { ContractWizard.clock(ContractWizard.BEDTIME_MIN + it * 15) }),
        Fader("Fin du temps", 2, { ContractWizard.GRACE_CHOICES.indexOf(it.graceMin).coerceAtLeast(0) }, { m, v -> m.graceMin = ContractWizard.GRACE_CHOICES[v] },
            { listOf("Pile", "+5 min", "Épisode")[it] }),
        Fader("Souplesse", 3, { it.softness }, { m, v -> m.softness = v }, { listOf("Aucune", "15 min", "30 min", "45 min")[it] }),
        Fader("Relecture", 2, { Contract.REVIEW_CHOICES.indexOf(it.reviewDays).coerceAtLeast(0) }, { m, v -> m.reviewDays = Contract.REVIEW_CHOICES[v] },
            { listOf("Jamais", "1 mois", "2 sem.")[it] })
    )

    private fun mixer(h: LinearLayout) {
        val c = this
        h.addView(Premium.heroText(c, if (editing) "Le contrat de ${profile.name}" else "Vos réglages, en un geste", 24f, bold = true), Ui.lp(c, 16))
        h.addView(Premium.heroText(c, if (editing) "Montez ou descendez les curseurs." else "D'après vos réponses, voici le contrat proposé. Montez ou descendez les curseurs.", 14f, soft = true), Ui.lp(c, 6))
        val sentence = Ui.text(c, ContractWizard.sentence(profile.name, mix), 16f).apply { typeface = Ui.medium; setLineSpacing(0f, 1.2f) }
        val desk = LinearLayout(c).apply {
            background = GradientDrawable().apply { cornerRadius = Ui.dp(c, 22).toFloat(); setColor(0x22FFFFFF); setStroke(Ui.dp(c, 1), 0x22FFFFFF) }
            setPadding(Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 12))
        }
        faders.forEach { f ->
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val valueTv = Premium.heroText(c, f.text(f.get(mix)), 12.5f, bold = true).apply {
                setTextColor(Ui.color(c, R.color.mint)); gravity = Gravity.CENTER; maxLines = 2; minLines = 2
            }
            col.addView(valueTv, LinearLayout.LayoutParams(-1, -2))
            col.addView(FaderView(c).apply {
                steps = f.steps; value = f.get(mix); label = f.label; valueText = f.text
                contentDescription = "${f.label} : ${f.text(f.get(mix))}"
                onChange = { v -> f.set(mix, v); valueTv.text = f.text(v); sentence.text = ContractWizard.sentence(profile.name, mix) }
            }, LinearLayout.LayoutParams(-1, Ui.dp(c, 210)).apply { topMargin = Ui.dp(c, 4) })
            col.addView(Premium.heroText(c, f.label, 11.5f, soft = true).apply { gravity = Gravity.CENTER; maxLines = 2; minLines = 2 }, Ui.lp(c, 4))
            desk.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        h.addView(desk, Ui.lp(c, 16))

        val card = Ui.card(c)
        card.addView(Ui.label(c, "Concrètement"))
        card.addView(sentence, Ui.lp(c, 6))
        content.addView(card, Ui.lp(c, 16))
        if (!editing) content.addView(Ui.button(c, "Revenir à la proposition") { mix = proposal.copy(); render() }, Ui.lp(c, 10))
    }

    // ---------- 4. L'enfant voit-il le contrat ? ----------

    private fun child(h: LinearLayout) {
        val c = this
        h.addView(Premium.heroText(c, "Montrer le contrat à ${profile.name} ?", 24f, bold = true), Ui.lp(c, 16))
        h.addView(Premium.heroText(c, "Sur la TV, ${profile.name} verra ses règles expliquées simplement et pourra appuyer sur « J'ai compris ».", 14f, soft = true), Ui.lp(c, 6))
        content.addView(choiceSelect("Oui, sur la TV", "Pour qu'il comprenne les règles. Il le verra la prochaine fois qu'il regarde.", showChild) { showChild = true; render() }, Ui.lp(c, 18))
        content.addView(choiceSelect("Non", "Le contrat reste sur votre téléphone.", !showChild) { showChild = false; render() }, Ui.lp(c, 10))
        val card = Ui.card(c)
        card.addView(Ui.label(c, "Votre contrat"))
        card.addView(Ui.text(c, ContractWizard.sentence(profile.name, mix), 15f), Ui.lp(c, 6))
        content.addView(card, Ui.lp(c, 16))
    }

    private fun choiceSelect(title: String, sub: String, on: Boolean, action: () -> Unit) = LinearLayout(this).apply {
        val c = this@ContractActivity
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = Ui.dp(c, 20).toFloat(); setColor(Ui.color(c, if (on) R.color.indigo_soft else R.color.surface))
            setStroke(Ui.dp(c, if (on) 2 else 1), Ui.color(c, if (on) R.color.accent else R.color.line))
        }
        setPadding(Ui.dp(c, 18), Ui.dp(c, 14), Ui.dp(c, 18), Ui.dp(c, 14))
        addView(Ui.text(c, title, 16f).apply { typeface = Ui.bold })
        addView(Ui.text(c, sub, 13.5f, muted = true), Ui.lp(c, 3))
        isClickable = true; isFocusable = true; contentDescription = "$title. $sub" + if (on) ", choisi" else ""
        setOnClickListener { action() }
    }

    // ---------- Enregistrement et envoi à la TV ----------

    private fun save() {
        saving = true; render()
        val now = System.currentTimeMillis()
        // On repart des règles enregistrées (elles ont pu changer pendant l'assistant)
        val rules = store.boxRules(device.id)
        val p = rules.profiles.firstOrNull { it.id == profile.id } ?: run { Ui.toast(this, "Ce profil n'existe plus"); finish(); return }
        ContractWizard.apply(p, mix, answers, showChild, now)
        rules.enabled = true
        rules.pinHash = store.boxPinHash
        rules.version = now
        store.saveBoxRules(device.id, rules)
        lifecycleScope.launch {
            val ok = runCatching { BoxClient.of(this@ContractActivity, device).call("set_rules", JSONObject().put("rules", rules.toJson())) }.isSuccess
            Ui.toast(this@ContractActivity, if (ok) "Contrat de ${p.name} envoyé à la TV" else "TV injoignable : le contrat partira dès qu'elle répondra")
            finish()
        }
    }
}
