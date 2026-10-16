package app.phonemcp.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phonemcp.PrivilegeMode
import app.phonemcp.R
import app.phonemcp.RunStatus
import app.phonemcp.TunnelStatus
import app.phonemcp.TunnelType
import app.phonemcp.app
import app.phonemcp.exec.RootExecutor
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@Composable
private fun WizardFrame(
    topLeft: @Composable () -> Unit = {},
    step: Int? = null,
    bottom: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(start = 0.dp)) { topLeft() }
            Spacer(Modifier.weight(1f))
            if (step != null) Text(stringResource(R.string.step_n_of_3, step), color = C.Muted, fontSize = 13.sp)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) { content() }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { bottom() }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Heading(title: String, sub: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
        if (sub != null) Text(sub, fontSize = 15.sp, lineHeight = 22.sp, color = C.Muted)
    }
}

// ---- Welcome ------------------------------------------------------------------------------------------

@Composable
fun WelcomeScreen(vm: AppViewModel) {
    WizardFrame(bottom = { PrimaryButton(stringResource(R.string.start_setup), { vm.startSetup() }) }) {
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(68.dp).clip(RoundedCornerShape(20.dp)).background(C.Accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Terminal, size = 34.dp, tint = C.OnAccent, stroke = 2.2f)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.app_name), fontSize = 36.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
                Text(stringResource(R.string.welcome_tagline), fontSize = 17.sp, lineHeight = 25.sp, color = C.Muted)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Feature(Icons.Bolt, R.string.welcome_f1_t, R.string.welcome_f1_d)
            Feature(Icons.Phone, R.string.welcome_f2_t, R.string.welcome_f2_d)
            Feature(Icons.ShieldCheck, R.string.welcome_f3_t, R.string.welcome_f3_d)
        }
        WarningCard(stringResource(R.string.welcome_warning))
    }
}

@Composable
private fun Feature(icon: List<IconShape>, title: Int, desc: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface).border(1.dp, C.Border, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, size = 22.dp, tint = C.Accent) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(stringResource(desc), fontSize = 13.sp, color = C.Muted, lineHeight = 19.sp)
        }
    }
}

@Composable
fun WarningCard(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(C.Warn.copy(alpha = 0.08f))
            .border(1.dp, C.Warn.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Warning, size = 20.dp, tint = C.Warn, modifier = Modifier.padding(top = 1.dp))
        Text(text, fontSize = 13.sp, lineHeight = 20.sp, color = C.WarnSoft)
    }
}

// ---- Mode ---------------------------------------------------------------------------------------------

@Composable
fun ModeScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val suDetected = remember(vm.resumeTick) { RootExecutor.suDetected() }
    val shizInstalled = remember(vm.resumeTick) { ctx.app.shizuku.isInstalled() }
    val recommended = if (suDetected) PrivilegeMode.ROOT else PrivilegeMode.SHIZUKU
    WizardFrame(
        topLeft = { BackButton({ vm.back() }, stringResource(R.string.back)) },
        step = 1,
        bottom = {
            PrimaryButton(stringResource(R.string.continue_), { vm.continueFromMode() })
        }
    ) {
        Heading(stringResource(R.string.mode_title), stringResource(R.string.mode_sub))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ModeCard(
                selected = vm.wizardMode == PrivilegeMode.ROOT, onClick = { vm.wizardMode = PrivilegeMode.ROOT },
                icon = Icons.Terminal, title = stringResource(R.string.mode_root_name),
                badges = buildList {
                    if (suDetected) add(stringResource(R.string.badge_su) to true)
                    if (recommended == PrivilegeMode.ROOT) add(stringResource(R.string.badge_recommended) to false)
                },
                pro = stringResource(R.string.root_pro), con = stringResource(R.string.root_con)
            )
            ModeCard(
                selected = vm.wizardMode == PrivilegeMode.SHIZUKU, onClick = { vm.wizardMode = PrivilegeMode.SHIZUKU },
                icon = Icons.ShieldMinus, title = stringResource(R.string.mode_shizuku_name),
                badges = buildList {
                    add((if (shizInstalled) stringResource(R.string.badge_installed) else stringResource(R.string.badge_not_installed)) to shizInstalled)
                    if (recommended == PrivilegeMode.SHIZUKU) add(stringResource(R.string.badge_recommended) to false)
                    add(stringResource(R.string.badge_no_root) to false)
                },
                pro = stringResource(R.string.shiz_pro), con = stringResource(R.string.shiz_con)
            )
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.mode_skip),
                color = C.Muted, fontSize = 14.sp, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { vm.skipPrivileges() }
                    .padding(horizontal = 12.dp, vertical = 14.dp)
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ModeCard(
    selected: Boolean, onClick: () -> Unit, icon: List<IconShape>, title: String,
    badges: List<Pair<String, Boolean>>, pro: String, con: String
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) C.Accent.copy(alpha = 0.06f) else C.Surface)
            .border(1.5.dp, if (selected) C.Accent else C.Border, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(C.Surface2), contentAlignment = Alignment.Center) {
                Icon(icon, size = 22.dp, tint = C.Accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    badges.forEach { (t, good) ->
                        Pill(t, if (good) C.Accent else C.Muted, if (good) C.Accent.copy(alpha = 0.12f) else C.Surface2)
                    }
                }
            }
            Box(
                Modifier.size(24.dp).clip(CircleShape).border(2.dp, if (selected) C.Accent else C.Border, CircleShape),
                contentAlignment = Alignment.Center
            ) { if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(C.Accent)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("+", color = C.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(pro, color = C.Soft, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("−", color = C.Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(con, color = C.Muted, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

// ---- Checks -------------------------------------------------------------------------------------------

enum class StepStatus { PENDING, RUNNING, OK, FAIL }

@Composable
private fun StepRow(index: Int, title: String, status: StepStatus, divider: Boolean = true, body: @Composable () -> Unit = {}) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                when (status) {
                    StepStatus.OK -> Box(Modifier.fillMaxSize().clip(CircleShape).background(C.Accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Check, size = 16.dp, tint = C.OnAccent, stroke = 3f)
                    }
                    StepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(24.dp), color = C.Accent, strokeWidth = 2.5.dp)
                    StepStatus.FAIL -> Box(
                        Modifier.fillMaxSize().clip(CircleShape).border(2.dp, C.Danger, CircleShape), contentAlignment = Alignment.Center
                    ) { Text("!", color = C.Danger, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    StepStatus.PENDING -> Box(
                        Modifier.fillMaxSize().clip(CircleShape).border(2.dp, C.Border, CircleShape), contentAlignment = Alignment.Center
                    ) { Text("$index", color = C.Dim, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    title, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    color = if (status == StepStatus.PENDING) C.Dim else C.Text,
                    modifier = Modifier.padding(top = 3.dp)
                )
                body()
            }
        }
        if (divider) RowDivider()
    }
}

@Composable
private fun OutputBox(text: String) {
    if (text.isBlank()) return
    Text(
        text, fontFamily = Mono, fontSize = 12.sp, lineHeight = 18.sp, color = C.Soft,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Bg).border(1.dp, C.Border, RoundedCornerShape(12.dp)).padding(12.dp)
    )
}

@Composable
fun CheckRootScreen(vm: AppViewModel) {
    val c = vm.rootCheck
    val busy = vm.rootChecking
    val s1 = when { busy && c == null -> StepStatus.RUNNING; c == null -> StepStatus.PENDING; c.suFound -> StepStatus.OK; else -> StepStatus.FAIL }
    val s2 = when {
        c == null -> if (busy) StepStatus.PENDING else StepStatus.PENDING
        !c.suFound -> StepStatus.PENDING
        c.granted -> StepStatus.OK
        else -> StepStatus.FAIL
    }
    val s3 = when {
        c == null || !c.suFound || !c.granted -> StepStatus.PENDING
        c.uidOk -> StepStatus.OK
        else -> StepStatus.FAIL
    }
    // While the root manager prompt is open the second step is the one the user has to answer.
    val s2Shown = if (busy && c == null) StepStatus.RUNNING else s2
    val s1Shown = if (busy && c == null) StepStatus.OK else s1
    WizardFrame(
        topLeft = { BackButton({ vm.back() }, stringResource(R.string.back)) },
        step = 2,
        bottom = {
            PrimaryButton(stringResource(R.string.continue_), { vm.checksPassed() }, enabled = c?.ok == true)
            SmallButton(stringResource(R.string.recheck), { vm.runRootCheck() }, Modifier.fillMaxWidth(), borderColor = C.Border, weight = FontWeight.Medium)
            if (vm.canSwitchAnyway && c?.ok != true) {
                SmallButton(stringResource(R.string.switch_anyway), { vm.checksPassed() }, Modifier.fillMaxWidth(), textColor = C.Muted, weight = FontWeight.Medium)
            }
        }
    ) {
        Heading(stringResource(R.string.check_root_title), stringResource(R.string.check_root_sub))
        McpCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 0.dp)) {
            StepRow(1, stringResource(R.string.rc_step1), s1Shown) {
                if (c != null && !c.suFound) Text(stringResource(R.string.rc_hint_missing), color = C.WarnSoft, fontSize = 13.sp, lineHeight = 19.sp)
            }
            StepRow(2, stringResource(R.string.rc_step2), s2Shown) {
                if (c != null && c.suFound && !c.granted) {
                    Text(stringResource(R.string.rc_hint_denied), color = C.WarnSoft, fontSize = 13.sp, lineHeight = 19.sp)
                    OutputBox(c.output)
                }
            }
            StepRow(3, stringResource(R.string.rc_step3), s3, divider = false) {
                if (c != null && c.granted) {
                    OutputBox(c.output)
                    if (!c.uidOk) Text(stringResource(R.string.rc_hint_notroot), color = C.WarnSoft, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}

@Composable
fun CheckShizukuScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.pollShizuku() }
    val c = vm.shizukuCheck
    val s1 = when { c == null -> StepStatus.RUNNING; c.installed -> StepStatus.OK; else -> StepStatus.FAIL }
    val s2 = when {
        c == null || !c.installed -> StepStatus.PENDING
        c.running && !c.tooOld -> StepStatus.OK
        else -> StepStatus.FAIL
    }
    val s3 = when {
        c == null || s2 != StepStatus.OK -> StepStatus.PENDING
        vm.shizukuBusy -> StepStatus.RUNNING
        c.granted -> StepStatus.OK
        else -> StepStatus.FAIL
    }
    val s4 = when {
        c == null || s3 != StepStatus.OK -> StepStatus.PENDING
        c.uidOk -> StepStatus.OK
        else -> StepStatus.FAIL
    }
    val sub = when {
        c == null -> stringResource(R.string.checking)
        c.ok -> stringResource(R.string.shiz_sub_ok)
        !c.installed -> stringResource(R.string.shiz_sub_missing)
        c.tooOld -> stringResource(R.string.shiz_sub_old)
        !c.running -> stringResource(R.string.shiz_sub_not_running)
        !c.granted -> stringResource(R.string.shiz_sub_no_perm)
        else -> stringResource(R.string.shiz_sub_test)
    }
    WizardFrame(
        topLeft = { BackButton({ vm.back() }, stringResource(R.string.back)) },
        step = 2,
        bottom = {
            PrimaryButton(stringResource(R.string.continue_), { vm.checksPassed() }, enabled = c?.ok == true)
            if (vm.canSwitchAnyway && c?.ok != true) {
                SmallButton(stringResource(R.string.switch_anyway), { vm.checksPassed() }, Modifier.fillMaxWidth(), textColor = C.Muted, weight = FontWeight.Medium)
            }
        }
    ) {
        Heading(stringResource(R.string.check_shiz_title), sub)
        McpCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 0.dp)) {
            StepRow(1, stringResource(if (s1 == StepStatus.FAIL) R.string.sk1_fail else R.string.sk1), s1) {
                if (s1 == StepStatus.FAIL) {
                    Text(stringResource(R.string.shiz_install_hint), color = C.WarnSoft, fontSize = 13.sp, lineHeight = 19.sp)
                    SmallButton(
                        stringResource(R.string.shiz_download),
                        { openUrl(ctx, "https://github.com/RikkaApps/Shizuku/releases/latest") },
                        Modifier.fillMaxWidth(), fill = C.Warn, textColor = Color(0xFF2A1D00)
                    )
                }
            }
            StepRow(2, stringResource(R.string.sk2), s2) {
                if (s2 == StepStatus.FAIL) {
                    if (c?.tooOld == true) {
                        Text(stringResource(R.string.shiz_old), color = C.WarnSoft, fontSize = 13.sp, lineHeight = 19.sp)
                    } else {
                        Text(stringResource(R.string.shiz_start_hint), color = C.WarnSoft, fontSize = 13.sp, lineHeight = 19.sp)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(R.string.shiz_step_a, R.string.shiz_step_b, R.string.shiz_step_c).forEachIndexed { i, r ->
                                Text("${i + 1}. ${stringResource(r)}", color = C.Soft, fontSize = 13.sp, lineHeight = 19.sp)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SmallButton(
                            stringResource(R.string.shiz_open),
                            { openShizuku(ctx) },
                            Modifier.weight(1f), fill = C.Warn, textColor = Color(0xFF2A1D00)
                        )
                        SmallButton(
                            stringResource(R.string.recheck),
                            { vm.shizukuCheck = null },
                            Modifier.weight(1f), borderColor = C.Border, weight = FontWeight.Medium
                        )
                    }
                }
            }
            StepRow(3, stringResource(R.string.sk3), s3) {
                if (s3 == StepStatus.FAIL) {
                    Text(stringResource(R.string.shiz_perm_hint), color = C.WarnSoft, fontSize = 13.sp, lineHeight = 19.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SmallButton(stringResource(R.string.shiz_grant), { vm.authorizeShizuku() }, Modifier.weight(1f), fill = C.Warn, textColor = Color(0xFF2A1D00))
                        SmallButton(stringResource(R.string.shiz_open), { openShizuku(ctx) }, Modifier.weight(1f), borderColor = C.Border, weight = FontWeight.Medium)
                    }
                }
            }
            StepRow(4, stringResource(R.string.sk4), s4, divider = false) {
                if (c != null && s3 == StepStatus.OK) {
                    OutputBox(c.output)
                    if (!c.uidOk) Text(stringResource(R.string.shiz_test_failed), color = C.WarnSoft, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}

private fun openShizuku(ctx: android.content.Context) {
    val launch = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
    try {
        if (launch != null) ctx.startActivity(launch) else openUrl(ctx, "https://github.com/RikkaApps/Shizuku/releases/latest")
    } catch (_: Exception) {
    }
}

private fun openUrl(ctx: android.content.Context, url: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
}

// ---- Tunnel choice ------------------------------------------------------------------------------------

@Composable
fun TunnelScreen(vm: AppViewModel, onStart: () -> Unit) {
    WizardFrame(
        topLeft = { BackButton({ vm.back() }, stringResource(R.string.back)) },
        step = 3,
        bottom = {
            PrimaryButton(stringResource(R.string.start_and_get), { vm.finishSetup(); onStart() }, enabled = vm.canFinishSetup)
        }
    ) {
        Heading(stringResource(R.string.tunnel_title), stringResource(R.string.tunnel_sub))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TunnelOption(vm, TunnelType.QUICK, R.string.tunnel_quick, R.string.tunnel_quick_d, recommended = true)
            TunnelOption(vm, TunnelType.NAMED, R.string.tunnel_named, R.string.tunnel_named_d)
            if (vm.wizardTunnel == TunnelType.NAMED) NamedFields(vm)
            TunnelOption(vm, TunnelType.TAILSCALE, R.string.tunnel_tailscale, R.string.tunnel_tailscale_d)
            TunnelOption(vm, TunnelType.LAN, R.string.tunnel_lan, R.string.tunnel_lan_d)
        }
    }
}

@Composable
private fun TunnelOption(vm: AppViewModel, type: TunnelType, title: Int, desc: Int, recommended: Boolean = false) {
    val selected = vm.wizardTunnel == type
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) C.Accent.copy(alpha = 0.06f) else C.Surface)
            .border(1.5.dp, if (selected) C.Accent else C.Border, shape)
            .selectable(selected = selected, role = Role.RadioButton) { vm.wizardTunnel = type }
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(title), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (recommended) Pill(stringResource(R.string.badge_recommended), C.Muted, C.Surface2)
            }
            Text(stringResource(desc), fontSize = 13.sp, lineHeight = 19.sp, color = C.Muted)
        }
        Box(
            Modifier.size(24.dp).clip(CircleShape).border(2.dp, if (selected) C.Accent else C.Border, CircleShape),
            contentAlignment = Alignment.Center
        ) { if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(C.Accent)) }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
    focusedBorderColor = C.Accent, unfocusedBorderColor = C.Border,
    focusedLabelColor = C.Accent, unfocusedLabelColor = C.Muted,
    cursorColor = C.Accent
)

@Composable
private fun NamedFields(vm: AppViewModel) {
    Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = vm.wizardToken, onValueChange = { vm.wizardToken = it.trim() },
            label = { Text(stringResource(R.string.named_token)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = fieldColors(), modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = vm.wizardHost, onValueChange = { vm.wizardHost = it.trim() },
            label = { Text(stringResource(R.string.named_host)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = fieldColors(), modifier = Modifier.fillMaxWidth()
        )
        Text(stringResource(R.string.named_help, vm.settings.collectAsState().value.port), fontSize = 12.sp, lineHeight = 18.sp, color = C.Muted)
    }
}

// ---- Done ---------------------------------------------------------------------------------------------

@Composable
fun DoneScreen(vm: AppViewModel, onStart: () -> Unit) {
    val ctx = LocalContext.current
    val s by vm.server.collectAsState()
    val url = s.publicBase
    val ready = s.status == RunStatus.RUNNING && url != null
    val waiting = vm.startPending || s.status == RunStatus.STARTING || (s.status == RunStatus.RUNNING && url == null &&
        (s.tunnelStatus == TunnelStatus.STARTING || s.tunnelStatus == TunnelStatus.RESTARTING || s.tunnelStatus == TunnelStatus.OFF))
    WizardFrame(
        bottom = {
            if (ready) {
                PrimaryButton(stringResource(R.string.go_home), { vm.screen = Screen.MAIN; vm.tab = Tab.HOME })
            } else if (!waiting) {
                PrimaryButton(stringResource(R.string.retry), onStart)
                SmallButton(stringResource(R.string.go_home), { vm.screen = Screen.MAIN; vm.tab = Tab.HOME }, Modifier.fillMaxWidth(), borderColor = C.Border, weight = FontWeight.Medium)
            }
        }
    ) {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(if (ready) C.Accent else C.Surface2), contentAlignment = Alignment.Center) {
                when {
                    ready -> Icon(Icons.Check, size = 28.dp, tint = C.OnAccent, stroke = 3f)
                    waiting -> CircularProgressIndicator(Modifier.size(28.dp), color = C.Accent, strokeWidth = 3.dp)
                    else -> Icon(Icons.Warning, size = 28.dp, tint = C.Warn)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(if (ready) R.string.done_title else if (waiting) R.string.done_title_starting else R.string.done_title_stopped),
                    fontSize = 28.sp, fontWeight = FontWeight.Bold
                )
                val sub = when {
                    ready -> stringResource(R.string.done_sub)
                    waiting -> stringResource(R.string.done_sub_starting)
                    else -> errorText(s)
                }
                Text(sub, fontSize = 15.sp, lineHeight = 22.sp, color = C.Muted, textAlign = TextAlign.Center)
            }
        }
        if (ready) {
            val full = remember(url, vm.urlRevealed) { vm.fullUrl() }
            McpCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.connector_url).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = C.Muted, letterSpacing = 0.5.sp)
                    UrlText(url!!, vm.urlRevealed, full)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SmallButton(stringResource(R.string.copy_url), { vm.copyUrl(ctx) }, Modifier.weight(1f), fill = C.Accent, textColor = C.OnAccent)
                        SmallButton(stringResource(R.string.share), { vm.shareUrl(ctx) }, Modifier.weight(1f), borderColor = C.Border, weight = FontWeight.Medium)
                        SmallButton(
                            stringResource(if (vm.urlRevealed) R.string.hide else R.string.reveal),
                            { vm.urlRevealed = !vm.urlRevealed }, Modifier.weight(1f), borderColor = C.Border, weight = FontWeight.Medium
                        )
                    }
                }
            }
            if (full != null && vm.urlRevealed) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    QrCode(full)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.other_device), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.qr_help), fontSize = 13.sp, lineHeight = 19.sp, color = C.Muted)
                    }
                }
            } else {
                Text(stringResource(R.string.qr_hidden), fontSize = 13.sp, lineHeight = 19.sp, color = C.Muted)
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).border(1.dp, C.Border, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.mcp_config_steps), fontSize = 13.sp, lineHeight = 21.sp, color = C.Soft)
                Text(vm.mcpConfig(masked = !vm.urlRevealed).orEmpty(), fontFamily = Mono, fontSize = 12.sp, lineHeight = 18.sp, color = C.Soft)
                s.tlsFingerprint?.let {
                    Text(stringResource(R.string.tls_pin, it), fontFamily = Mono, fontSize = 11.sp, lineHeight = 16.sp, color = C.Muted)
                }
                SmallButton(stringResource(R.string.copy_mcp_config), { vm.copyConfig(ctx) }, Modifier.fillMaxWidth(), borderColor = C.Border, weight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun UrlText(base: String, revealed: Boolean, full: String?, modifier: Modifier = Modifier, size: Float = 12.5f) {
    val text: AnnotatedString = buildAnnotatedString {
        if (revealed && full != null) {
            val secretStart = full.indexOf("/mcp/") + 5
            withStyle(SpanStyle(color = C.Accent)) { append(full.substring(0, full.indexOf("/mcp/"))) }
            append("/mcp/")
            withStyle(SpanStyle(color = C.Accent)) { append(full.substring(secretStart)) }
        } else {
            withStyle(SpanStyle(color = C.Accent)) { append(base) }
            append("/mcp/••••••••••••")
        }
    }
    Text(text, fontFamily = Mono, fontSize = size.sp, lineHeight = (size * 1.6f).sp, color = C.Soft, modifier = modifier)
}

@Composable
private fun QrCode(text: String) {
    val matrix: BitMatrix? = remember(text) {
        try {
            QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
            )
        } catch (_: Exception) {
            null
        }
    }
    Box(
        Modifier.size(132.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(8.dp)
            .androidxSemanticsDesc(stringResource(R.string.cd_qr))
    ) {
        if (matrix != null) {
            Canvas(Modifier.fillMaxSize()) {
                val cell = size.width / matrix.width
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                    if (matrix.get(x, y)) drawRect(Color(0xFF0D1117), Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

private fun Modifier.androidxSemanticsDesc(desc: String) =
    this.then(Modifier.semantics { contentDescription = desc })

@Composable
fun errorText(s: app.phonemcp.ServerSnapshot): String {
    return when (s.startError) {
        app.phonemcp.StartError.PORT_IN_USE -> stringResource(R.string.err_port)
        app.phonemcp.StartError.PROBE_FAILED -> probeFailureTitle(s.probeFailure) + "\n" + stringResource(R.string.banner_hint)
        app.phonemcp.StartError.NO_MODE -> stringResource(R.string.err_no_mode)
        app.phonemcp.StartError.INTERNAL -> stringResource(R.string.err_internal, s.errorDetail)
        null -> when (s.tunnelError) {
            null -> stringResource(R.string.done_not_started)
            else -> tunnelErrorText(s)
        }
    }
}

@Composable
fun tunnelErrorText(s: app.phonemcp.ServerSnapshot): String = when (s.tunnelError) {
    app.phonemcp.TunnelError.NO_BINARY -> stringResource(R.string.terr_no_binary)
    app.phonemcp.TunnelError.TIMEOUT -> stringResource(R.string.terr_timeout)
    app.phonemcp.TunnelError.NO_TOKEN -> stringResource(R.string.terr_no_token)
    app.phonemcp.TunnelError.NO_HOSTNAME -> stringResource(R.string.terr_no_host)
    app.phonemcp.TunnelError.NO_NETWORK -> stringResource(R.string.terr_no_network)
    app.phonemcp.TunnelError.PROCESS_FAILED -> stringResource(R.string.terr_process) +
        (if (s.tunnelDetail.isNotBlank()) "\n" + s.tunnelDetail.take(160) else "")
    null -> ""
}

@Composable
fun probeFailureTitle(f: app.phonemcp.ProbeFailure?): String = stringResource(
    when (f) {
        app.phonemcp.ProbeFailure.SU_MISSING -> R.string.probe_su_missing
        app.phonemcp.ProbeFailure.ROOT_DENIED -> R.string.probe_root_denied
        app.phonemcp.ProbeFailure.NOT_ROOT -> R.string.probe_not_root
        app.phonemcp.ProbeFailure.SHIZUKU_MISSING -> R.string.probe_shiz_missing
        app.phonemcp.ProbeFailure.SHIZUKU_NOT_RUNNING -> R.string.probe_shiz_not_running
        app.phonemcp.ProbeFailure.SHIZUKU_OLD -> R.string.probe_shiz_old
        app.phonemcp.ProbeFailure.SHIZUKU_NO_PERMISSION -> R.string.probe_shiz_no_perm
        app.phonemcp.ProbeFailure.SHIZUKU_BIND_FAILED, app.phonemcp.ProbeFailure.TEST_FAILED, null -> R.string.probe_test_failed
        app.phonemcp.ProbeFailure.NO_STORAGE_ACCESS -> R.string.probe_no_storage
    }
)
