package app.phonemcp.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phonemcp.PrivilegeMode
import app.phonemcp.R
import app.phonemcp.TunnelType
import app.phonemcp.capabilitiesFor
import app.phonemcp.data.AppSettings
import app.phonemcp.exec.BasicExecutor
import app.phonemcp.i18n.Locales
import app.phonemcp.mcp.ToolCatalog
import app.phonemcp.policy.PathPolicy

private sealed interface Dlg {
    data object Tunnel : Dlg
    data object Port : Dlg
    data object AutoStop : Dlg
    data object WakeMax : Dlg
    data object Retention : Dlg
    data object Rotate : Dlg
    data object FullAccess : Dlg
    data object Vendor : Dlg
    data class Paths(val deny: Boolean) : Dlg
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    SectionLabel(title)
    McpCard { content() }
}

@Composable
fun minutesText(minutes: Int): String = when {
    minutes <= 0 -> stringResource(R.string.never)
    minutes < 60 -> stringResource(R.string.dur_minutes, minutes)
    minutes % 60 == 0 -> stringResource(R.string.dur_hours, minutes / 60)
    else -> stringResource(R.string.dur_minutes, minutes)
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    val server by vm.server.collectAsState()
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    val batteryOk = remember(vm.resumeTick, vm.batteryDialog) { vm.isBatteryWhitelisted() }
    val storageOk = remember(vm.resumeTick) { BasicExecutor.hasStorageAccess() }
    val caps = capabilitiesFor(s.mode, storageOk)

    // Anything that gives the AI more access needs the same proof as starting the server.
    fun withOwnerProof(action: () -> Unit) {
        val act = ctx as? androidx.fragment.app.FragmentActivity
        if (act != null && Biometrics.available(ctx)) {
            Biometrics.prompt(act, ctx.getString(R.string.bio_settings_title), ctx.getString(R.string.bio_settings_sub)) { ok ->
                if (ok) action()
            }
        } else action()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.settings_title), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.heightIn(min = 44.dp))

        Section(stringResource(R.string.sec_connection)) {
            NavRow(stringResource(R.string.set_tunnel), tunnelName(s.tunnel), { dlg = Dlg.Tunnel })
            NavRow(stringResource(R.string.set_port), s.port.toString(), { dlg = Dlg.Port }, valueMono = true)
            NavRow(
                stringResource(R.string.set_autostop),
                if (s.autoStopMinutes <= 0) stringResource(R.string.never) else stringResource(R.string.autostop_after, minutesText(s.autoStopMinutes)),
                { dlg = Dlg.AutoStop }, divider = false
            )
        }
        if (server.active) Footnote(stringResource(R.string.set_restart_note))

        Section(stringResource(R.string.sec_security)) {
            SwitchRow(
                stringResource(R.string.set_full_access), s.fullAccess,
                { v -> if (v) dlg = Dlg.FullAccess else vm.update { it.copy(fullAccess = false) } },
                note = stringResource(R.string.full_access_note), noteColor = if (s.fullAccess) C.Warn else C.Muted
            )
            SwitchRow(stringResource(R.string.set_biometric), s.biometricOnStart, { v ->
                // Turning the gate OFF needs the same proof as passing it, or it protects nothing.
                val act = ctx as? androidx.fragment.app.FragmentActivity
                if (!v && s.biometricOnStart && act != null && Biometrics.available(ctx)) {
                    Biometrics.prompt(act, ctx.getString(R.string.bio_start_title), ctx.getString(R.string.bio_start_sub)) { ok ->
                        if (ok) vm.update { it.copy(biometricOnStart = false) }
                    }
                } else vm.update { it.copy(biometricOnStart = v) }
            })
            SwitchRow(
                stringResource(R.string.set_approval), s.approval, { v -> vm.update { it.copy(approval = v) } },
                note = if (s.fullAccess) stringResource(R.string.full_access_overrides)
                else if (!s.approval) stringResource(R.string.approval_off_note) else null,
                noteColor = C.WarnSoft, enabled = !s.fullAccess
            )
            SwitchRow(
                stringResource(R.string.set_readonly), s.readOnly && !s.fullAccess,
                { v -> if (v) vm.update { it.copy(readOnly = true) } else withOwnerProof { vm.update { it.copy(readOnly = false) } } },
                enabled = !s.fullAccess
            )
            NavRow(stringResource(R.string.set_rotate), stringResource(R.string.rotate_note), { dlg = Dlg.Rotate }, divider = false)
        }

        Section(stringResource(R.string.sec_keep)) {
            SwitchRow(
                stringResource(R.string.set_wake), s.wakeLock, { v -> vm.update { it.copy(wakeLock = v) } },
                note = stringResource(R.string.wake_note)
            )
            SwitchRow(
                stringResource(R.string.set_wifi), s.wifiLock, { v -> vm.update { it.copy(wifiLock = v) } },
                note = stringResource(R.string.wifi_note)
            )
            NavRow(
                stringResource(R.string.set_wake_max),
                if (s.wakeLockMaxMinutes <= 0) stringResource(R.string.wake_max_same) else minutesText(s.wakeLockMaxMinutes),
                { dlg = Dlg.WakeMax }
            )
            SwitchRow(stringResource(R.string.set_boot), s.startOnBoot, { v -> vm.update { it.copy(startOnBoot = v) } })
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.set_battery), fontSize = 15.sp)
                    Text(
                        stringResource(if (batteryOk) R.string.battery_added else R.string.battery_not_note),
                        fontSize = 12.sp, lineHeight = 17.sp, color = if (batteryOk) C.Muted else C.WarnSoft
                    )
                }
                if (!batteryOk) {
                    SmallButton(
                        stringResource(R.string.set_btn), {
                            try { ctx.startActivity(vm.batteryIntent()) } catch (_: Exception) {
                                try { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                            }
                        },
                        fill = C.Warn.copy(alpha = 0.15f), textColor = C.Warn, height = 40.dp, radius = 10.dp
                    )
                }
            }
            RowDivider()
            NavRow(stringResource(R.string.set_vendor), android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, { dlg = Dlg.Vendor }, divider = false)
        }
        Footnote(stringResource(R.string.keep_footer))

        Section(stringResource(R.string.sec_tools)) {
            val list = ToolCatalog.all
            list.forEachIndexed { i, def ->
                val available = def.capability in caps
                val note = when {
                    !available -> stringResource(R.string.tool_unavailable)
                    def.sensitive -> stringResource(R.string.tool_sensitive_note)
                    else -> null
                }
                SwitchRow(
                    def.name, available && ToolCatalog.isEnabled(def, s),
                    { v ->
                        val change = { vm.update { it.copy(toolOverrides = it.toolOverrides + (def.name to v)) } }
                        if (v) withOwnerProof(change) else change()
                    },
                    note = note, noteColor = if (available) C.WarnSoft else C.Muted,
                    enabled = available && !s.fullAccess, mono = true, divider = i < list.lastIndex
                )
            }
        }
        Footnote(stringResource(R.string.tool_footer))

        Section(stringResource(R.string.sec_paths)) {
            NavRow(stringResource(R.string.path_allow), s.allowPaths.joinToString(", ").ifEmpty { "—" }, { dlg = Dlg.Paths(false) }, valueColor = C.Accent, valueMono = true)
            NavRow(stringResource(R.string.path_deny), stringResource(R.string.path_deny_count, s.denyPaths.size), { dlg = Dlg.Paths(true) }, valueColor = C.Danger, divider = false)
        }
        Footnote(stringResource(R.string.path_footer))

        Section(stringResource(R.string.sec_audit)) {
            NavRow(stringResource(R.string.set_retention), stringResource(R.string.days, s.auditRetentionDays), { dlg = Dlg.Retention }, divider = false)
        }

        Section(stringResource(R.string.sec_language)) {
            LangRow(stringResource(R.string.lang_system), Locales.SYSTEM, s.language, vm)
            LangRow(stringResource(R.string.lang_zh), Locales.ZH_TW, s.language, vm)
            LangRow(stringResource(R.string.lang_en), Locales.EN, s.language, vm, divider = false)
        }

        Section(stringResource(R.string.sec_mode)) {
            NavRow(
                stringResource(R.string.set_switch_mode), modeName(s.mode), { vm.openModeSwitch(Tab.SETTINGS) },
                valueColor = C.Accent, divider = s.mode == PrivilegeMode.BASIC
            )
            if (s.mode == PrivilegeMode.BASIC) {
                NavRow(
                    stringResource(R.string.set_storage),
                    stringResource(if (storageOk) R.string.on else R.string.off),
                    {
                        try {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                        } catch (_: Exception) {
                            try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) {}
                        }
                    },
                    valueColor = if (storageOk) C.Accent else C.Warn, note = stringResource(R.string.set_storage_note), divider = false
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    when (val d = dlg) {
        null -> {}
        Dlg.Tunnel -> TunnelDialog(vm, s) { dlg = null }
        Dlg.Port -> PortDialog(s.port, { p -> vm.update { it.copy(port = p) } }) { dlg = null }
        Dlg.AutoStop -> ChoiceDialog(
            stringResource(R.string.set_autostop), listOf(30, 60, 120, 240, 480, 0), s.autoStopMinutes,
            { minutesText(it) }, { m -> vm.update { it.copy(autoStopMinutes = m) } }
        ) { dlg = null }
        Dlg.WakeMax -> ChoiceDialog(
            stringResource(R.string.set_wake_max), listOf(0, 30, 60, 120, 240, 480, 1440), s.wakeLockMaxMinutes,
            { if (it == 0) stringResource(R.string.wake_max_same) else minutesText(it) }, { m -> vm.update { it.copy(wakeLockMaxMinutes = m) } }
        ) { dlg = null }
        Dlg.Retention -> ChoiceDialog(
            stringResource(R.string.set_retention), listOf(7, 30, 90), s.auditRetentionDays,
            { stringResource(R.string.days, it) }, { n -> vm.update { it.copy(auditRetentionDays = n) } }
        ) { dlg = null }
        Dlg.FullAccess -> AlertDialog(
            onDismissRequest = { dlg = null },
            containerColor = C.Surface,
            title = { Text(stringResource(R.string.full_access_title)) },
            text = { Text(stringResource(R.string.full_access_text), color = C.Soft) },
            confirmButton = {
                TextButton({ dlg = null; withOwnerProof { vm.update { it.copy(fullAccess = true) } } }) {
                    Text(stringResource(R.string.full_access_confirm), color = C.Danger)
                }
            },
            dismissButton = { TextButton({ dlg = null }) { Text(stringResource(R.string.cancel), color = C.Muted) } }
        )
        Dlg.Rotate -> AlertDialog(
            onDismissRequest = { dlg = null },
            containerColor = C.Surface,
            title = { Text(stringResource(R.string.rotate_title)) },
            text = { Text(stringResource(R.string.rotate_text), color = C.Soft) },
            confirmButton = { TextButton({ dlg = null; vm.rotateSecret() }) { Text(stringResource(R.string.rotate_confirm), color = C.Danger) } },
            dismissButton = { TextButton({ dlg = null }) { Text(stringResource(R.string.cancel), color = C.Muted) } }
        )
        Dlg.Vendor -> AlertDialog(
            onDismissRequest = { dlg = null },
            containerColor = C.Surface,
            title = { Text(stringResource(R.string.set_vendor)) },
            text = { Text(stringResource(R.string.vendor_manual), color = C.Soft) },
            confirmButton = {
                TextButton({
                    dlg = null
                    for (i in vm.vendorAutoStartIntents()) {
                        try { ctx.startActivity(i); break } catch (_: Exception) {}
                    }
                }) { Text(stringResource(R.string.vendor_open), color = C.Accent) }
            },
            dismissButton = { TextButton({ dlg = null }) { Text(stringResource(R.string.close), color = C.Muted) } }
        )
        is Dlg.Paths -> PathsDialog(d.deny, s, vm, ::withOwnerProof) { dlg = null }
    }
}

@Composable
private fun LangRow(label: String, tag: String, current: String, vm: AppViewModel, divider: Boolean = true) {
    val selected = tag == current
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .selectable(selected = selected, role = Role.RadioButton) { if (!selected) vm.setLanguage(tag) }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Check, size = 20.dp, tint = C.Accent, stroke = 2.6f)
        }
        if (divider) RowDivider()
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = o == selected, role = Role.RadioButton) { onPick(o); onDismiss() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = o == selected, onClick = null,
                            colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = C.Accent, unselectedColor = C.Muted)
                        )
                        Text(label(o), modifier = Modifier.padding(start = 12.dp), fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel), color = C.Muted) } }
    )
}

@Composable
private fun PortDialog(current: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val port = text.toIntOrNull()
    val valid = port != null && port in 1024..65535
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(stringResource(R.string.set_port)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.filter(Char::isDigit).take(5) }, singleLine = true,
                isError = !valid, supportingText = { Text(stringResource(R.string.port_hint), color = if (valid) C.Muted else C.Danger) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors()
            )
        },
        confirmButton = {
            TextButton({ if (valid) { onSave(port!!); onDismiss() } }, enabled = valid) {
                Text(stringResource(R.string.save), color = if (valid) C.Accent else C.Dim)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel), color = C.Muted) } }
    )
}

@Composable
private fun TunnelDialog(vm: AppViewModel, s: AppSettings, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(s.tunnel) }
    var host by remember { mutableStateOf(s.namedHostname) }
    var token by remember { mutableStateOf("") }
    val hasToken = remember { vm.hasNamedToken() }
    val valid = type != TunnelType.NAMED || (host.isNotBlank() && (token.isNotBlank() || hasToken))
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(stringResource(R.string.set_tunnel)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TunnelType.values().forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = type == t, role = Role.RadioButton) { type = t },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = type == t, onClick = null,
                            colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = C.Accent, unselectedColor = C.Muted)
                        )
                        Text(tunnelName(t), modifier = Modifier.padding(start = 12.dp), fontSize = 15.sp)
                    }
                }
                if (type == TunnelType.NAMED) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = token, onValueChange = { token = it.trim() }, singleLine = true,
                        label = { Text(stringResource(R.string.named_token)) },
                        supportingText = if (hasToken) ({ Text(stringResource(R.string.token_saved_hint), color = C.Muted) }) else null,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = fieldColors(), modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = host, onValueChange = { host = it.trim() }, singleLine = true,
                        label = { Text(stringResource(R.string.named_host)) },
                        colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Text(stringResource(R.string.named_help, s.port), fontSize = 12.sp, lineHeight = 18.sp, color = C.Muted, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton({
                if (token.isNotBlank()) vm.saveNamedToken(token)
                vm.update { it.copy(tunnel = type, namedHostname = host.trim()) }
                onDismiss()
            }, enabled = valid) { Text(stringResource(R.string.save), color = if (valid) C.Accent else C.Dim) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel), color = C.Muted) } }
    )
}

@Composable
private fun PathsDialog(deny: Boolean, s: AppSettings, vm: AppViewModel, withOwnerProof: (() -> Unit) -> Unit, onDismiss: () -> Unit) {
    val list = if (deny) s.denyPaths else s.allowPaths
    var input by remember { mutableStateOf("") }
    val normalized = remember(input) { if (input.isBlank()) null else runCatching { PathPolicy.normalize(input.trim(), "/sdcard") }.getOrNull() }
    fun save(next: List<String>, widens: Boolean) {
        val change = { vm.update { if (deny) it.copy(denyPaths = next) else it.copy(allowPaths = next) } }
        if (widens) withOwnerProof(change) else change()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(stringResource(if (deny) R.string.path_deny else R.string.path_allow)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                list.forEach { p ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p, fontFamily = Mono, fontSize = 13.sp, color = if (deny) C.Danger else C.Accent, modifier = Modifier.weight(1f))
                        TextButton({ save(list - p, widens = deny) }) { Text(stringResource(R.string.remove), color = C.Muted) }
                    }
                }
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.path_hint), color = C.Dim) },
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                TextButton(
                    { normalized?.let { n -> if (n !in list) save(list + n, widens = !deny); input = "" } },
                    enabled = normalized != null && normalized != "/"
                ) { Text(stringResource(R.string.add), color = C.Accent) }
                TextButton({ save(if (deny) PathPolicy.DEFAULT_DENY else PathPolicy.DEFAULT_ALLOW, widens = true) }) {
                    Text(stringResource(R.string.path_reset), color = C.Muted)
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close), color = C.Accent) } }
    )
}
