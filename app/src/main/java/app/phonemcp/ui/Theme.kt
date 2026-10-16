package app.phonemcp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens from the Phone MCP design (dark only). */
object C {
    val Bg = Color(0xFF0D1117)
    val Surface = Color(0xFF161B22)
    val Surface2 = Color(0xFF1C222B)
    val Border = Color(0xFF2A313C)
    val Divider = Color(0xFF222934)
    val Text = Color(0xFFE6EAF0)
    val Soft = Color(0xFFC5CCD8)
    val Muted = Color(0xFF8A94A3)
    val Dim = Color(0xFF5B6574)
    val Accent = Color(0xFF5EEAD4)
    val OnAccent = Color(0xFF06231F)
    val Warn = Color(0xFFFBBF24)
    val WarnSoft = Color(0xFFFBD46B)
    val Danger = Color(0xFFF87171)
    val Scrim = Color(0xFF06080B)
}

val Mono = FontFamily.Monospace

@Composable
fun McpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent, onPrimary = C.OnAccent,
            background = C.Bg, onBackground = C.Text,
            surface = C.Surface, onSurface = C.Text,
            surfaceVariant = C.Surface2, onSurfaceVariant = C.Muted,
            error = C.Danger, outline = C.Border
        )
    ) {
        Surface(color = C.Bg, contentColor = C.Text, content = content)
    }
}

@Composable
fun McpCard(
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    padding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(C.Surface)
            .border(1.dp, C.Border, RoundedCornerShape(radius))
            .padding(padding),
        content = content
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) C.Accent else C.Surface2)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier.semantics { disabled() }),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) C.OnAccent else C.Dim, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** Compact button used inside cards. */
@Composable
fun SmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Color.Transparent,
    textColor: Color = C.Text,
    borderColor: Color? = null,
    height: Dp = 44.dp,
    radius: Dp = 12.dp,
    weight: FontWeight = FontWeight.Bold
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .heightIn(min = height)
            .clip(shape)
            .background(fill)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = textColor, fontSize = 14.sp, fontWeight = weight, textAlign = TextAlign.Center)
    }
}

@Composable
fun Pill(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier, mono: Boolean = false) {
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
        color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = if (mono) Mono else null,
        maxLines = 1, softWrap = false
    )
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text, color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp)
    )
}

@Composable
fun Footnote(text: String) {
    Text(text, color = C.Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 4.dp))
}

/** Visual-only switch; the surrounding row carries the toggle semantics. */
@Composable
fun SwitchVisual(checked: Boolean, enabled: Boolean = true) {
    Box(
        Modifier
            .size(46.dp, 28.dp)
            .clip(CircleShape)
            .background(if (checked && enabled) C.Accent else C.Border)
            .padding(3.dp)
    ) {
        Box(
            Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (enabled) Color.White else C.Muted)
        )
    }
}

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    note: String? = null,
    noteColor: Color = C.Muted,
    enabled: Boolean = true,
    mono: Boolean = false,
    divider: Boolean = true
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    label, fontSize = if (mono) 13.5.sp else 15.sp, fontFamily = if (mono) Mono else null,
                    color = if (enabled) C.Text else C.Dim
                )
                if (note != null) Text(note, fontSize = 12.sp, color = noteColor, lineHeight = 17.sp)
            }
            SwitchVisual(checked, enabled)
        }
        if (divider) RowDivider()
    }
}

@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Divider))
}

@Composable
fun NavRow(
    label: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    valueColor: Color = C.Muted,
    valueMono: Boolean = false,
    note: String? = null,
    noteColor: Color = C.Muted,
    chevron: Boolean = onClick != null,
    divider: Boolean = true
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, fontSize = 15.sp)
                if (note != null) Text(note, fontSize = 12.sp, color = noteColor, lineHeight = 17.sp)
            }
            if (value != null) {
                Text(
                    value, fontSize = if (valueMono) 13.sp else 14.sp, color = valueColor,
                    fontFamily = if (valueMono) Mono else null, textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(max = 170.dp)
                )
            }
            if (chevron) Icon(Icons.Chevron, size = 16.dp, tint = C.Dim, stroke = 2.2f)
        }
        if (divider) RowDivider()
    }
}

@Composable
fun BackButton(onClick: () -> Unit, label: String) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Back, size = 24.dp, tint = C.Text)
    }
}

@Composable
fun IconButtonBox(icon: List<IconShape>, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(C.Surface2)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, size = 20.dp, tint = C.Text)
    }
}
