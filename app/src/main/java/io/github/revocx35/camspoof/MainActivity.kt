package io.github.revocx35.camspoof

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.revocx35.camspoof.ui.CamSpoofViewModel
import io.github.revocx35.camspoof.ui.CameraScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val vm: CamSpoofViewModel = viewModel()
                    CameraScreen(vm)
                }
            }
        }
    }
}
