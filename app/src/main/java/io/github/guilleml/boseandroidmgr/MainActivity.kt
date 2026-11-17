package io.github.guilleml.boseandroidmgr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import io.github.guilleml.boseandroidmgr.ui.MainScreen

class MainActivity : ComponentActivity() {
    // `by viewModels()`: Android crea el ViewModel la primera vez y devuelve el mismo
    // tras un giro de pantalla (propiedad delegada, ver HeadphonesViewModel).
    private val viewModel: HeadphonesViewModel by viewModels()

    /** Para no volver a mostrar el diálogo de permiso cada vez que la app vuelve a primer plano. */
    private var permissionAsked = false

    // Petición de permiso en tiempo de ejecución. Sustituye al antiguo onRequestPermissionsResult.
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onForeground(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionAsked = savedInstanceState?.getBoolean("permissionAsked") ?: false
        enableEdgeToEdge()
        setContent {
            MainScreen(viewModel, onRequestPermission = ::requestPermission)
        }
    }

    // onStart/onStop marcan cuándo la app es visible. Solo entonces hay canal abierto (batería).
    override fun onStart() {
        super.onStart()
        if (hasPermission()) viewModel.onForeground(true)
        else if (!permissionAsked) requestPermission()
        else viewModel.onForeground(false)
    }

    override fun onStop() {
        super.onStop()
        // En un giro de pantalla la Activity se destruye y se recrea en el acto: no se desconecta.
        if (!isChangingConfigurations) viewModel.onBackground()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("permissionAsked", permissionAsked)
    }

    private fun hasPermission() =
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requestPermission() {
        permissionAsked = true
        permissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
}
