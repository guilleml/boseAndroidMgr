// Pantalla principal: batería, modos y ecualizador (curva + campos), y registro de tráfico.
//
// Compose para quien viene de Java/Swing: cada función @Composable describe la interfaz
// a partir del estado; no se crean ni se modifican widgets. Cuando cambia un estado que la
// función ha leído (p. ej. vm.battery), Compose la vuelve a ejecutar ("recomposición").
package io.github.guilleml.boseandroidmgr.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilleml.boseandroidmgr.ConnectionStatus
import io.github.guilleml.boseandroidmgr.HeadphonesViewModel
import io.github.guilleml.boseandroidmgr.protocol.EqBand

@Composable
fun MainScreen(vm: HeadphonesViewModel, onRequestPermission: () -> Unit) {
    // Colores dinámicos (Material You, Android 12+): toman la paleta del fondo de pantalla.
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme) {
        Scaffold { padding ->
            // Deslizar hacia abajo: vuelve a leer todo (o reconecta si no hay conexión).
            PullToRefreshBox(
                isRefreshing = vm.refreshing,
                onRefresh = vm::userRefresh,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Header(vm, onRequestPermission)
                    BatteryCard(vm)
                    ModeCard(vm)
                    EqualizerCard(vm)
                    TrafficLog(vm)
                }
            }
        }
    }
}

// ------------------------------------------------------------------- Cabecera

@Composable
private fun Header(vm: HeadphonesViewModel, onRequestPermission: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(vm.deviceName ?: "Bose QC Ultra Earbuds", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.status == ConnectionStatus.Connecting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(statusText(vm.status), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        vm.lastError?.takeIf { !vm.isConnected }?.let {
            Text("Último error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (vm.status) {
            ConnectionStatus.NoPermission -> Button(onClick = onRequestPermission) { Text("Conceder permiso") }
            is ConnectionStatus.Retrying, ConnectionStatus.WaitingForDevice, ConnectionStatus.NotPaired,
            ConnectionStatus.BluetoothOff, ConnectionStatus.Idle -> Button(onClick = vm::reconnectNow) { Text("Reconectar") }
            else -> {}
        }
    }
}

private fun statusText(s: ConnectionStatus): String = when (s) {
    ConnectionStatus.Idle -> "Sin conectar"
    ConnectionStatus.NoPermission -> "Falta el permiso de dispositivos cercanos (Bluetooth)"
    ConnectionStatus.BluetoothOff -> "El Bluetooth está desactivado"
    ConnectionStatus.NotPaired -> "No hay auriculares QC Ultra 2 emparejados"
    ConnectionStatus.Connecting -> "Conectando…"
    ConnectionStatus.Connected -> "Conectado"
    is ConnectionStatus.Retrying -> "Sin conexión — reintento en ${s.seconds} s"
    ConnectionStatus.WaitingForDevice -> "Sin conexión — esperando a los auriculares"
}

// ------------------------------------------------------------------- Batería

@Composable
private fun BatteryCard(vm: HeadphonesViewModel) {
    SectionCard("Batería") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BatteryItem("Izquierdo", vm.leftPercent, vm.isConnected, Modifier.weight(1f))
            BatteryItem("Derecho", vm.rightPercent, vm.isConnected, Modifier.weight(1f))
            BatteryItem("Estuche", vm.casePercent, vm.isConnected, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BatteryItem(label: String, percent: Int?, live: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            percent?.let { "$it %" } ?: "–",
            style = MaterialTheme.typography.titleLarge,
            color = if (live) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
        LinearProgressIndicator(
            progress = { (percent ?: 0) / 100f },
            modifier = Modifier.fillMaxWidth(),
            color = if (percent != null && percent <= 20) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

// ------------------------------------------------------------------- Modos

@Composable
private fun ModeCard(vm: HeadphonesViewModel) {
    SectionCard("Modo") {
        val slots = vm.selectableModes
        if (slots.isEmpty()) {
            Text("–")
            return@SectionCard
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            slots.forEachIndexed { i, slot ->
                val pending = slot.index == vm.pendingModeIndex
                SegmentedButton(
                    // Seleccionado = modo CONFIRMADO por los auriculares.
                    selected = slot.index == vm.currentModeIndex,
                    onClick = { vm.setMode(slot.index) },
                    enabled = vm.isConnected && vm.pendingModeIndex == null || pending,
                    shape = SegmentedButtonDefaults.itemShape(i, slots.size),
                    icon = {
                        if (pending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else SegmentedButtonDefaults.Icon(slot.index == vm.currentModeIndex)
                    },
                ) { Text(slot.name) }
            }
        }
        vm.currentOtherModeName?.let {
            Text("Modo activo: $it", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ------------------------------------------------------------------- Ecualizador

@Composable
private fun EqualizerCard(vm: HeadphonesViewModel) {
    SectionCard("Ecualizador") {
        if (vm.equalizer.isEmpty()) {
            Text("–")
            return@SectionCard
        }
        val bands = vm.equalizer.sortedBy { it.index }
        EqualizerCurve(bands, vm.pendingEq, vm.isConnected, vm::setEqualizer, bandName = ::bandName)
        Spacer(Modifier.height(8.dp))
        bands.forEach { b -> BandRow(b, vm.pendingEq[b.index], vm.isConnected) { v -> vm.setEqualizer(b.index, v) } }
    }
}

/** Fila «nombre − [valor] +». El campo muestra el valor confirmado; se envía con «Hecho» o al salir. */
@Composable
private fun BandRow(band: EqBand, pending: Int?, enabled: Boolean, onSet: (Int) -> Unit) {
    val focus = LocalFocusManager.current
    // `rememberSaveable(band.value)`: el texto se reinicia cada vez que llega un valor confirmado nuevo.
    var text by rememberSaveable(band.value) { mutableStateOf(signed(band.value)) }
    var focused by remember { mutableStateOf(false) }
    fun submit() {
        val v = text.trim().removePrefix("+").toIntOrNull()
        if (v != null && v in band.min..band.max) onSet(v) else text = signed(band.value)
    }
    val base = pending ?: band.value
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(bandName(band), Modifier.width(64.dp))
        FilledTonalIconButton(onClick = { onSet(base - 1) }, enabled = enabled && base > band.min) { Text("−", fontSize = 20.sp) }
        OutlinedTextField(
            value = text,
            onValueChange = { new -> if (new.length <= 3 && new.all { it.isDigit() || it == '-' || it == '+' }) text = new },
            modifier = Modifier.width(80.dp).onFocusChanged {
                if (focused && !it.isFocused) submit()
                focused = it.isFocused
            },
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }), // al perder el foco se envía
        )
        FilledTonalIconButton(onClick = { onSet(base + 1) }, enabled = enabled && base < band.max) { Text("+", fontSize = 20.sp) }
        if (pending != null && pending != band.value) {
            Text("→ ${signed(pending)}", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun bandName(b: EqBand) = HeadphonesViewModel.EQ_BAND_NAMES[b.index] ?: "Banda ${b.index}"
private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

// ------------------------------------------------------------------- Registro

@Composable
private fun TrafficLog(vm: HeadphonesViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Ocultar registro de tráfico" else "Ver registro de tráfico (${vm.traffic.size})")
        }
        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = vm::refresh, enabled = vm.isConnected) { Text("Volver a leer") }
                TextButton(onClick = vm::clearTraffic) { Text("Borrar") }
            }
            // Las últimas 100 líneas, las más recientes arriba (sin lista perezosa: va dentro de un scroll).
            vm.traffic.takeLast(100).asReversed().forEach { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
            }
        }
    }
}

// ------------------------------------------------------------------- Comunes

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
