package app.phonemcp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phonemcp.R
import app.phonemcp.policy.ApprovalRequest
import app.phonemcp.policy.ApprovalText
import app.phonemcp.policy.Risk
import kotlinx.coroutines.delay

@Composable
fun riskLabel(r: Risk): String = stringResource(
    when (r) {
        Risk.DELETE_FILES -> R.string.risk_delete_files
        Risk.IRREVERSIBLE -> R.string.risk_irreversible
        Risk.DISK_WRITE -> R.string.risk_disk_write
        Risk.FORMAT -> R.string.risk_format
        Risk.REBOOT -> R.string.risk_reboot
        Risk.BOOTLOADER -> R.string.risk_bootloader
        Risk.SELINUX -> R.string.risk_selinux
        Risk.UNINSTALL -> R.string.risk_uninstall
        Risk.WIPE -> R.string.risk_wipe
        Risk.REMOTE_EXEC -> R.string.risk_remote_exec
        Risk.SYSTEM_WRITE -> R.string.risk_system_write
        Risk.BROAD_PERMS -> R.string.risk_broad_perms
        Risk.OUTSIDE_ALLOWED_PATH -> R.string.risk_outside_path
        Risk.CRITICAL -> R.string.risk_critical
        Risk.UNVERIFIED -> R.string.risk_unverified
        Risk.PROTECTED_PATH -> R.string.risk_protected_path
        Risk.HIDDEN_CHARS -> R.string.risk_hidden_chars
    }
)

/** Full-screen sheet shown over everything while a tool call waits for the owner. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApprovalSheet(req: ApprovalRequest, onDeny: () -> Unit, onAllow: () -> Unit) {
    val remaining by produceState(req.timeoutMs, req.id) {
        while (true) {
            value = (req.createdAt + req.timeoutMs - System.currentTimeMillis()).coerceAtLeast(0)
            if (value <= 0) break
            delay(250)
        }
    }
    val secs = ((remaining + 999) / 1000).toInt()
    val writeOnly = req.risks == setOf(Risk.OUTSIDE_ALLOWED_PATH)
    val plain = req.risks.all { it == Risk.UNVERIFIED || it == Risk.PROTECTED_PATH || it == Risk.HIDDEN_CHARS }

    Box(
        Modifier.fillMaxSize().background(C.Scrim.copy(alpha = 0.85f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(C.Surface)
                .border(1.dp, C.Border, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Border))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(C.Warn.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Warning, size = 24.dp, tint = C.Warn)
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        if (Risk.CRITICAL in req.risks) stringResource(R.string.approval_title_critical, req.step, req.steps)
                        else stringResource(if (writeOnly) R.string.approval_title_write else if (plain) R.string.approval_title_plain else R.string.approval_title),
                        fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold
                    )
                    Text(stringResource(R.string.approval_sub), fontSize = 13.sp, color = C.Muted)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.approval_command), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = C.Muted, letterSpacing = 0.7.sp)
                Text(
                    "$ " + ApprovalText.display(req.command), fontFamily = Mono, fontSize = 13.5.sp, lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(14.dp)).background(C.Bg)
                        .border(1.dp, C.Border, RoundedCornerShape(14.dp)).verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    req.risks.forEach { r ->
                        val warn = r == Risk.IRREVERSIBLE
                        Pill(riskLabel(r), if (warn) C.Warn else C.Danger, (if (warn) C.Warn else C.Danger).copy(alpha = 0.12f))
                    }
                    Pill(stringResource(R.string.approval_source, req.source), C.Muted, C.Surface2)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Surface2)) {
                    Box(
                        Modifier.fillMaxWidth((remaining.toFloat() / req.timeoutMs).coerceIn(0f, 1f)).height(6.dp)
                            .background(C.Warn)
                    )
                }
                Text(stringResource(R.string.approval_seconds, secs), fontSize = 12.sp, color = C.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(18.dp)).background(C.Text)
                        .clickable(role = Role.Button, onClick = onDeny),
                    contentAlignment = Alignment.Center
                ) { Text(stringResource(R.string.approval_deny), color = C.Bg, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                Box(
                    Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(18.dp))
                        .border(1.5.dp, C.Warn.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                        .clickable(role = Role.Button, onClick = onAllow),
                    contentAlignment = Alignment.Center
                ) { Text(stringResource(R.string.approval_allow), color = C.Warn, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            }
            Text(
                stringResource(R.string.approval_biometric), fontSize = 12.sp, color = C.Muted,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}
