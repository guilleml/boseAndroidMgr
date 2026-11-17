// Estado de los auriculares y conexión con ellos (equivalente a HeadphonesController.swift).
//
// Decisiones Android/Kotlin:
//  - ViewModel: sobrevive a los giros de pantalla (la Activity se destruye y se recrea; el
//    ViewModel no), así que la conexión no se corta al girar el móvil.
//  - El estado se guarda en `mutableStateOf` de Compose: al cambiar, Compose redibuja solo lo
//    que lo lee. Es como un campo con PropertyChangeSupport, pero automático.
//  - `by mutableStateOf(...)` es una propiedad delegada: el get/set lo implementa otro objeto.
//  - Todo el estado se modifica SOLO en el hilo principal (como el EDT de Swing). Los avisos
//    del hilo lector se reenvían con `main.post { … }`.
//
// Batería (prioridad del proyecto): a diferencia de macOS, el canal solo está abierto mientras
// la app está visible (onForeground/onBackground). En segundo plano no hay canal, ni consulta
// periódica, ni reintentos, ni receptores registrados.
package local.boseqcultra2.android

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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
    data class Retrying(val seconds: Int) : ConnectionStatus
    /** Reintentos agotados: se espera al aviso del sistema de que los auriculares se han conectado. */
    data object WaitingForDevice : ConnectionStatus
}

class HeadphonesViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        /** Modos que se muestran, buscados por nombre (no por índice). */
        val MENU_MODE_NAMES = listOf("Quiet", "Aware", "Immersion")
        /** Banda 0 = Graves [confirmado]; 1 = Medio y 2 = Agudos [hipótesis por orden de la app oficial]. */
        val EQ_BAND_NAMES = mapOf(0 to "Graves", 1 to "Medio", 2 to "Agudos")
        /** El usuario tiene emparejados también los Ultra de 1.ª gen. («Bose QC Ultra Earbuds»). */
        private val NAME_FILTERS = listOf("Bose", "Ultra 2")
        private const val MAX_TRAFFIC_LINES = 300
        /** Reintentos tras perder la conexión (s); después, se espera al aviso del sistema. */
        private val RETRY_DELAYS_S = listOf(2, 5, 10, 30)
        private const val POLL_INTERVAL_MS = 60_000L
        private const val ACK_TIMEOUT_MS = 3_000L
        /** Margen tras el aviso de conexión del sistema para que monte los perfiles de audio. */
        private const val AFTER_SYSTEM_CONNECT_MS = 2_000L
        /** Máximo que gira el indicador de «deslizar para actualizar». */
        private const val REFRESH_TIMEOUT_MS = 12_000L
    }

    var status by mutableStateOf<ConnectionStatus>(ConnectionStatus.Idle); private set
    var lastError by mutableStateOf<String?>(null); private set
    var deviceName by mutableStateOf<String?>(null); private set
    var modes by mutableStateOf<List<ModeSlot>>(emptyList()); private set
    var currentModeIndex by mutableStateOf<Int?>(null); private set
    var battery by mutableStateOf<List<BatteryEntry>>(emptyList()); private set
    /** Valores CONFIRMADOS por los auriculares (última respuesta `01 07 03`). */
    var equalizer by mutableStateOf<List<EqBand>>(emptyList()); private set
    /** Modo pedido cuya confirmación (`1f 03 06` o `1f 03 03`) aún no ha llegado. */
    var pendingModeIndex by mutableStateOf<Int?>(null); private set
    /** Valores de EQ enviados cuya confirmación aún no ha llegado (banda → valor). */
    val pendingEq = mutableStateMapOf<Int, Int>()
    /** Actualización pedida con el gesto de deslizar hacia abajo, en curso. */
    var refreshing by mutableStateOf(false); private set
    val traffic = mutableStateListOf<String>()

    private val main = Handler(Looper.getMainLooper())
    private var connection: BoseConnection? = null
    private var device: BluetoothDevice? = null
    private var collectingModes: MutableList<ModeSlot>? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    private var foreground = false
    private var hasPermission = false
    private var retryIndex = 0

    // Runnables con nombre para poder cancelarlos con main.removeCallbacks(...).
    private val retryRunnable = Runnable { connect() }
    private val refreshTimeout = Runnable { refreshing = false }
    private val pollRunnable = object : Runnable {
        override fun run() {
            connection?.send(BoseCommand.getCurrentMode)
            connection?.send(BoseCommand.dumpBlock02)
            main.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // ---------------------------------------------------------------- Datos derivados

    // Propiedades calculadas: sin campo detrás, como un getter de Java.
    val isConnected: Boolean get() = status == ConnectionStatus.Connected
    val leftPercent: Int? get() = battery.firstOrNull { it.componentId == BatteryEntry.LEFT }?.percent
    val rightPercent: Int? get() = battery.firstOrNull { it.componentId == BatteryEntry.RIGHT }?.percent
    val casePercent: Int? get() = battery.firstOrNull { it.componentId == BatteryEntry.CASE }?.percent
    val selectableModes: List<ModeSlot>
        get() = MENU_MODE_NAMES.mapNotNull { name -> modes.firstOrNull { it.name == name } }

    /** Si el modo activo no es Quiet/Aware/Immersion (p. ej. uno personalizado), su nombre. */
    val currentOtherModeName: String?
        get() {
            val i = currentModeIndex ?: return null
            if (selectableModes.any { it.index == i }) return null
            return modes.firstOrNull { it.index == i }?.name ?: "índice %02x".format(i)
        }

    // ---------------------------------------------------------------- Ciclo de vida

    /** La Activity pasa a ser visible (onStart). */
    fun onForeground(hasPermission: Boolean) {
        foreground = true
        this.hasPermission = hasPermission
        registerSystemReceiver()
        retryIndex = 0
        connect()
    }

    /** La Activity deja de ser visible (onStop, salvo giro de pantalla): se libera todo. */
    fun onBackground() {
        foreground = false
        unregisterSystemReceiver()
        main.removeCallbacks(retryRunnable)
        dropConnection()
        status = ConnectionStatus.Idle
        Log.d(LOG_TAG, "App en segundo plano: canal cerrado")
    }

    /** Botón «Reconectar»: reinicia la secuencia de reintentos. */
    fun reconnectNow() {
        retryIndex = 0
        connect()
    }

    // ---------------------------------------------------------------- Conexión

    @SuppressLint("MissingPermission") // hasPermission se comprueba antes de tocar el adaptador.
    private fun connect() {
        main.removeCallbacks(retryRunnable)
        if (!foreground || connection != null) return
        if (!hasPermission) { status = ConnectionStatus.NoPermission; return }
        val adapter = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { status = ConnectionStatus.BluetoothOff; return }

        val candidates = adapter.bondedDevices.filter { d ->
            val n = d.name ?: ""
            NAME_FILTERS.all { n.contains(it, ignoreCase = true) }
        }
        val d = candidates.firstOrNull()
        Log.d(LOG_TAG, "Emparejados que encajan: ${candidates.map { it.name }}; se usa: ${d?.name}")
        if (d == null) { status = ConnectionStatus.NotPaired; return }
        device = d
        deviceName = d.name
        status = ConnectionStatus.Connecting

        // `object : Interfaz { … }` = clase anónima de Java.
        // `lateinit`: se asigna justo después; el listener comprueba que los avisos vienen de
        // la conexión actual y no de una anterior ya abandonada.
        lateinit var c: BoseConnection
        c = BoseConnection(d, object : BoseConnection.Listener {
            override fun onOpened() { main.post { if (connection === c) opened() } }
            override fun onClosed(reason: String, wasOpen: Boolean) { main.post { if (connection === c) connectionLost(reason) } }
            override fun onMessage(message: BoseMessage) { main.post { if (connection === c) handle(message) } }
            override fun onTrace(line: String) {
                val stamped = "${timeFormat.format(Date())}  $line"
                main.post { addTraffic(stamped) }
            }
        })
        connection = c
        c.open()
    }

    private fun opened() {
        status = ConnectionStatus.Connected
        lastError = null
        retryIndex = 0
        refresh()
        main.removeCallbacks(pollRunnable)
        main.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun connectionLost(reason: String) {
        Log.i(LOG_TAG, "Conexión perdida: $reason")
        dropConnection()
        lastError = reason
        finishRefresh()
        if (!foreground) { status = ConnectionStatus.Idle; return }
        if (retryIndex >= RETRY_DELAYS_S.size) {
            status = ConnectionStatus.WaitingForDevice
            return
        }
        val delay = RETRY_DELAYS_S[retryIndex++]
        status = ConnectionStatus.Retrying(delay)
        main.postDelayed(retryRunnable, delay * 1000L)
    }

    /** Cierra el canal sin disparar la lógica de reintentos (connection = null antes de cerrar). */
    private fun dropConnection() {
        main.removeCallbacks(pollRunnable)
        val c = connection
        connection = null
        c?.close()
        collectingModes = null
        pendingModeIndex = null
        pendingEq.clear()
    }

    // ---------------------------------------------------------------- Avisos del sistema

    /**
     * ACL_CONNECTED / ACL_DISCONNECTED del sistema. Registrado solo mientras la app es visible.
     * Equivale a register(forConnectNotifications:) de IOBluetooth en la app de macOS.
     */
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // IntentCompat: la versión con tipo de getParcelableExtra es de API 33; minSdk es 31.
            val d = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (d == null && intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (connection != null || !isOurDevice(d!!)) return
                    Log.i(LOG_TAG, "El sistema avisa de que los auriculares se han conectado")
                    retryIndex = 0
                    main.removeCallbacks(retryRunnable)
                    main.postDelayed(retryRunnable, AFTER_SYSTEM_CONNECT_MS)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (connection != null && isOurDevice(d!!)) connectionLost("el sistema avisa de desconexión")
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (state == BluetoothAdapter.STATE_ON) reconnectNow()
                }
            }
        }
    }
    private var systemReceiverRegistered = false

    @SuppressLint("MissingPermission")
    private fun isOurDevice(d: BluetoothDevice): Boolean {
        val known = device
        if (known != null) return d.address == known.address // la MAC solo se compara, no se registra
        val n = try { d.name ?: "" } catch (_: SecurityException) { "" }
        return NAME_FILTERS.all { n.contains(it, ignoreCase = true) }
    }

    private fun registerSystemReceiver() {
        if (systemReceiverRegistered || !hasPermission) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        // EXPORTED a propósito: son broadcasts protegidos (solo los puede emitir el sistema), así
        // que nadie más puede falsificarlos. Con NOT_EXPORTED, en Android 12 ContextCompat exigiría
        // al emisor un permiso propio de la app que el proceso Bluetooth no tiene.
        ContextCompat.registerReceiver(getApplication(), systemReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        systemReceiverRegistered = true
    }

    private fun unregisterSystemReceiver() {
        if (!systemReceiverRegistered) return
        getApplication<Application>().unregisterReceiver(systemReceiver)
        systemReceiverRegistered = false
    }

    // ---------------------------------------------------------------- Lecturas

    fun refresh() {
        val c = connection ?: return
        // Mismo orden que la app de macOS. Todos son de lectura.
        c.send(BoseCommand.dumpBlock01)
        c.send(BoseCommand.listModes)
        c.send(BoseCommand.getCurrentMode)
        c.send(BoseCommand.dumpBlock02)
    }

    /**
     * Gesto de deslizar hacia abajo. Conectado: vuelve a leer todo (EQ, modos, modo, batería).
     * Sin conexión: reconecta ya (reinicia los reintentos); al abrir el canal se lee todo.
     * El indicador se apaga al llegar `02 01 06 00` (fin del volcado de batería, la última
     * respuesta de refresh), si se pierde la conexión o a los 12 s.
     */
    fun userRefresh() {
        refreshing = true
        main.removeCallbacks(refreshTimeout)
        main.postDelayed(refreshTimeout, REFRESH_TIMEOUT_MS)
        if (connection != null && isConnected) refresh() else reconnectNow()
        if (status !is ConnectionStatus.Connecting && !isConnected) finishRefresh() // no se pudo ni empezar
    }

    private fun finishRefresh() {
        refreshing = false
        main.removeCallbacks(refreshTimeout)
    }

    fun clearTraffic() = traffic.clear()

    // ---------------------------------------------------------------- Cambios de estado
    // Los dos CAMBIAN EL ESTADO de los auriculares.

    /** §3.3. El modo mostrado solo cambia cuando llega la confirmación de los auriculares. */
    fun setMode(index: Int) {
        val c = connection ?: return
        if (index == currentModeIndex || pendingModeIndex != null) return
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
        }, ACK_TIMEOUT_MS)
    }

    /**
     * §5. Solo se envía si el valor está dentro del rango que informan los propios auriculares.
     * La interfaz marca el valor como pendiente hasta que llega `01 07 03`.
     */
    fun setEqualizer(band: Int, value: Int) {
        val c = connection ?: return
        val current = equalizer.firstOrNull { it.index == band } ?: return
        val shown = pendingEq[band] ?: current.value
        if (value == shown || value !in current.min..current.max) return
        Log.i(LOG_TAG, "Cambio de EQ pedido: banda $band = $value")
        pendingEq[band] = value
        c.send(BoseCommand.setEqualizer(band, value))
        main.postDelayed({
            if (pendingEq[band] == value) {
                Log.w(LOG_TAG, "Sin confirmación del EQ banda $band = $value; se relee")
                pendingEq.remove(band)
                connection?.send(BoseCommand.dumpBlock01)
            }
        }, ACK_TIMEOUT_MS)
    }

    // ---------------------------------------------------------------- Mensajes recibidos

    private fun addTraffic(line: String) {
        traffic.add(line)
        if (traffic.size > MAX_TRAFFIC_LINES) traffic.removeRange(0, traffic.size - MAX_TRAFFIC_LINES)
    }

    /** docs/protocol.md. Mismo reparto que HeadphonesController.handle. */
    private fun handle(m: BoseMessage) {
        when {
            m.matches(0x02, 0x01, 0x06) -> finishRefresh() // fin del volcado del bloque 02 (§4)
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
                BoseDecode.equalizer(m)?.let { bands ->
                    equalizer = bands
                    // Se da por confirmado lo pendiente que ya coincide con el estado recibido.
                    bands.forEach { b -> if (pendingEq[b.index] == b.value) pendingEq.remove(b.index) }
                    return
                }
                BoseDecode.battery(m)?.let { battery = it; return }
            }
        }
    }

    // ---------------------------------------------------------------- Depuración

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
            Log.i(LOG_TAG, "Orden de depuración: $cmd ${intent.extras?.let { e -> e.keySet().associateWith { e.getString(it) ?: e.getInt(it) } }} (conectado=${connection != null})")
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
            ContextCompat.registerReceiver(app, debugReceiver, IntentFilter("local.boseqcultra2.android.DEBUG"), ContextCompat.RECEIVER_EXPORTED)
        }
    }

    override fun onCleared() {
        if (debuggable) getApplication<Application>().unregisterReceiver(debugReceiver)
        unregisterSystemReceiver()
        main.removeCallbacks(retryRunnable)
        dropConnection()
        Log.d(LOG_TAG, "ViewModel destruido; se cierra la conexión")
    }
}
