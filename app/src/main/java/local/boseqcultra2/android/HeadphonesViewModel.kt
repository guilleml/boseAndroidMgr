// Estado de los auriculares (equivalente a HeadphonesController.swift, fase 1: solo lectura).
//
// Decisiones Android/Kotlin:
//  - ViewModel: sobrevive a los giros de pantalla (la Activity se destruye y se recrea; el
//    ViewModel no), así que la conexión no se corta al girar el móvil.
//  - El estado se guarda en `mutableStateOf` de Compose: al cambiar, Compose redibuja solo lo
//    que lo lee. Es como un campo con PropertyChangeSupport, pero automático.
//  - `by mutableStateOf(...)` es una propiedad delegada: el get/set lo implementa otro objeto.
//  - Todo el estado se modifica SOLO en el hilo principal (como el EDT de Swing). Los avisos
//    del hilo lector se reenvían con `main.post { … }`.
package local.boseqcultra2.android

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import local.boseqcultra2.android.bluetooth.BoseConnection
import local.boseqcultra2.android.bluetooth.LOG_TAG
import local.boseqcultra2.android.protocol.BatteryEntry
import local.boseqcultra2.android.protocol.BoseCommand
import local.boseqcultra2.android.protocol.BoseDecode
import local.boseqcultra2.android.protocol.BoseMessage
import local.boseqcultra2.android.protocol.EqBand
import local.boseqcultra2.android.protocol.ModeSlot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Estados de conexión. `sealed interface` ≈ jerarquía cerrada (como `sealed` de Java 17). */
sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data object NoPermission : ConnectionStatus
    data object BluetoothOff : ConnectionStatus
    data object NotPaired : ConnectionStatus
    data object Connecting : ConnectionStatus
    data object Connected : ConnectionStatus
    data class Disconnected(val reason: String) : ConnectionStatus
}

class HeadphonesViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        /** Modos que se muestran, buscados por nombre (no por índice). */
        val MENU_MODE_NAMES = listOf("Quiet", "Aware", "Immersion")
        val EQ_BAND_NAMES = mapOf(0 to "Graves", 1 to "Medio", 2 to "Agudos")
        /** El usuario tiene emparejados también los Ultra de 1.ª gen. («Bose QC Ultra Earbuds»). */
        private val NAME_FILTERS = listOf("Bose", "Ultra 2")
        private const val MAX_TRAFFIC_LINES = 300
    }

    var status by mutableStateOf<ConnectionStatus>(ConnectionStatus.Idle); private set
    var deviceName by mutableStateOf<String?>(null); private set
    var modes by mutableStateOf<List<ModeSlot>>(emptyList()); private set
    var currentModeIndex by mutableStateOf<Int?>(null); private set
    var battery by mutableStateOf<List<BatteryEntry>>(emptyList()); private set
    var equalizer by mutableStateOf<List<EqBand>>(emptyList()); private set
    /** Modo pedido cuya confirmación (`1f 03 06` o `1f 03 03`) aún no ha llegado. */
    var pendingModeIndex by mutableStateOf<Int?>(null); private set
    val traffic = mutableStateListOf<String>()

    private val main = Handler(Looper.getMainLooper())
    private var connection: BoseConnection? = null
    private var collectingModes: MutableList<ModeSlot>? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    /**
     * Solo en compilaciones de depuración: órdenes desde adb para las pruebas, aunque la
     * pantalla esté bloqueada (un broadcast llega al proceso; un intent a la Activity, no).
     *   adb shell am broadcast -a local.boseqcultra2.android.DEBUG -p local.boseqcultra2.android --es cmd mode --ei index 1
     *   … --es cmd eq --ei band 1 --ei value -1        … --es cmd refresh
     * Pasan por setMode / setEqualizer / refresh, con sus mismas comprobaciones.
     */
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cmd = intent.getStringExtra("cmd")
            val connected = connection != null
            Log.i(LOG_TAG, "Orden de depuración: $cmd ${intent.extras?.let { e -> e.keySet().associateWith { e.getString(it) ?: e.getInt(it) } }} (conectado=$connected)")
            when (cmd) {
                "mode" -> intent.getIntExtra("index", -1).takeIf { it >= 0 }?.let(::setMode)
                "eq" -> {
                    val band = intent.getIntExtra("band", -1)
                    val value = intent.getIntExtra("value", Int.MIN_VALUE)
                    if (band >= 0 && value != Int.MIN_VALUE) setEqualizer(band, value)
                }
                "refresh" -> refresh()
            }
        }
    }
    private val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    // `init` = cuerpo del constructor en Java.
    init {
        if (debuggable) {
            // RECEIVER_EXPORTED: tiene que aceptar emisores externos (el shell de adb).
            // Es de API 33; en 31–32 el flag se ignora y el receptor ya es exportado.
            @SuppressLint("InlinedApi")
            app.registerReceiver(debugReceiver, IntentFilter("local.boseqcultra2.android.DEBUG"), Context.RECEIVER_EXPORTED)
        }
    }

    // Datos derivados para la interfaz (propiedades calculadas: sin campo detrás, como un getter).
    val leftPercent: Int? get() = battery.firstOrNull { it.componentId == BatteryEntry.LEFT }?.percent
    val rightPercent: Int? get() = battery.firstOrNull { it.componentId == BatteryEntry.RIGHT }?.percent
    val casePercent: Int? get() = battery.firstOrNull { it.componentId == BatteryEntry.CASE }?.percent
    val selectableModes: List<ModeSlot>
        get() = MENU_MODE_NAMES.mapNotNull { name -> modes.firstOrNull { it.name == name } }
    val currentModeName: String?
        get() = currentModeIndex?.let { i -> modes.firstOrNull { it.index == i }?.name ?: "índice %02x".format(i) }

    @SuppressLint("MissingPermission")
    fun connect(hasPermission: Boolean) {
        if (connection != null) return
        if (!hasPermission) { status = ConnectionStatus.NoPermission; return }
        val adapter = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { status = ConnectionStatus.BluetoothOff; return }

        val candidates = adapter.bondedDevices.filter { d ->
            val n = d.name ?: ""
            NAME_FILTERS.all { n.contains(it, ignoreCase = true) }
        }
        val device: BluetoothDevice? = candidates.firstOrNull()
        Log.d(LOG_TAG, "Emparejados que encajan: ${candidates.map { it.name }}; se usa: ${device?.name}")
        if (device == null) { status = ConnectionStatus.NotPaired; return }
        deviceName = device.name
        status = ConnectionStatus.Connecting

        // `object : Interfaz { … }` = clase anónima de Java.
        // `lateinit`: se asigna justo después; el listener comprueba que los avisos vienen de
        // la conexión actual y no de una anterior ya abandonada.
        lateinit var c: BoseConnection
        c = BoseConnection(device, object : BoseConnection.Listener {
            override fun onOpened() { main.post { if (connection === c) opened() } }
            override fun onClosed(reason: String, wasOpen: Boolean) { main.post { if (connection === c) closed(reason) } }
            override fun onMessage(message: BoseMessage) { main.post { if (connection === c) handle(message) } }
            override fun onTrace(line: String) {
                val stamped = "${timeFormat.format(Date())}  $line"
                main.post { addTraffic(stamped) }
            }
        })
        connection = c
        c.open()
    }

    fun disconnect() {
        connection?.close()
    }

    fun refresh() {
        val c = connection ?: return
        // Mismo orden que la app de macOS. Todos son de lectura.
        c.send(BoseCommand.dumpBlock01)
        c.send(BoseCommand.listModes)
        c.send(BoseCommand.getCurrentMode)
        c.send(BoseCommand.dumpBlock02)
    }

    fun clearTraffic() = traffic.clear()

    // --- Cambios de estado (fase 2). Los dos CAMBIAN EL ESTADO de los auriculares. ---

    /** §3.3. El modo mostrado solo cambia cuando llega la confirmación de los auriculares. */
    fun setMode(index: Int) {
        val c = connection ?: return
        if (index == currentModeIndex || index == pendingModeIndex) return
        Log.i(LOG_TAG, "Cambio de modo pedido: índice %02x".format(index))
        pendingModeIndex = index
        c.send(BoseCommand.setMode(index))
        // Si en 3 s no llega la confirmación, se relee el modo real (igual que en macOS).
        main.postDelayed({
            if (pendingModeIndex == index) {
                Log.w(LOG_TAG, "Sin confirmación del cambio a %02x; se relee el modo".format(index))
                pendingModeIndex = null
                connection?.send(BoseCommand.getCurrentMode)
            }
        }, 3_000)
    }

    /**
     * §5. Solo se envía si el valor está dentro del rango que informan los propios auriculares.
     * La interfaz muestra el valor nuevo cuando llega `01 07 03`, no antes.
     */
    fun setEqualizer(band: Int, value: Int) {
        val c = connection ?: return
        val current = equalizer.firstOrNull { it.index == band } ?: return
        if (value == current.value || value !in current.min..current.max) return
        Log.i(LOG_TAG, "Cambio de EQ pedido: banda $band = $value")
        c.send(BoseCommand.setEqualizer(band, value))
    }

    private fun opened() {
        status = ConnectionStatus.Connected
        refresh()
    }

    private fun closed(reason: String) {
        connection = null
        collectingModes = null
        pendingModeIndex = null
        status = ConnectionStatus.Disconnected(reason)
    }

    private fun addTraffic(line: String) {
        traffic.add(line)
        if (traffic.size > MAX_TRAFFIC_LINES) traffic.removeRange(0, traffic.size - MAX_TRAFFIC_LINES)
    }

    /** Mensajes recibidos (docs/protocol.md). Mismo reparto que HeadphonesController.handle. */
    private fun handle(m: BoseMessage) {
        when {
            BoseDecode.isModeListStart(m) -> collectingModes = mutableListOf()
            BoseDecode.isModeListEnd(m) -> {
                collectingModes?.let { modes = it.toList() }
                collectingModes = null
            }
            else -> {
                BoseDecode.modeSlot(m)?.let { slot ->
                    val list = collectingModes
                    if (list != null) list += slot
                    else modes = modes.filter { it.index != slot.index } + slot
                    return
                }
                BoseDecode.modeChangeAck(m)?.let {
                    currentModeIndex = it
                    if (pendingModeIndex == it) pendingModeIndex = null
                    return
                }
                BoseDecode.equalizer(m)?.let { equalizer = it; return }
                BoseDecode.battery(m)?.let { battery = it; return }
            }
        }
    }

    override fun onCleared() {
        if (debuggable) getApplication<Application>().unregisterReceiver(debugReceiver)
        Log.d(LOG_TAG, "ViewModel destruido; se cierra la conexión")
        connection?.close()
    }
}
