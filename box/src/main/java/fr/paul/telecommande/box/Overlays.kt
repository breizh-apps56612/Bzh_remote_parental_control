package fr.paul.telecommande.box

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Fenêtres affichées par-dessus les applis (fenêtres d'accessibilité : aucune autorisation
 * supplémentaire). Pilotables à la télécommande de la TV (croix + OK).
 */
class Overlays(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    /** Tests visuels : exécuter tout de suite au lieu de passer par le fil principal. */
    var immediate = false
    private fun ui(block: () -> Unit) { if (immediate) block() else main.post(block) }
    private val d = ctx.resources.displayMetrics.density
    private var block: View? = null
    private var banner: View? = null
    private var code: View? = null
    private var pin: View? = null

    private val accent = BoxUi.ACCENT
    private val ink = BoxUi.INK
    private val muted = BoxUi.SOFT

    private fun dp(v: Int) = (v * d).toInt()

    private fun params(focusable: Boolean, fullscreen: Boolean, gravity: Int = Gravity.CENTER) = WindowManager.LayoutParams(
        if (fullscreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        if (fullscreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { this.gravity = gravity }

    /** Dernière fenêtre ajoutée (vérifications visuelles uniquement). */
    var lastRootForTest: View? = null; private set
    /** Fenêtres plein écran qui prennent la main (l'appli derrière perd le focus) : notées dans le journal. */
    private val named = java.util.WeakHashMap<View, String>()
    private fun add(v: View, p: WindowManager.LayoutParams, name: String? = null) {
        noClip(v); lastRootForTest = v
        val ok = runCatching { wm.addView(v, p) }.isSuccess
        if (name != null) { named[v] = name; BoxLog.log("FENÊTRE", "Affichée : $name" + if (ok) "" else " (refusée par Android)") }
    }

    /** Les cartes et boutons grossissent quand ils ont le focus : on ne les rogne pas sur les bords. */
    private fun noClip(v: View) {
        if (v is android.view.ViewGroup) {
            v.clipChildren = false; v.clipToPadding = false
            for (i in 0 until v.childCount) noClip(v.getChildAt(i))
        }
    }
    private fun remove(v: View?) {
        if (v == null) return
        runCatching { wm.removeView(v) }
        named.remove(v)?.let { BoxLog.log("FENÊTRE", "Fermée : $it") }
    }

    private fun card(padH: Int = 44, padV: Int = 36) = BoxUi.card(ctx, padH, padV)

    private fun text(t: String, size: Float, color: Int = ink, bold: Boolean = false) = BoxUi.text(ctx, t, size, color, bold)

    /** Bouton bien visible quand il a le focus de la télécommande. */
    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = BoxUi.button(ctx, label, primary, onClick = onClick)

    // ---------- Écran « Temps écoulé » ----------

    fun showBlock(title: String, reason: String, canRequest: Boolean, requestMinutes: Int, pending: Boolean,
                  onParentCode: (() -> Unit)? = null, icon: String = "⏳", requestLabel: String? = null, onRequest: () -> Unit) = ui {
        remove(block)
        val root = BoxUi.Fit(ctx).apply { background = BoxUi.scrim() }
        val c = card()
        c.addView(BoxUi.badge(ctx, icon, false, 76f).apply { textSize = 30f })
        c.addView(text(title, 34f, bold = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
        c.addView(text(reason, 19f, muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
        var first: View? = null
        if (canRequest) {
            val b = if (pending) button("Demande envoyée, en attente…", false) {} .apply { isEnabled = false }
            else button(requestLabel ?: "Demander $requestMinutes min de plus", true) { onRequest() }
            row.addView(b, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) })
            if (!pending) first = b
        }
        if (onParentCode != null) {
            val p = button("🔓 Code parent : continuer", first == null) { onParentCode() }
            row.addView(p, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) })
            if (first == null) first = p
        }
        val ok = button("J'ai compris", false) { hideBlock() }
        row.addView(ok)
        if (first == null) first = ok
        c.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })
        root.addView(c)
        root.isFocusable = true
        root.setOnKeyListener { _, code, e -> if (code == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { hideBlock(); true } else false }
        add(root, params(focusable = true, fullscreen = true), "écran de blocage")
        block = root
        first?.requestFocus()
    }

    fun hideBlock() = ui { remove(block); block = null }
    val blockVisible get() = block != null

    // ---------- Bandeau de compte à rebours ----------

    /**
     * Place du bandeau « Bonne séance… » : en haut à droite, ou juste SOUS la pastille de profil quand elle est
     * affichée (accueil Freebox) — avant, il recouvrait la pastille et le bandeau d'Oqee.
     */
    private fun bannerParams() = params(focusable = false, fullscreen = false, gravity = Gravity.TOP or Gravity.END).apply {
        val bp = badgeParams
        if (badge != null && bp != null) { x = bp.x; y = bp.y + badgeSize + dp(14) } else { x = dp(32); y = dp(32) }
    }

    fun showBanner(message: String) = ui {
        remove(banner)
        val v = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = BoxUi.cardBg(ctx, 999f, highlight = true)
            setPadding(dp(10), dp(10), dp(30), dp(10))
            addView(BoxUi.badge(ctx, "⏳", false, 44f).apply { textSize = 18f })
            addView(text(message, 19f, bold = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
        }
        add(v, bannerParams())
        banner = v
        main.postDelayed({ if (banner === v) { remove(v); banner = null } }, 9000)
    }

    // ---------- Code d'appairage ----------

    fun showPairingCode(codeText: String, durationMs: Long) = ui {
        remove(code)
        val c = card()
        c.addView(BoxUi.label(ctx, "Appairage d'un téléphone"))
        c.addView(text(codeText.chunked(3).joinToString("  "), 64f, bold = true).apply { letterSpacing = 0.08f; setTextColor(BoxUi.INK) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14); bottomMargin = dp(8) })
        c.minimumWidth = dp(620)
        c.addView(text("Saisissez ce code dans l'appli Télécommande du téléphone.\nNe le communiquez à personne d'autre.", 16f, muted), LinearLayout.LayoutParams(-1, -2))
        add(c, params(focusable = false, fullscreen = false))
        code = c
        main.postDelayed({ if (code === c) { remove(c); code = null } }, durationMs)
    }

    fun hidePairingCode() = ui { remove(code); code = null }

    // ---------- Code parent (protection des Paramètres) ----------

    /** Appelé à l'affichage / la fermeture du pavé : le téléphone du parent peut valider à distance. */
    var onPinRequest: ((id: String, title: String) -> Unit)? = null
    var onPinClosed: ((id: String) -> Unit)? = null
    private var pinId: String? = null
    private var pinApprove: (() -> Unit)? = null
    @Volatile private var pinTitle: String = ""

    /** Demande de code affichée en ce moment (id, titre), pour un téléphone qui se (re)connecte. */
    val pendingPin: Pair<String, String>? get() { val id = pinId; return if (id != null && pin != null) id to pinTitle else null }

    /**
     * Demandes de code encore valables 10 min, même si le pavé a été recouvert ou remplacé :
     * la validation du parent sur son téléphone aboutit toujours (sauf si la demande a été annulée sur la TV).
     */
    private class Approval(val expiry: Long, val title: String, val action: () -> Unit)
    private val approvals = LinkedHashMap<String, Approval>()

    /** Dernier diagnostic de validation à distance (pour le journal de la TV). */
    @Volatile var lastApproveInfo = ""

    /**
     * Validation depuis le téléphone (empreinte, visage ou code saisi sur le téléphone).
     * 1) la demande exacte (id) ; 2) sinon le pavé affiché en ce moment s'il porte le même titre ;
     * 3) sinon la dernière demande encore valable portant ce titre.
     */
    fun approvePin(id: String, title: String): Boolean {
        val shownId = pinId; val shownTitle = pinTitle; val shown = pin != null
        val action = synchronized(approvals) {
            val now = android.os.SystemClock.elapsedRealtime()
            approvals.entries.removeAll { it.value.expiry < now }
            val known = approvals.keys.joinToString(",")
            val a = approvals.remove(id)
                ?: (if (shown && shownId != null && (title.isBlank() || title == shownTitle)) approvals.remove(shownId) else null)
                ?: approvals.entries.lastOrNull { title.isNotBlank() && it.value.title == title }?.let { approvals.remove(it.key) }
            lastApproveInfo = "reçu=$id « $title » · affiché=${if (shown) "$shownId « $shownTitle »" else "aucun"} · en attente=[$known] → " +
                (if (a != null) "accepté" else "refusé")
            a?.action
        } ?: return false
        ui { action() }
        return true
    }

    private fun forgetApproval(id: String) = synchronized(approvals) { approvals.remove(id) }

    fun showPinPad(title: String, check: (String) -> Boolean, onSuccess: () -> Unit, onCancel: () -> Unit) =
        showPinPadEx(title, { if (check(it)) null else "Code incorrect, réessayez." }, onSuccess, onCancel)

    /** [check] renvoie null si le code est bon, sinon le message à afficher (code faux, pause en cours…). */
    fun showPinPadEx(title: String, check: (String) -> String?, onSuccess: () -> Unit, onCancel: () -> Unit) = ui {
        remove(pin)
        pinId?.let { old -> onPinClosed?.invoke(old) }
        val id = java.util.UUID.randomUUID().toString().take(12)
        val entered = StringBuilder()
        val root = BoxUi.Fit(ctx).apply { background = BoxUi.scrim() }
        val c = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = BoxUi.cardBg(ctx)
            setPadding(dp(44), dp(36), dp(40), dp(36))
        }
        // à gauche : titre, points, message, annuler
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        left.addView(BoxUi.badge(ctx, "🔒", false, 60f).apply { textSize = 22f })
        left.addView(text(title, 26f, bold = true).apply { maxWidth = dp(340) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        val dots = text("", 34f, bold = true).apply { setTextColor(BoxUi.ACCENT); letterSpacing = 0.3f }
        val hint = "Validez sur le téléphone du parent (empreinte ou code), ou tapez le code ici."
        val msg = text(hint, 15f, muted).apply { maxWidth = dp(320) }
        // Bandeau rouge « Code refusé » : invisible tant qu'aucun code n'a été refusé (TV ou téléphone)
        val refused = text("✕  Code refusé", 20f, Color.WHITE, bold = true).apply {
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(BoxUi.DANGER) }
            setPadding(dp(18), dp(8), dp(18), dp(8)); visibility = View.GONE
        }
        left.addView(refused, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        left.addView(dots, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        left.addView(msg)
        fun close() { if (pinId == id) { remove(pin); pin = null; pinId = null; pinApprove = null; pinRefused = null; onPinClosed?.invoke(id) } }
        pinId = id; pinTitle = title
        pinApprove = { close(); onSuccess() }
        synchronized(approvals) {
            approvals[id] = Approval(android.os.SystemClock.elapsedRealtime() + 10 * 60_000L, title) { close(); onSuccess() }
            while (approvals.size > 10) approvals.remove(approvals.keys.first())
        }
        left.addView(button("Annuler", false) { forgetApproval(id); close(); onCancel() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
        c.addView(left, LinearLayout.LayoutParams(dp(360), -2))
        // à droite : pavé numérique compact
        val grid = GridLayout(ctx).apply { columnCount = 3 }
        fun refresh() { dots.text = "●".repeat(entered.length).ifEmpty { "○ ○ ○ ○" } }
        // Code refusé : bandeau rouge + message + secousse de la fenêtre, puis retour à l'état normal (le message reste)
        val restore = Runnable { if (pinId == id) { refused.visibility = View.GONE; dots.setTextColor(BoxUi.ACCENT); refresh() } }
        fun showRefused(text: String) {
            entered.clear()
            refused.visibility = View.VISIBLE
            msg.text = text; msg.setTextColor(BoxUi.DANGER)
            dots.setTextColor(BoxUi.DANGER); dots.text = "✕ ✕ ✕ ✕"
            c.animate().cancel(); c.translationX = 0f
            android.animation.ObjectAnimator.ofFloat(c, "translationX", 0f, -22f, 22f, -16f, 16f, -8f, 8f, 0f).setDuration(480).start()
            main.removeCallbacks(restore); main.postDelayed(restore, 2500)
        }
        pinRefused = { t -> showRefused(t) }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
        var firstBtn: View? = null
        keys.forEach { k ->
            val b = button(k, k == "OK") {
                when (k) {
                    "⌫" -> if (entered.isNotEmpty()) entered.setLength(entered.length - 1)
                    "OK" -> { val error = check(entered.toString()); if (error == null) { forgetApproval(id); close(); onSuccess() } else { showRefused(error); return@button } }
                    else -> { if (entered.length < 8) entered.append(k); if (refused.visibility == View.VISIBLE) { main.removeCallbacks(restore); refused.visibility = View.GONE; dots.setTextColor(BoxUi.ACCENT) } }
                }
                refresh()
            }
            b.textSize = if (k == "OK") 17f else 21f
            b.minHeight = 0; b.minimumHeight = 0; b.setPadding(0, 0, 0, 0)
            grid.addView(b, GridLayout.LayoutParams().apply { width = dp(88); height = dp(56); setMargins(dp(6), dp(6), dp(6), dp(6)) })
            if (k == "1") firstBtn = b
        }
        c.addView(grid, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(28) })
        root.addView(c)
        root.isFocusable = true
        // Touches chiffres et Retour de la télécommande : le focus est sur une touche du pavé, pas sur le fond,
        // donc l'écouteur est posé sur chaque touche ET sur le fond (avant : les chiffres de la télécommande étaient ignorés).
        val keys2 = View.OnKeyListener { _, code, e ->
            val digit = code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 || code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9
            if (code != KeyEvent.KEYCODE_BACK && !digit) return@OnKeyListener false
            if (e.action != KeyEvent.ACTION_UP) return@OnKeyListener true
            if (code == KeyEvent.KEYCODE_BACK) { forgetApproval(id); close(); onCancel() }
            else {
                val n = if (code >= KeyEvent.KEYCODE_NUMPAD_0) code - KeyEvent.KEYCODE_NUMPAD_0 else code - KeyEvent.KEYCODE_0
                if (entered.length < 8) entered.append(n)
                if (refused.visibility == View.VISIBLE) { main.removeCallbacks(restore); refused.visibility = View.GONE; dots.setTextColor(BoxUi.ACCENT) }
                refresh()
            }
            true
        }
        root.setOnKeyListener(keys2)
        for (i in 0 until grid.childCount) grid.getChildAt(i).setOnKeyListener(keys2)
        left.getChildAt(left.childCount - 1)?.setOnKeyListener(keys2)   // « Annuler »
        add(root, params(focusable = true, fullscreen = true), "pavé du code parent")
        pin = root
        refresh()
        firstBtn?.requestFocus()
        onPinRequest?.invoke(id, title)
    }

    val pinVisible get() = pin != null

    private var pinRefused: ((String) -> Unit)? = null

    /**
     * Affiche « Code refusé » sur le pavé ouvert (code faux tapé sur le téléphone du parent, validation refusée…).
     * [id] vide = le pavé affiché, quel qu'il soit. Renvoie false si aucun pavé correspondant n'est à l'écran.
     */
    fun pinRefusedElsewhere(id: String, message: String): Boolean {
        val shown = pinId ?: return false
        if (id.isNotBlank() && id != shown) return false
        ui { if (pinId == shown) pinRefused?.invoke(message) }
        return true
    }

    // ---------- Vraie souris : flèche dessinée par l'appli, déplacée depuis le téléphone ----------

    private var pointer: View? = null
    private var pointerParams: WindowManager.LayoutParams? = null
    var pointerX = 0f; private set
    var pointerY = 0f; private set
    private val hidePointerLater = Runnable { remove(pointer); pointer = null }

    private fun screenSize(): Pair<Int, Int> {
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    /** Déplace la flèche (en pixels d'écran) ; elle apparaît au centre la première fois et disparaît après 8 s sans mouvement. */
    fun movePointer(dx: Float, dy: Float) = ui {
        val (w, h) = screenSize()
        if (pointer == null) {
            if (pointerX == 0f && pointerY == 0f) { pointerX = w / 2f; pointerY = h / 2f }
            val v = PointerView(ctx)
            val p = params(focusable = false, fullscreen = false, gravity = Gravity.TOP or Gravity.START).apply {
                width = dp(36); height = dp(36)
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            }
            add(v, p); pointer = v; pointerParams = p
        }
        pointerX = (pointerX + dx).coerceIn(0f, w - 1f); pointerY = (pointerY + dy).coerceIn(0f, h - 1f)
        pointerParams?.let { p -> p.x = pointerX.toInt(); p.y = pointerY.toInt(); runCatching { wm.updateViewLayout(pointer, p) } }
        main.removeCallbacks(hidePointerLater); main.postDelayed(hidePointerLater, 8_000)
    }

    fun pulsePointer() = ui { (pointer as? PointerView)?.pulse(); main.removeCallbacks(hidePointerLater); main.postDelayed(hidePointerLater, 8_000) }

    private class PointerView(c: Context) : View(c) {
        private val d = c.resources.displayMetrics.density
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14121F.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 2 * d; strokeJoin = android.graphics.Paint.Join.ROUND }
        private val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = BoxUi.ACCENT }
        private var pulseUntil = 0L
        private val path = android.graphics.Path()
        fun pulse() { pulseUntil = android.os.SystemClock.uptimeMillis() + 250; invalidate(); postDelayed({ invalidate() }, 260) }
        override fun onDraw(canvas: android.graphics.Canvas) {
            if (android.os.SystemClock.uptimeMillis() < pulseUntil) { glow.alpha = 140; canvas.drawCircle(3 * d, 3 * d, 14 * d, glow) }
            path.reset()
            path.moveTo(2 * d, 2 * d); path.lineTo(2 * d, 26 * d); path.lineTo(8.5f * d, 20 * d); path.lineTo(13 * d, 30 * d)
            path.lineTo(17.5f * d, 28 * d); path.lineTo(13 * d, 18.5f * d); path.lineTo(21.5f * d, 18.5f * d); path.close()
            canvas.drawPath(path, fill); canvas.drawPath(path, edge)
        }
    }

    // ---------- Sablier discret (coin de l'écran) ----------

    private var timer: View? = null
    private var timerText: TextView? = null

    fun showTimer(text: String, urgent: Boolean) = ui {
        if (timer == null) {
            val t = BoxUi.text(ctx, text, 17f, bold = true)
            val v = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = BoxUi.cardBg(ctx, 999f, highlight = urgent).apply { alpha = 235 }
                setPadding(dp(8), dp(8), dp(22), dp(8))
                addView(BoxUi.badge(ctx, "⏳", false, 36f).apply { textSize = 15f })
                addView(t, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            }
            add(v, params(focusable = false, fullscreen = false, gravity = Gravity.TOP or Gravity.END).apply { x = dp(32); y = dp(28) })
            timer = v; timerText = t
        } else {
            timerText?.text = text
            timer?.background = BoxUi.cardBg(ctx, 999f, highlight = urgent).apply { alpha = 235 }
        }
    }

    fun hideTimer() = ui { remove(timer); timer = null; timerText = null }

    // ---------- « Qui regarde ? » ----------

    private var who: View? = null
    val whoVisible get() = who != null

    /**
     * « Qui regarde ? » (style A Premium) : nuit à reflets aurore, grandes cartes ; celle qui a le focus devient blanche.
     * [info] : temps restant du profil (« Encore 20 min »), [warn] : ce temps est presque écoulé ; [footer] : « Extinction à 20:30 ».
     */
    fun showWhoWatches(profiles: List<fr.paul.telecommande.shared.Profile>, lastId: String? = null,
                       needsPin: (fr.paul.telecommande.shared.Profile) -> Boolean = { it.unlimited },
                       info: (fr.paul.telecommande.shared.Profile) -> Pair<String, Boolean>? = { null }, footer: String? = null,
                       cancelable: Boolean = false,
                       onPick: (fr.paul.telecommande.shared.Profile) -> Unit) = ui {
        if (who != null) return@ui
        val night = 0xFF05061A.toInt()
        // écran opaque : c'est un passage obligé, rien ne doit transparaître derrière
        val root = BoxUi.Fit(ctx).apply {
            fun glow(color: Int, x: Float, y: Float, r: Int) = android.graphics.drawable.GradientDrawable().apply {
                gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT; gradientRadius = dp(r).toFloat()
                setGradientCenter(x, y); colors = intArrayOf(color, color and 0x00FFFFFF)
            }
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.GradientDrawable().apply { setColor(night) },
                glow(0x8C5B54FF.toInt(), 0.95f, 0f, 520), glow(0x4034F5C5, 0.05f, 1.05f, 480)))
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(ctx).apply { gravity = Gravity.BOTTOM }
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text(java.text.SimpleDateFormat("EEEE · HH:mm", java.util.Locale.FRANCE).format(java.util.Date()).replaceFirstChar { it.uppercase() }, 18f, 0xFFC7C8EE.toInt()).apply { gravity = Gravity.START })
        titles.addView(text("Qui regarde ?", 56f, Color.WHITE, bold = true).apply { gravity = Gravity.START }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        if (!footer.isNullOrBlank()) head.addView(text(footer, 16f, Color.WHITE, bold = true).apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(99).toFloat(); setStroke(dp(1), 0x33FFFFFF) }
            setPadding(dp(18), dp(9), dp(18), dp(9))
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(24); bottomMargin = dp(8) })
        col.addView(head, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(ctx).apply { gravity = Gravity.START; setPadding(0, dp(34), 0, dp(26)) }
        var first: View? = null
        val focusedState = intArrayOf(android.R.attr.state_focused)
        fun states(focused: Int, normal: Int) = android.content.res.ColorStateList(arrayOf(focusedState, intArrayOf()), intArrayOf(focused, normal))
        profiles.forEachIndexed { i, p ->
            val lock = needsPin(p)
            val (sub, warn) = info(p) ?: (when {
                p.unlimited -> if (lock) "Code parent" else "Sans limite"
                lastId == p.id -> "Continuer"
                lock -> "Changer : code parent"
                p.dailyTotalMin > 0 -> "${fr.paul.telecommande.shared.RulesEngine.fmt(p.dailyTotalMin * 60_000L)} par jour"
                else -> "Règles de la maison"
            } to false)
            val tile = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                val normal = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(32).toFloat(); setColor(0x12FFFFFF); setStroke(dp(1), 0x29FFFFFF) }
                val focused = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(32).toFloat(); setColor(Color.WHITE); setStroke(dp(6), 0x737C74FF) }
                background = android.graphics.drawable.StateListDrawable().apply { addState(focusedState, focused); addState(intArrayOf(), normal) }
                setPadding(dp(24), dp(24), dp(24), dp(22))
                addView(text(p.emoji, 40f).apply {
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0x1F8C89FF) }
                    isDuplicateParentStateEnabled = true
                }, LinearLayout.LayoutParams(dp(76), dp(76)))
                addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
                addView(text(p.name, 30f, Color.WHITE, bold = true).apply {
                    gravity = Gravity.START; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(states(night, Color.WHITE)); isDuplicateParentStateEnabled = true
                })
                addView(text((if (lock) "🔒 " else "") + sub, 17f, bold = true).apply {
                    gravity = Gravity.START; maxLines = 1
                    setTextColor(if (warn) states(0xFF8A5A00.toInt(), 0xFFFFC857.toInt()) else if (p.unlimited || lock) states(0xFF3D3F5C.toInt(), 0xFFC7C8EE.toInt()) else states(0xFF0A6E5C.toInt(), 0xFF34F5C5.toInt()))
                    isDuplicateParentStateEnabled = true
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
                setOnFocusChangeListener { v, has -> v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(140).start() }
                setOnClickListener { remove(root); who = null; onPick(p) }
            }
            row.addView(tile, LinearLayout.LayoutParams(dp(236), dp(228)).apply { if (i > 0) marginStart = dp(24) })
            if (first == null || p.id == lastId) first = tile
        }
        col.addView(row)
        col.addView(text("Flèches pour choisir · OK pour valider", 15f, 0xFFC7C8EE.toInt()).apply { gravity = Gravity.START })
        root.addView(col)
        root.isFocusable = true
        // à l'allumage on doit choisir ; « Changer de profil » (un profil est déjà actif) se ferme avec Retour
        root.setOnKeyListener { _, code, e ->
            if (code != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            if (cancelable && e.action == KeyEvent.ACTION_UP) { remove(root); who = null }
            true
        }
        add(root, params(focusable = true, fullscreen = true), "« Qui regarde ? »")
        who = root
        first?.requestFocus()
    }

    // ---------- Message du téléphone (« À table ! ») ----------

    private var message: View? = null

    fun showMessage(emoji: String, title: String, body: String, onOk: () -> Unit) = ui {
        remove(message)
        val root = BoxUi.Fit(ctx).apply { background = BoxUi.scrim() }
        val c = card()
        c.addView(text(emoji, 72f))
        c.addView(text(title, 40f, bold = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        if (body.isNotBlank()) c.addView(text(body, 20f, muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        val ok = button("J'arrive !", true) { remove(root); message = null; onOk() }
        c.addView(ok, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(30) })
        root.addView(c)
        add(root, params(focusable = true, fullscreen = true), "message")
        message = root
        ok.requestFocus()
    }

    // ---------- Contrat parental montré à l'enfant ----------

    private var contract: View? = null

    /** « Voici ton contrat » : les règles en quelques lignes simples, puis « J'ai compris » ou « J'ai une question ». */
    fun showContract(name: String, lines: List<Triple<String, String, String>>, onOk: () -> Unit, onQuestion: () -> Unit) = ui {
        remove(contract)
        val root = BoxUi.Fit(ctx).apply { background = BoxUi.scrim() }
        val c = card(padH = 52, padV = 40)
        c.addView(text("Voici ton contrat, $name", 36f, bold = true))
        c.addView(text("Tes parents ont choisi ces règles. Si tu n'es pas d'accord, parles-en avec eux.", 19f, muted),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        lines.forEach { (icon, title, sub) ->
            val row = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = BoxUi.cardBg(ctx, 22f); setPadding(dp(22), dp(14), dp(26), dp(14))
            }
            row.addView(text(icon, 30f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(18) })
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(BoxUi.text(ctx, title, 22f, ink, bold = true, center = false))
            if (sub.isNotBlank()) col.addView(BoxUi.text(ctx, sub, 17f, muted, center = false))
            row.addView(col)
            c.addView(row, LinearLayout.LayoutParams(dp(560), -2).apply { topMargin = dp(12) })
        }
        fun close() { remove(root); if (contract === root) contract = null }
        val btns = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
        val ok = button("J'ai compris", true) { close(); onOk() }
        btns.addView(ok)
        btns.addView(button("J'ai une question", false) { close(); onQuestion() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
        c.addView(btns, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(26) })
        root.addView(c)
        add(root, params(focusable = true, fullscreen = true), "contrat")
        contract = root
        ok.requestFocus()
    }

    // ---------- Récompense (« Bravo ! +15 min ») ----------

    fun showReward(title: String, sub: String) = ui {
        val c = card()
        c.background = BoxUi.cardBg(ctx, 28f, highlight = true)
        c.addView(text("🎉", 64f))
        c.addView(text(title, 34f, bold = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        if (sub.isNotBlank()) c.addView(text(sub, 19f, muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        c.scaleX = 0.8f; c.scaleY = 0.8f; c.alpha = 0f
        add(c, params(focusable = false, fullscreen = false))
        c.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).start()
        main.postDelayed({ remove(c) }, 6_000)
    }

    // ---------- « Vous êtes toujours là ? » ----------

    private var still: View? = null
    val stillVisible get() = still != null

    fun showStillThere(seconds: Int, onYes: () -> Unit, onTimeout: () -> Unit) = ui {
        if (still != null) return@ui
        val root = BoxUi.Fit(ctx).apply { background = BoxUi.scrim() }
        val c = card()
        c.addView(text("👀", 64f))
        c.addView(text("Vous êtes toujours là ?", 36f, bold = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        val left = text("", 19f, muted)
        c.addView(left, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        val yes = button("Oui, je regarde", true) { main.removeCallbacksAndMessages(root); remove(root); still = null; onYes() }
        c.addView(yes, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })
        root.addView(c)
        add(root, params(focusable = true, fullscreen = true), "« Tu es toujours là ? »")
        still = root
        yes.requestFocus()
        var n = seconds
        val tickDown = object : Runnable {
            override fun run() {
                if (still !== root) return
                if (n <= 0) { remove(root); still = null; onTimeout(); return }
                left.text = "Sans réponse, la TV se met en veille dans $n s."
                n--; main.postAtTime(this, root, android.os.SystemClock.uptimeMillis() + 1000)
            }
        }
        tickDown.run()
    }

    // ---------- Pastille de profil (accueil Freebox) ----------

    private var badge: View? = null
    private var badgeKey: String? = null
    private var badgeParams: WindowManager.LayoutParams? = null
    private var badgeSize = 0
    val badgeVisible get() = badge != null

    /**
     * Pastille ronde avec l'emoji du profil, placée juste après l'avatar d'Oqee.
     * [centerX] / [centerY] : centre voulu (pixels écran). Sélectionnée : liseré blanc, plus grande,
     * et « Léo · changer de profil » dessous.
     */
    fun showProfileBadge(emoji: String, name: String, centerX: Int, centerY: Int, selected: Boolean) = ui {
        val key = "$emoji|$name|$centerX|$centerY|$selected"
        if (badge != null && key == badgeKey) return@ui
        remove(badge)
        val size = dp(if (selected) 46 else 40)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val circle = TextView(ctx).apply {
            text = emoji; gravity = Gravity.CENTER; textSize = if (selected) 22f else 19f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (selected) { setColor(accent); setStroke(dp(3), Color.WHITE) }
                else { setColor(0xE61B1B2A.toInt()); setStroke(dp(2), accent) }
            }
            if (selected) elevation = dp(8).toFloat()
        }
        col.addView(circle, LinearLayout.LayoutParams(size, size))
        if (selected) col.addView(TextView(ctx).apply {
            text = "$name · OK pour changer de profil"
            textSize = 14f; typeface = BoxUi.bold; setTextColor(Color.WHITE)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply { cornerRadius = dp(999).toFloat(); setColor(0xF01B1B2A.toInt()); setStroke(dp(1), accent) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        val screenW = ctx.resources.displayMetrics.widthPixels
        add(col, params(focusable = false, fullscreen = false, gravity = Gravity.TOP or Gravity.END).apply {
            x = (screenW - (centerX + size / 2)).coerceAtLeast(0)
            y = (centerY - size / 2).coerceAtLeast(0)
        }.also { badgeParams = it })
        badge = col; badgeKey = key; badgeSize = size
        // Un bandeau déjà affiché descend sous la pastille au lieu de la recouvrir
        banner?.let { b -> runCatching { wm.updateViewLayout(b, bannerParams()) } }
    }

    fun hideProfileBadge() = ui {
        val had = badge != null
        remove(badge); badge = null; badgeKey = null; badgeParams = null
        if (had) banner?.let { b -> runCatching { wm.updateViewLayout(b, bannerParams()) } }
    }

    fun hideAll() = ui {
        listOf(block, banner, code, pin, pointer, timer, who, message, still, badge, contract).forEach { remove(it) }
        block = null; banner = null; code = null; pin = null; pointer = null; timer = null; who = null; message = null; still = null; contract = null
        badge = null; badgeKey = null
    }
}
