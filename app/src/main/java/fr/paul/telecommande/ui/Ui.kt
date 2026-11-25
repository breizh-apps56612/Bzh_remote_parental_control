package fr.paul.telecommande.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import fr.paul.telecommande.R
import fr.paul.telecommande.core.ParentalStore

/** Petits constructeurs de vues partagés par les écrans construits en code. */
object Ui {
    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
    fun color(c: Context, id: Int) = ContextCompat.getColor(c, id)

    /** Polices : titres serrés et gras, textes courants lisibles. */
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun title(c: Context, text: String, size: Float = 30f) = TextView(c).apply {
        this.text = text; textSize = size; setTextColor(color(c, R.color.ink)); typeface = bold
        letterSpacing = -0.02f; includeFontPadding = false
    }

    fun text(c: Context, text: String, size: Float = 14f, muted: Boolean = false) = TextView(c).apply {
        this.text = text; textSize = size; setTextColor(color(c, if (muted) R.color.muted else R.color.ink))
        setLineSpacing(0f, 1.12f)
    }

    /** Petit intitulé de section en capitales espacées (« AUJOURD'HUI »). */
    fun label(c: Context, text: String) = TextView(c).apply {
        this.text = text.uppercase(); textSize = 11.5f; setTextColor(color(c, R.color.muted)); typeface = medium
        letterSpacing = 0.12f
    }

    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        val p = dp(c, 18); setPadding(p, p, p, p)
    }

    fun lp(c: Context, topDp: Int = 12) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(c, topDp) }

    /** Bouton : principal = pilule dégradée violette ; secondaire = pilule cerclée. */
    fun button(c: Context, label: String, primary: Boolean = false, action: () -> Unit) = TextView(c).apply {
        text = label; gravity = Gravity.CENTER; textSize = 15f; typeface = medium; letterSpacing = 0.01f
        setTextColor(if (primary) Color.WHITE else color(c, R.color.ink))
        setBackgroundResource(if (primary) R.drawable.bg_chip_on else R.drawable.bg_chip)
        minHeight = dp(c, 50); setPadding(dp(c, 18), 0, dp(c, 18), 0)
        if (primary && android.os.Build.VERSION.SDK_INT >= 21) { elevation = dp(c, 3).toFloat(); stateListAnimator = null }
        isClickable = true; isFocusable = true
        setOnClickListener { action() }
    }

    // ---------- Briques « liste de réglages » (tableau de bord) ----------

    /** Carte de liste : lignes séparées par un trait fin, sans marge intérieure verticale. */
    fun group(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(0, dp(c, 4), 0, dp(c, 4))
    }

    fun divider(c: Context) = View(c).apply { setBackgroundColor(color(c, R.color.line)) }

    /** Ajoute un trait de séparation (aligné sur le texte) si la carte contient déjà une ligne. */
    fun addRow(g: LinearLayout, row: View) {
        val c = g.context
        if (g.childCount > 0) g.addView(divider(c), LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(c, 66) })
        g.addView(row)
    }

    /** Pastille ronde teintée contenant un emoji (icône de ligne). */
    fun badge(c: Context, emoji: String, danger: Boolean = false) = TextView(c).apply {
        text = emoji; textSize = 18f; gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (danger) (color(c, R.color.danger) and 0x00FFFFFF) or 0x22000000 else color(c, R.color.accent_soft))
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Ligne cliquable : icône, titre, sous-titre facultatif, valeur à droite et chevron. */
    fun row(c: Context, emoji: String, title: String, sub: String? = null, value: String? = null,
            danger: Boolean = false, icon: android.graphics.Bitmap? = null, action: (() -> Unit)? = null) = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(c, 62)
        setPadding(dp(c, 16), dp(c, 10), dp(c, 14), dp(c, 10))
        if (icon != null) addView(android.widget.ImageView(c).apply {
            setImageBitmap(icon); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(c, 10).toFloat(); setColor(color(c, R.color.surface2)) }
        }, LinearLayout.LayoutParams(dp(c, 38), dp(c, 38)).apply { marginEnd = dp(c, 12) })
        else addView(badge(c, emoji, danger), LinearLayout.LayoutParams(dp(c, 38), dp(c, 38)).apply { marginEnd = dp(c, 12) })
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(c, title, 15.5f).apply {
            typeface = medium; maxLines = 2
            if (danger) setTextColor(color(c, R.color.danger))
        })
        if (!sub.isNullOrBlank()) texts.addView(text(c, sub, 12.5f, muted = true).apply { maxLines = 2 })
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (!value.isNullOrBlank()) addView(text(c, value, 14f, muted = true).apply { maxLines = 1; setPadding(dp(c, 8), 0, 0, 0) })
        if (action != null) {
            addView(text(c, "›", 22f, muted = true).apply { setPadding(dp(c, 8), 0, 0, dp(c, 2)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            isClickable = true; isFocusable = true
            val tv = android.util.TypedValue()
            c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { action() }
        }
    }

    /** Ligne avec interrupteur : toute la ligne bascule (cible large, plus facile au doigt). */
    fun switchRow(c: Context, emoji: String, title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val sw = androidx.appcompat.widget.SwitchCompat(c).apply {
            isChecked = checked; isClickable = false; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return row(c, emoji, title, sub).apply {
            addView(sw, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 8) })
            isClickable = true; isFocusable = true
            val tv = android.util.TypedValue()
            c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            contentDescription = title + if (checked) ", activé" else ", désactivé"
            setOnClickListener { sw.isChecked = !sw.isChecked; onChange(sw.isChecked) }
        }
    }

    /** Tuile d'action : emoji + libellé court sur une ligne (jamais coupé en deux). */
    fun tile(c: Context, emoji: String, label: String, primary: Boolean = false, action: () -> Unit) = LinearLayout(c).apply {
        gravity = Gravity.CENTER
        setBackgroundResource(if (primary) R.drawable.bg_tile_on else R.drawable.bg_tile)
        setPadding(dp(c, 10), 0, dp(c, 10), 0)
        addView(TextView(c).apply { text = emoji; textSize = 19f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(c, 8) })
        addView(TextView(c).apply {
            text = label; textSize = 14.5f; typeface = medium; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (primary) Color.WHITE else color(c, R.color.ink))
        })
        contentDescription = label
        isClickable = true; isFocusable = true
        setOnClickListener { action() }
    }

    /** Pastille d'état : point coloré + texte (« En direct · Netflix »). */
    fun pill(c: Context, label: String, dotColor: Int) = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { cornerRadius = dp(c, 99).toFloat(); setColor(color(c, R.color.surface)); setStroke(1, color(c, R.color.line)) }
        setPadding(dp(c, 12), dp(c, 6), dp(c, 14), dp(c, 6))
        addView(View(c).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) } },
            LinearLayout.LayoutParams(dp(c, 8), dp(c, 8)).apply { marginEnd = dp(c, 8) })
        addView(text(c, label, 13f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    }

    /** Sélecteur segmenté (onglets) : un seul choix actif, remplissage dégradé. */
    fun tabs(c: Context, options: List<String>, selected: Int, onSelect: (Int) -> Unit) = LinearLayout(c).apply {
        background = GradientDrawable().apply { cornerRadius = dp(c, 99).toFloat(); setColor(color(c, R.color.surface2)) }
        setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4))
        options.forEachIndexed { i, o ->
            val on = i == selected
            addView(TextView(c).apply {
                text = o; gravity = Gravity.CENTER; textSize = 13.5f; typeface = medium; maxLines = 1
                setTextColor(if (on) Color.WHITE else color(c, R.color.muted))
                if (on) setBackgroundResource(R.drawable.bg_chip_on)
                isClickable = true; isFocusable = true
                contentDescription = o + if (on) ", sélectionné" else ""
                setOnClickListener { if (!on) onSelect(i) }
            }, LinearLayout.LayoutParams(0, dp(c, 38), 1f))
        }
    }

    /** Barre de progression en pilule, remplissage dégradé (rouge si dépassement). */
    fun progress(c: Context, ratio: Float, over: Boolean) = LinearLayout(c).apply {
        val r = ratio.coerceIn(0f, 1f)
        background = GradientDrawable().apply { setColor(color(c, R.color.track)); cornerRadius = dp(c, 99).toFloat() }
        addView(View(c).apply {
            background = if (over) GradientDrawable().apply { setColor(color(c, R.color.danger)); cornerRadius = dp(c, 99).toFloat() }
            else GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(color(c, R.color.accent), color(c, R.color.accent2)))
                .apply { cornerRadius = dp(c, 99).toFloat() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, r))
        addView(View(c), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - r))
    }

    /** Icônes de barre d'état sombres en thème clair, claires en thème sombre. */
    fun systemBars(a: Activity) {
        val night = (a.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        a.window.statusBarColor = color(a, R.color.bg)
        a.window.navigationBarColor = color(a, if (a is RemoteActivity) R.color.surface else R.color.bg)
        androidx.core.view.WindowCompat.getInsetsController(a.window, a.window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    /** Choix d'une heure : molettes + boutons « Annuler / Valider » toujours visibles (même style que les autres fenêtres). */
    fun pickTime(c: Context, title: String, minutes: Int, done: (Int) -> Unit) {
        val tp = android.view.LayoutInflater.from(c).inflate(fr.paul.telecommande.R.layout.time_spinner, null) as android.widget.TimePicker
        tp.setIs24HourView(true)
        @Suppress("DEPRECATION") run { tp.currentHour = minutes / 60; tp.currentMinute = minutes % 60 }
        val frame = android.widget.FrameLayout(c).apply { setPadding(0, dp(c, 8), 0, 0); addView(tp, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER_HORIZONTAL)) }
        androidx.appcompat.app.AlertDialog.Builder(c).setTitle(title).setView(frame)
            .setPositiveButton("Valider") { _, _ -> tp.clearFocus(); @Suppress("DEPRECATION") done(tp.currentHour * 60 + tp.currentMinute) }
            .setNegativeButton("Annuler", null).show()
    }

    /** Le code parent n'est pas un mot de passe à enregistrer (Proton Pass, Google…). */
    fun noAutofill(v: View) { if (android.os.Build.VERSION.SDK_INT >= 26) v.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }

    fun toast(c: Context, msg: String) = Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()

    /** Demande le code parent (s'il existe) avant d'exécuter [ok]. */
    /** Crée le code parent s'il n'existe pas encore (indispensable pour protéger la box). */
    fun createPinIfNeeded(a: Activity, ok: () -> Unit) {
        if (ParentalStore(a).hasPin) ok() else createPin(a) { created -> if (created) ok() }
    }

    fun canUseBiometric(c: Context) = ParentAuth.available(c)

    /**
     * Demande le code parent (s'il existe) avant d'exécuter [ok].
     * Par défaut : l'empreinte (ou le visage), jamais le code de verrouillage du téléphone.
     * Le code parent reste possible : bouton « Taper le code parent », ou en fermant la fenêtre de l'empreinte.
     */
    fun requirePin(a: Activity, reason: String, cancel: (() -> Unit)? = null, ok: () -> Unit) {
        val store = ParentalStore(a)
        if (!store.hasPin) { ok(); return }
        val fa = a as? androidx.fragment.app.FragmentActivity
        if (fa == null || !ParentAuth.available(a)) { askPinCode(a, reason, cancel, ok); return }
        ParentAuth.prompt(fa, "Code parent", reason, onOk = ok,
            onCode = { askPinCode(a, reason, cancel, ok) },
            onNewFingerprint = {
                askPinCode(a, "Une nouvelle empreinte a été ajoutée sur ce téléphone. Tapez le code parent une fois pour la confirmer.\n\n$reason", cancel) {
                    ParentAuth.reset(); ok()
                }
            })
    }

    /** Saisie du code parent au clavier (secours de l'empreinte), avec essais limités. */
    fun askPinCode(a: Activity, reason: String, cancel: (() -> Unit)? = null, ok: () -> Unit) {
        val store = ParentalStore(a)
        if (!store.hasPin) { ok(); return }
        val input = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "Code parent"
            noAutofill(this)
        }
        val box = FrameLayout(a).apply { setPadding(dp(a, 20), 0, dp(a, 20), 0); addView(input) }
        AlertDialog.Builder(a).setTitle("Code parent").setMessage(reason).setView(box)
            .setPositiveButton("Valider") { _, _ ->
                when (val r = store.tryPin(input.text.toString())) {
                    fr.paul.telecommande.shared.PinThrottle.Result.Ok -> ok()
                    is fr.paul.telecommande.shared.PinThrottle.Result.Wrong -> {
                        toast(a, if (r.triesLeft <= 2) "Code incorrect. Encore ${r.triesLeft} essai${if (r.triesLeft > 1) "s" else ""} avant une pause." else "Code incorrect")
                        cancel?.invoke()
                    }
                    is fr.paul.telecommande.shared.PinThrottle.Result.Locked -> {
                        if (r.fails > 0) fr.paul.telecommande.core.DiagLog.log("PARENTAL", "${r.fails} codes parent faux sur le téléphone : pause de ${fr.paul.telecommande.shared.RulesEngine.fmt(r.ms)}")
                        toast(a, "Trop d'essais. Réessayez dans ${fr.paul.telecommande.shared.RulesEngine.fmt(r.ms)}, ou utilisez l'empreinte.")
                        cancel?.invoke()
                    }
                }
            }
            .setNeutralButton("Code oublié ?") { _, _ -> PinResetActivity.start(a, ok) }
            .setNegativeButton("Annuler") { _, _ -> cancel?.invoke() }
            .setOnCancelListener { cancel?.invoke() }.show()
    }

    /** Création du code parent (4 à 8 chiffres, saisi deux fois). */
    fun createPin(a: Activity, done: (Boolean) -> Unit) {
        val p1 = EditText(a).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "Nouveau code (4 à 8 chiffres)"; noAutofill(this) }
        val p2 = EditText(a).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "Confirmer le code"; noAutofill(this) }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 20), 0, dp(a, 20), 0); addView(p1); addView(p2) }
        AlertDialog.Builder(a).setTitle("Créer le code parent")
            .setMessage("Il protège les réglages du contrôle parental et l'arrêt du suivi.")
            .setView(box)
            .setPositiveButton("Créer") { _, _ ->
                val c1 = p1.text.toString(); val c2 = p2.text.toString()
                if (c1.length !in 4..8) { toast(a, "Le code doit faire 4 à 8 chiffres"); done(false) }
                else if (c1 != c2) { toast(a, "Les deux codes ne correspondent pas"); done(false) }
                else { ParentalStore(a).setPin(c1); ParentAuth.reset(); toast(a, "Code parent enregistré"); done(true) }
            }
            .setNegativeButton("Annuler") { _, _ -> done(false) }
            .setOnCancelListener { done(false) }
            .show()
    }
}
