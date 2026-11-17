// Pantalla mínima de las fases 1–2 (lectura, cambio de modo y −/+ del EQ). La interfaz completa llega en la fase 3.
//
// Compose para quien viene de Java/Swing: cada función @Composable describe la interfaz
// a partir del estado; no se crean ni se modifican widgets. Cuando cambia un estado que la
// función ha leído (p. ej. vm.battery), Compose la vuelve a ejecutar ("recomposición").
package local.boseqcultra2.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.boseqcultra2.android.ConnectionStatus
import local.boseqcultra2.android.HeadphonesViewModel

@Composable
fun MainScreen(vm: HeadphonesViewModel, onConnect: () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Scaffold { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(vm.deviceName ?: "Bose QC Ultra Earbuds", style = MaterialTheme.typography.titleLarge)
                Text(statusText(vm.status), style = MaterialTheme.typography.bodyMedium)

                val connected = vm.status == ConnectionStatus.Connected
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (connected) {
                        Button(onClick = vm::refresh) { Text("Volver a leer") }
                        OutlinedButton(onClick = vm::disconnect) { Text("Desconectar") }
                    } else if (vm.status != ConnectionStatus.Connecting) {
                        Button(onClick = onConnect) { Text("Conectar") }
                    }
                    OutlinedButton(onClick = vm::clearTraffic) { Text("Borrar registro") }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Section("Batería")
                        Text("Izquierdo ${pct(vm.leftPercent)}   Derecho ${pct(vm.rightPercent)}   Estuche ${pct(vm.casePercent)}")
                        Spacer(Modifier.height(4.dp))
                        Section("Modo")
                        Text("Actual: ${vm.currentModeName ?: "–"}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (slot in vm.selectableModes) {
                                val active = slot.index == vm.currentModeIndex
                                val pending = slot.index == vm.pendingModeIndex
                                // Un botón relleno marca el modo confirmado por los auriculares.
                                if (active) Button(onClick = {}) { Text(slot.name) }
                                else OutlinedButton(onClick = { vm.setMode(slot.index) }, enabled = connected && vm.pendingModeIndex == null) {
                                    Text(if (pending) "${slot.name}…" else slot.name)
                                }
                            }
                        }
                        Text("Todas las ranuras: " + vm.modes.joinToString { "%02x %s".format(it.index, it.name) }.ifEmpty { "–" })
                        Spacer(Modifier.height(4.dp))
                        Section("Ecualizador")
                        if (vm.equalizer.isEmpty()) Text("–")
                        for (b in vm.equalizer) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(HeadphonesViewModel.EQ_BAND_NAMES[b.index] ?: "Banda ${b.index}", Modifier.width(72.dp))
                                OutlinedButton(onClick = { vm.setEqualizer(b.index, b.value - 1) }, enabled = connected && b.value > b.min) { Text("−") }
                                Text(signed(b.value), Modifier.width(36.dp), fontFamily = FontFamily.Monospace)
                                OutlinedButton(onClick = { vm.setEqualizer(b.index, b.value + 1) }, enabled = connected && b.value < b.max) { Text("+") }
                                Text("[${b.min}…${b.max}]", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Section("Tráfico (logcat: BoseUltra)")
                HorizontalDivider()
                val listState = rememberLazyListState()
                // Desplaza al final cuando llegan líneas nuevas.
                LaunchedEffect(vm.traffic.size) {
                    if (vm.traffic.isNotEmpty()) listState.scrollToItem(vm.traffic.size - 1)
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
                    items(vm.traffic) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

private fun pct(p: Int?) = p?.let { "$it %" } ?: "–"
private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

private fun statusText(s: ConnectionStatus): String = when (s) {
    ConnectionStatus.Idle -> "Sin conectar"
    ConnectionStatus.NoPermission -> "Falta el permiso de dispositivos cercanos (Bluetooth)"
    ConnectionStatus.BluetoothOff -> "El Bluetooth está desactivado"
    ConnectionStatus.NotPaired -> "No hay auriculares Bose emparejados"
    ConnectionStatus.Connecting -> "Conectando…"
    ConnectionStatus.Connected -> "Conectado"
    is ConnectionStatus.Disconnected -> "Desconectado: ${s.reason}"
}
