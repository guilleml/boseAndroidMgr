// Tile de Ajustes rápidos.
//
// Consumo: el tile NO abre el canal con los auriculares. Al desplegar el panel solo lee la caché
// (StateCache) y pregunta al sistema si hay audio Bluetooth conectado; ninguna de las dos cosas
// usa la radio. Al pulsarlo abre ModePickerActivity, que es quien conecta y cambia el modo.
//
// Un TileService es un Service que el sistema enlaza (bind) mientras el panel está visible:
// onStartListening/onStopListening marcan ese intervalo.
package io.github.guilleml.boseandroidmgr.tile

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.guilleml.boseandroidmgr.MainActivity
import io.github.guilleml.boseandroidmgr.ModePickerActivity
import io.github.guilleml.boseandroidmgr.R
import io.github.guilleml.boseandroidmgr.StateCache

class BoseTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val cached = StateCache.load(this)

        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.label = cached.modeName ?: "Bose Ultra"
        tile.subtitle = batteryText(cached.left, cached.right, cached.case)
        tile.state = when {
            !hasPermission() -> Tile.STATE_INACTIVE
            audioConnected() -> Tile.STATE_ACTIVE
            else -> Tile.STATE_UNAVAILABLE // sin auriculares conectados no se puede cambiar el modo
        }
        tile.stateDescription = if (tile.state == Tile.STATE_ACTIVE) "Conectados" else "Desconectados"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        // Sin permiso, se abre la app para concederlo; si no, el diálogo de modos.
        val target = if (hasPermission()) ModePickerActivity::class.java else MainActivity::class.java
        val intent = Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            // Desde Android 14 hay que pasar un PendingIntent; la variante con Intent lanza excepción.
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            startActivityAndCollapseLegacy(intent)
        }
    }

    /** Android 12–13: solo existe la variante con Intent (obsoleta desde la 14, donde no se usa). */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseLegacy(intent: Intent) = startActivityAndCollapse(intent)

    private fun hasPermission() =
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * ¿Hay algún dispositivo de audio Bluetooth conectado? Consulta el estado que ya tiene el
     * sistema, sin radio. Es aproximado: no distingue si es otro dispositivo de audio.
     */
    @SuppressLint("MissingPermission") // se comprueba hasPermission() antes
    private fun audioConnected(): Boolean {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled) return false
        return adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothAdapter.STATE_CONNECTED ||
            adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothAdapter.STATE_CONNECTED
    }

    companion object {
        /** «I 100 · D 100 · E 80 %», con «–» si no hay dato. */
        fun batteryText(left: Int?, right: Int?, case: Int?): String {
            fun v(p: Int?) = p?.toString() ?: "–"
            return "I ${v(left)} · D ${v(right)} · E ${v(case)} %"
        }
    }
}
