// Diálogo de elección de modo que abre el tile de Ajustes rápidos.
//
// Es una Activity translúcida (no un Dialog dentro del TileService) para reutilizar Compose y el
// mismo HeadphonesViewModel: abre el canal al mostrarse (onStart), lee modo y batería, cambia el
// modo, espera la confirmación y se cierra. Al desaparecer (onStop) el canal se cierra.
// Si la app principal estaba en pantalla, pasa a segundo plano y suelta su canal antes: los
// auriculares solo admiten un cliente por equipo.
package io.github.guilleml.boseandroidmgr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.guilleml.boseandroidmgr.tile.BoseTileService

class ModePickerActivity : ComponentActivity() {
    private val viewModel: HeadphonesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ModePicker(viewModel, onClose = ::finish) }
    }

    override fun onStart() {
        super.onStart()
        val granted = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        viewModel.onForeground(granted)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            viewModel.onBackground()
            // noHistory en el manifiesto ya la cierra al salir; finish() por si se vuelve con «Recientes».
            finish()
        }
    }
}

@Composable
private fun ModePicker(vm: HeadphonesViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    // Mientras no hay conexión se muestra lo que hay en caché (último modo y batería).
    val cached = remember { StateCache.load(context) }
    /** Modo elegido; al llegar su confirmación se cierra el diálogo. */
    var chosen by remember { mutableStateOf<Int?>(null) }

    val modes = vm.selectableModes.ifEmpty { cached.selectableModes }
    val current = vm.currentModeIndex ?: cached.modeIndex
    val currentName = vm.currentModeIndex?.let { vm.modeName(it) } ?: cached.modeName

    LaunchedEffect(chosen, vm.currentModeIndex, vm.pendingModeIndex) {
        val c = chosen ?: return@LaunchedEffect
        if (vm.currentModeIndex == c && vm.pendingModeIndex == null) onClose()
        // Sin confirmación en 3 s el ViewModel relee el modo y anula el pendiente: se deja elegir de nuevo.
        if (vm.pendingModeIndex == null && vm.currentModeIndex != c) chosen = null
    }

    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme) {
        // Fondo semitransparente: tocar fuera cierra el diálogo.
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                Modifier.padding(24.dp).widthIn(max = 360.dp)
                    // Los toques dentro de la tarjeta no la cierran.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Modo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (!vm.isConnected) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        pickerStatus(vm.status, currentName),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (modes.isEmpty()) Text("Abre la app una vez para leer la lista de modos.")
                    modes.forEach { slot ->
                        val isCurrent = slot.index == current
                        val isChosen = slot.index == chosen
                        val enabled = vm.isConnected && chosen == null && !isCurrent
                        val content: @Composable () -> Unit = {
                            if (isChosen) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                            Text(if (isCurrent) "${slot.name}  ✓" else slot.name)
                        }
                        if (isCurrent) Button(onClick = {}, Modifier.fillMaxWidth(), enabled = vm.isConnected) { content() }
                        else FilledTonalButton(onClick = { chosen = slot.index; vm.setMode(slot.index) }, Modifier.fillMaxWidth(), enabled = enabled) { content() }
                    }
                    val left = vm.leftPercent ?: cached.left.takeIf { !vm.isConnected }
                    val right = vm.rightPercent ?: cached.right.takeIf { !vm.isConnected }
                    val case = vm.casePercent ?: cached.case.takeIf { !vm.isConnected }
                    Text(
                        "Batería: " + BoseTileService.batteryText(left, right, case),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onClose, Modifier.align(Alignment.End)) { Text("Cerrar") }
                }
            }
        }
    }
}

private fun pickerStatus(s: ConnectionStatus, currentName: String?): String = when (s) {
    ConnectionStatus.Connected -> "Actual: ${currentName ?: "–"}"
    ConnectionStatus.Connecting -> "Conectando…  (último: ${currentName ?: "–"})"
    is ConnectionStatus.Retrying, ConnectionStatus.WaitingForDevice -> "Sin conexión con los auriculares"
    ConnectionStatus.NoPermission -> "Falta el permiso Bluetooth: abre la app"
    ConnectionStatus.BluetoothOff -> "El Bluetooth está desactivado"
    ConnectionStatus.NotPaired -> "No hay auriculares QC Ultra 2 emparejados"
    ConnectionStatus.Idle -> "Último: ${currentName ?: "–"}"
}
