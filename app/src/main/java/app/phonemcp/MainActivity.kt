package app.phonemcp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import app.phonemcp.ui.ApprovalSheet
import app.phonemcp.ui.AppShell
import app.phonemcp.ui.AppViewModel
import app.phonemcp.ui.Biometrics
import app.phonemcp.ui.CheckRootScreen
import app.phonemcp.ui.CheckShizukuScreen
import app.phonemcp.ui.DoneScreen
import app.phonemcp.ui.McpTheme
import app.phonemcp.ui.ModeScreen
import app.phonemcp.ui.Screen
import app.phonemcp.ui.Tab
import app.phonemcp.ui.TunnelScreen
import app.phonemcp.ui.WelcomeScreen

class MainActivity : AppCompatActivity() {
    private val vm: AppViewModel by viewModels()
    private var askedNotifications = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.requestStart(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { McpTheme { Root() } }
    }

    override fun onStart() {
        super.onStart()
        app.uiVisible = true
    }

    override fun onStop() {
        app.uiVisible = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        vm.resumeTick++
    }

    /** Notification permission first (approvals need it), then the biometric gate, then the service. */
    private fun beginStart() {
        val needs = Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) {
            askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.requestStart(this)
        }
    }

    @Composable
    private fun Root() {
        val pending by vm.pendingApproval.collectAsState()
        BackHandler(enabled = vm.screen != Screen.WELCOME && vm.screen != Screen.DONE && !(vm.screen == Screen.MAIN && vm.tab == Tab.HOME)) {
            vm.back()
        }
        Box(Modifier.fillMaxSize()) {
            when (vm.screen) {
                Screen.WELCOME -> WelcomeScreen(vm)
                Screen.MODE -> ModeScreen(vm)
                Screen.CHECK -> if (vm.wizardMode == PrivilegeMode.SHIZUKU) CheckShizukuScreen(vm) else CheckRootScreen(vm)
                Screen.TUNNEL -> TunnelScreen(vm, onStart = ::beginStart)
                Screen.DONE -> DoneScreen(vm, onStart = ::beginStart)
                Screen.MAIN -> AppShell(vm, onStart = ::beginStart)
            }
            pending?.let { req ->
                BackHandler(true) {}
                ApprovalSheet(
                    req,
                    onDeny = { vm.resolveApproval(req.id, false) },
                    onAllow = {
                        if (Biometrics.available(this@MainActivity)) {
                            Biometrics.prompt(
                                this@MainActivity,
                                getString(R.string.bio_allow_title),
                                getString(R.string.bio_allow_sub)
                            ) { ok -> if (ok) vm.resolveApproval(req.id, true) }
                        } else {
                            vm.resolveApproval(req.id, false) // no lock screen: refuse rather than trust a bare tap
                        }
                    }
                )
            }
        }
    }
}
