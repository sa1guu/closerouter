package com.closerouter.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PanelInk = Color(0xFF34363B)
private val PanelMuted = Color(0xFF81858C)
private val PanelSurface = Color(0xFFF6F6F5)
private val PanelBorder = Color(0xFFE8E8E6)

@Composable
internal fun ControlHeader(page: Int, title: String, subtitle: String, onPage: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("0${page + 1} / 07  ·  ${BAY_CODES[page]}", color = Pal.orangeDeep,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            Spacer(Modifier.weight(1f))
            Row {
                repeat(BAY_COUNT) { index ->
                    Box(Modifier.size(20.dp).clickable { onPage(index) }, contentAlignment = Alignment.Center) {
                        Box(Modifier.width(if (index == page) 12.dp else 4.dp).height(4.dp)
                            .background(if (index == page) Pal.orangeDeep else Color(0xFFDADBD9), RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = PanelInk, letterSpacing = 0.3.sp)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, fontSize = 11.sp, color = PanelMuted, lineHeight = 17.sp)
    }
}

@Composable
internal fun ControlCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = PanelSurface,
        border = BorderStroke(1.dp, PanelBorder)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun ControlStatus(title: String, detail: String, active: Boolean, badge: String) {
    ControlCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (active) Pal.orangeDeep else Color(0xFFB4B7BB), RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(9.dp))
            Text(title, color = PanelInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(badge, color = if (active) Pal.orangeDeep else PanelMuted, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.background(Color.White, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 4.dp))
        }
        Text(detail, color = PanelMuted, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

@Composable
internal fun ControlAction(
    text: String,
    code: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    enabled: Boolean = true
) {
    val background = if (secondary) Color.White else PanelInk
    val foreground = if (secondary) PanelInk else Color.White
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 12.dp),
        border = if (secondary) BorderStroke(1.dp, Color(0xFFDEDFDF)) else null,
        colors = ButtonDefaults.buttonColors(containerColor = background, contentColor = foreground,
            disabledContainerColor = Color(0xFFEDEEEB), disabledContentColor = PanelMuted)
    ) {
        Text(code, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            color = if (enabled) Pal.orangeDeep else PanelMuted,
            modifier = Modifier.background(if (secondary) PanelSurface else Color(0xFF48494E), RoundedCornerShape(5.dp))
                .padding(horizontal = 6.dp, vertical = 5.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
        Spacer(Modifier.width(6.dp))
        Text("→", fontSize = 17.sp, color = if (secondary) PanelMuted else Color(0xFFCBCDD0))
    }
}

@Composable
internal fun ControlValue(label: String, value: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = PanelMuted, fontSize = 10.sp, letterSpacing = 0.5.sp)
        SelectionContainer {
            Text(value, color = if (accent) Pal.orangeDeep else PanelInk, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 18.sp, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ControlMetric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = PanelSurface, border = BorderStroke(1.dp, PanelBorder)) {
        Column(Modifier.padding(13.dp)) {
            Text(label, fontSize = 10.sp, color = PanelMuted)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 22.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, color = PanelInk,
                    modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(5.dp))
                Text(unit, fontSize = 9.sp, color = PanelMuted, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
internal fun ControlHint(text: String) {
    Text(text, color = PanelMuted, fontSize = 10.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 2.dp))
}
