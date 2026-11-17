// Último estado leído de los auriculares, guardado en el móvil para el tile de Ajustes rápidos.
//
// El tile no abre el canal (batería): muestra lo último que leyó la app o el diálogo.
// SharedPreferences ≈ un java.util.Properties persistente que Android guarda en un XML privado.
package io.github.guilleml.boseandroidmgr

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.core.content.edit
import io.github.guilleml.boseandroidmgr.protocol.BatteryEntry
import io.github.guilleml.boseandroidmgr.protocol.ModeSlot
import io.github.guilleml.boseandroidmgr.tile.BoseTileService

data class CachedState(
    val modeIndex: Int?,
    val modeName: String?,
    /** Quiet/Aware/Immersion → índice, tal como los devolvieron los auriculares. */
    val selectableModes: List<ModeSlot>,
    val left: Int?, val right: Int?, val case: Int?,
    val batteryTime: Long?,
)

object StateCache {
    private const val PREFS = "estado"

    fun load(context: Context): CachedState {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun intOrNull(key: String) = if (p.contains(key)) p.getInt(key, 0) else null
        val modes = (p.getString("modes", null) ?: "").split(';').mapNotNull { e ->
            val parts = e.split('=', limit = 2)
            if (parts.size == 2) parts[1].toIntOrNull()?.let { ModeSlot(it, parts[0]) } else null
        }
        return CachedState(
            modeIndex = intOrNull("modeIndex"),
            modeName = p.getString("modeName", null),
            selectableModes = modes,
            left = intOrNull("left"), right = intOrNull("right"), case = intOrNull("case"),
            batteryTime = if (p.contains("batteryTime")) p.getLong("batteryTime", 0) else null,
        )
    }

    fun saveMode(context: Context, index: Int, name: String?) {
        // edit { } (AndroidX KTX) abre el editor y llama a apply() al final, que escribe en segundo plano.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt("modeIndex", index)
            putString("modeName", name)
        }
        updateTile(context)
    }

    /** Nombres sin «=» ni «;» (Quiet/Aware/Immersion). */
    fun saveSelectableModes(context: Context, modes: List<ModeSlot>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("modes", modes.joinToString(";") { "${it.name}=${it.index}" })
        }
    }

    fun saveBattery(context: Context, entries: List<BatteryEntry>) {
        fun pct(id: Int) = entries.firstOrNull { it.componentId == id }?.percent
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            // Si falta un componente (p. ej. auricular en el estuche), se borra: el tile muestra «–».
            listOf("left" to pct(BatteryEntry.LEFT), "right" to pct(BatteryEntry.RIGHT), "case" to pct(BatteryEntry.CASE))
                .forEach { (k, v) -> if (v != null) putInt(k, v) else remove(k) }
            putLong("batteryTime", System.currentTimeMillis())
        }
        updateTile(context)
    }

    /** Pide al sistema que vuelva a pintar el tile (si el panel está abierto o en su próxima apertura). */
    private fun updateTile(context: Context) {
        TileService.requestListeningState(context, ComponentName(context, BoseTileService::class.java))
    }
}
