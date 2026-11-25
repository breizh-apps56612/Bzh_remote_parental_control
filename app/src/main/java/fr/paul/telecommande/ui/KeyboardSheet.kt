package fr.paul.telecommande.ui

import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import fr.paul.telecommande.R
import fr.paul.telecommande.drivers.AndroidTvDriver
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Clavier du téléphone synchronisé avec le champ de saisie de la TV.
 * S'ouvre tout seul quand un champ prend le focus sur la TV ; chaque frappe met à jour
 * le champ de la TV en direct (contenu complet : pas de doublons ni de restes).
 */
/** [box] : si l'appli Box est installée, le texte est écrit directement dans le champ de la TV (méthode la plus fiable). */
class KeyboardSheet(private val a: AppCompatActivity, private val tv: AndroidTvDriver?, private val session: fr.paul.telecommande.core.BoxSession? = null) {
    private var dialog: BottomSheetDialog? = null
    private var syncJob: Job? = null

    val isShowing get() = dialog?.isShowing == true

    fun open(title: String = "Saisie sur la TV") {
        if (isShowing) return
        val c = a
        val input = EditText(c).apply {
            setText(tv?.fieldText?.value ?: ""); setSelection(text.length)
            hint = "Tapez ici, le texte s'écrit sur la TV"
            textSize = 18f; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(c, 20); setPadding(p, p, p, p)
            setBackgroundResource(R.drawable.bg_screen)
        }
        box.addView(Ui.text(c, title, 18f))
        box.addView(Ui.text(c, "Le champ de la TV suit votre saisie en direct.", 12f, muted = true), Ui.lp(c, 2))
        box.addView(input, Ui.lp(c, 12))
        val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        fun send(now: Boolean) {
            syncJob?.cancel()
            syncJob = a.lifecycleScope.launch {
                if (!now) delay(180)
                if (session != null) session.typeText(input.text.toString(), false)
                else runCatching { tv?.sendText(input.text.toString()) }   // remplacement direct, sans attente à chaque frappe
            }
        }
        row.addView(Ui.button(c, "Effacer") { input.setText("") }, LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f).apply { marginEnd = Ui.dp(c, 6) })
        row.addView(Ui.button(c, "Coller") {
            val clip = (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(c)?.toString()
            if (t.isNullOrBlank()) Ui.toast(c, "Presse-papiers vide") else { input.setText(t); input.setSelection(t.length) }
        }, LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f).apply { marginEnd = Ui.dp(c, 6) })
        row.addView(Ui.button(c, "Valider ↵", primary = true) { submit(input) }, LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1.2f))
        box.addView(row, Ui.lp(c, 12))

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { send(false) }
        })
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE) { submit(input); true } else false }

        dialog = BottomSheetDialog(c).apply {
            setContentView(box)
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setOnShowListener { input.requestFocus() }
            show()
        }
    }

    private fun submit(input: EditText) {
        syncJob?.cancel()
        a.lifecycleScope.launch {
            if (session != null) {
                session.typeText(input.text.toString(), true)
                delay(300); runCatching { tv?.sendKeyCode(66) }      // Entrée (les box Android 10 n'ont pas « valider » côté appli)
            } else runCatching { tv?.setFieldText(input.text.toString()); delay(150); tv?.submitField() }
        }
        close()
    }

    fun close() { dialog?.dismiss(); dialog = null }

}
