package com.cosmicoppai.lisa

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmicoppai.lisa.ui.components.WebViewChallengeDialog
import com.cosmicoppai.lisa.ui.navigation.LiSANavHost
import com.cosmicoppai.lisa.ui.screens.MainViewModel
import com.cosmicoppai.lisa.ui.theme.LisaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Best-effort for foreground download progress notifications */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            LisaTheme {
                val mainViewModel: MainViewModel = hiltViewModel()
                val activeDownloadsCount by mainViewModel.activeDownloadCount.collectAsStateWithLifecycle()
                val challenge by mainViewModel.interactiveChallenge.collectAsStateWithLifecycle()

                LiSANavHost(activeDownloadsCount = activeDownloadsCount)

                challenge?.let { req ->
                    WebViewChallengeDialog(
                        challenge = req,
                        onDone = { mainViewModel.completeInteractiveChallenge(req) },
                        onDismiss = { mainViewModel.dismissInteractiveChallenge(req) }
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
