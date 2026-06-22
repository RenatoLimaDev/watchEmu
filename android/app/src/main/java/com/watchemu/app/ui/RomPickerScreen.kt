package com.watchemu.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.watchemu.app.R
import com.watchemu.app.ui.theme.OnSurfaceVariant
import java.io.File
import kotlin.math.abs

private data class CartridgeColors(
    val body: Color,
    val bodyDark: Color,
    val label: Color,
    val labelDark: Color,
    val accent: Color
)

private fun colorsForName(name: String): CartridgeColors {
    val hash = abs(name.lowercase().hashCode())
    val palette = listOf(
        // Red cartridges
        CartridgeColors(Color(0xFFC0392B), Color(0xFF962D22), Color(0xFFF5E6D3), Color(0xFFE8D5BE), Color(0xFFE74C3C)),
        // Blue cartridges
        CartridgeColors(Color(0xFF2980B9), Color(0xFF206694), Color(0xFFD6EAF8), Color(0xFFBDD8ED), Color(0xFF3498DB)),
        // Green cartridges
        CartridgeColors(Color(0xFF27AE60), Color(0xFF1E8C4C), Color(0xFFD5F5E3), Color(0xFFC1EDD3), Color(0xFF2ECC71)),
        // Purple cartridges
        CartridgeColors(Color(0xFF8E44AD), Color(0xFF71368A), Color(0xFFE8D5F5), Color(0xFFD7BDE8), Color(0xFF9B59B6)),
        // Orange cartridges
        CartridgeColors(Color(0xFFD35400), Color(0xFFA94300), Color(0xFFFDE8D0), Color(0xFFF5D5B5), Color(0xFFE67E22)),
        // Teal cartridges
        CartridgeColors(Color(0xFF16A085), Color(0xFF117F69), Color(0xFFD1F2EB), Color(0xFFBCE8DE), Color(0xFF1ABC9C)),
        // Dark gray (classic NES)
        CartridgeColors(Color(0xFF5D6D7E), Color(0xFF4A5768), Color(0xFFD5D8DC), Color(0xFFC5C9CE), Color(0xFF85929E)),
        // Gold special
        CartridgeColors(Color(0xFFD4920A), Color(0xFFB07A08), Color(0xFFFEF9E7), Color(0xFFF9ECCC), Color(0xFFF0C040)),
    )
    return palette[hash % palette.size]
}

/**
 * ROM picker for plain Android (Amazfit Stratos). Uses regular Jetpack Compose
 * Material instead of Wear Compose so it runs without a Wear OS runtime. The
 * round display is respected by padding content inward so list rows are not
 * clipped by the circular bezel.
 */
@Composable
fun RomPickerScreen(
    romFiles: List<File>,
    isReceiving: Boolean = false,
    onReceive: () -> Unit = {},
    onRomSelected: (File) -> Unit,
    onRomDeleted: (File) -> Unit = {}
) {
    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Inset content from the round bezel so the first/last rows and the
            // header are not swallowed by the circular corners.
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 36.dp)
        ) {
            item {
                Image(
                    painter = painterResource(R.drawable.logo),
                    contentDescription = "WatchEmu Logo",
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                )
            }
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "WatchEmu",
                    style = MaterialTheme.typography.h6,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center
                )
            }
            item {
                Text(
                    text = "NES Emulator",
                    style = MaterialTheme.typography.overline,
                    color = OnSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            item {
                ReceiveRomButton(isReceiving = isReceiving, onClick = onReceive)
            }
            if (romFiles.isNotEmpty()) {
                item {
                    Text(
                        text = "Found on device",
                        style = MaterialTheme.typography.caption,
                        color = OnSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }
                items(romFiles) { file ->
                    RomRow(
                        file = file,
                        onClick = { onRomSelected(file) },
                        onDelete = { onRomDeleted(file) }
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** Primary action row: start/stop Bluetooth ROM receiving. */
@Composable
private fun ReceiveRomButton(isReceiving: Boolean, onClick: () -> Unit) {
    val bg = if (isReceiving) MaterialTheme.colors.secondary else MaterialTheme.colors.primary
    val fg = if (isReceiving) MaterialTheme.colors.onSecondary else MaterialTheme.colors.onPrimary
    Surface(
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isReceiving) Icons.AutoMirrored.Filled.BluetoothSearching else Icons.Filled.Bluetooth,
                contentDescription = "Bluetooth",
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = if (isReceiving) "Receiving..." else "Receive ROM",
                    style = MaterialTheme.typography.button
                )
                if (isReceiving) {
                    Text(
                        text = "Send .nes via Bluetooth",
                        style = MaterialTheme.typography.caption
                    )
                }
            }
        }
    }
}

/** A single ROM entry: tap the row to play, tap the trash icon to delete. */
@Composable
private fun RomRow(file: File, onClick: () -> Unit, onDelete: () -> Unit) {
    val gameName = file.nameWithoutExtension
    val cartColors = colorsForName(gameName)
    Surface(
        color = MaterialTheme.colors.surface,
        contentColor = MaterialTheme.colors.onSurface,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onClick)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
            ) {
                Canvas(modifier = Modifier.size(24.dp)) {
                    drawCartridge(size.width, size.height, cartColors)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = gameName,
                        style = MaterialTheme.typography.body2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (file.extension.equals("zip", true)) "ZIP" else "NES",
                        style = MaterialTheme.typography.caption,
                        color = OnSurfaceVariant
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colors.error,
                modifier = Modifier
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .size(20.dp)
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCartridge(
    w: Float, h: Float, colors: CartridgeColors
) {
    val pad = 1f

    // Cartridge body
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(colors.body, colors.bodyDark),
            startY = pad, endY = h - pad
        ),
        topLeft = Offset(pad, pad),
        size = Size(w - pad * 2, h - pad * 2),
        cornerRadius = CornerRadius(3f, 3f)
    )

    // Top notch (connector slot)
    val notchW = w * 0.4f
    val notchH = h * 0.08f
    val notchX = (w - notchW) / 2f
    drawRect(
        color = colors.bodyDark,
        topLeft = Offset(notchX, pad),
        size = Size(notchW, notchH)
    )

    // Label area (lighter rectangle in center)
    val labelMarginX = w * 0.12f
    val labelTop = h * 0.2f
    val labelBottom = h * 0.75f
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(colors.label, colors.labelDark),
            startY = labelTop, endY = labelBottom
        ),
        topLeft = Offset(labelMarginX, labelTop),
        size = Size(w - labelMarginX * 2, labelBottom - labelTop),
        cornerRadius = CornerRadius(2f, 2f)
    )

    // Label border
    drawRoundRect(
        color = colors.bodyDark.copy(alpha = 0.5f),
        topLeft = Offset(labelMarginX, labelTop),
        size = Size(w - labelMarginX * 2, labelBottom - labelTop),
        cornerRadius = CornerRadius(2f, 2f),
        style = Stroke(0.8f)
    )

    // Accent stripe on label
    val stripeH = h * 0.06f
    drawRect(
        color = colors.accent,
        topLeft = Offset(labelMarginX + 2f, labelTop + (labelBottom - labelTop) * 0.35f),
        size = Size(w - labelMarginX * 2 - 4f, stripeH)
    )

    // Bottom grip lines
    val gripY = h * 0.82f
    val gripH = h * 0.04f
    for (i in 0..2) {
        val gy = gripY + i * (gripH + 1f)
        drawLine(
            color = colors.bodyDark,
            start = Offset(w * 0.2f, gy),
            end = Offset(w * 0.8f, gy),
            strokeWidth = 0.7f
        )
    }

    // Edge highlight (3D effect)
    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.White.copy(alpha = 0.15f), Color.Transparent, Color.Transparent),
            startX = pad, endX = w * 0.3f
        ),
        topLeft = Offset(pad, pad),
        size = Size(w - pad * 2, h - pad * 2),
        cornerRadius = CornerRadius(3f, 3f)
    )
}
