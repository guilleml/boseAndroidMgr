// Conexión RFCOMM con los auriculares (equivalente a BoseKit/BoseConnection.swift).
//
// Modelo de hilos (lo explico porque en Android no hay un run loop como en IOBluetooth):
//  - BluetoothSocket es bloqueante, como un java.net.Socket: connect() y read() bloquean.
//  - Un hilo propio abre el socket y luego se queda leyendo. Cada mensaje se entrega al
//    `Listener` EN ESE HILO; quien lo use debe pasarlo al hilo principal (lo hace el ViewModel).
//  - Las escrituras van a un executor de un solo hilo para no bloquear la interfaz y para
//    que salgan en orden.
package local.boseqcultra2.android.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import local.boseqcultra2.android.protocol.BoseMessage
import local.boseqcultra2.android.protocol.MessageParser
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Etiqueta de logcat para todo el tráfico: `adb logcat -s BoseUltra`. */
const val LOG_TAG = "BoseUltra"

class BoseConnection(
    private val device: BluetoothDevice,
    private val listener: Listener,
    private val connectTimeoutMs: Long = 10_000,
) {
    interface Listener {
        fun onOpened()
        /** Fallo al abrir o canal cerrado después de abierto. `wasOpen` distingue ambos casos. */
        fun onClosed(reason: String, wasOpen: Boolean)
        fun onMessage(message: BoseMessage)
        fun onTrace(line: String)
    }

    companion object {
        /** Servicio SerialPort (0x1101). En los auriculares es el registro SDP del canal 2 (§1). */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    // @Volatile: lo leen y escriben hilos distintos (mismo significado que en Java).
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var closedByUs = false
    @Volatile private var opened = false
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "bose-writer") }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "bose-watchdog") }

    /** Abre el canal en segundo plano. El resultado llega por onOpened() u onClosed(). */
    @SuppressLint("MissingPermission") // El llamador comprueba BLUETOOTH_CONNECT antes.
    fun open() {
        Thread({ run() }, "bose-reader").start()
    }

    @SuppressLint("MissingPermission")
    private fun run() {
        val s: BluetoothSocket
        try {
            trace("Abriendo RFCOMM (SDP UUID $SPP_UUID)…")
            s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket = s
            // connect() no admite plazo; si en connectTimeoutMs no ha terminado, cerramos
            // el socket desde otro hilo y connect() lanza IOException.
            val timeout = watchdog.schedule({
                if (!opened) {
                    trace("Sin respuesta en ${connectTimeoutMs / 1000} s; se cancela la apertura")
                    closeSocketQuietly()
                }
            }, connectTimeoutMs, TimeUnit.MILLISECONDS)
            s.connect()
            timeout.cancel(false)
            opened = true
        } catch (e: IOException) {
            finish("apertura fallida: ${e.message}", wasOpen = false)
            return
        } catch (e: SecurityException) {
            finish("sin permiso BLUETOOTH_CONNECT", wasOpen = false)
            return
        }

        trace("Canal abierto (maxTx ${s.maxTransmitPacketSize}, maxRx ${s.maxReceivePacketSize})")
        listener.onOpened()

        val parser = MessageParser()
        val buf = ByteArray(1024)
        try {
            val input = s.inputStream
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                for (m in parser.feed(buf, n)) {
                    trace("← $m")
                    listener.onMessage(m)
                }
            }
            finish("fin del flujo", wasOpen = true)
        } catch (e: IOException) {
            finish(if (closedByUs) "cerrado por la app" else "lectura: ${e.message}", wasOpen = true)
        }
    }

    fun send(m: BoseMessage) {
        val s = socket ?: return
        // try/catch: si el executor ya está parado (conexión cerrada) se ignora el envío.
        try {
            writer.execute {
                try {
                    trace("→ $m")
                    s.outputStream.write(m.toBytes())
                    s.outputStream.flush()
                } catch (e: IOException) {
                    trace("Error al escribir ${m}: ${e.message}")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    fun close() {
        closedByUs = true
        closeSocketQuietly()
    }

    private fun finish(reason: String, wasOpen: Boolean) {
        closeSocketQuietly()
        writer.shutdown()
        watchdog.shutdownNow()
        trace("Canal cerrado: $reason")
        listener.onClosed(reason, wasOpen)
    }

    private fun closeSocketQuietly() {
        try { socket?.close() } catch (_: IOException) {}
    }

    private fun trace(line: String) {
        Log.d(LOG_TAG, line)
        listener.onTrace(line)
    }
}
