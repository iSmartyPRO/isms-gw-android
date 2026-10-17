package app.simgate.ui

import app.simgate.data.t
import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.simgate.sms.SmsStore

class MainActivity : ComponentActivity() {
    /** Number to open a conversation for (from a notification or an sms: link). */
    private var openPhone by mutableStateOf<String?>(null)
    private var tick by mutableIntStateOf(0)

    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        tick++
        if (SmsStore.isDefaultApp()) requestRuntimePermissions()
    }
    private var askedPermissions = false
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { askedPermissions = true; tick++; permissionsRevision.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        lifecycle.addObserver(LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { tick++; permissionsRevision.intValue++ } })
        setContent {
            SimGateTheme {
                val dark = isDarkTheme()
                androidx.compose.runtime.SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
                    }
                }
                @Suppress("UNUSED_EXPRESSION") tick
                val ready = SmsStore.isDefaultApp() && missingPermissions().isEmpty()
                if (!ready) {
                    Onboarding(
                        isDefault = SmsStore.isDefaultApp(),
                        missing = missingPermissions(),
                        onRequestRole = ::requestRole,
                        onRequestPermissions = ::requestRuntimePermissions,
                    )
                } else {
                    AppRoot(openPhone) { openPhone = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        i.getStringExtra(EXTRA_PHONE)?.let { openPhone = it; return }
        val data = i.data
        if ((i.action == Intent.ACTION_SENDTO || i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_VIEW) && data != null) {
            val num = data.schemeSpecificPart?.substringBefore('?')?.split(',')?.firstOrNull()
            if (!num.isNullOrBlank()) openPhone = num
        }
    }

    /** The app cannot work without these: SMS and SIM info. */
    private fun requiredPermissions(): List<String> = listOf(
        Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_PHONE_STATE,
    )

    /** Requested together with the required ones, but denial does not block the app (USSD/calls, SIM number, notifications). */
    private fun optionalPermissions(): List<String> = buildList {
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_PHONE_NUMBERS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    fun missingPermissions() = requiredPermissions().filterNot(::granted)

    /** Per Google Play policy, obtain the SMS app role first, and only then the SMS permissions. */
    private fun requestRole() {
        val intent = if (Build.VERSION.SDK_INT >= 29) {
            getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else {
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
        }
        roleLauncher.launch(intent)
    }

    private fun requestRuntimePermissions() {
        val required = missingPermissions()
        // "Don't ask again" denial: the system dialog will not appear, so open the app settings.
        if (askedPermissions && required.isNotEmpty() && required.none { shouldShowRequestPermissionRationale(it) }) {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
            }
            return
        }
        val all = (required + optionalPermissions().filterNot(::granted)).distinct()
        if (all.isNotEmpty()) permLauncher.launch(all.toTypedArray())
    }

    /** Re-request optional permissions (e.g. "Phone" for USSD) from settings. */
    fun requestOptionalPermissions() {
        val m = optionalPermissions().filterNot(::granted)
        if (m.isNotEmpty()) permLauncher.launch(m.toTypedArray())
    }

    companion object {
        const val EXTRA_PHONE = "phone"
        /** Changes after a permission request result and on returning to the app, so screens re-read permissions. */
        val permissionsRevision = androidx.compose.runtime.mutableIntStateOf(0)
    }
}

@Composable
private fun AppRoot(openPhone: String?, consumed: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var chat by rememberSaveable { mutableStateOf<String?>(null) }
    if (openPhone != null) { chat = openPhone; tab = 1; consumed() }

    BackHandler(enabled = chat != null) { chat = null }
    BackHandler(enabled = chat == null && tab != 0) { tab = 0 }

    if (chat != null) {
        ChatScreen(chat!!) { chat = null }
        return
    }
    Scaffold(
        containerColor = C.Bg,
        bottomBar = {
            NavigationBar(containerColor = C.Surface, tonalElevation = 0.dp) {
                listOf(Triple(t("Шлюз", "Gateway"), Icons.Rounded.Hub, 0), Triple(t("Сообщения", "Messages"), Icons.Rounded.Chat, 1), Triple(t("Настройки", "Settings"), Icons.Rounded.Tune, 2)).forEach { (label, icon, i) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, null) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = C.Emerald, selectedTextColor = C.Emerald,
                            indicatorColor = C.Emerald.copy(alpha = 0.14f), unselectedIconColor = C.Muted, unselectedTextColor = C.Muted,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().background(C.Bg).padding(pad)) {
            when (tab) {
                0 -> HomeScreen()
                1 -> ConversationsScreen(onOpen = { chat = it })
                else -> SettingsScreen()
            }
        }
    }
}
