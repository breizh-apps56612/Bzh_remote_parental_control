package fr.paul.telecommande.box

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.os.SystemClock
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import fr.paul.telecommande.shared.BoxProtocol
import fr.paul.telecommande.shared.BoxRules
import fr.paul.telecommande.shared.SelfSignedCert
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Stockage chiffré (AES-256, clé dans le Keystore Android) des données sensibles de la box :
 * clé privée, téléphones appairés, règles, bonus, dérogations, demandes en attente.
 */
class BoxStore private constructor(context: Context) {
    /** Faux si le coffre chiffré d'Android est définitivement inutilisable sur cette box (stockage de secours). */
    var encrypted = true
        private set
    /** Résumé du démarrage du stockage (pour le journal de diagnostic). */
    var startupInfo = ""
        private set

    /**
     * Le coffre chiffré d'Android (Keystore) peut refuser de s'ouvrir quelques secondes après un démarrage
     * ou une mise à jour. Avant : on basculait aussitôt sur un stockage de secours vide → nouvelle identité,
     * plus de téléphone appairé ni de règles, puis retour à l'ancienne identité au démarrage suivant.
     * Maintenant : on réessaie, et le choix du stockage est définitif (jamais d'aller-retour).
     */
    private val prefs: SharedPreferences = run {
        val meta = context.getSharedPreferences("box_meta", Context.MODE_PRIVATE)
        val backend = meta.getString("backend", "")
        val secureFile = java.io.File(context.applicationInfo.dataDir, "shared_prefs/box_secure.xml")
        val secureExists = secureFile.exists() && secureFile.length() > 0
        fun fallback(): SharedPreferences = context.getSharedPreferences("box_fallback", Context.MODE_PRIVATE)
        if (backend == "fallback") { encrypted = false; startupInfo = "stockage de secours (choisi précédemment)"; return@run fallback() }
        var last: Throwable? = null
        val tries = if (secureExists || backend == "secure") 12 else 3
        for (i in 1..tries) {
            val p = runCatching {
                EncryptedSharedPreferences.create(
                    "box_secure", MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { it.all }   // lecture d'essai : une clé illisible échoue ici, pas plus tard
            }.onFailure { last = it }.getOrNull()
            if (p != null) {
                if (backend != "secure") meta.edit().putString("backend", "secure").apply()
                startupInfo = "stockage chiffré" + if (i > 1) " (ouvert au ${i}e essai)" else ""
                return@run p
            }
            SystemClock.sleep(minOf(250L * i, 1_000L))
        }
        // Coffre définitivement illisible : on passe au stockage de secours une fois pour toutes (réappairage nécessaire).
        encrypted = false
        meta.edit().putString("backend", "fallback").apply()
        startupInfo = "coffre chiffré illisible (${last?.javaClass?.simpleName ?: "?"}) : stockage de secours, réappairez le téléphone"
        fallback()
    }

    // ---------- Identité TLS ----------

    @Synchronized
    fun identity(): Pair<PrivateKey, X509Certificate> {
        val k = prefs.getString("key", null); val c = prefs.getString("cert", null)
        if (k != null && c != null) runCatching {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(k, Base64.NO_WRAP)))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(Base64.decode(c, Base64.NO_WRAP).inputStream()) as X509Certificate
            return key to cert
        }
        val (kp, cert) = SelfSignedCert.generate("telecommande-box")
        prefs.edit().putString("key", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString("cert", Base64.encodeToString(cert.encoded, Base64.NO_WRAP)).apply()
        return kp.private to cert
    }

    // ---------- Téléphones appairés (empreinte → nom) ----------

    @Synchronized
    fun phones(): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString("phones", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    @Synchronized
    fun addPhone(fp: String, name: String) = savePhones(phones() + (fp to name.take(60)))

    @Synchronized
    fun removePhone(fp: String) = savePhones(phones() - fp)

    private fun savePhones(m: Map<String, String>) = prefs.edit().putString("phones", JSONObject(m).toString()).apply()

    // ---------- Règles ----------

    @Synchronized
    fun rules(): BoxRules = BoxRules.fromJson(runCatching { JSONObject(prefs.getString("rules", "{}") ?: "{}") }.getOrNull())

    @Synchronized
    fun saveRules(r: BoxRules) = prefs.edit().putString("rules", r.toJson().toString()).apply()

    /** Code parent, avec pause après 5 essais faux (gardée sur le disque : un redémarrage ne la lève pas). */
    val pinThrottle = fr.paul.telecommande.shared.PinThrottle(
        load = { synchronized(this) { prefs.getInt("pinFails", 0) to prefs.getLong("pinLockUntil", 0L) } },
        save = { f, u -> synchronized(this) { prefs.edit().putInt("pinFails", f).putLong("pinLockUntil", u).apply() } }
    )

    fun tryPin(pin: String): fr.paul.telecommande.shared.PinThrottle.Result = pinThrottle.attempt {
        val h = rules().pinHash
        h.isNotBlank() && BoxProtocol.safeEquals(h, BoxProtocol.pinHash(pin))
    }

    fun checkPin(pin: String): Boolean = tryPin(pin) == fr.paul.telecommande.shared.PinThrottle.Result.Ok

    // ---------- Bonus du jour (clé « jour|appli », « * » = temps total) et dérogations ----------

    @Synchronized
    fun bonusMs(day: Long, pkg: String): Long = prefs.getLong("bonus|$day|$pkg", 0L)

    @Synchronized
    fun addBonus(day: Long, pkg: String, ms: Long) {
        prefs.edit().putLong("bonus|$day|$pkg", bonusMs(day, pkg) + ms).apply()
    }

    /** Dérogation horaire jusqu'à [until] pour une appli (« * » = couvre-feu). */
    @Synchronized
    fun overrideUntil(pkg: String): Long = prefs.getLong("override|$pkg", 0L)

    @Synchronized
    fun setOverride(pkg: String, until: Long) = prefs.edit().putLong("override|$pkg", until).apply()

    /** Programme autorisé exceptionnellement par le parent, pour la journée. */
    fun programAllowed(day: Long, key: String) = prefs.getBoolean("progok|$day|$key", false)
    fun allowProgram(day: Long, key: String) = prefs.edit().putBoolean("progok|$day|$key", true).apply()

    // ---------- « Finir l'épisode » : gardé sur le disque, pour qu'un redémarrage ou une mise à jour ne redonne pas de temps ----------

    /** Vrai si la fin d'épisode n'avait pas encore été accordée pour cette clé (jour|profil|appli) — et la marque comme utilisée. */
    @Synchronized fun claimGrace(key: String): Boolean {
        if (prefs.getBoolean("grace|$key", false)) return false
        prefs.edit().putBoolean("grace|$key", true).apply(); return true
    }
    @Synchronized fun graceState(): Pair<Long, String?> = prefs.getLong("graceUntil", 0L) to prefs.getString("gracePkg", null)
    @Synchronized fun setGraceState(until: Long, pkg: String?) = prefs.edit().putLong("graceUntil", until).putString("gracePkg", pkg).apply()

    /** Nettoyage des bonus des jours passés. */
    @Synchronized
    fun purge(today: Long) {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("grace|") && (it.split("|").getOrNull(1)?.toLongOrNull() ?: 0) < today }.forEach { e.remove(it) }
        prefs.all.keys.filter { it.startsWith("bonus|") && (it.split("|").getOrNull(1)?.toLongOrNull() ?: 0) < today }.forEach { e.remove(it) }
        prefs.all.keys.filter { it.startsWith("progok|") && (it.split("|").getOrNull(1)?.toLongOrNull() ?: 0) < today }.forEach { e.remove(it) }
        e.apply()
    }

    // ---------- Profil « Qui regarde ? » ----------

    /** Contrat déjà montré à l'enfant (empreinte de la version vue), pour ne pas le remontrer à chaque séance. */
    fun contractSeen(profile: String): String? = prefs.getString("contractSeen|$profile", null)
    fun setContractSeen(profile: String, signature: String) = prefs.edit().putString("contractSeen|$profile", signature).apply()

    @Synchronized fun activeProfile(): String? = prefs.getString("activeProfile", null)
    @Synchronized fun setActiveProfile(id: String?) {
        val e = prefs.edit().putString("activeProfile", id)
        if (id != null) e.putString("lastProfile", id)    // le dernier profil choisi reste connu après la mise en veille
        e.apply()
    }
    /** Dernier profil choisi (même TV éteinte) : changer pour un autre profil enfant demande le code parent. */
    @Synchronized fun lastProfile(): String? = prefs.getString("lastProfile", null)

    /** Démarrage de la box : vrai une seule fois par démarrage (l'heure du démarrage est mémorisée). */
    @Synchronized fun isNewBoot(bootAt: Long): Boolean {
        val old = prefs.getLong("bootAt", 0L)
        if (kotlin.math.abs(old - bootAt) < 60_000L) return false
        prefs.edit().putLong("bootAt", bootAt).apply(); return true
    }

    // ---------- Diagnostic (30 derniers événements) ----------

    @Synchronized
    fun diag(): MutableList<JSONObject> = runCatching {
        val a = JSONArray(prefs.getString("diag", "[]") ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
    }.getOrDefault(mutableListOf())

    @Synchronized
    fun addDiag(e: JSONObject) {
        val a = JSONArray(); (diag() + e).takeLast(30).forEach { a.put(it) }
        prefs.edit().putString("diag", a.toString()).apply()
    }

    // ---------- Demandes de temps ----------

    @Synchronized
    fun requests(): MutableList<JSONObject> = runCatching {
        val a = JSONArray(prefs.getString("requests", "[]") ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
    }.getOrDefault(mutableListOf())

    @Synchronized
    fun saveRequests(list: List<JSONObject>) {
        val a = JSONArray(); list.takeLast(20).forEach { a.put(it) }
        prefs.edit().putString("requests", a.toString()).apply()
    }

    companion object {
        @Volatile private var inst: BoxStore? = null
        fun get(c: Context): BoxStore = inst ?: synchronized(this) { inst ?: BoxStore(c.applicationContext).also { inst = it } }
    }
}
