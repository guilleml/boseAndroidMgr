// Formato de mensaje del canal RFCOMM 2 (BoseMenuBar, docs/protocol.md §2):
//
//   [b0 bloque] [b1 función] [op] [longitud] [carga…]
//
// Kotlin puro, sin dependencias de Android: se prueba con JUnit en la JVM.
//
// Nota para Java: `Byte` en Kotlin tiene signo, igual que en Java (0xff = -1). Por eso la
// cabecera se guarda como `Int` 0..255 (más legible) y la carga como `ByteArray`, y para leer
// un byte de la carga sin signo se usa `payloadU(i)` (equivale a `b & 0xff` en Java).
package local.boseqcultra2.android.protocol

class BoseMessage(
    val block: Int,
    val function: Int,
    val op: Int,
    val payload: ByteArray = ByteArray(0),
) {
    init {
        require(block in 0..0xff && function in 0..0xff && op in 0..0xff) { "cabecera fuera de rango" }
        require(payload.size <= 0xff) { "la carga no cabe en el byte de longitud" }
    }

    /** Byte `i` de la carga, sin signo (0..255). */
    fun payloadU(i: Int): Int = payload[i].toInt() and 0xff

    fun toBytes(): ByteArray =
        byteArrayOf(block.toByte(), function.toByte(), op.toByte(), payload.size.toByte()) + payload

    fun matches(block: Int, function: Int, op: Int): Boolean =
        this.block == block && this.function == function && this.op == op

    // Un ByteArray es un array de Java: su equals() compara referencias. Como queremos
    // igualdad por contenido (lo que en Swift da el struct), se escribe a mano.
    // Por eso esto no es una `data class`: su equals generado usaría el del array.
    override fun equals(other: Any?): Boolean =
        other is BoseMessage && other.block == block && other.function == function &&
            other.op == op && other.payload.contentEquals(payload)

    override fun hashCode(): Int =
        ((block * 31 + function) * 31 + op) * 31 + payload.contentHashCode()

    override fun toString(): String {
        val head = Hex.format(byteArrayOf(block.toByte(), function.toByte(), op.toByte(), payload.size.toByte()))
        return if (payload.isEmpty()) head else "$head | ${Hex.format(payload)}"
    }
}

/**
 * Reensambla mensajes a partir de los trozos leídos del socket. Un trozo puede traer
 * varios mensajes, o la mitad de uno (observado en la captura).
 *
 * No es seguro entre hilos: úsalo desde un solo hilo (el lector del socket).
 */
class MessageParser {
    private var buffer = ByteArray(0)

    val pendingBytes: Int get() = buffer.size

    fun feed(data: ByteArray, length: Int = data.size): List<BoseMessage> {
        buffer += data.copyOfRange(0, length)
        val out = mutableListOf<BoseMessage>()
        var start = 0
        while (buffer.size - start >= 4) {
            val len = buffer[start + 3].toInt() and 0xff
            val total = 4 + len
            if (buffer.size - start < total) break
            out += BoseMessage(
                buffer[start].toInt() and 0xff,
                buffer[start + 1].toInt() and 0xff,
                buffer[start + 2].toInt() and 0xff,
                buffer.copyOfRange(start + 4, start + total),
            )
            start += total
        }
        if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
        return out
    }

    fun reset() {
        buffer = ByteArray(0)
    }
}

/** `object` = singleton; en Java sería una clase con miembros `static`. */
object Hex {
    fun format(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }

    /** "1f 03 01 00" → bytes. Para pruebas. */
    fun parse(hex: String): ByteArray =
        hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .map { it.toInt(16).toByte() }.toByteArray()
}
