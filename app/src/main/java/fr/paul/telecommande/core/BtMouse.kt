package fr.paul.telecommande.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Vraie souris : le téléphone se présente au Player comme une souris Bluetooth (profil HID, Android 9+).
 * Le Player affiche alors son pointeur système, exactement comme avec une souris branchée.
 * Aucune appli ni mode développeur côté TV.
 */
@SuppressLint("MissingPermission")   // les permissions Bluetooth sont demandées par l'écran avant tout appel
object BtMouse {
    enum class State { UNSUPPORTED, OFF, IDLE, REGISTERING, READY, CONNECTING, CONNECTED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    /** La TV a accepté puis aussitôt coupé la connexion (relation Bluetooth à refaire depuis la TV). */
    private val _refused = MutableStateFlow(false)
    val refused: StateFlow<Boolean> = _refused
    private var connectedAt = 0L
    private val _hostName = MutableStateFlow("")
    val hostName: StateFlow<String> = _hostName

    val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "bt-mouse").apply { isDaemon = true } }
    private var impl: Any? = null   // Impl (API 28+), gardé en Any pour rester compatible Android 5

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("btmouse", Context.MODE_PRIVATE)
    fun savedHost(c: Context): String? = prefs(c).getString("host", null)

    fun adapter(c: Context): BluetoothAdapter? = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Prépare le profil souris (à appeler une fois les permissions accordées). */
    fun start(c: Context) {
        if (!supported) { _state.value = State.UNSUPPORTED; return }
        val a = adapter(c) ?: run { _state.value = State.UNSUPPORTED; return }
        if (!a.isEnabled) { _state.value = State.OFF; return }
        if (impl != null && _state.value >= State.READY) return
        (impl as? Impl ?: Impl(c.applicationContext).also { impl = it }).open()
    }

    /** Se connecter à la TV choisie (la relie au passage si ce n'est pas déjà fait). */
    fun connect(c: Context, address: String) {
        if (!supported) return
        prefs(c).edit().putString("host", address).apply()
        (impl as? Impl)?.connectTo(address)
    }

    fun disconnect() { if (supported) (impl as? Impl)?.disconnect() }

    fun stop() { if (supported) (impl as? Impl)?.close(); impl = null; _state.value = State.IDLE }

    // ---------- Envoi des mouvements ----------

    private var buttons = 0
    private var restX = 0f; private var restY = 0f

    /** Déplacement relatif (en unités souris). Les décimales sont reportées pour un mouvement fluide. */
    fun move(dx: Float, dy: Float) {
        restX += dx; restY += dy
        var ix = restX.roundToInt(); var iy = restY.roundToInt()
        if (ix == 0 && iy == 0) return
        restX -= ix; restY -= iy
        while (ix != 0 || iy != 0) {
            val sx = ix.coerceIn(-127, 127); val sy = iy.coerceIn(-127, 127)
            report(sx, sy, 0); ix -= sx; iy -= sy
        }
    }

    fun scroll(steps: Int) { if (steps != 0) report(0, 0, steps.coerceIn(-127, 127)) }

    /** Clic gauche (1) ; clic droit (2) = « Retour » sur Android. */
    fun click(button: Int = 1) {
        buttons = button; report(0, 0, 0)
        buttons = 0; report(0, 0, 0)
    }

    private fun report(dx: Int, dy: Int, wheel: Int) {
        if (!supported) return
        val i = impl as? Impl ?: return
        val data = byteArrayOf(buttons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte())
        io.execute { i.send(data) }
    }

    /** Souris 3 boutons + X/Y/molette relatifs, rapport n°1. */
    private val DESCRIPTOR = byteArrayOf(
        0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
        0x09, 0x01, 0xA1.toByte(), 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03, 0x81.toByte(), 0x06,
        0xC0.toByte(), 0xC0.toByte()
    )

    @RequiresApi(Build.VERSION_CODES.P)
    private class Impl(val ctx: Context) {
        var hid: BluetoothHidDevice? = null
        var host: BluetoothDevice? = null
        var pendingConnect: String? = null

        val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                DiagLog.log("BT", "Profil souris ${if (registered) "enregistré" else "retiré"}")
                if (!registered) { _state.value = State.IDLE; return }
                _state.value = State.READY
                val target = pendingConnect ?: pluggedDevice?.address ?: savedHost(ctx)
                pendingConnect = null
                target?.let { connectTo(it) }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                DiagLog.log("BT", "Connexion ${device?.name ?: device?.address} : état $state")
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedAt = System.currentTimeMillis(); _refused.value = false
                        DiagLog.log("BT", "Relation : bond=${device?.bondState} classe=${device?.bluetoothClass}")
                        host = device; _hostName.value = device?.name ?: "TV"
                        prefs(ctx).edit().putString("host", device?.address).apply()
                        _state.value = State.CONNECTED
                    }
                    BluetoothProfile.STATE_CONNECTING -> _state.value = State.CONNECTING
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (connectedAt > 0 && System.currentTimeMillis() - connectedAt < 3_000) {
                            _refused.value = true
                            DiagLog.log("BT", "La TV a coupé la souris juste après l'avoir acceptée (relation à refaire depuis la TV)")
                        }
                        connectedAt = 0
                        if (device == host) host = null; if (_state.value != State.IDLE) _state.value = State.READY }
                }
            }
        }

        fun open() {
            _state.value = State.REGISTERING
            val a = adapter(ctx) ?: return
            a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val h = proxy as BluetoothHidDevice
                    hid = h
                    val sdp = BluetoothHidDeviceAppSdpSettings(
                        "Souris tactile", "Souris et pavé tactile", "Contrat parental",
                        BluetoothHidDevice.SUBCLASS1_MOUSE, DESCRIPTOR
                    )
                    val ok = runCatching { h.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), callback) }.getOrDefault(false)
                    DiagLog.log("BT", "Enregistrement du profil souris : $ok")
                    if (!ok) _state.value = State.UNSUPPORTED
                }
                override fun onServiceDisconnected(profile: Int) { hid = null; _state.value = State.IDLE }
            }, BluetoothProfile.HID_DEVICE)
        }

        fun connectTo(address: String) {
            val h = hid
            if (h == null || _state.value < State.READY) { pendingConnect = address; return }
            val dev = runCatching { adapter(ctx)?.getRemoteDevice(address) }.getOrNull() ?: return
            _state.value = State.CONNECTING
            val ok = runCatching { h.connect(dev) }.getOrDefault(false)
            DiagLog.log("BT", "Demande de connexion à ${dev.name ?: address} : $ok")
            if (!ok) _state.value = State.READY
        }

        fun disconnect() { host?.let { d -> runCatching { hid?.disconnect(d) } } }

        fun send(data: ByteArray) {
            val h = hid ?: return; val d = host ?: return
            runCatching { h.sendReport(d, 1, data) }
        }

        fun close() {
            runCatching { host?.let { hid?.disconnect(it) } }
            runCatching { hid?.unregisterApp() }
            runCatching { adapter(ctx)?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) }
            hid = null; host = null
        }
    }
}
