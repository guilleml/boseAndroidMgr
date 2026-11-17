// Comandos y decodificadores. Solo lo documentado en BoseMenuBar/docs/protocol.md;
// cada elemento cita su sección. Portado de tools/bt-control/Sources/BoseKit/BoseProtocol.swift.
package io.github.guilleml.boseandroidmgr.protocol

/** Peticiones (§2–§5). */
object BoseCommand {
    /** §3.2: `1f 03 01 00` → `1f 03 03 01 <idx>`. */
    val getCurrentMode = BoseMessage(0x1f, 0x03, 0x01)

    /** §3.1: `1f 06 05 00` → `1f 06 07 00`, N × `1f 06 03 30 …`, `1f 06 06 00` al final. */
    val listModes = BoseMessage(0x1f, 0x06, 0x05)

    /** §4: `02 01 05 00` → volcado del bloque 02, que incluye `02 02 03` (batería). */
    val dumpBlock02 = BoseMessage(0x02, 0x01, 0x05)

    /** §5: `01 01 05 00` → volcado del bloque 01, que incluye `01 07 03` (ecualizador). */
    val dumpBlock01 = BoseMessage(0x01, 0x01, 0x05)

    /**
     * §3.3: `1f 03 05 02 <idx> 01` → `1f 03 06 01 <idx>` (y/o `1f 03 03 01 <idx>`).
     * CAMBIA EL ESTADO de los auriculares. No se usa en la fase 1.
     */
    fun setMode(index: Int): BoseMessage {
        require(index in 0..0xff)
        return BoseMessage(0x1f, 0x03, 0x05, byteArrayOf(index.toByte(), 0x01))
    }

    /**
     * §5: `01 07 02 02 <valor> <banda>` → `01 07 03 0c` con las 3 bandas.
     * Valor: entero con signo de 8 bits (verificado: −1 = `ff`).
     * CAMBIA EL ESTADO de los auriculares. No se usa en la fase 1.
     */
    fun setEqualizer(band: Int, value: Int): BoseMessage {
        require(band in 0..0xff)
        require(value in -128..127)
        // toByte() de un Int negativo da el complemento a dos, igual que el cast (byte) de Java.
        return BoseMessage(0x01, 0x07, 0x02, byteArrayOf(value.toByte(), band.toByte()))
    }
}

data class ModeSlot(val index: Int, val name: String)

/**
 * §5: grupo `[mín] [máx] [valor] [banda]`. Banda 0 = Graves [confirmado];
 * 1 = Medio y 2 = Agudos [hipótesis por orden de la app oficial].
 */
data class EqBand(val index: Int, val value: Int, val min: Int, val max: Int)

/**
 * §4: grupo `[nivel %] ff ff [id]`. id 03 = estuche [hipótesis fuerte];
 * 01 = derecho, 02 = izquierdo [hipótesis]; 04 = desconocido.
 */
data class BatteryEntry(val componentId: Int, val percent: Int) {
    companion object {
        const val RIGHT = 0x01
        const val LEFT = 0x02
        const val CASE = 0x03
    }
}

object BoseDecode {
    /** §3.2: `1f 03 03 01 <idx>`. Devuelve null si el mensaje no es ese (como Optional vacío). */
    fun currentMode(m: BoseMessage): Int? =
        if (m.matches(0x1f, 0x03, 0x03) && m.payload.size == 1) m.payloadU(0) else null

    /** §3.3: confirmación de cambio de modo: `1f 03 06 01 <idx>` o `1f 03 03 01 <idx>`. */
    fun modeChangeAck(m: BoseMessage): Int? =
        if ((m.matches(0x1f, 0x03, 0x06) || m.matches(0x1f, 0x03, 0x03)) && m.payload.size == 1) m.payloadU(0)
        else null

    /** §3.1: inicio (`1f 06 07`) y fin (`1f 06 06`) de la lista de modos. */
    fun isModeListStart(m: BoseMessage) = m.matches(0x1f, 0x06, 0x07)
    fun isModeListEnd(m: BoseMessage) = m.matches(0x1f, 0x06, 0x06)

    /**
     * §3.1: `1f 06 03 30`: índice = byte 0 de la carga; nombre ASCII de hasta 32 bytes desde
     * el byte 6 (contando desde 0; el «byte 7» de docs/protocol.md cuenta desde 1), terminado en 00.
     * Las ranuras vacías («None») se descartan.
     */
    fun modeSlot(m: BoseMessage): ModeSlot? {
        if (!m.matches(0x1f, 0x06, 0x03) || m.payload.size < 7) return null
        val nameBytes = m.payload.copyOfRange(6, minOf(m.payload.size, 6 + 32)).takeWhile { it != 0.toByte() }
        val name = nameBytes.toByteArray().toString(Charsets.UTF_8)
        if (name.isEmpty() || name == "None") return null
        return ModeSlot(m.payloadU(0), name)
    }

    /** §5: `01 07 03 <n×4>`, enteros con signo de 8 bits. */
    fun equalizer(m: BoseMessage): List<EqBand>? {
        if (!m.matches(0x01, 0x07, 0x03) || m.payload.isEmpty() || m.payload.size % 4 != 0) return null
        val p = m.payload
        // Byte.toInt() conserva el signo (0xf6 → −10), como en Java.
        return (p.indices step 4).map { i ->
            EqBand(index = m.payloadU(i + 3), value = p[i + 2].toInt(), min = p[i].toInt(), max = p[i + 1].toInt())
        }
    }

    /** §4: `02 02 03 <n×4>` con grupos `[nivel] ff ff [id]`. */
    fun battery(m: BoseMessage): List<BatteryEntry>? {
        if (!m.matches(0x02, 0x02, 0x03) || m.payload.isEmpty() || m.payload.size % 4 != 0) return null
        return (m.payload.indices step 4).map { i -> BatteryEntry(m.payloadU(i + 3), m.payloadU(i)) }
    }
}
