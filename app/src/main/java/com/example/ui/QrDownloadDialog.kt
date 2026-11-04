package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.qr.QrCodeEncoder
import kotlin.math.ceil
import kotlin.math.floor

@Composable
fun QrDownloadDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val qrMatrix = remember { QrCodeEncoder.encodeByteMode(QrCodeEncoder.APK_DOWNLOAD_URL) }
    var copied by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 380.dp)
                .testTag("qr_download_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.qr_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.qr_dialog_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .widthIn(max = 260.dp)
                        .aspectRatio(1f)
                ) {
                    val cdQr = stringResource(R.string.cd_qr_code_image)
                    Canvas(
                        contentDescription = cdQr,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .testTag("qr_code_canvas")
                    ) {
                        val quietZone = 3
                        val totalUnits = qrMatrix.size + quietZone * 2
                        val unitPx = size.minDimension / totalUnits.toFloat()
                        val originX = (size.width - unitPx * totalUnits) / 2f
                        val originY = (size.height - unitPx * totalUnits) / 2f

                        for (y in 0 until qrMatrix.size) {
                            for (x in 0 until qrMatrix.size) {
                                if (qrMatrix.get(x, y)) {
                                    val left = floor(originX + (x + quietZone) * unitPx)
                                    val top = floor(originY + (y + quietZone) * unitPx)
                                    val right = ceil(originX + (x + quietZone + 1) * unitPx)
                                    val bottom = ceil(originY + (y + quietZone + 1) * unitPx)
                                    drawRect(
                                        color = Color(0xFF080814),
                                        topLeft = Offset(left, top),
                                        size = Size(right - left, bottom - top)
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("MaskMaze APK", QrCodeEncoder.APK_DOWNLOAD_URL))
                            copied = true
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("qr_copy_link_button")
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(if (copied) R.string.qr_copied else R.string.qr_copy_link),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(QrCodeEncoder.APK_DOWNLOAD_URL))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (_: Exception) {
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("qr_open_link_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.qr_open_link),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("qr_close_button")
                ) {
                    Text(
                        text = stringResource(R.string.qr_close),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
