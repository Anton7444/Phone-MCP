package app.phonemcp.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import app.phonemcp.PrivilegeMode
import app.phonemcp.R
import app.phonemcp.RunStatus
import app.phonemcp.TunnelStatus
import app.phonemcp.TunnelType
import app.phonemcp.app
import app.phonemcp.data.AuditEntry
import app.phonemcp.data.Decision
import kotlinx.coroutines.delay

@Composable
fun modeName(mode: PrivilegeMode?): String = stringResource(
    when (mode) {
        PrivilegeMode.ROOT -> R.string.mode_root_name
        PrivilegeMode.SHIZUKU -> R.string.mode_shizuku_name
        PrivilegeMode.BASIC -> R.string.mode_basic_name
        null -> R.string.mode_none
    }
)

@Composable
fun tunnelName(t: TunnelType): String = stringResource(
    when (t) {
        TunnelType.QUICK -> R.string.tunnel_quick
        TunnelType.NAMED -> R.string.tunnel_named
        TunnelType.TAILSCALE -> R.string.tunnel_tailscale
        TunnelType.LAN -> R.string.tunnel_lan
        TunnelType.RELAY -> R.string.tunnel_relay
    }
)

@Composable
fun AppShell(vm: AppViewModel, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Box(Modifier.weight(1f)) {
            when (vm.tab) {
                Tab.HOME -> HomeScreen(vm, onStart)
                Tab.ACTIVITY -> ActivityScreen(vm)
                Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
        BottomNav(vm)
    }
}

@Composable
private fun BottomNav(vm: AppViewModel) {
    Column {
        RowDivider()
        Row(Modifier.fillMaxWidth().height(72.dp).background(C.Bg)) {
            NavItem(Tab.HOME, Icons.Home, R.string.nav_home, vm)
            NavItem(Tab.ACTIVITY, Icons.List, R.string.nav_activity, vm)
            NavItem(Tab.SETTINGS, Icons.Gear, R.string.nav_settings, vm)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(tab: Tab, icon: List<IconShape>, label: Int, vm: AppViewModel) {
    val selected = vm.tab == tab
    val color = if (selected) C.Accent else C.Muted
    Column(
        Modifier.weight(1f).fillMaxSize().clickable(role = Role.Tab) { vm.tab = tab },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, size = 22.dp, tint = color)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(label), color = color, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

// ---- Home ---------------------------------------------------------------------------------------------

@Composable
fun HomeScreen(vm: AppViewModel, onStart: () -> Unit) {
    val ctx = LocalContext.current
    val s by vm.server.collectAsState()
    val settings by vm.settings.collectAsState()
    val changes by vm.auditChanges.collectAsState()
    val recent by produceState(emptyList<AuditEntry>(), changes) { value = vm.loadActivity(3) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(15_000); value = System.currentTimeMillis() } }
    val batteryOk = remember(vm.resumeTick, vm.batteryDialog) { vm.isBatteryWhitelisted() }
    val notifOk = remember(vm.resumeTick) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    val storageOk = remember(vm.resumeTick) { app.phonemcp.exec.BasicExecutor.hasStorageAccess() }

    LaunchedEffect(settings.mode, s.active) {
        if (s.active) { vm.refreshProbe(); return@LaunchedEffect }
        vm.refreshProbe()
        if (settings.mode != PrivilegeMode.ROOT) while (true) { delay(3000); vm.refreshProbe() }
    }

    val probe = vm.probe
    val probeBad = !s.active && probe != null && !probe.ok
    // The saved setting is the truth; the snapshot's mode is only meaningful while a server is actually running.
    val mode = if (s.active) (s.mode ?: settings.mode) else settings.mode

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Row(
                Modifier.height(30.dp).clip(RoundedCornerShape(999.dp)).background(C.Accent.copy(alpha = 0.12f)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(if (mode == PrivilegeMode.SHIZUKU) Icons.ShieldMinus else Icons.Terminal, size = 14.dp, tint = C.Accent, stroke = 2.4f)
                Text(modeName(mode), color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (settings.fullAccess) NoticeCard(stringResource(R.string.full_access_banner), C.Danger)
        if (probeBad) ProbeBanner(vm, probe!!.failure)
        if (!notifOk) {
            NoticeCard(
                stringResource(R.string.notif_off_banner), C.Warn,
                action = stringResource(R.string.open_settings_btn)
            ) {
                ctx.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        if (mode == PrivilegeMode.BASIC && !storageOk && !s.active) {
            NoticeCard(stringResource(R.string.basic_storage_banner), C.Warn, action = stringResource(R.string.open_settings_btn)) { vm.tab = Tab.SETTINGS }
        }
        if (s.urlChanged) {
            NoticeCard(stringResource(R.string.url_changed_banner), C.Warn, action = stringResource(R.string.dismiss)) { vm.dismissUrlChanged() }
        }

        StatusCard(vm, s, now, probeBad, if (probeBad) ({ vm.fixProbe() }) else onStart)

        if (s.status == RunStatus.RUNNING) UrlRow(vm, s)

        if (s.status == RunStatus.RUNNING && s.tunnelStatus == TunnelStatus.ERROR && s.tunnelError != null) {
            NoticeCard(tunnelErrorText(s), C.Danger, action = stringResource(R.string.retry_tunnel)) { vm.retryTunnel() }
        }
        if (s.status == RunStatus.ERROR) {
            NoticeCard(errorText(s), C.Danger)
        }

        HealthCard(vm, s, probe?.ok, mode, batteryOk, ctx)

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.recent_activity), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.view_all), color = C.Muted, fontSize = 13.sp,
                    modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { vm.tab = Tab.ACTIVITY }.padding(start = 12.dp)
                        .wrapContentHeightCenter()
                )
            }
            McpCard(padding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
                if (recent.isEmpty()) {
                    Text(stringResource(R.string.no_activity), color = C.Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 16.dp))
                } else {
                    recent.forEachIndexed { i, e -> ActivityRow(e, now, divider = i < recent.lastIndex) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (vm.batteryDialog) {
        AlertDialog(
            onDismissRequest = { vm.batteryDialog = false },
            containerColor = C.Surface,
            title = { Text(stringResource(R.string.battery_dialog_title)) },
            text = { Text(stringResource(R.string.battery_dialog_text), color = C.Soft) },
            confirmButton = {
                TextButton({
                    vm.batteryDialog = false
                    try { ctx.startActivity(vm.batteryIntent()) } catch (_: Exception) {
                        try { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                    }
                }) { Text(stringResource(R.string.battery_dialog_ok), color = C.Accent) }
            },
            dismissButton = { TextButton({ vm.batteryDialog = false }) { Text(stringResource(R.string.later), color = C.Muted) } }
        )
    }
}

private fun Modifier.wrapContentHeightCenter() = this.then(Modifier.padding(vertical = 14.dp))

@Composable
private fun ProbeBanner(vm: AppViewModel, failure: app.phonemcp.ProbeFailure?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Danger.copy(alpha = 0.08f))
            .border(1.dp, C.Danger.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Warning, size = 20.dp, tint = C.Danger)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(probeFailureTitle(failure), color = C.Danger, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.banner_hint), color = C.Soft, fontSize = 12.sp, lineHeight = 18.sp)
        }
        SmallButton(stringResource(R.string.fix), { vm.fixProbe() }, fill = C.Danger.copy(alpha = 0.18f), textColor = C.Danger, height = 40.dp)
    }
}

@Composable
fun NoticeCard(text: String, color: Color, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Warning, size = 20.dp, tint = color)
        Text(text, color = C.Soft, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
        if (action != null) SmallButton(action, onAction, fill = color.copy(alpha = 0.18f), textColor = color, height = 40.dp)
    }
}

@Composable
private fun StatusCard(vm: AppViewModel, s: app.phonemcp.ServerSnapshot, now: Long, probeBad: Boolean, onStart: () -> Unit) {
    val running = s.status == RunStatus.RUNNING
    val starting = s.status == RunStatus.STARTING
    val color = when (s.status) {
        RunStatus.RUNNING -> C.Accent
        RunStatus.STARTING -> C.Warn
        RunStatus.ERROR -> C.Danger
        RunStatus.STOPPED -> C.Muted
    }
    McpCard(padding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(color, 10.dp)
                    Text(
                        stringResource(
                            when (s.status) {
                                RunStatus.RUNNING -> R.string.status_running
                                RunStatus.STARTING -> R.string.status_starting
                                RunStatus.ERROR -> R.string.status_error
                                RunStatus.STOPPED -> R.string.status_stopped
                            }
                        ),
                        fontSize = 20.sp, fontWeight = FontWeight.Bold
                    )
                }
                if (running) {
                    val mins = ((now - s.startedAt) / 60_000).toInt().coerceAtLeast(0)
                    Text(stringResource(R.string.run_summary, durationText(mins), s.calls), fontSize = 13.sp, color = C.Muted)
                }
            }
            if (running || starting) {
                SmallButton(stringResource(R.string.stop), { vm.stopServer() }, borderColor = C.Danger.copy(alpha = 0.5f), textColor = C.Danger, radius = 14.dp)
            } else {
                SmallButton(
                    stringResource(R.string.start), onStart, fill = if (probeBad) C.Surface2 else C.Accent,
                    textColor = if (probeBad) C.Dim else C.OnAccent, radius = 14.dp
                )
            }
        }
    }
}

@Composable
fun durationText(minutes: Int): String = when {
    minutes < 60 -> stringResource(R.string.duration_min, minutes)
    else -> stringResource(R.string.duration_hr_min, minutes / 60, minutes % 60)
}

@Composable
private fun UrlRow(vm: AppViewModel, s: app.phonemcp.ServerSnapshot) {
    val ctx = LocalContext.current
    val base = s.publicBase
    val copied = stringResource(R.string.url_copied)
    McpCard(radius = 16.dp, padding = PaddingValues(start = 16.dp, top = 6.dp, end = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (base == null) {
                Text(
                    stringResource(R.string.url_waiting), color = C.Muted, fontSize = 13.sp,
                    modifier = Modifier.weight(1f).padding(vertical = 14.dp)
                )
            } else {
                val full = remember(base, vm.urlRevealed) { if (vm.urlRevealed) vm.fullUrl() else null }
                UrlText(
                    base, vm.urlRevealed, full, size = 12f,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                )
                IconButtonBox(
                    if (vm.urlRevealed) Icons.EyeOff else Icons.Eye,
                    stringResource(if (vm.urlRevealed) R.string.cd_hide_url else R.string.cd_show_url),
                    { vm.urlRevealed = !vm.urlRevealed }
                )
                IconButtonBox(Icons.Copy, stringResource(R.string.cd_copy_url), {
                    if (vm.copyUrl(ctx)) android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show()
                })
            }
        }
    }
}

@Composable
private fun HealthCard(
    vm: AppViewModel, s: app.phonemcp.ServerSnapshot, probeOk: Boolean?, mode: PrivilegeMode?, batteryOk: Boolean, ctx: android.content.Context
) {
    val settings by vm.settings.collectAsState()
    McpCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        val permColor = when { s.active || probeOk == true -> C.Accent; probeOk == false -> C.Danger; else -> C.Muted }
        HealthRow(stringResource(R.string.health_permission), modeName(mode) + if (probeOk == false) " · " + stringResource(R.string.needs_attention) else "", permColor)

        val serverText = when (s.status) {
            RunStatus.RUNNING -> (if (s.tunnelType == TunnelType.LAN || s.tunnelType == TunnelType.TAILSCALE) "0.0.0.0:" else "127.0.0.1:") + s.port
            RunStatus.STARTING -> stringResource(R.string.status_starting)
            RunStatus.ERROR -> stringResource(R.string.status_error)
            RunStatus.STOPPED -> stringResource(R.string.status_stopped)
        }
        HealthRow(
            stringResource(R.string.health_server), serverText,
            when (s.status) { RunStatus.RUNNING -> C.Accent; RunStatus.ERROR -> C.Danger; RunStatus.STARTING -> C.Warn; else -> C.Muted },
            mono = s.status == RunStatus.RUNNING
        )

        val tType = if (s.active) s.tunnelType else settings.tunnel
        val tStatus = if (!s.active) stringResource(R.string.tunnel_off) else when (s.tunnelStatus) {
            TunnelStatus.OFF -> stringResource(R.string.tunnel_off)
            TunnelStatus.STARTING -> stringResource(R.string.tunnel_starting)
            TunnelStatus.UP -> stringResource(R.string.tunnel_up)
            TunnelStatus.RESTARTING -> stringResource(R.string.tunnel_restarting)
            TunnelStatus.ERROR -> stringResource(R.string.tunnel_error)
        }
        HealthRow(
            stringResource(R.string.health_tunnel), "${tunnelName(tType)} · $tStatus",
            when (s.tunnelStatus) { TunnelStatus.UP -> C.Accent; TunnelStatus.ERROR -> C.Danger; TunnelStatus.OFF -> C.Muted; else -> C.Warn }
        )

        HealthRow(
            stringResource(R.string.health_wake),
            stringResource(if (s.wakeLockHeld) R.string.wake_held else R.string.wake_not_held),
            if (s.wakeLockHeld) C.Accent else if (s.active) C.Warn else C.Muted
        )

        HealthRow(
            stringResource(R.string.health_battery),
            if (batteryOk) stringResource(R.string.battery_ok) else null,
            if (batteryOk) C.Accent else C.Warn, divider = false
        ) {
            if (!batteryOk) {
                SmallButton(
                    stringResource(R.string.fix), {
                        try { ctx.startActivity(vm.batteryIntent()) } catch (_: Exception) {
                            try { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                        }
                    },
                    fill = C.Warn.copy(alpha = 0.15f), textColor = C.Warn, height = 40.dp, radius = 10.dp
                )
            }
        }
    }
}

@Composable
private fun HealthRow(
    label: String, value: String?, color: Color, mono: Boolean = false, divider: Boolean = true,
    trailing: @Composable () -> Unit = {}
) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Dot(color)
            Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (value != null) {
                Text(
                    value, fontSize = if (mono) 12.sp else 13.sp, color = C.Muted,
                    fontFamily = if (mono) Mono else null, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.2f, fill = false)
                )
            }
            trailing()
        }
        if (divider) RowDivider()
    }
}

// ---- Activity -----------------------------------------------------------------------------------------

@Composable
fun relativeTime(ts: Long, now: Long): String {
    val m = ((now - ts) / 60_000).toInt()
    return when {
        m < 1 -> stringResource(R.string.time_now)
        m < 60 -> stringResource(R.string.time_min, m)
        m < 1440 -> stringResource(R.string.time_hr, m / 60)
        else -> stringResource(R.string.time_day, m / 1440)
    }
}

@Composable
fun ActivityRow(e: AuditEntry, now: Long, divider: Boolean) {
    val bad = e.decision == Decision.DENIED || e.decision == Decision.BLOCKED || e.decision == Decision.TIMEOUT || !e.ok
    var showDetail by remember { mutableStateOf(false) }
    val label = when (e.decision) {
        Decision.DENIED -> stringResource(R.string.dec_denied)
        Decision.BLOCKED -> stringResource(R.string.dec_blocked)
        Decision.TIMEOUT -> stringResource(R.string.dec_timeout)
        else -> if (!e.ok) stringResource(R.string.dec_failed) else e.tool
    }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { showDetail = true }.heightIn(min = 50.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                label, fontFamily = Mono, fontSize = 11.5.sp,
                color = if (bad) C.Danger else C.Accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (bad) C.Danger.copy(alpha = 0.12f) else C.Accent.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
            Text(
                e.summary.ifBlank { e.tool }, fontFamily = Mono, fontSize = 12.sp, color = C.Soft, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Text(relativeTime(e.ts, now), fontSize = 12.sp, color = C.Muted)
        }
        if (divider) RowDivider()
    }

    if (showDetail) {
        AlertDialog(
            onDismissRequest = { showDetail = false },
            containerColor = C.Surface,
            title = { Text(label, fontFamily = Mono, fontSize = 16.sp, color = if (bad) C.Danger else C.Accent) },
            text = {
                SelectionContainer {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "${e.tool} · ${relativeTime(e.ts, now)} · ${e.status}",
                            fontSize = 12.sp, color = C.Muted
                        )
                        DetailBlock(stringResource(R.string.detail_command), e.summary.ifBlank { e.tool })
                        if (e.args.isNotBlank()) DetailBlock(stringResource(R.string.detail_args), e.args)
                    }
                }
            },
            confirmButton = { TextButton({ showDetail = false }) { Text(stringResource(R.string.close), color = C.Accent) } }
        )
    }
}

@Composable
private fun DetailBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 12.sp, color = C.Muted, fontWeight = FontWeight.Medium)
        Text(body, fontFamily = Mono, fontSize = 12.sp, color = C.Soft, lineHeight = 17.sp)
    }
}

@Composable
fun ActivityScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val changes by vm.auditChanges.collectAsState()
    val entries by produceState(emptyList<AuditEntry>(), changes) { value = vm.loadActivity(500) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(15_000); value = System.currentTimeMillis() } }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.activity_title), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (entries.isNotEmpty()) {
                SmallButton(stringResource(R.string.export), { vm.exportActivity(ctx) }, borderColor = C.Border, weight = FontWeight.Medium, height = 40.dp)
                SmallButton(stringResource(R.string.clear), { confirmClear = true }, borderColor = C.Danger.copy(alpha = 0.5f), textColor = C.Danger, height = 40.dp)
            }
        }
        if (entries.isEmpty()) {
            Text(stringResource(R.string.no_activity), color = C.Muted, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp))
        } else {
            McpCard(Modifier.weight(1f, fill = false), padding = PaddingValues(horizontal = 16.dp)) {
                LazyColumn {
                    items(entries.size, key = { entries[it].id }) { i -> ActivityRow(entries[i], now, divider = i < entries.lastIndex) }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = C.Surface,
            title = { Text(stringResource(R.string.clear_confirm)) },
            confirmButton = {
                TextButton({ confirmClear = false; vm.clearActivity() }) { Text(stringResource(R.string.clear), color = C.Danger) }
            },
            dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(R.string.cancel), color = C.Muted) } }
        )
    }
}
