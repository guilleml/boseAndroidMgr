package local.boseqcultra2.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import local.boseqcultra2.android.ui.MainScreen

class MainActivity : ComponentActivity() {
    // `by viewModels()`: Android crea el ViewModel la primera vez y devuelve el mismo
    // tras un giro de pantalla (propiedad delegada, ver HeadphonesViewModel).
    private val viewModel: HeadphonesViewModel by viewModels()

    // Petición de permiso en tiempo de ejecución. Sustituye al antiguo onRequestPermissionsResult.
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.connect(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MainScreen(viewModel, onConnect = ::connectWithPermission)
        }
        if (savedInstanceState == null) connectWithPermission()
    }

    private fun connectWithPermission() {
        val granted = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.connect(true) else permissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
}
